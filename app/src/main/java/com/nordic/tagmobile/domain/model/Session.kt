package com.nordic.tagmobile.domain.model

/**
 * Domain session index (metadata only).
 * Binary files stay on disk under [folderPath].
 */
data class Session(
    val sessionId: String,
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
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val lastError: String? = null,
    val lastSyncAttemptAt: Long? = null,
    val remoteStoragePath: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
)
