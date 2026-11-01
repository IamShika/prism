package com.prism.musicplayer.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Reads a whole song and draws its waveform. Runs in the background; takes a few seconds. */
object WaveformAnalyzer {

    /** Enough detail to draw the shape; a lower rate also means faster. */
    private const val RATE = 11025

    suspend fun build(
        context: Context,
        uri: Uri,
        durationMs: Long,
        onProgress: (Float) -> Unit,
    ): Waveform? = withContext(Dispatchers.Default) {
        val job = coroutineContext[Job]
        val builder = WaveformBuilder(RATE)
        val readable = AudioDecoder.decodeMono(
            context = context,
            uri = uri,
            startMs = 0L,
            maxMs = durationMs + 2000L, // a little extra, in case the length is slightly off
            outRate = RATE,
            onProgress = onProgress,
            shouldStop = { job?.isActive == false },
        ) { samples, count -> builder.push(samples, count) }
        if (readable) builder.build(durationMs) else null
    }
}
