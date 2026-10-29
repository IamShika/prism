package com.prism.musicplayer.data

import android.net.Uri

/** One song found on the phone. */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: Uri,
    /** The folder the file lives in, e.g. /storage/emulated/0/Music/Road Trip. Empty if unknown. */
    val folderPath: String = "",
)

/** A folder that contains music. Every folder works as an "album", including the ones Prism creates. */
data class AlbumFolder(
    val path: String,
    val songCount: Int,
    val totalMs: Long,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { "Unknown folder" }
}
