package com.prism.musicplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

/**
 * The bridge between the music player and [ReverbEngine].
 *
 * The player hands over small chunks of sound (16-bit numbers). We turn them into decimal numbers,
 * let the reverb engine add its effect, and hand them back. When the reverb is switched off the
 * sound passes through untouched.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ReverbAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var params = ReverbParams()
    private var engine: ReverbEngine? = null
    private var inputChannels = 2
    private var wasEnabled = false
    private var scratch = FloatArray(0)

    /** Called from the screen whenever a knob moves. */
    fun setParams(newParams: ReverbParams) {
        params = newParams
        engine?.setParams(newParams)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.channelCount in 1..2
        if (!supported) {
            // Unusual audio formats just play without reverb instead of failing.
            engine = null
            return AudioProcessor.AudioFormat.NOT_SET
        }
        inputChannels = inputAudioFormat.channelCount
        engine = ReverbEngine(inputAudioFormat.sampleRate).also { it.setParams(params) }
        wasEnabled = false
        // Always output stereo, so a mono song also gets a stereo reverb.
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, C.ENCODING_PCM_16BIT, 2)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val reverb = engine ?: return
        val frames = inputBuffer.remaining() / (2 * inputChannels)
        if (frames == 0) return

        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)

        // 16-bit whole numbers -> decimal numbers between -1 and 1
        var pos = inputBuffer.position()
        for (i in 0 until frames) {
            val left = inputBuffer.getShort(pos)
            pos += 2
            val right = if (inputChannels == 2) {
                val r = inputBuffer.getShort(pos)
                pos += 2
                r
            } else {
                left
            }
            scratch[i * 2] = left / 32768f
            scratch[i * 2 + 1] = right / 32768f
        }
        inputBuffer.position(pos)

        val current = params
        if (current.enabled) {
            if (!wasEnabled) reverb.reset() // start with a clean, silent room
            reverb.process(scratch, 0, frames)
        }
        wasEnabled = current.enabled

        // back to 16-bit numbers, with a soft limiter so loud reverb never crackles
        val out = replaceOutputBuffer(frames * 4)
        for (i in 0 until frames * 2) {
            out.putShort((softLimit(scratch[i]) * 32767f).toInt().toShort())
        }
        out.flip()
    }

    private fun softLimit(x: Float): Float {
        val a = abs(x)
        return if (a <= 0.85f) x else sign(x) * (0.85f + 0.15f * tanh((a - 0.85f) / 0.15f))
    }
}

/**
 * Puts the reverb AFTER the speed/pitch changer, so the room sound itself is not
 * stretched or pitched when you change the song's speed.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ReverbProcessorChain(private val reverb: ReverbAudioProcessor) :
    DefaultAudioSink.DefaultAudioProcessorChain() {

    override fun getAudioProcessors(): Array<AudioProcessor> {
        val standard: Array<AudioProcessor> = super.getAudioProcessors()
        return standard + reverb // the speed/pitch changer comes first, our reverb last
    }
}
