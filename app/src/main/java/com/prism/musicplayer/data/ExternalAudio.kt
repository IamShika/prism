package com.prism.musicplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A music file that another app (like a file manager) asked us to open. */
data class OpenedAudio(
    val uri: Uri,
    /** The song's id in the phone's music library, or "-1" if it isn't in the library. */
    val mediaId: String,
    val title: String,
    val artist: String,
)

/**
 * Works out what to play when another app hands us a file.
 *
 * File managers often give us a temporary permission that ends when the small player closes,
 * and the music must keep playing after that. So we look for the same file in the phone's music
 * library, which we can always open. Only if it isn't there do we make a private copy.
 */
object ExternalAudio {

    private const val UNKNOWN_ARTIST = "Unknown artist"

    suspend fun resolve(context: Context, uri: Uri): OpenedAudio = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        // 1. It is already a song in the music library.
        if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
            findInLibrary(context, uri, null, null)?.let { return@withContext it }
        }

        // 2. A plain file path.
        if (uri.scheme == "file") {
            val name = uri.lastPathSegment ?: "Audio"
            return@withContext OpenedAudio(uri, "-1", titleFromName(name), UNKNOWN_ARTIST)
        }

        // 3. A file from another app: find the same file in the library by name and size.
        var name: String? = null
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        size = if (c.isNull(1)) -1L else c.getLong(1)
                    }
                }
        } catch (e: Exception) {
            // Some apps don't answer. We try copying below.
        }

        val fileName = name
        if (fileName != null) {
            var selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
            val args = mutableListOf(fileName)
            if (size >= 0) {
                selection += " AND ${MediaStore.Audio.Media.SIZE} = ?"
                args += size.toString()
            }
            findInLibrary(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, selection, args.toTypedArray())
                ?.let { return@withContext it }
        }

        // 4. Last resort: copy it into the app's private folder. Only the newest copy is kept.
        val dir = File(context.cacheDir, "opened").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val safeName = (fileName ?: "audio").replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val copy = File(dir, safeName)
        val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open $uri")
        input.use { source -> copy.outputStream().use { target -> source.copyTo(target) } }
        OpenedAudio(Uri.fromFile(copy), "-1", titleFromName(fileName ?: "Audio"), UNKNOWN_ARTIST)
    }

    private fun findInLibrary(context: Context, source: Uri, selection: String?, args: Array<String>?): OpenedAudio? {
        return try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
            )
            context.contentResolver.query(source, projection, selection, args, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val id = c.getLong(0)
                val artist = c.getString(2)
                OpenedAudio(
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    mediaId = id.toString(),
                    title = c.getString(1) ?: "Unknown title",
                    artist = if (artist.isNullOrBlank() || artist == "<unknown>") UNKNOWN_ARTIST else artist,
                )
            }
        } catch (e: Exception) {
            null // no permission to read the library, or it doesn't answer
        }
    }

    private fun titleFromName(name: String) = name.substringBeforeLast('.').ifBlank { name }
}
