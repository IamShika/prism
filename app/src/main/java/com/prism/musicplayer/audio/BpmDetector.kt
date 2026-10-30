package com.prism.musicplayer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The tempo found in a song. [confidence] goes from 0 (a guess) to 1 (very clear beat). */
class BpmResult(val bpm: Float, val confidence: Float)

/**
 * Finds the tempo (beats per minute) of a song. No Android code in here, so it can be tested on a
 * normal computer with made-up drum tracks (tools/bpm-test/run.sh).
 *
 * How it works, in plain words:
 *  1. The sound is cut into short overlapping slices (about 46 ms, every 11.6 ms).
 *  2. For each slice we measure "how much new sound appeared since the last slice", separately for the low
 *     sounds (kick, bass), the middle (snare, voice, guitar) and the high ones (hi-hats). Drum hits and other
 *     beats make big jumps, so this gives curves of where the beats are ("onset strength"). Listening to the
 *     bands separately keeps a busy hi-hat from drowning out the kick drum.
 *  3. A song with a steady tempo repeats itself, so the curve looks like itself when shifted by one beat.
 *     We test every tempo from 70 to 190 BPM and see how well the curve matches itself shifted by 1, 2, 3
 *     and 4 beats. The best match is the tempo.
 *  4. Tempos near 120 are more common in music than very slow or very fast ones, which helps to choose
 *     between "100 BPM" and its double "200 BPM".
 *
 * Usage: feed mono samples (-1..1) at [sampleRate] with [push], then call [result].
 */
class BpmDetector(val sampleRate: Int = SAMPLE_RATE) {

    companion object {
        const val SAMPLE_RATE = 22050
        const val FFT_SIZE = 1024
        const val HOP = 256
        const val MIN_BPM = 70.0
        const val MAX_BPM = 190.0

        /** Less audio than this (in frames, about 8 seconds) is not enough to tell the tempo. */
        private const val MIN_FRAMES = 700
        private const val BANDS = 4
        private const val COMB_HARMONICS = 4
        private const val PRIOR_CENTER_BPM = 120.0
        private const val PRIOR_WIDTH_OCTAVES = 0.9
    }

    private val window = FloatArray(FFT_SIZE) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat() }
    private val fft = Fft(FFT_SIZE)
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val slice = FloatArray(FFT_SIZE)
    private var sliceFill = 0
    private val previous = FloatArray(FFT_SIZE / 2)
    private var havePrevious = false

    /** The bands: bass, low-mid, high-mid, treble. Edges in Hz. */
    private val bandEdgesHz = doubleArrayOf(40.0, 300.0, 1500.0, 5000.0, sampleRate / 2.0 - 1.0)
    private val bandStart = IntArray(BANDS) { bin(bandEdgesHz[it]) }
    private val bandEnd = IntArray(BANDS) { bin(bandEdgesHz[it + 1]) }
    private var flux = Array(BANDS) { FloatArray(8192) }
    private var frames = 0

    private fun bin(hz: Double) = (hz * FFT_SIZE / sampleRate).toInt().coerceIn(1, FFT_SIZE / 2 - 1)

    /** How many onset-strength values have been collected so far. */
    val frameCount: Int get() = frames

    /** Seconds of sound analysed so far. */
    val analysedSeconds: Double get() = frames.toDouble() * HOP / sampleRate

    fun push(samples: FloatArray, count: Int = samples.size) {
        var i = 0
        while (i < count) {
            val take = min(FFT_SIZE - sliceFill, count - i)
            System.arraycopy(samples, i, slice, sliceFill, take)
            sliceFill += take
            i += take
            if (sliceFill == FFT_SIZE) {
                processSlice()
                // keep the overlap: the next slice starts HOP samples later
                System.arraycopy(slice, HOP, slice, 0, FFT_SIZE - HOP)
                sliceFill = FFT_SIZE - HOP
            }
        }
    }

    private fun processSlice() {
        for (i in 0 until FFT_SIZE) {
            re[i] = slice[i] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im)

        if (frames == flux[0].size) for (b in 0 until BANDS) flux[b] = flux[b].copyOf(flux[b].size * 2)
        for (band in 0 until BANDS) {
            var sum = 0f
            for (k in bandStart[band] until bandEnd[band]) {
                val magnitude = sqrt(re[k] * re[k] + im[k] * im[k])
                val loud = ln(1f + 20f * magnitude) // squash, so a loud bass doesn't hide everything else
                if (havePrevious) {
                    val rise = loud - previous[k]
                    if (rise > 0f) sum += rise
                }
                previous[k] = loud
            }
            flux[band][frames] = sum
        }
        havePrevious = true
        frames++
    }

    /** The tempo, or null if there isn't enough sound, or it is silent. */
    fun result(): BpmResult? {
        if (frames < MIN_FRAMES) return null
        val fps = sampleRate.toDouble() / HOP

        val maxLag = (COMB_HARMONICS * fps * 60.0 / MIN_BPM).toInt() + 3
        if (maxLag >= frames / 2) return null

        // each band gets its own beat curve; the bands that have something to say vote together
        val curves = (0 until BANDS).mapNotNull { onsetCurve(flux[it], fps) }
        if (curves.isEmpty()) return null
        val strongest = curves.maxOf { it.second }
        val acf = DoubleArray(maxLag + 2)
        var voters = 0
        for ((curve, energy) in curves) {
            if (energy < strongest * 0.05) continue // a band with hardly any beats would only add noise
            val one = autocorrelation(curve, maxLag)
            for (i in acf.indices) acf[i] += one[i]
            voters++
        }
        for (i in acf.indices) acf[i] /= voters

        // test every tempo from 70 to 190 in steps of 0.1 BPM
        var bestBpm = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        var bestMatch = 0.0
        var bpm = MIN_BPM
        while (bpm <= MAX_BPM + 1e-9) {
            val lag = fps * 60.0 / bpm
            var match = 0.0
            for (h in 1..COMB_HARMONICS) match += acfAt(acf, lag * h)
            val octaves = ln(bpm / PRIOR_CENTER_BPM) / ln(2.0)
            val prior = exp(-0.5 * (octaves / PRIOR_WIDTH_OCTAVES) * (octaves / PRIOR_WIDTH_OCTAVES))
            val score = match * prior
            if (score > bestScore) {
                bestScore = score
                bestBpm = bpm
                bestMatch = match
            }
            bpm += 0.1
        }
        if (bestScore <= 0.0) return null

        // Confident when the beat repeats strongly: on average, how much the beat curve matches itself
        // one, two, three and four beats later. A steady beat scores high, random sound close to zero.
        val confidence = (bestMatch / COMB_HARMONICS / 0.2).coerceIn(0.0, 1.0)

        return BpmResult(((bestBpm * 10).roundToInt() / 10.0).toFloat(), confidence.toFloat())
    }

    /** "Where do beats happen": the rise in sound, minus its local average, never below zero. */
    private fun onsetCurve(flux: FloatArray, fps: Double): Pair<DoubleArray, Double>? {
        val n = frames
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + flux[i]
        val half = max(2, (fps * 0.4).toInt())
        val out = DoubleArray(n)
        var energy = 0.0
        for (i in 0 until n) {
            val a = max(0, i - half)
            val b = min(n, i + half + 1)
            val mean = (prefix[b] - prefix[a]) / (b - a)
            val v = flux[i] - mean
            out[i] = if (v > 0) v else 0.0
            energy += out[i]
        }
        if (energy / n < 1e-4) return null // silence
        return out to energy / n
    }

    /** How much the curve looks like itself when shifted by 0..maxLag slices. Index 0 is 1.0. */
    private fun autocorrelation(x: DoubleArray, maxLag: Int): DoubleArray {
        val n = x.size
        var mean = 0.0
        for (v in x) mean += v
        mean /= n
        val c = DoubleArray(n) { x[it] - mean }
        val acf = DoubleArray(maxLag + 2)
        for (lag in 0..maxLag + 1) {
            var s = 0.0
            for (i in 0 until n - lag) s += c[i] * c[i + lag]
            acf[lag] = s / (n - lag) // fair to long lags, which have fewer pairs
        }
        val zero = acf[0]
        if (zero <= 0.0) return DoubleArray(maxLag + 2)
        for (i in acf.indices) acf[i] /= zero
        return acf
    }

    /** The autocorrelation at a lag between two whole slices (smoothly in between). */
    private fun acfAt(acf: DoubleArray, lag: Double): Double {
        val i = floor(lag).toInt()
        if (i < 1 || i + 1 >= acf.size) return 0.0
        val f = lag - i
        val v = acf[i] * (1 - f) + acf[i + 1] * f
        return if (v > 0) v else 0.0
    }
}

