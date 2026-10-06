package com.nordic.tagmobile.data.local

import android.content.Context
import com.nordic.tagmobile.data.local.db.PetEntity
import com.nordic.tagmobile.data.local.db.SessionEntity
import com.nordic.tagmobile.data.local.db.TagDatabase
import com.nordic.tagmobile.data.local.db.UserEntity
import com.nordic.tagmobile.domain.model.SyncStatus
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.model.AppUser
import com.nordic.tagmobile.model.UserProfile
import com.nordic.tagmobile.storage.HistoryEntry
import com.nordic.tagmobile.storage.RecordingStore
import com.nordic.tagmobile.storage.SessionManifestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Keeps Room session index in sync with on-disk session packages.
 * Does not move or rewrite media files.
 */
object SessionIndexer {
    suspend fun upsertFromHistoryEntry(context: Context, entry: HistoryEntry) = withContext(Dispatchers.IO) {
        val db = TagDatabase.get(context)
        val existing = db.sessionDao().get(entry.baseName)
        val folder = entry.sessionDir?.absolutePath
            ?: RecordingStore.sessionDir(context, entry.baseName).absolutePath
        val manifest = readManifest(entry.sessionDir)
        val entity = SessionEntity(
            sessionId = entry.baseName,
            localUserId = manifest?.localUserId.orEmpty(),
            localPetId = manifest?.localPetId.orEmpty(),
            cloudUserId = existing?.cloudUserId,
            cloudPetId = existing?.cloudPetId,
            deviceId = manifest?.deviceId.orEmpty(),
            deviceAddress = manifest?.deviceAddress.orEmpty(),
            startedAtMs = manifest?.startTimeMs ?: entry.savedAtMs,
            endedAtMs = manifest?.endTimeMs ?: entry.savedAtMs,
            quality = entry.status,
            terminationReason = manifest?.terminationReason ?: "USER_STOP",
            folderPath = folder,
            hasVideo = entry.videoFile?.exists() == true,
            hasXlsx = entry.dataFile.exists(),
            hasLog = entry.logFile.exists(),
            hasManifest = entry.sessionDir?.let { RecordingStore.sessionManifestFile(it).exists() } == true,
            syncStatus = existing?.syncStatus ?: when {
                entry.audioFile?.exists() == true && !entry.dataFile.exists() -> SyncStatus.LOCAL_ONLY.name
                else -> SyncStatus.PENDING.name
            },
            lastError = existing?.lastError,
            lastSyncAttemptAt = existing?.lastSyncAttemptAt,
            remoteStoragePath = existing?.remoteStoragePath,
            createdAtMs = existing?.createdAtMs ?: entry.savedAtMs,
        )
        db.sessionDao().upsert(entity)
        upsertIdentityFromManifest(context, manifest)
    }

    suspend fun backfillFromDisk(context: Context) = withContext(Dispatchers.IO) {
        RecordingStore.listHistory(context).forEach { upsertFromHistoryEntry(context, it) }
        val user = AppUser.load(context)
        if (user.isComplete) {
            TagDatabase.get(context).userDao().upsert(
                UserEntity(
                    localUserId = user.id,
                    name = user.name,
                    phone = user.phone,
                ),
            )
        }
        UserProfile.loadAll(context).forEach { pet ->
            TagDatabase.get(context).petDao().upsert(
                PetEntity(
                    localPetId = pet.id,
                    localUserId = user.id,
                    name = pet.dogName.ifBlank { pet.name },
                    animalType = pet.animalType,
                    breed = pet.breed,
                    sex = pet.gender,
                    age = pet.age,
                    weightKg = pet.weight,
                ),
            )
        }
    }

    suspend fun delete(context: Context, sessionId: String) = withContext(Dispatchers.IO) {
        TagDatabase.get(context).sessionDao().delete(sessionId)
    }

    suspend fun syncStatusMap(context: Context): Map<String, SyncStatus> = withContext(Dispatchers.IO) {
        TagDatabase.get(context).sessionDao().getAll()
            .associate { it.sessionId to SyncStatus.fromRaw(it.syncStatus) }
    }

    data class SyncUiInfo(
        val status: SyncStatus,
        val lastError: String? = null,
    )

