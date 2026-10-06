package com.nordic.tagmobile.storage

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Pre-record phone storage gate — avoids starting video when free space is too low.
 */
object StorageGate {
    /** Minimum free bytes on app files volume before allowing Start (~200 MB). */
    const val MIN_FREE_BYTES: Long = 200L * 1024L * 1024L

    data class Result(
        val ok: Boolean,
        val freeBytes: Long,
        val message: String,
    )

    fun check(context: Context): Result {
        val free = freeBytes(context.filesDir)
        if (free < 0L) {
            return Result(
                ok = true,
                freeBytes = free,
                message = "Storage check unavailable; proceeding",
            )
        }
        return if (free >= MIN_FREE_BYTES) {
            Result(ok = true, freeBytes = free, message = "OK")
        } else {
            Result(
                ok = false,
                freeBytes = free,
                message = "Not enough phone storage to record. Free at least 200 MB and try again. (Free: ${formatBytes(free)})",
            )
        }
    }

    private fun freeBytes(dir: File): Long {
        return try {
            val path = dir.absolutePath
            val stat = StatFs(path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            -1L
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
