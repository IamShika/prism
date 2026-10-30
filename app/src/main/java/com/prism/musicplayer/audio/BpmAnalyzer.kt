package com.prism.musicplayer.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Reads a song and finds its tempo. Runs in the background; takes a few seconds. */
object BpmAnalyzer {

    /** 90 seconds from the middle of the song are enough, and far quicker than the whole song. */
    private const val WINDOW_MS = 90_000L

    suspend fun analyze(
        context: Context,
        uri: Uri,
        durationMs: Long,
        onProgress: (Float) -> Unit,
    ): BpmResult? = withContext(Dispatchers.Default) {
        val job = coroutineContext[Job]
        // skip intros and outros: take the middle of the song (the whole song if it is short)
        val start = if (durationMs > WINDOW_MS + 20_000L) durationMs / 2 - WINDOW_MS / 2 else 0L
        val detector = BpmDetector()
        val readable = AudioDecoder.decodeMono(
            context = context,
            uri = uri,
            startMs = start,
            maxMs = WINDOW_MS,
            outRate = BpmDetector.SAMPLE_RATE,
            onProgress = onProgress,
            shouldStop = { job?.isActive == false },
        ) { samples, count -> detector.push(samples, count) }
        if (readable) detector.result() else null
    }
}
