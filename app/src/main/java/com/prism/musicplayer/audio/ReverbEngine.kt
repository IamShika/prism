package com.prism.musicplayer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A reverb made only of simple maths, so it works on every phone (no Android effect needed).
 *
 * How it works, in plain words:
 *  1. The sound is delayed a little (pre-delay) and a few quick "wall bounces" are added (early reflections).
 *  2. It is smeared by four all-pass filters (diffusion) so the echoes blur together.
 *  3. It then enters 8 delay lines that feed into each other through a mixing matrix.
 *     This is called a Feedback Delay Network. Each pass the sound gets a little quieter, and the
 *     highs and lows are treated differently, which makes the tail fade away like a real room.
 *  4. The result is split into left and right, widened, panned, filtered and mixed with the original.
 *
 * It processes stereo audio as a plain float array: [L0, R0, L1, R1, ...] with values between -1 and 1.
 */
class ReverbEngine(sampleRate: Int) {

    /** The screen writes here, the audio thread reads. A new object each time, so no half-updates. */
    @Volatile
    private var requested = ReverbParams()
    private var applied = ReverbParams()

    private val sr = sampleRate.toFloat()

    // ------------------------------------------------------------------ delay lines

    private class DelayLine(maxDelay: Int) {
        private val size = maxDelay + 4
        private val buf = FloatArray(size)
        private var w = 0

        /** The sample that was written [delay] samples ago (delay >= 1). Call before [write]. */
        fun read(delay: Float): Float {
            val pos = w - delay
            var i0 = floor(pos).toInt()
            val frac = pos - i0
            while (i0 < 0) i0 += size
            val i1 = if (i0 + 1 >= size) 0 else i0 + 1
            val a = buf[i0]
            return a + (buf[i1] - a) * frac
        }

        fun write(x: Float) {
            buf[w] = x
            if (++w == size) w = 0
        }

        fun clear() {
            buf.fill(0f)
            w = 0
        }
    }

    private class AllPass(val delay: Int) {
        private val buf = FloatArray(delay)
        private var i = 0

        fun process(x: Float, g: Float): Float {
            val d = buf[i]
            val w = x + g * d
            buf[i] = w
            if (++i == delay) i = 0
            return d - g * w
        }

        fun clear() {
            buf.fill(0f)
            i = 0
        }
    }

    // The 8 feedback lines. These lengths (in ms) don't share common factors, so echoes never pile up.
    private val baseMs = floatArrayOf(29.7f, 37.1f, 41.1f, 43.7f, 53.3f, 59.9f, 67.3f, 73.1f)
    private val modMaxSamples = 0.0008f * sr
    private val lines = Array(N) { DelayLine((baseMs[it] * MAX_SIZE_SCALE * sr / 1000f).toInt() + modMaxSamples.toInt() + 8) }

    private val preDelayLine = DelayLine((MAX_PRE_MS * sr / 1000f).toInt() + 8)
    private val earlyLine = DelayLine((100f * sr / 1000f).toInt() + 8)
    private val allPasses = listOf(5.1f, 7.9f, 11.3f, 13.7f).map { AllPass((it * sr / 1000f).toInt().coerceAtLeast(2)) }

    // early reflection tap times (ms) and gains
    private val erMsL = floatArrayOf(11f, 17f, 23f, 31f, 43f, 57f)
    private val erMsR = floatArrayOf(13f, 19f, 29f, 37f, 47f, 61f)
    private val erGain = floatArrayOf(0.8f, -0.65f, 0.55f, 0.45f, -0.35f, 0.28f)

    // ------------------------------------------------------------------ state

    private val y = FloatArray(N)       // what the lines currently output
    private val f = FloatArray(N)       // after filtering in the feedback loop
    private val lenSamples = FloatArray(N)
    private val gMid = FloatArray(N)
    private val gLow = FloatArray(N)
    private val lowState = FloatArray(N)   // splits lows from the rest
    private val dampState = FloatArray(N)  // makes highs fade faster
    private val phase = FloatArray(N) { it * 2f * PI.toFloat() / N }

    // output filters: two one-pole filters each, per side = 12 dB per octave
    private val hc = FloatArray(4)   // high cut: L1, L2, R1, R2
    private val lc = FloatArray(4)   // low cut:  L1, L2, R1, R2

