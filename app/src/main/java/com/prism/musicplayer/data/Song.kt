package com.prism.musicplayer.data

import android.net.Uri

/** One song found on the phone. */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: Uri,
)
