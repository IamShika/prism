package com.prism.musicplayer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Reads a song and turns it into raw sound, using the phone's own decoders (so it understands every
 * format the phone can play: mp3, m4a, flac, ogg, wav...).
 *
 * The BPM detector uses it now; the waveform view will use it later.
 */
object AudioDecoder {

    /**
     * Decodes up to [maxMs] of the song, starting at [startMs], as mono sound at [outRate] Hz.
     * The sound comes in pieces: each piece is given to [sink] as (samples, how many are valid).
     * [onProgress] goes from 0 to 1. Decoding stops early if [shouldStop] returns true.
     * Returns false if the file couldn't be read.
     */
    fun decodeMono(
        context: Context,
        uri: Uri,
        startMs: Long,
        maxMs: Long,
        outRate: Int,
        onProgress: (Float) -> Unit,
        shouldStop: () -> Boolean = { false },
        sink: (FloatArray, Int) -> Unit,
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) return false
            extractor.selectTrack(track)
            if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var inRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var floatPcm = false
            var resampler = Resampler(inRate, outRate)

            val maxOut = maxMs * outRate / 1000
            var produced = 0L
            val piece = FloatArray(4096)
            var pieceFill = 0
            val emit: (Float) -> Unit = { value ->
                piece[pieceFill++] = value
                if (pieceFill == piece.size) {
                    sink(piece, pieceFill)
                    produced += pieceFill
                    pieceFill = 0
                }
            }

            var mono = FloatArray(8192)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && produced < maxOut && !shouldStop()) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val out = decoder.dequeueOutputBuffer(info, 10_000)
                if (out >= 0) {
                    val buffer = decoder.getOutputBuffer(out)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        val frames: Int
                        if (floatPcm) {
                            val samples = buffer.asFloatBuffer()
                            frames = samples.remaining() / channels
                            if (mono.size < frames) mono = FloatArray(frames)
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += samples.get()
                                mono[f] = sum / channels
                            }
                        } else {
                            val samples = buffer.asShortBuffer()
                            frames = samples.remaining() / channels
                            if (mono.size < frames) mono = FloatArray(frames)
                            for (f in 0 until frames) {
                                var sum = 0
                                for (c in 0 until channels) sum += samples.get()
                                mono[f] = sum / (channels * 32768f)
                            }
                        }
                        resampler.push(mono, frames, emit)
                    }
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // the decoder tells us what it really produces
                    val actual = decoder.outputFormat
                    if (actual.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (actual.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (rate != inRate) {
                            inRate = rate
                            resampler = Resampler(inRate, outRate)
                        }
                    }
                    floatPcm = actual.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        actual.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                onProgress((produced.toFloat() / maxOut).coerceIn(0f, 1f))
            }
            if (pieceFill > 0) sink(piece, pieceFill)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
                // already stopped
            }
            codec?.release()
            extractor.release()
        }
    }
}
