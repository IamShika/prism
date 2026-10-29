package com.prism.musicplayer.data

/** A folder that contains music. Every folder works as an "album", including the ones Prism creates. */
data class AlbumFolder(
    val path: String,
    val songCount: Int,
    val totalMs: Long,
    val totalBytes: Long = 0L,
    val newestAddedSec: Long = 0L,
    val oldestAddedSec: Long = 0L,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { "Unknown folder" }
}
