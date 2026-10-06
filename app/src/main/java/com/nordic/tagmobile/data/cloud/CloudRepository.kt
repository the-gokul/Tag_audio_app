package com.nordic.tagmobile.data.cloud

import android.content.Context
import com.nordic.tagmobile.data.local.db.TagDatabase
import com.nordic.tagmobile.domain.model.CloudPetIds
import com.nordic.tagmobile.domain.model.CloudUser
import com.nordic.tagmobile.domain.model.SyncStatus
import com.nordic.tagmobile.domain.model.UploadResult
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.model.AppUser
import com.nordic.tagmobile.model.UserProfile
import com.nordic.tagmobile.storage.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

interface CloudRepository {
    suspend fun ensureSignedIn(): CloudUser
    suspend fun upsertPetProfile(pet: UserProfile): CloudPetIds
    suspend fun uploadSession(sessionId: String): UploadResult
    suspend fun uploadPendingSessions(): List<UploadResult>
}

class CloudRepositoryImpl(
    private val context: Context,
    private val backend: CloudBackend,
) : CloudRepository {

    private val db get() = TagDatabase.get(context)
    private val uploadMutex = Mutex()

    override suspend fun ensureSignedIn(): CloudUser = withContext(Dispatchers.IO) {
        val user = AppUser.load(context)
        val cloud = backend.ensureSignedIn(user.id, user.name, user.phone)
        val existing = db.userDao().get(user.id)
        db.userDao().upsert(
            (existing ?: com.nordic.tagmobile.data.local.db.UserEntity(localUserId = user.id)).copy(
                cloudUserId = cloud.cloudUserId,
                name = user.name.ifBlank { existing?.name.orEmpty() },
                phone = user.phone.ifBlank { existing?.phone.orEmpty() },
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        cloud
    }

    override suspend fun upsertPetProfile(pet: UserProfile): CloudPetIds = withContext(Dispatchers.IO) {
        val user = AppUser.load(context)
        val cloudUser = ensureSignedIn()
        val petIds = backend.upsertPet(
            localPetId = pet.id,
            localUserId = user.id,
            cloudUserId = cloudUser.cloudUserId,
            name = pet.dogName.ifBlank { pet.name },
            animalType = pet.animalType,
            breed = pet.breed,
            sex = pet.gender,
            age = pet.age,
            weightKg = pet.weight,
        )
        val existing = db.petDao().get(pet.id)
        db.petDao().upsert(
            (existing ?: com.nordic.tagmobile.data.local.db.PetEntity(localPetId = pet.id)).copy(
                cloudPetId = petIds.cloudPetId,
                localUserId = user.id,
                name = pet.dogName.ifBlank { pet.name },
                animalType = pet.animalType,
                breed = pet.breed,
                sex = pet.gender,
                age = pet.age,
                weightKg = pet.weight,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        petIds
    }

    override suspend fun uploadSession(sessionId: String): UploadResult = uploadMutex.withLock {
        withContext(Dispatchers.IO) {
            uploadSessionLocked(sessionId)
        }
    }

    private suspend fun uploadSessionLocked(sessionId: String): UploadResult {
        val dao = db.sessionDao()
        val row = dao.get(sessionId)
            ?: return UploadResult.Failed(sessionId, "Session not in local index", retryable = false)

        if (row.syncStatus == SyncStatus.SYNCED.name && !row.remoteStoragePath.isNullOrBlank()) {
            TagLogger.log(LogCategory.APP, "CLOUD_SKIP_ALREADY_SYNCED", sessionId)
            return UploadResult.AlreadySynced(sessionId)
        }

        // Recover stuck UPLOADING from a killed/hung previous attempt.
        val now = System.currentTimeMillis()
        if (row.syncStatus == SyncStatus.UPLOADING.name) {
            val started = row.lastSyncAttemptAt ?: 0L
            if (started > 0L && now - started > 3 * 60_000L) {
                TagLogger.log(LogCategory.APP, "CLOUD_STUCK_UPLOADING_RESET", sessionId)
                dao.updateSyncState(
                    sessionId,
                    SyncStatus.FAILED.name,
                    "Upload timed out — tap Retry",
                    now,
                    row.remoteStoragePath,
                )
            }
        }

        val net = NetworkProbe.summary(context)
        dao.updateSyncState(sessionId, SyncStatus.UPLOADING.name, null, now, row.remoteStoragePath)
        TagLogger.log(
            LogCategory.APP,
            "CLOUD_SESSION_UPLOADING",
            "session=$sessionId prev=${row.syncStatus} $net",
        )

        return try {
            // Long recordings are several GB. Stalls are caught per chunk by the backend,
            // and TagServerCloudBackend resumes a cut-off upload, so this is only a backstop.
            withTimeout(UPLOAD_TIMEOUT_MS) {
                val cloudUser = ensureSignedIn()
                TagLogger.log(LogCategory.APP, "CLOUD_USER_OK", cloudUser.cloudUserId)
                val userIds = backend.upsertUser(
                    localUserId = row.localUserId.ifBlank { AppUser.load(context).id },
                    name = AppUser.load(context).name,
                    phone = AppUser.load(context).phone,
                )
                db.userDao().get(userIds.localUserId)?.let {
                    db.userDao().upsert(it.copy(cloudUserId = userIds.cloudUserId))
                }

                var petIds: CloudPetIds? = null
                if (row.localPetId.isNotBlank()) {
                    val pet = UserProfile.loadAll(context).firstOrNull { it.id == row.localPetId }
                    petIds = backend.upsertPet(
                        localPetId = row.localPetId,
                        localUserId = row.localUserId,
                        cloudUserId = userIds.cloudUserId,
                        name = pet?.dogName ?: row.localPetId,
                        animalType = pet?.animalType.orEmpty(),
                        breed = pet?.breed.orEmpty(),
                        sex = pet?.gender.orEmpty(),
                        age = pet?.age.orEmpty(),
                        weightKg = pet?.weight.orEmpty(),
                    )
                    db.petDao().get(row.localPetId)?.let {
                        db.petDao().upsert(it.copy(cloudPetId = petIds.cloudPetId))
                    }
                    TagLogger.log(
                        LogCategory.APP,
                        "CLOUD_PET_OK",
                        "pet=${petIds.cloudPetId} name=${pet?.dogName ?: row.localPetId}",
                    )
                }

                val folder = File(row.folderPath).takeIf { it.isDirectory }
                    ?: RecordingStore.sessionDir(context, sessionId)
                if (!folder.isDirectory) {
                    throw IllegalStateException("Session folder missing")
                }
                val files = folder.listFiles()?.filter { it.isFile }.orEmpty()
                val totalBytes = files.sumOf { it.length() }
                TagLogger.log(
                    LogCategory.APP,
                    "CLOUD_FOLDER_READY",
                    "session=$sessionId files=${files.size} total=${NetworkProbe.formatBytes(totalBytes)} " +
                        files.joinToString { "${it.name}:${NetworkProbe.formatBytes(it.length())}" },
                )

                val remote = backend.uploadSessionPackage(
                    sessionId = sessionId,
                    folder = folder,
                    metadata = mapOf(
                        "session_id" to sessionId,
                        "cloud_user_id" to cloudUser.cloudUserId,
                        "cloud_pet_id" to petIds?.cloudPetId,
                        "local_user_id" to row.localUserId,
                        "local_pet_id" to row.localPetId,
                        "quality" to row.quality,
                        "started_at_ms" to row.startedAtMs,
                        "ended_at_ms" to row.endedAtMs,
                        "device_id" to row.deviceId,
                        "device_address" to row.deviceAddress,
                    ),
                )

                val refreshed = dao.get(sessionId)
                if (refreshed != null) {
                    dao.upsert(
                        refreshed.copy(
                            cloudUserId = userIds.cloudUserId,
                            cloudPetId = petIds?.cloudPetId ?: refreshed.cloudPetId,
                            syncStatus = SyncStatus.SYNCED.name,
                            lastError = null,
                            lastSyncAttemptAt = now,
                            remoteStoragePath = remote,
                        ),
                    )
                } else {
                    dao.updateSyncState(sessionId, SyncStatus.SYNCED.name, null, now, remote)
                }
                TagLogger.log(LogCategory.APP, "CLOUD_SESSION_SYNCED", "session=$sessionId path=$remote $net")
                UploadResult.Success(sessionId, remote)
            }
        } catch (e: Exception) {
            val msg = when {
                e is kotlinx.coroutines.TimeoutCancellationException ->
                    "Upload timed out — tap Retry"
                e.message?.contains("closed by peer", ignoreCase = true) == true ->
                    "Network blocked Supabase TLS (DNS/firewall). Retrying with secure DNS — tap Retry. (${e.message})"
                else -> e.message ?: "Upload failed"
            }
            dao.updateSyncState(
                sessionId,
                SyncStatus.FAILED.name,
                msg,
                now,
                row.remoteStoragePath,
            )
            TagLogger.log(
                LogCategory.ERRORS,
                "CLOUD_SESSION_FAIL",
                "session=$sessionId err=$msg $net",
            )
            UploadResult.Failed(sessionId, msg, retryable = true)
        }
    }

    override suspend fun uploadPendingSessions(): List<UploadResult> = withContext(Dispatchers.IO) {
        db.sessionDao().getPendingOrFailed().map { uploadSession(it.sessionId) }
    }

    private companion object {
        const val UPLOAD_TIMEOUT_MS = 4 * 60 * 60_000L
    }
}
