package com.nordic.tagmobile.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val localUserId: String,
    val cloudUserId: String? = null,
    val name: String = "",
    val phone: String = "",
    val updatedAtMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "pets")
data class PetEntity(
    @PrimaryKey val localPetId: String,
    val cloudPetId: String? = null,
    val localUserId: String = "",
    val name: String = "",
    val animalType: String = "",
    val breed: String = "",
    val sex: String = "",
    val age: String = "",
    val weightKg: String = "",
    val updatedAtMs: Long = System.currentTimeMillis(),
)
