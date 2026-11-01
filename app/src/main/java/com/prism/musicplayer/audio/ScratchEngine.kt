package com.prism.musicplayer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import kotlin.math.max

/**
 * Plays a song under your finger: forwards, backwards, fast or slow, like a turntable.
 *
 * While you scrub or hold Rewind, the normal player is paused and this takes over from the same spot;
 * when you let go, the normal player continues from wherever this one ended up.
 * The sound itself is made by [ScratchRenderer] (tested on a computer); this class only connects it to the phone's speaker.
 */
class ScratchEngine {

    private var track: AudioTrack? = null
    private var renderer: ScratchRenderer? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var stopRequested = false

    /** True when a song is loaded and the speaker is ready. */
    val loaded: Boolean get() = renderer != null

    /** Where the playhead is, in milliseconds from the start of the song. */
    val positionMs: Long get() = renderer?.positionMs ?: 0L

    /** Gets ready to scratch [pcm]. Returns false if the phone wouldn't give us a speaker connection. */
    @Synchronized
    fun load(pcm: PcmSource): Boolean {
        unloadLocked()
        try {
            val nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
            val rate = if (nativeRate >= 8000) nativeRate else 48000
            val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes <= 0) return false
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBytes, CHUNK_FRAMES * 4 * 6)) // small buffer = the sound follows the finger quickly
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                audioTrack.release()
                return false
            }
            track = audioTrack
            renderer = ScratchRenderer(pcm, rate)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /** Lets go of the song and the speaker connection. */
    @Synchronized
    fun unload() = unloadLocked()

    private fun unloadLocked() {
        val old = track
        track = null
        renderer = null
        worker = null // the sound thread notices and ends
        stopRequested = true
        try {
            old?.pause()
            old?.flush()
            old?.release()
        } catch (e: Exception) {
            // nothing to do
        }
    }

    /** Starts the sound at [positionMs]. Nothing is heard until you [follow] or [run]. Returns false if it couldn't start. */
    @Synchronized
    fun begin(positionMs: Long): Boolean {
        val r = renderer ?: return false
        val t = track ?: return false
        r.start(positionMs)
        stopRequested = false
        if (worker == null) {
            try {
                t.play()
            } catch (e: Exception) {
                return false
            }
            val thread = Thread({ loop(r, t) }, "prism-scratch")
            worker = thread
            thread.start()
        }
        return true
    }

    /** The playhead should be at [targetMs] (a finger holding the record there). */
    fun follow(targetMs: Long) {
        renderer?.follow(targetMs)
    }

    /** Play at [speed]: 1.0 normal, 4.0 four times faster, -3.0 backwards at triple speed. */
    fun run(speed: Double) {
        renderer?.run(speed)
    }

    /** Fades out and stops. Returns where the playhead ended up. */
    @Synchronized
    fun end(): Long {
        val r = renderer ?: return 0L
        r.fadeOut()
        stopRequested = true
        return r.positionMs
    }

    private fun loop(r: ScratchRenderer, t: AudioTrack) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
            // not allowed: run at normal priority
        }
        val buffer = ShortArray(CHUNK_FRAMES * 2)
        try {
            while (worker === Thread.currentThread()) {
                r.render(buffer, CHUNK_FRAMES)
                if (t.write(buffer, 0, buffer.size) < 0) break // the speaker connection was lost
                if (stopRequested && r.silent) {
                    synchronized(this) {
                        // begin() may have restarted us a moment ago; only stop if nobody did
                        if (stopRequested) {
                            t.pause()
                            t.flush()
                            if (worker === Thread.currentThread()) worker = null
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // the track was released under us
        }
        synchronized(this) {
            if (worker === Thread.currentThread()) worker = null
        }
    }

    private companion object {
        /** About 5 milliseconds of sound per round. */
        const val CHUNK_FRAMES = 256
    }
}
