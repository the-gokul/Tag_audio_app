package com.nordic.tagmobile.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionEntity)

    @Update
    suspend fun update(entity: SessionEntity)

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun get(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY createdAtMs DESC")
    suspend fun getAll(): List<SessionEntity>

    @Query(
        """
        SELECT * FROM sessions
        WHERE syncStatus IN ('PENDING', 'FAILED', 'UPLOADING')
        ORDER BY createdAtMs ASC
        """,
    )
    suspend fun getPendingOrFailed(): List<SessionEntity>

    @Query("SELECT syncStatus FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSyncStatus(sessionId: String): String?

    @Query(
        """
        UPDATE sessions SET syncStatus = :status, lastError = :lastError,
        lastSyncAttemptAt = :attemptAt, remoteStoragePath = :remotePath
        WHERE sessionId = :sessionId
        """,
    )
    suspend fun updateSyncState(
        sessionId: String,
        status: String,
        lastError: String?,
        attemptAt: Long?,
        remotePath: String?,
    )

    @Query("DELETE FROM sessions WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)
}

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserEntity)

    @Query("SELECT * FROM users WHERE localUserId = :localUserId LIMIT 1")
    suspend fun get(localUserId: String): UserEntity?
}

@Dao
interface PetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PetEntity)

    @Query("SELECT * FROM pets WHERE localPetId = :localPetId LIMIT 1")
    suspend fun get(localPetId: String): PetEntity?
}
