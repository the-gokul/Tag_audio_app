package com.nordic.tagmobile.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import java.io.File

/**
 * Publishes app recordings to public Movies/Tag (system Gallery) and
 * permanently removes those MediaStore rows on History delete.
 */
object GalleryPublisher {

    /**
     * @param displayName optional Gallery filename. Defaults to [videoFile] name
     * (session packages use SESSION-….mp4).
     */
    fun publishVideo(context: Context, videoFile: File, displayName: String? = null): Uri? {
        if (!videoFile.exists() || videoFile.length() <= 0L) return null
        val name = displayName?.takeIf { it.isNotBlank() } ?: videoFile.name
        // Replace any prior gallery copy with the same name (e.g. re-burn)
        deleteByDisplayName(context, name)
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Tag")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                videoFile.inputStream().use { input -> input.copyTo(out) }
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            TagLogger.log(LogCategory.FILE, "GALLERY_PUBLISH_OK", name)
            uri
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "GALLERY_PUBLISH_FAIL", e.message ?: "")
            null
        }
    }

    /** Complete erase from Gallery / MediaStore (by saved URI and/or file name). */
    fun deletePublished(context: Context, displayName: String?, galleryUri: String?) {
        if (!galleryUri.isNullOrBlank()) {
            try {
                context.contentResolver.delete(Uri.parse(galleryUri), null, null)
            } catch (e: Exception) {
                TagLogger.log(LogCategory.ERRORS, "GALLERY_DELETE_URI_FAIL", e.message ?: "")
            }
        }
        if (!displayName.isNullOrBlank()) {
            deleteByDisplayName(context, displayName)
        }
    }

    fun deleteByDisplayName(context: Context, displayName: String) {
        try {
            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // EXTERNAL covers all volumes (more reliable than PRIMARY alone)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            var deleted = 0
            resolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME}=?",
                arrayOf(displayName),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    deleted += resolver.delete(uri, null, null)
                }
            }
            // Also wipe leftover public files some OEMs keep indexed
            try {
                val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                File(File(movies, "Tag"), displayName).delete()
                File(movies, displayName).delete()
            } catch (_: Exception) {
            }
            TagLogger.log(LogCategory.FILE, "GALLERY_DELETE_OK", "$displayName count=$deleted")
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "GALLERY_DELETE_FAIL", e.message ?: "")
        }
    }
}