/** A plain radix-2 FFT (the maths that turns sound into "how much of each pitch"). [size] must be a power of two. */
class Fft(private val size: Int) {
    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { -sin(2.0 * PI * it / size).toFloat() }
    private val reversed = IntArray(size)

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) reversed[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** In place. After this, re[k] and im[k] describe the frequency k * sampleRate / size. */
    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
    }
}

/**
 * Turns sound of any sample rate into sound at [outRate] by averaging, which also removes the
 * high pitches we don't need. Feed it one piece after another.
 */
class Resampler(inRate: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate  // input samples per output sample
    private var cellEnd = step                      // where the current output sample ends, counted in input samples
    private var position = 0.0                      // where the next input sample starts
    private var sum = 0.0

    fun push(input: FloatArray, count: Int, emit: (Float) -> Unit) {
        for (i in 0 until count) {
            val x = input[i].toDouble()
            var from = position
            val to = position + 1.0
            while (to >= cellEnd) {
                sum += x * (cellEnd - from)
                emit((sum / step).toFloat())
                sum = 0.0
                from = cellEnd
                cellEnd += step
            }
            sum += x * (to - from)
            position = to
        }
    }
}

/** Tap on the beat and it works out the tempo. Taps more than 2.5 seconds apart start over. */
class TapTempo {
    private val taps = ArrayDeque<Long>()

    /** Call on every tap. Returns the tempo after the 2nd tap, or null after the 1st. */
    fun tap(nowMs: Long): Float? {
        if (taps.isNotEmpty() && nowMs - taps.last() > 2500L) taps.clear()
        taps.addLast(nowMs)
        while (taps.size > 8) taps.removeFirst()
        if (taps.size < 2) return null
        val average = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
        if (average <= 0.0) return null
        return ((60000.0 / average).coerceIn(30.0, 300.0) * 10).roundToInt() / 10f
    }

    fun reset() = taps.clear()
}

/** How the tempos are saved: "id,tenths;id,tenths" (the tempo times 10, so 128.5 BPM is 1285). */
object BpmStore {
    fun encode(bpms: Map<Long, Float>): String =
        bpms.entries.joinToString(";") { "${it.key},${(it.value * 10).roundToInt()}" }

    fun decode(text: String): Map<Long, Float> {
        val result = LinkedHashMap<Long, Float>()
        for (entry in text.split(';')) {
            val parts = entry.split(',')
            if (parts.size != 2) continue
            val id = parts[0].trim().toLongOrNull() ?: continue
            val tenths = parts[1].trim().toIntOrNull() ?: continue
            if (id < 0 || tenths < 300 || tenths > 3000) continue
            result[id] = tenths / 10f
        }
        return result
    }
}
