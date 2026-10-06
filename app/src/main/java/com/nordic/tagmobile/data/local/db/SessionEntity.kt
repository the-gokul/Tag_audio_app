package com.nordic.tagmobile.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nordic.tagmobile.domain.model.Session
import com.nordic.tagmobile.domain.model.SyncStatus

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val localUserId: String = "",
    val localPetId: String = "",
    val cloudUserId: String? = null,
    val cloudPetId: String? = null,
    val deviceId: String = "",
    val deviceAddress: String = "",
    val startedAtMs: Long = 0L,
    val endedAtMs: Long = 0L,
    val quality: String = "GOOD",
    val terminationReason: String = "USER_STOP",
    val folderPath: String = "",
    val hasVideo: Boolean = false,
    val hasXlsx: Boolean = false,
    val hasLog: Boolean = false,
    val hasManifest: Boolean = false,
    val syncStatus: String = SyncStatus.PENDING.name,
    val lastError: String? = null,
    val lastSyncAttemptAt: Long? = null,
    val remoteStoragePath: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    fun toDomain(): Session = Session(
        sessionId = sessionId,
        localUserId = localUserId,
        localPetId = localPetId,
        cloudUserId = cloudUserId,
        cloudPetId = cloudPetId,
        deviceId = deviceId,
        deviceAddress = deviceAddress,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        quality = quality,
        terminationReason = terminationReason,
        folderPath = folderPath,
        hasVideo = hasVideo,
        hasXlsx = hasXlsx,
        hasLog = hasLog,
        hasManifest = hasManifest,
        syncStatus = SyncStatus.fromRaw(syncStatus),
        lastError = lastError,
        lastSyncAttemptAt = lastSyncAttemptAt,
        remoteStoragePath = remoteStoragePath,
        createdAtMs = createdAtMs,
    )

    companion object {
        fun fromDomain(session: Session): SessionEntity = SessionEntity(
            sessionId = session.sessionId,
            localUserId = session.localUserId,
            localPetId = session.localPetId,
            cloudUserId = session.cloudUserId,
            cloudPetId = session.cloudPetId,
            deviceId = session.deviceId,
            deviceAddress = session.deviceAddress,
            startedAtMs = session.startedAtMs,
            endedAtMs = session.endedAtMs,
            quality = session.quality,
            terminationReason = session.terminationReason,
            folderPath = session.folderPath,
            hasVideo = session.hasVideo,
            hasXlsx = session.hasXlsx,
            hasLog = session.hasLog,
            hasManifest = session.hasManifest,
            syncStatus = session.syncStatus.name,
            lastError = session.lastError,
            lastSyncAttemptAt = session.lastSyncAttemptAt,
            remoteStoragePath = session.remoteStoragePath,
            createdAtMs = session.createdAtMs,
        )
    }
}
