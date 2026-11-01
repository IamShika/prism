package com.prism.musicplayer.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The shape of a song's sound over time, for drawing: for every slice of [1000 / peaksPerSec] milliseconds,
 * the lowest and highest sound level in it. Levels are stored in one byte each (-127..127).
 *
 * No Android code in here, so it can be tested on a normal computer (tools/wave-test/run.sh).
 */
class Waveform(
    val peaksPerSec: Int,
    val min: ByteArray,
    val max: ByteArray,
    val durationMs: Long,
) {
    val count: Int get() = min.size

    /** The loudest level in the song, 0..1. Used to scale the picture so quiet songs still fill it. */
    val peak: Float by lazy {
        var p = 0
        for (i in 0 until count) p = max(p, max(abs(min[i].toInt()), abs(max[i].toInt())))
        p / 127f
    }

    fun toBytes(): ByteArray {
        val out = ByteArrayOutputStream(count * 2 + 32)
        DataOutputStream(out).use {
            it.writeInt(MAGIC)
            it.writeInt(peaksPerSec)
            it.writeLong(durationMs)
            it.writeInt(count)
            it.write(min)
            it.write(max)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAGIC = 0x50574631 // "PWF1"

        /** Reads what [toBytes] wrote. Returns null if the data is damaged, so a bad cache file is just ignored. */
        fun fromBytes(bytes: ByteArray): Waveform? = try {
            DataInputStream(ByteArrayInputStream(bytes)).use {
                if (it.readInt() != MAGIC) return null
                val perSec = it.readInt()
                val duration = it.readLong()
                val count = it.readInt()
                if (perSec !in 1..1000 || count < 0 || count > 5_000_000 || bytes.size < 20 + count * 2) return null
                val lows = ByteArray(count)
                val highs = ByteArray(count)
                it.readFully(lows)
                it.readFully(highs)
                Waveform(perSec, lows, highs, duration)
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Builds a [Waveform] from sound that arrives piece by piece. Mono samples (-1..1) at [sampleRate]. */
class WaveformBuilder(private val sampleRate: Int, private val peaksPerSec: Int = 50) {

    private var lows = ByteArray(4096)
    private var highs = ByteArray(4096)
    private var buckets = 0           // buckets finished so far
    private var current = -1L         // the bucket being filled
    private var curMin = 0f
    private var curMax = 0f
    private var index = 0L            // how many samples we have seen

    fun push(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) {
            val bucket = index * peaksPerSec / sampleRate
            index++
            if (bucket != current) {
                flush()
                current = bucket
                curMin = 0f
                curMax = 0f
            }
            val v = samples[i]
            if (v < curMin) curMin = v
            if (v > curMax) curMax = v
        }
    }

    private fun flush() {
        if (current < 0) return
        // buckets that got no sound at all (should not happen) stay silent
        while (buckets < current) store(0f, 0f)
        store(curMin, curMax)
    }

    private fun store(low: Float, high: Float) {
        if (buckets == lows.size) {
            lows = lows.copyOf(lows.size * 2)
            highs = highs.copyOf(highs.size * 2)
        }
        lows[buckets] = (low.coerceIn(-1f, 1f) * 127f).roundToInt().toByte()
        highs[buckets] = (high.coerceIn(-1f, 1f) * 127f).roundToInt().toByte()
        buckets++
    }

    fun build(durationMs: Long): Waveform {
        flush()
        current = -1
        return Waveform(peaksPerSec, lows.copyOf(buckets), highs.copyOf(buckets), durationMs)
    }
}

/** Turning a waveform into something drawable, and screen positions into times. */
object WaveformMath {

    /**
     * What to draw for the time span [fromMs] to [toMs], as [columns] vertical lines.
     * Returns 2 numbers per column: the lowest and the highest level, scaled so the loudest part of the
     * song reaches 1. Times outside the song give silence (0, 0).
     */
    fun columns(wave: Waveform, fromMs: Long, toMs: Long, columns: Int): FloatArray {
        val out = FloatArray(max(columns, 0) * 2)
        if (columns <= 0 || wave.count == 0 || toMs <= fromMs) return out
        val scale = 1f / max(wave.peak, 0.1f) / 127f
        val spanMs = (toMs - fromMs).toDouble()
        for (c in 0 until columns) {
            val t0 = fromMs + spanMs * c / columns
            val t1 = fromMs + spanMs * (c + 1) / columns
            var first = floor(t0 * wave.peaksPerSec / 1000.0).toInt()
            var last = ceil(t1 * wave.peaksPerSec / 1000.0).toInt() - 1
            if (last < first) last = first
            if (last < 0 || first >= wave.count) continue // outside the song
            first = max(first, 0)
            last = min(last, wave.count - 1)
            var low = 0
            var high = 0
            for (b in first..last) {
                low = min(low, wave.min[b].toInt())
                high = max(high, wave.max[b].toInt())
            }
            out[c * 2] = (low * scale).coerceIn(-1f, 1f)
            out[c * 2 + 1] = (high * scale).coerceIn(-1f, 1f)
        }
        return out
    }

    /** Where a grid of equal columns starts: the number of the first column, and how far into it the view begins (0 to 1). */
    class GridStart(val firstColumn: Long, val fraction: Float)

    /**
     * For a scrolling view: the columns sit on a fixed grid in song time (column n covers n * [columnMs] to
     * (n + 1) * [columnMs]), so a column always shows the same sound while the view slides past.
     * Draw the columns starting from [GridStart.firstColumn] and shift them left by [GridStart.fraction] of a column.
     * Pick [columnMs] as a multiple of the waveform's slice length (20 ms) for the steadiest picture.
     */
    fun gridStart(fromMs: Long, columnMs: Long): GridStart {
        val first = Math.floorDiv(fromMs, columnMs)
        return GridStart(first, (fromMs - first * columnMs).toFloat() / columnMs)
    }

    /** A finger at [x] pixels on a bar that is [width] pixels wide and shows the whole song of [durationMs]. */
    fun timeAt(x: Float, width: Float, durationMs: Long): Long {
        if (width <= 0f) return 0L
        return (x / width).coerceIn(0f, 1f).times(durationMs).toLong()
    }

    /**
     * Dragging the zoomed waveform: the sound moves with the finger, so dragging to the right goes back in
     * time. [windowMs] is how much time fits on the screen across [width] pixels.
     */
    fun timeAfterDrag(startMs: Long, dragPixels: Float, width: Float, windowMs: Long): Long {
        if (width <= 0f) return startMs
        return startMs - (dragPixels * windowMs / width).toLong()
    }
}
