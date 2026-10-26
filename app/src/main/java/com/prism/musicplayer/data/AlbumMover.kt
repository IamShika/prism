package com.prism.musicplayer.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Moves songs into an album folder (Music/<name>/).
 *
 * Android 10 and newer: we ask MediaStore (the phone's music index) to change the song's folder, and
 * Android moves the file for us. On Android 11+ the user must first allow this in a system dialog
 * (see PlayerViewModel.createAlbum), which is what makes it safe to touch other apps' files.
 *
 * Android 9 and older: we move the files ourselves and tell MediaStore about it.
 */
object AlbumMover {

    class Result(val moved: Int, val failed: Int)

    suspend fun move(context: Context, songs: List<Song>, folder: String): Result = withContext(Dispatchers.IO) {
        var moved = 0
        var failed = 0
        for (song in songs) {
            val ok = try {
                if (Build.VERSION.SDK_INT >= 29) moveWithMediaStore(context, song, folder) else moveWithFiles(context, song, folder)
            } catch (e: Exception) {
                false // one broken file must not stop the others
            }
            if (ok) moved++ else failed++
        }
        Result(moved, failed)
    }

    private fun moveWithMediaStore(context: Context, song: Song, folder: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, AlbumNaming.relativePath(folder))
        }
        return context.contentResolver.update(song.uri, values, null, null) > 0
    }

    @Suppress("DEPRECATION")
    private fun moveWithFiles(context: Context, song: Song, folder: String): Boolean {
        val path = context.contentResolver.query(
            song.uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return false

        val source = File(path)
        if (!source.exists()) return false

        val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), folder)
        if (!targetDir.exists() && !targetDir.mkdirs()) return false
        if (source.parentFile?.canonicalPath == targetDir.canonicalPath) return true // already there

        val name = AlbumNaming.uniqueName(source.name, targetDir.list()?.toList().orEmpty())
        val target = File(targetDir, name)
        if (!source.renameTo(target)) {
            // renameTo fails between different storage volumes (e.g. SD card -> phone): copy, then delete
            source.copyTo(target, overwrite = false)
            if (!source.delete()) {
                target.delete()
                return false
            }
        }
        // Tell Android: the old file is gone, the new one exists.
        MediaScannerConnection.scanFile(context, arrayOf(source.absolutePath, target.absolutePath), null, null)
        return true
    }
}
