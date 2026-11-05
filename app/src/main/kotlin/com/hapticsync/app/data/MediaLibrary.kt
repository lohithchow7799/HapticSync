package com.hapticsync.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Metadata of a user-selected media file. */
data class MediaInfo(
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val hasVideo: Boolean,
    val mimeType: String?,
)

/**
 * Reads titles, durations and thumbnails of media chosen through the Photo Picker or the
 * Storage Access Framework, and keeps read access to them across app restarts.
 */
class MediaLibrary(context: Context) {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver
    private val thumbnails = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Keeps read permission for [uri] after the app restarts (best effort). Returns whether the
     * file can be reopened later (a persistable grant, or a file this app owns).
     */
    fun persistAccess(uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return true
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        return try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            // Not all providers grant persistable permissions; playback still works this session.
            false
        }
    }

    /** Gives up the persisted grant of a file that is no longer listed. */
    fun releaseAccess(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Nothing was persisted for this file.
        }
    }

    suspend fun info(uri: Uri): MediaInfo = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        var duration = 0L
        var hasVideo = false
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(app, uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } catch (_: RuntimeException) {
            // Unreadable metadata is not fatal; the player reports real errors.
        } finally {
            runCatching { retriever.release() }
        }
        MediaInfo(uri, name ?: uri.lastPathSegment ?: "Video", duration, hasVideo, runCatching { resolver.getType(uri) }.getOrNull())
    }

    /** A small preview frame, or null if none can be produced. Cached in memory. */
    suspend fun thumbnail(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val key = uri.toString()
        thumbnails.get(key)?.let { return@withContext it }
        val bitmap = try {
            resolver.loadThumbnail(uri, Size(480, 270), null)
        } catch (_: Exception) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(app, uri)
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
        bitmap?.also { thumbnails.put(key, it) }
    }

    /** Whether [uri] can still be opened (it may have been deleted or its permission revoked). */
    suspend fun isAccessible(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return@withContext uri.path?.let { java.io.File(it).exists() } == true
        try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.lastPathSegment
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.substringBeforeLast('.')
        } catch (_: Exception) {
            null
        }
    }
}
