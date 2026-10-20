package com.biskyt.mytvplayer.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore.Downloads
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The backup file, Download/MyTVPlayer/mytvplayer-backup.json, written through MediaStore: Android 10+ needs no storage
 * permission for that, and file managers can copy it elsewhere. Files the app didn't write (copied in, or from before a
 * reinstall) aren't visible to it, so those are opened through the system file picker instead.
 *
 * Android 9 has no MediaStore downloads: there the file is written directly, which needs the storage permission
 * ([needsPermission]). With it, the app sees the file whoever wrote it (e.g. an install before a reinstall), and
 * [write] replaces it: callers check [find] first so a backup the user hasn't seen isn't lost.
 */
class BackupFile(private val context: Context) {
    private val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Android 9 only: the storage permission is needed (and not granted yet) to read or write the file. */
    val needsPermission: Boolean
        get() = legacy && ContextCompat.checkSelfPermission(context, STORAGE_PERMISSION) != PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION") // the only way on Android 9, the one version this is used on
    private val legacyFile get() = File(Environment.getExternalStorageDirectory(), FOLDER + NAME)

    data class Saved(val uri: Uri, val name: String, val modifiedAt: Long) {
        val path: String get() = "$FOLDER$name"
    }

    private val resolver get() = context.contentResolver

    /** The file this install last wrote, if it is still there. */
    suspend fun find(): Saved? = withContext(Dispatchers.IO) {
        if (legacy) {
            legacyFile.takeIf { !needsPermission && it.exists() }?.let { Saved(Uri.fromFile(it), it.name, it.lastModified()) }
        } else {
            findInMediaStore()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findInMediaStore(): Saved? {
        return resolver.query(
            Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(Downloads._ID, Downloads.DISPLAY_NAME, Downloads.DATE_MODIFIED),
            "${Downloads.RELATIVE_PATH} = ? AND ${Downloads.DISPLAY_NAME} LIKE 'mytvplayer-backup%'",
            arrayOf(FOLDER),
            "${Downloads.DATE_MODIFIED} DESC"
        )?.use { c ->
            if (c.moveToFirst()) {
                Saved(
                    ContentUris.withAppendedId(Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)),
                    c.getString(1),
                    c.getLong(2) * 1000
                )
            } else {
                null
            }
        }
    }

    /**
     * Overwrites this install's file with [bytes], or creates it. If a file of the same name it can't see is in the way
     * (from before a reinstall), Android saves this one as "mytvplayer-backup (1).json"; [Saved.path] says where it went.
     */
    suspend fun write(bytes: ByteArray): Saved = withContext(Dispatchers.IO) {
        if (legacy) {
            writeLegacy(bytes)
        } else {
            writeToMediaStore(bytes)
            findInMediaStore() ?: throw IOException("Couldn't save the backup file")
        }
    }

    /**
     * Android 9: written beside the file first, then put in its place, so a failed write never leaves half a backup;
     * then shown to the media index, so file managers and a computer over USB see it.
     */
    private fun writeLegacy(bytes: ByteArray): Saved {
        if (needsPermission) throw BackupException("MyTVPlayer needs permission to save to the Download folder.")
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            throw BackupException("This TV's storage isn't available right now, so the backup can't be saved.")
        }
        val target = legacyFile
        val folder = target.parentFile
        if (folder != null && !folder.isDirectory && !folder.mkdirs()) {
            throw BackupException("Couldn't create the folder Download/MyTVPlayer for the backup.")
        }
        val part = File(target.path + ".part")
        try {
            part.writeBytes(bytes)
            if (!part.renameTo(target)) throw IOException("Couldn't save the backup file")
        } finally {
            part.delete() // gone already once renamed
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
        return Saved(Uri.fromFile(target), target.name, target.lastModified())
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToMediaStore(bytes: ByteArray) {
        val existing = findInMediaStore()?.uri
        // A new file stays hidden from other apps until it is complete.
        val uri = existing ?: resolver.insert(
            Downloads.EXTERNAL_CONTENT_URI,
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
    }

    /** Hands [read] the file's contents as they stream in. */
    suspend fun <T> read(uri: Uri, read: suspend (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")
        input.buffered().use { read(it) }
    }

    companion object {
        const val FOLDER = "Download/MyTVPlayer/"
        const val NAME = "mytvplayer-backup.json"

        /** Asked for on Android 9 only (the manifest limits it to that). */
        const val STORAGE_PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE

        /** Where the file picker opens, so the backup is one press away. */
        val PICKER_START: Uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FMyTVPlayer")
    }
}
