package com.nordic.tagmobile.data.cloud

import com.nordic.tagmobile.domain.model.CloudPetIds
import com.nordic.tagmobile.domain.model.CloudUser
import com.nordic.tagmobile.domain.model.CloudUserIds
import com.nordic.tagmobile.domain.model.UploadResult
import java.io.File

/**
 * Low-level cloud backend. Swap Supabase ↔ own HTTP server here.
 */
interface CloudBackend {
    suspend fun ensureSignedIn(localUserId: String, name: String, phone: String): CloudUser

    suspend fun upsertUser(localUserId: String, name: String, phone: String): CloudUserIds

    suspend fun upsertPet(
        localPetId: String,
        localUserId: String,
        cloudUserId: String,
        name: String,
        animalType: String,
        breed: String,
        sex: String,
        age: String,
        weightKg: String,
    ): CloudPetIds

    /**
     * Upload session folder files and register metadata.
     * @return remote storage path prefix on success
     */
    suspend fun uploadSessionPackage(
        sessionId: String,
        folder: File,
        metadata: Map<String, Any?>,
    ): String
}
