package com.nordic.tagmobile.data.cloud

import com.nordic.tagmobile.domain.model.CloudPetIds
import com.nordic.tagmobile.domain.model.CloudUser
import com.nordic.tagmobile.domain.model.CloudUserIds
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import kotlinx.coroutines.delay
import java.io.File

/**
 * Development stub: pretends upload succeeded.
 * Replace with SupabaseCloudBackend / HttpCloudBackend without changing UI.
 */
class StubCloudBackend : CloudBackend {
    override suspend fun ensureSignedIn(localUserId: String, name: String, phone: String): CloudUser {
        val cloudId = "stub-user-$localUserId"
        return CloudUser(cloudUserId = cloudId, localUserId = localUserId, name = name, phone = phone)
    }

    override suspend fun upsertUser(localUserId: String, name: String, phone: String): CloudUserIds {
        return CloudUserIds(cloudUserId = "stub-user-$localUserId", localUserId = localUserId)
    }

    override suspend fun upsertPet(
        localPetId: String,
        localUserId: String,
        cloudUserId: String,
        name: String,
        animalType: String,
        breed: String,
        sex: String,
        age: String,
        weightKg: String,
    ): CloudPetIds {
        return CloudPetIds(cloudPetId = "stub-pet-$localPetId", localPetId = localPetId)
    }

    override suspend fun uploadSessionPackage(
        sessionId: String,
        folder: File,
        metadata: Map<String, Any?>,
    ): String {
        delay(400)
        val remote = "stub://sessions/$sessionId"
        TagLogger.log(
            LogCategory.APP,
            "CLOUD_STUB_UPLOAD",
            "session=$sessionId folder=${folder.absolutePath} remote=$remote files=${folder.list()?.size ?: 0}",
        )
        return remote
    }
}