    suspend fun syncUiMap(context: Context): Map<String, SyncUiInfo> = withContext(Dispatchers.IO) {
        val dao = TagDatabase.get(context).sessionDao()
        val now = System.currentTimeMillis()
        dao.getAll().associate { entity ->
            var status = SyncStatus.fromRaw(entity.syncStatus)
            var err = entity.lastError
            // Recover stuck UPLOADING so History can show Retry.
            if (status == SyncStatus.UPLOADING) {
                val started = entity.lastSyncAttemptAt ?: 0L
                if (started == 0L || now - started > 3 * 60_000L) {
                    err = "Upload timed out — tap Retry"
                    dao.updateSyncState(
                        entity.sessionId,
                        SyncStatus.FAILED.name,
                        err,
                        now,
                        entity.remoteStoragePath,
                    )
                    status = SyncStatus.FAILED
                    TagLogger.log(LogCategory.APP, "CLOUD_STUCK_UPLOADING_RESET_UI", entity.sessionId)
                }
            }
            entity.sessionId to SyncUiInfo(status = status, lastError = err)
        }
    }

    private suspend fun upsertIdentityFromManifest(context: Context, manifest: SessionManifestData?) {
        if (manifest == null) return
        val db = TagDatabase.get(context)
        if (manifest.localUserId.isNotBlank()) {
            val existing = db.userDao().get(manifest.localUserId)
            db.userDao().upsert(
                UserEntity(
                    localUserId = manifest.localUserId,
                    cloudUserId = existing?.cloudUserId,
                    name = manifest.userName.ifBlank { existing?.name.orEmpty() },
                    phone = manifest.userPhone.ifBlank { existing?.phone.orEmpty() },
                ),
            )
        }
        if (manifest.localPetId.isNotBlank()) {
            val existing = db.petDao().get(manifest.localPetId)
            db.petDao().upsert(
                PetEntity(
                    localPetId = manifest.localPetId,
                    cloudPetId = existing?.cloudPetId,
                    localUserId = manifest.localUserId,
                    name = manifest.petName.ifBlank { existing?.name.orEmpty() },
                    animalType = manifest.animalType.ifBlank { existing?.animalType.orEmpty() },
                    breed = manifest.breed.ifBlank { existing?.breed.orEmpty() },
                    sex = manifest.sex.ifBlank { existing?.sex.orEmpty() },
                    age = manifest.age.ifBlank { existing?.age.orEmpty() },
                    weightKg = manifest.weightKg.ifBlank { existing?.weightKg.orEmpty() },
                ),
            )
        }
    }

    private fun readManifest(sessionDir: File?): SessionManifestData? {
        if (sessionDir == null) return null
        val file = RecordingStore.sessionManifestFile(sessionDir)
        if (!file.exists()) return null
        return try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            val user = o.optJSONObject("user")
            val pet = o.optJSONObject("pet")
            val device = o.optJSONObject("device")
            val recording = o.optJSONObject("recording")
            SessionManifestData(
                sessionId = o.optString("session_id", sessionDir.name),
                packetCount = o.optInt("packet_count", 0),
                sampleCount = o.optInt("sample_count", 0),
                parseFailures = o.optInt("parse_failures", 0),
                statusDetail = o.optJSONObject("quality")?.optString("status_detail").orEmpty(),
                hasPossibleLoss = o.optJSONObject("quality")?.optBoolean("has_possible_loss") ?: false,
                terminationReason = o.optString("termination_reason", "USER_STOP"),
                localUserId = user?.optString("local_user_id").orEmpty(),
                userName = user?.optString("name").orEmpty(),
                userPhone = user?.optString("phone").orEmpty(),
                localPetId = pet?.optString("local_pet_id").orEmpty(),
                petName = pet?.optString("name").orEmpty(),
                animalType = pet?.optString("animal_type").orEmpty(),
                breed = pet?.optString("breed").orEmpty(),
                sex = pet?.optString("sex").orEmpty(),
                age = pet?.optString("age").orEmpty(),
                weightKg = pet?.optString("weight_kg").orEmpty(),
                deviceId = device?.optString("device_id").orEmpty(),
                deviceAddress = device?.optString("address").orEmpty(),
                startTimeMs = recording?.optLong("start_time_ms") ?: 0L,
                endTimeMs = recording?.optLong("end_time_ms") ?: 0L,
                videoWidth = null,
                videoHeight = null,
                videoFps = null,
                orientationHint = null,
                videoOrientationLabel = null,
                sensorSamplePeriodMs = null,
                sensorSamplesPerPacket = null,
                phoneStartTimestampMs = recording?.optLong("start_time_ms") ?: 0L,
                collarUptimeAtSyncMs = null,
                hasData = true,
                hasVideo = true,
                appVersionName = o.optJSONObject("app")?.optString("version_name") ?: "unknown",
                appVersionCode = o.optJSONObject("app")?.optInt("version_code") ?: 0,
                qualityLabel = o.optJSONObject("quality")?.optString("status") ?: "GOOD",
            )
        } catch (_: Exception) {
            null
        }
    }
}
