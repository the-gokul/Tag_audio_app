package com.nordic.tagmobile.data.cloud.server

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 of session files, cached by path + size + modified time so a 5 GB video
 * is hashed once, not on every upload retry.
 */
object FileHashCache {
    private const val PREFS = "tag_file_hash_cache_v1"

    fun sha256(context: Context, file: File): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = "${file.length()}:${file.lastModified()}"
        val cached = prefs.getString(file.absolutePath, null)
        if (cached != null && cached.startsWith("$stamp:")) return cached.substringAfterLast(':')

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        prefs.edit().putString(file.absolutePath, "$stamp:$hex").apply()
        return hex
    }
}