    // smoothed values (they glide towards their targets so turning a knob doesn't click)
    private var sizeCur = applied.size
    private var dryCur = applied.dry
    private var wetCur = 0f
    private var earlyCur = applied.earlyLevel
    private var sepCur = applied.separation
    private var panCur = applied.pan
    private var diffCur = applied.diffusion
    private var modCur = applied.mod

    // coefficients that only change when a knob moves
    private var aHighCut = 0f
    private var aLowCut = 0f
    private var aDamp = 0f
    private var aCross = 0f
    private var modInc = 0f

    init {
        applyParams()
        sizeCur = applied.size
        dryCur = applied.dry
        wetCur = applied.wet * WET_CAL
    }

    // ------------------------------------------------------------------ public

    fun setParams(params: ReverbParams) {
        requested = params
    }

    /** Forget the old tail. Call when a new song starts or the reverb is switched on. */
    fun reset() {
        lines.forEach { it.clear() }
        preDelayLine.clear()
        earlyLine.clear()
        allPasses.forEach { it.clear() }
        lowState.fill(0f)
        dampState.fill(0f)
        hc.fill(0f)
        lc.fill(0f)
        y.fill(0f)
    }

    /** Processes [frames] stereo frames in place. */
    fun process(data: FloatArray, offset: Int, frames: Int) {
        val r = requested
        if (r !== applied) {
            applied = r
            applyParams()
        }
        var done = 0
        while (done < frames) {
            val chunk = min(CHUNK, frames - done)
            processChunk(data, offset + done * 2, chunk)
            done += chunk
        }
    }

    // ------------------------------------------------------------------ internals

    private fun onePole(fc: Float): Float = exp(-2f * PI.toFloat() * fc.coerceIn(10f, sr * 0.45f) / sr)

    private fun applyParams() {
        val p = applied
        aHighCut = onePole(p.highCutHz)
        aLowCut = onePole(p.lowCutHz)
        aDamp = onePole(p.dampingHz)
        aCross = onePole(p.crossHz)
        modInc = 2f * PI.toFloat() * (0.1f + p.modSpeed * 2f) / sr
    }

