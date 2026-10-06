package com.nordic.tagmobile.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, UserEntity::class, PetEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TagDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun userDao(): UserDao
    abstract fun petDao(): PetDao

    companion object {
        @Volatile
        private var instance: TagDatabase? = null

        fun get(context: Context): TagDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TagDatabase::class.java,
                    "tag_mobile.db",
                ).build().also { instance = it }
            }
    }
}
