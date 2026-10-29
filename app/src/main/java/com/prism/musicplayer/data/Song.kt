package com.prism.musicplayer.data

import android.net.Uri

/** One song found on the phone. */
data class Song(
    override val id: Long,
    override val title: String,
    override val artist: String,
    val durationMs: Long,
    val uri: Uri,
    /** The folder the file lives in, e.g. /storage/emulated/0/Music/Road Trip. Empty if unknown. */
    override val folderPath: String = "",
    /** File size in bytes. */
    override val sizeBytes: Long = 0L,
    /** When the file was added to the phone (seconds since 1970). */
    override val addedSec: Long = 0L,
) : Sortable
