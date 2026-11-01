package com.prism.musicplayer.data

import android.content.Context
import com.prism.musicplayer.audio.Waveform
import java.io.File

/**
 * Keeps finished waveforms in the app's cache folder, so each song is only analysed once.
 * Android may clear the cache when the phone is short on space; then the waveform is simply drawn again.
 */
class WaveformCache(context: Context) {

    private val folder = File(context.applicationContext.cacheDir, "waveforms").also { it.mkdirs() }

    // the length is part of the name, so a replaced file with the same id isn't mixed up with the old one
    private fun fileFor(songId: Long, durationMs: Long) = File(folder, "$songId-$durationMs.pwf")

    fun load(songId: Long, durationMs: Long): Waveform? {
        val file = fileFor(songId, durationMs)
        if (!file.exists()) return null
        return try {
            Waveform.fromBytes(file.readBytes()) ?: run { file.delete(); null }
        } catch (e: Exception) {
            null
        }
    }

    fun save(songId: Long, durationMs: Long, wave: Waveform) {
        try {
            fileFor(songId, durationMs).writeBytes(wave.toBytes())
        } catch (e: Exception) {
            // not saving is fine, it will be drawn again next time
        }
    }
}
