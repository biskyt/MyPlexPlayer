package com.biskyt.mytvplayer.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore.Downloads
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The backup file, Download/MyTVPlayer/mytvplayer-backup.json, written through MediaStore: Android 11+ needs no storage
 * permission for that, and file managers can copy it elsewhere. Files the app didn't write (copied in, or from before a
 * reinstall) aren't visible to it, so those are opened through the system file picker instead.
 */
class BackupFile(private val context: Context) {
    data class Saved(val uri: Uri, val name: String, val modifiedAt: Long) {
        val path: String get() = "$FOLDER$name"
    }

    private val resolver get() = context.contentResolver

    /** The file this install last wrote, if it is still there. */
    suspend fun find(): Saved? = withContext(Dispatchers.IO) {
        resolver.query(
            COLLECTION,
            arrayOf(Downloads._ID, Downloads.DISPLAY_NAME, Downloads.DATE_MODIFIED),
            "${Downloads.RELATIVE_PATH} = ? AND ${Downloads.DISPLAY_NAME} LIKE 'mytvplayer-backup%'",
            arrayOf(FOLDER),
            "${Downloads.DATE_MODIFIED} DESC"
        )?.use { c ->
            if (c.moveToFirst()) Saved(ContentUris.withAppendedId(COLLECTION, c.getLong(0)), c.getString(1), c.getLong(2) * 1000) else null
        }
    }

    /**
     * Overwrites this install's file with [bytes], or creates it. If a file of the same name it can't see is in the way
     * (from before a reinstall), Android saves this one as "mytvplayer-backup (1).json"; [Saved.path] says where it went.
     */
    suspend fun write(bytes: ByteArray): Saved = withContext(Dispatchers.IO) {
        val existing = find()?.uri
        // A new file stays hidden from other apps until it is complete.
        val uri = existing ?: resolver.insert(
            COLLECTION,
            ContentValues().apply {
                put(Downloads.DISPLAY_NAME, NAME)
                put(Downloads.MIME_TYPE, "application/json")
                put(Downloads.RELATIVE_PATH, FOLDER)
                put(Downloads.IS_PENDING, 1)
            }
        ) ?: throw IOException("Couldn't create the backup file")
        val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("Couldn't write the backup file")
        out.use { it.write(bytes) }
        if (existing == null) resolver.update(uri, ContentValues().apply { put(Downloads.IS_PENDING, 0) }, null, null)
        find() ?: throw IOException("Couldn't save the backup file")
    }

    /** Hands [read] the file's contents as they stream in. */
    suspend fun <T> read(uri: Uri, read: suspend (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")
        input.buffered().use { read(it) }
    }

    companion object {
        const val FOLDER = "Download/MyTVPlayer/"
        const val NAME = "mytvplayer-backup.json"
        private val COLLECTION = Downloads.EXTERNAL_CONTENT_URI

        /** Where the file picker opens, so the backup is one press away. */
        val PICKER_START: Uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FMyTVPlayer")
    }
}
