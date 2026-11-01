package com.prism.musicplayer.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** A whole song as raw stereo sound that can be read at any position. */
interface PcmSource {
    /** How many stereo frames (one left + one right sample) the song has. */
    val frames: Int
    val sampleRate: Int
    fun left(frame: Int): Float
    fun right(frame: Int): Float
}

/** A [PcmSource] held in memory (interleaved left, right, left, right...). Used by the tests. */
class ArrayPcm(override val sampleRate: Int, private val interleaved: ShortArray) : PcmSource {
    override val frames: Int get() = interleaved.size / 2
    override fun left(frame: Int): Float = interleaved[frame * 2] / 32768f
    override fun right(frame: Int): Float = interleaved[frame * 2 + 1] / 32768f
}

/**
 * The heart of the "scratch" sound: plays a song at ANY speed, forwards or backwards, like a turntable.
 *
 * Two ways to drive it:
 *  - [follow]: "the playhead should be here". It chases that spot, like a record held under your finger.
 *    When your finger stops, the sound stops. When your finger moves back, you hear the song backwards.
 *  - [run]: "play at this speed". 1.0 is normal, 3.0 is three times faster, -2.0 is backwards at double speed.
 *
 * Like a real record, speed changes pitch (faster = higher). Nothing here knows about Android: it just
 * fills arrays with sound, so it can be tested on a normal computer (tools/scratch-test/run.sh).
 * The phone's audio thread calls [render] over and over; the screen calls the other functions.
 */
class ScratchRenderer(private val source: PcmSource, private val outRate: Int) {

    private val lock = Any()
    private val lastFrame = (source.frames - 1).coerceAtLeast(0).toDouble()
    private val framesPerOut = source.sampleRate.toDouble() / outRate // source frames per output frame at speed 1
    private val maxLagFrames = MAX_LAG_S * source.sampleRate
    private val speedSmoothing = 1.0 - exp(-1.0 / (outRate * SPEED_SMOOTH_S))
    private val gainStep = (1.0 / (outRate * GAIN_RAMP_S)).toFloat()

    private var position = 0.0       // in source frames
    private var speed = 0.0          // song seconds per second, smoothed so speed changes don't click
    private var gain = 0f
    private var gainTarget = 0f
    private var following = true
    private var target = 0.0         // follow mode: where the playhead should be, in source frames
    private var velocity = 0.0       // run mode: the wanted speed

    // the sound of the frame being made (kept as fields so rendering doesn't create garbage)
    private var sumL = 0f
    private var sumR = 0f

    /** True when nothing is audible and nothing will be. The audio thread may stop then. */
    val silent: Boolean get() = synchronized(lock) { gain == 0f && gainTarget == 0f }

    /** Where the playhead is, in milliseconds from the start of the song. */
    val positionMs: Long get() = synchronized(lock) { (position * 1000.0 / source.sampleRate).toLong() }

    /** Start (or restart) at [positionMs]. The sound fades in over a few milliseconds. */
    fun start(positionMs: Long) = synchronized(lock) {
        if (gain == 0f && gainTarget == 0f) speed = 0.0
        position = msToFrame(positionMs)
        target = position
        velocity = 0.0
        following = true
        gainTarget = 1f
    }

    /** The playhead should go to [targetMs]. */
    fun follow(targetMs: Long) = synchronized(lock) {
        following = true
        target = msToFrame(targetMs)
    }

    /** Play at [speed] times normal speed. Negative plays backwards. */
    fun run(speed: Double) = synchronized(lock) {
        following = false
        velocity = if (speed.isNaN()) 0.0 else speed.coerceIn(-MAX_SPEED, MAX_SPEED)
    }

    /** Fade the sound out over a few milliseconds. After that [silent] is true. */
    fun fadeOut() = synchronized(lock) { gainTarget = 0f }

    /** Fills [out] with [frames] stereo frames (left, right, left, right...). */
    fun render(out: ShortArray, frames: Int) = synchronized(lock) {
        for (n in 0 until frames) {
            if (gain == 0f && gainTarget == 0f) {
                out[n * 2] = 0
                out[n * 2 + 1] = 0
                continue
            }

            if (following) {
                // a record can't be dragged half a song away: if the finger is far ahead, the sound catches up quickly
                val gap = target - position
                if (abs(gap) > maxLagFrames) position = target - sign(gap) * maxLagFrames
            }
            val wanted = if (following) {
                ((target - position) / source.sampleRate / FOLLOW_LAG_S).coerceIn(-MAX_SPEED, MAX_SPEED)
            } else {
                velocity
            }
            speed += (wanted - speed) * speedSmoothing

            val step = speed * framesPerOut
            var next = position + step
            if (next <= 0.0) {
                next = 0.0
                if (speed < 0.0) speed = 0.0
            } else if (next >= lastFrame) {
                next = lastFrame
                if (speed > 0.0) speed = 0.0
            }
            position = next

            sampleAt(position, step)

            if (gain < gainTarget) gain = min(gain + gainStep, gainTarget)
            else if (gain > gainTarget) gain = maxOf(gain - gainStep, gainTarget)

            out[n * 2] = toShort(sumL * gain)
            out[n * 2 + 1] = toShort(sumR * gain)
        }
    }

    /**
     * Reads the song around [pos]. Slow speeds use a simple blend between neighbouring samples.
     * Fast speeds skip over many samples per output sample, which would sound harsh and metallic
     * (high notes folding back into the audible range), so several are averaged first.
     */
    private fun sampleAt(pos: Double, step: Double) {
        val taps = if (abs(step) > 1.5) min(ceil(abs(step)).toInt(), MAX_TAPS) else 1
        var l = 0f
        var r = 0f
        for (k in 0 until taps) {
            val p = if (taps == 1) pos else pos + step * ((k + 0.5) / taps - 0.5)
            val c = p.coerceIn(0.0, lastFrame)
            val i = floor(c).toInt()
            val j = min(i + 1, lastFrame.toInt())
            val f = (c - i).toFloat()
            l += source.left(i) * (1f - f) + source.left(j) * f
            r += source.right(i) * (1f - f) + source.right(j) * f
        }
        sumL = l / taps
        sumR = r / taps
    }

    private fun toShort(v: Float): Short = (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()

    private fun msToFrame(ms: Long): Double = (ms * source.sampleRate / 1000.0).coerceIn(0.0, lastFrame)

    companion object {
        /** The fastest the song may be played, forwards or backwards. */
        const val MAX_SPEED = 8.0
        /** Follow mode: how quickly the playhead closes in on the finger (smaller = tighter, more nervous). */
        const val FOLLOW_LAG_S = 0.045
        /** The sound may never trail the finger by more than this. */
        const val MAX_LAG_S = 0.5
        const val SPEED_SMOOTH_S = 0.006
        const val GAIN_RAMP_S = 0.008
        const val MAX_TAPS = 8
    }
}
