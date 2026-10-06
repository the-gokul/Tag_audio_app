package com.nordic.tagmobile.domain.model

data class CloudUser(
    val cloudUserId: String,
    val localUserId: String,
    val name: String = "",
    val phone: String = "",
)

data class CloudUserIds(
    val cloudUserId: String,
    val localUserId: String,
)

data class CloudPetIds(
    val cloudPetId: String,
    val localPetId: String,
)

sealed class UploadResult {
    abstract val sessionId: String

    data class Success(
        override val sessionId: String,
        val remotePath: String,
    ) : UploadResult()

    data class Failed(
        override val sessionId: String,
        val message: String,
        val retryable: Boolean = true,
    ) : UploadResult()

    data class AlreadySynced(
        override val sessionId: String,
    ) : UploadResult()
}