    private fun processChunk(data: FloatArray, start: Int, n: Int) {
        val p = applied

        // ---- once per chunk: glide the slow values towards their targets
        val k = 1f - exp(-n / (sr * 0.03f))
        sizeCur += (p.size - sizeCur) * k
        dryCur += (p.dry - dryCur) * k
        wetCur += (p.wet * WET_CAL - wetCur) * k
        earlyCur += (p.earlyLevel - earlyCur) * k
        sepCur += (p.separation - sepCur) * k
        panCur += (p.pan - panCur) * k
        diffCur += (p.diffusion - diffCur) * k
        modCur += (p.mod - modCur) * k

        val sizeScale = MIN_SIZE_SCALE + (MAX_SIZE_SCALE - MIN_SIZE_SCALE) * sizeCur
        val rt60 = p.decaySec.coerceIn(0.1f, 30f) * RT_COMP
        for (i in 0 until N) {
            val len = baseMs[i] * sizeScale * sr / 1000f
            lenSamples[i] = len
            val seconds = len / sr
            // each trip round the loop must lose exactly the right amount of volume
            // so that after rt60 seconds the tail is 60 dB (1/1000) quieter
            gMid[i] = 10f.pow(-3f * seconds / rt60)
            gLow[i] = 10f.pow(-3f * seconds / (rt60 * p.bass.coerceIn(0.3f, 4f)))
        }
        val erScale = 0.4f + 1.2f * sizeCur
        val preSamples = (p.preDelayMs * sr / 1000f).coerceAtLeast(1f)
        val apG = 0.7f * diffCur
        val modDepth = modCur * modMaxSamples

        val angle = (panCur + 1f) * PI.toFloat() / 4f
        val panL = cos(angle) * SQRT2
        val panR = sin(angle) * SQRT2
        val sideGain = 1f + sepCur * 1.5f
        val erLevel = earlyCur * 0.5f

        var idx = start
        for (s in 0 until n) {
            val inL = data[idx]
            val inR = data[idx + 1]

            // ---- 1. pre-delay and early reflections
            val xd = preDelayLine.read(preSamples)
            preDelayLine.write((inL + inR) * 0.5f)

            var erl = 0f
            var err = 0f
            for (t in erMsL.indices) {
                erl += earlyLine.read(erMsL[t] * erScale * sr / 1000f) * erGain[t]
                err += earlyLine.read(erMsR[t] * erScale * sr / 1000f) * erGain[t]
            }
            earlyLine.write(xd)

            // ---- 2. diffusion
            var d = xd
            for (ap in allPasses) d = ap.process(d, apG)

            // ---- 3. feedback delay network
            for (i in 0 until N) {
                var delay = lenSamples[i]
                if (modDepth > 0f) {
                    delay += modDepth * sin(phase[i])
                    phase[i] += modInc * (1f + 0.07f * i)
                    if (phase[i] > TWO_PI) phase[i] -= TWO_PI
                }
                y[i] = lines[i].read(delay.coerceAtLeast(1f))
            }

            for (i in 0 until N) {
                // Both filters look at the whole signal. The "bass" part only adds the *extra*
                // ringing of the lows on top of the normal tail (gLow - gMid), so the loop can
                // never amplify anything, whatever the knobs are set to.
                val low = (1f - aCross) * y[i] + aCross * lowState[i]
                lowState[i] = low
                val damped = (1f - aDamp) * y[i] + aDamp * dampState[i]
                dampState[i] = damped
                f[i] = damped * gMid[i] + low * (gLow[i] - gMid[i])
            }

            hadamard8(f)

            for (i in 0 until N) {
                val sign = if (i and 1 == 0) 1f else -1f
                var v = f[i] + d * sign * INJECT
                if (abs(v) < 1e-18f) v = 0f // avoids very slow "denormal" numbers
                lines[i].write(v)
            }

            // ---- 4. left / right output
            var wl = (y[0] + y[1] + y[2] + y[3] - y[4] - y[5] - y[6] - y[7]) * OUT
            var wr = (y[0] - y[1] + y[2] - y[3] + y[4] - y[5] + y[6] - y[7]) * OUT
            wl += erl * erLevel
            wr += err * erLevel

            // widen
            val mid = (wl + wr) * 0.5f
            val side = (wl - wr) * 0.5f * sideGain
            wl = (mid + side) * panL
            wr = (mid - side) * panR

            // high cut, then low cut
            hc[0] = (1f - aHighCut) * wl + aHighCut * hc[0]
            hc[1] = (1f - aHighCut) * hc[0] + aHighCut * hc[1]
            hc[2] = (1f - aHighCut) * wr + aHighCut * hc[2]
            hc[3] = (1f - aHighCut) * hc[2] + aHighCut * hc[3]
            wl = hc[1]
            wr = hc[3]

            lc[0] = (1f - aLowCut) * wl + aLowCut * lc[0]
            wl -= lc[0]
            lc[1] = (1f - aLowCut) * wl + aLowCut * lc[1]
            wl -= lc[1]
            lc[2] = (1f - aLowCut) * wr + aLowCut * lc[2]
            wr -= lc[2]
            lc[3] = (1f - aLowCut) * wr + aLowCut * lc[3]
            wr -= lc[3]

            // ---- 5. mix with the original
            data[idx] = inL * dryCur + wl * wetCur
            data[idx + 1] = inR * dryCur + wr * wetCur
            idx += 2
        }
    }

    /** Mixes 8 numbers so every line feeds all the others, without changing the total energy. */
    private fun hadamard8(a: FloatArray) {
        var h = 1
        while (h < N) {
            var i = 0
            while (i < N) {
                for (j in i until i + h) {
                    val x = a[j]
                    val z = a[j + h]
                    a[j] = x + z
                    a[j + h] = x - z
                }
                i += h * 2
            }
            h *= 2
        }
        val norm = 0.35355339f // 1 / sqrt(8)
        for (i in 0 until N) a[i] *= norm
    }

    private companion object {
        const val N = 8
        const val CHUNK = 64
        const val MIN_SIZE_SCALE = 0.4f
        const val MAX_SIZE_SCALE = 2.0f
        const val MAX_PRE_MS = 250f
        const val TWO_PI = 2f * PI.toFloat()
        val SQRT2 = sqrt(2f)
        const val INJECT = 0.35355339f
        const val OUT = 0.35355339f

        /** Makes "Wet 100%" about 0.6x as loud as the original. Measured in tools/reverb-test. */
        const val WET_CAL = 1.8f

        /** The filters in the loop shorten the tail a little; this makes the Decay knob tell the truth. */
        const val RT_COMP = 1.17f
    }
}
