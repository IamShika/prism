package com.prism.musicplayer.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * How a decoded song is stored on the phone so the scratch engine can read it quickly.
 *
 * A file is a 12-byte header (the marker "PCM1", the sample rate, the number of frames) followed by the
 * sound itself as 16-bit stereo, left then right, little-endian. No Android code in here, so it can be
 * tested on a normal computer (tools/scratch-test/run.sh).
 */
object PcmFormat {
    private const val MAGIC = 0x50434D31 // "PCM1"
    const val HEADER_BYTES = 12
    const val BYTES_PER_FRAME = 4

    class Header(val sampleRate: Int, val frames: Int)

    fun header(sampleRate: Int, frames: Int): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC).putInt(sampleRate).putInt(frames).array()

    /**
     * Reads a header. Returns null if the file is not one of ours, or is cut short (for example because
     * the phone ran out of space or the app was closed while it was being written), so a bad file is never played.
     */
    fun parseHeader(bytes: ByteArray, fileLength: Long): Header? {
        if (bytes.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != MAGIC) return null
        val rate = buffer.int
        val frames = buffer.int
        if (rate !in 8000..192000 || frames <= 0) return null
        if (fileLength != HEADER_BYTES + frames.toLong() * BYTES_PER_FRAME) return null
        return Header(rate, frames)
    }

    /** Turns decoded 16-bit sound with any number of channels into stereo. Mono is copied to both sides; surround keeps front left and right. */
    fun toStereo(input: ShortArray, frames: Int, channels: Int, out: ShortArray) {
        if (channels <= 0) return
        for (f in 0 until frames) {
            if (channels == 1) {
                out[f * 2] = input[f]
                out[f * 2 + 1] = input[f]
            } else {
                out[f * 2] = input[f * channels]
                out[f * 2 + 1] = input[f * channels + 1]
            }
        }
    }

    /** Same, for decoders that give floating-point sound (-1..1). */
    fun toStereo(input: FloatArray, frames: Int, channels: Int, out: ShortArray) {
        if (channels <= 0) return
        for (f in 0 until frames) {
            val l = input[f * channels]
            val r = if (channels == 1) l else input[f * channels + 1]
            out[f * 2] = (l * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            out[f * 2 + 1] = (r * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
