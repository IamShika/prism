package com.prism.musicplayer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Reads a whole song as 16-bit stereo, at the song's own sample rate (nothing is resampled, so nothing is lost).
 * The scratch engine plays from this.
 *
 * It follows the same steps as [AudioDecoder] (which makes mono sound for the BPM detector and waveform);
 * this one keeps both channels instead.
 */
object PcmDecoder {

    /**
     * Decodes the whole song. Each piece of sound is given to [sink] as (stereo samples, how many frames).
     * [expectedMs] is only used to show progress (0..1 to [onProgress]).
     * Returns the sample rate, or 0 if the file couldn't be read, was stopped by [shouldStop],
     * or changed its sample rate halfway (which can't be stored in one file).
     */
    fun decodeStereo(
        context: Context,
        uri: Uri,
        expectedMs: Long,
        onProgress: (Float) -> Unit,
        shouldStop: () -> Boolean,
        sink: (ShortArray, Int) -> Unit,
    ): Int {
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
            if (track < 0 || format == null) return 0
            extractor.selectTrack(track)

            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var floatPcm = false
            var emittedRate = 0 // the rate of the sound sent so far; it must never change

            var rawShorts = ShortArray(8192)
            var rawFloats = FloatArray(8192)
            var stereo = ShortArray(8192)
            val expectedFrames = (expectedMs * rate / 1000).coerceAtLeast(1L)
            var produced = 0L

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (shouldStop()) return 0
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
                    if (buffer != null && info.size > 0 && channels > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        val frames: Int
                        if (floatPcm) {
                            val samples = buffer.asFloatBuffer()
                            frames = samples.remaining() / channels
                            if (rawFloats.size < frames * channels) rawFloats = FloatArray(frames * channels)
                            samples.get(rawFloats, 0, frames * channels)
                            if (stereo.size < frames * 2) stereo = ShortArray(frames * 2)
                            PcmFormat.toStereo(rawFloats, frames, channels, stereo)
                        } else {
                            val samples = buffer.asShortBuffer()
                            frames = samples.remaining() / channels
                            if (rawShorts.size < frames * channels) rawShorts = ShortArray(frames * channels)
                            samples.get(rawShorts, 0, frames * channels)
                            if (stereo.size < frames * 2) stereo = ShortArray(frames * 2)
                            PcmFormat.toStereo(rawShorts, frames, channels, stereo)
                        }
                        if (frames > 0) {
                            if (emittedRate == 0) emittedRate = rate
                            if (rate != emittedRate) return 0
                            sink(stereo, frames)
                            produced += frames
                        }
                    }
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // the decoder tells us what it really produces
                    val actual = decoder.outputFormat
                    if (actual.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (actual.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    floatPcm = actual.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        actual.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                onProgress((produced.toFloat() / expectedFrames).coerceIn(0f, 1f))
            }
            return if (produced > 0) emittedRate else 0
        } catch (e: Exception) {
            return 0
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
