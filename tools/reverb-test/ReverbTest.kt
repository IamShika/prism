// Offline checks for the reverb engine. Runs on a normal computer, no phone needed.
// See tools/reverb-test/run.sh
import com.prism.musicplayer.audio.ReverbEngine
import com.prism.musicplayer.audio.ReverbParams
import com.prism.musicplayer.audio.ReverbPresets
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

const val SR = 44100
var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    if (!ok) failures++
}

/** Impulse response of the wet signal only (dry = 0), left channel. */
fun impulseResponse(p: ReverbParams, seconds: Float): FloatArray {
    val e = ReverbEngine(SR)
    e.setParams(p.copy(enabled = true, dry = 0f))
    val frames = (seconds * SR).toInt()
    val data = FloatArray(frames * 2)
    data[0] = 1f
    data[1] = 1f
    // let the smoothed gains settle first, using silence
    val warm = FloatArray(SR * 2)
    e.process(warm, 0, SR)
    e.process(data, 0, frames)
    return FloatArray(frames) { data[it * 2] }
}

/** RT60 estimate from the energy decay curve: slope between -5 dB and -25 dB, extended to -60 dB. */
fun rt60(ir: FloatArray): Float {
    val edc = DoubleArray(ir.size)
    var acc = 0.0
    for (i in ir.indices.reversed()) {
        acc += ir[i].toDouble() * ir[i]
        edc[i] = acc
    }
    val total = edc[0]
    fun db(i: Int) = 10 * log10(edc[i] / total + 1e-30)
    var i5 = -1
    var i25 = -1
    for (i in ir.indices) {
        val d = db(i)
        if (i5 < 0 && d <= -5) i5 = i
        if (i25 < 0 && d <= -25) { i25 = i; break }
    }
    if (i5 < 0 || i25 < 0) return Float.NaN
    return (i25 - i5).toFloat() / SR * 3f   // 20 dB took this long, so 60 dB takes 3x
}

fun lowpass(x: FloatArray, fc: Float): FloatArray {
    val a = Math.exp(-2 * PI * fc / SR).toFloat()
    var s = 0f
    return FloatArray(x.size) { s = (1 - a) * x[it] + a * s; s }
}

fun main() {
    println("Reverb engine tests, sample rate $SR Hz\n")
    val base = ReverbParams(
        enabled = true, highCutHz = 20000f, lowCutHz = 20f, size = 0.6f, decaySec = 2f,
        diffusion = 0.8f, dampingHz = 20000f, dry = 1f, earlyLevel = 0.5f, wet = 1f,
        preDelayMs = 20f, mod = 0f, modSpeed = 0.33f, bass = 1f, crossHz = 300f, separation = 0f, pan = 0f,
    )

    // 1. The tail really lasts as long as the knob says
    for (target in listOf(0.8f, 2f, 4.5f)) {
        val ir = impulseResponse(base.copy(decaySec = target), target * 2.5f + 3f)
        val measured = rt60(ir)
        check("Decay $target s", measured in target * 0.88f..target * 1.12f, "measured %.2f s".format(measured))
    }

    // 2. Nothing blows up or turns into NaN, for lots of random knob settings
    val rnd = Random(7)
    var worst = 0f
    var bad = false
    repeat(60) {
        val p = ReverbParams(
            enabled = true,
            highCutHz = 1000f + rnd.nextFloat() * 19000f, lowCutHz = 20f + rnd.nextFloat() * 980f,
            size = rnd.nextFloat(), decaySec = 0.2f + rnd.nextFloat() * 11.8f, diffusion = rnd.nextFloat(),
            dampingHz = 1000f + rnd.nextFloat() * 19000f, dry = rnd.nextFloat(), earlyLevel = rnd.nextFloat(),
            wet = rnd.nextFloat() * 2f, preDelayMs = rnd.nextFloat() * 250f, mod = rnd.nextFloat(),
            modSpeed = rnd.nextFloat(), bass = 0.5f + rnd.nextFloat() * 2.5f, crossHz = 100f + rnd.nextFloat() * 900f,
            separation = rnd.nextFloat(), pan = rnd.nextFloat() * 2f - 1f,
        )
        val e = ReverbEngine(SR)
        e.setParams(p)
        val data = FloatArray(SR * 2 * 3) { (rnd.nextFloat() * 2f - 1f) * 0.5f } // 3 s of loud noise
        e.process(data, 0, SR * 3)
        for (v in data) {
            if (v.isNaN() || v.isInfinite()) bad = true
            worst = maxOf(worst, abs(v))
        }
    }
    check("No NaN / infinity in 60 random settings", !bad)
    check("Output stays reasonable (peak < 12)", worst < 12f, "peak %.2f".format(worst))

    // 3. Turning knobs while playing stays stable
    run {
        val e = ReverbEngine(SR)
        var peak = 0f
        var nan = false
        repeat(300) { step ->
            val t = step / 300f
            e.setParams(base.copy(
                size = (0.5f + 0.5f * Math.sin(t * 40.0).toFloat()).coerceIn(0f, 1f),
                decaySec = 0.3f + 6f * t, mod = t, wet = 1.5f,
                pan = Math.sin(t * 20.0).toFloat(), separation = t,
            ))
            val d = FloatArray(1024 * 2) { (rnd.nextFloat() * 2f - 1f) * 0.5f }
            e.process(d, 0, 1024)
            for (v in d) { if (v.isNaN() || v.isInfinite()) nan = true; peak = maxOf(peak, abs(v)) }
        }
        check("Stable while knobs move", !nan && peak < 12f, "peak %.2f".format(peak))
    }

    // 4. Loudness calibration: noise in, how loud is the wet part compared to the input?
    run {
        val e = ReverbEngine(SR)
        e.setParams(base.copy(dry = 0f))
        val n = SR * 6
        val inp = FloatArray(n * 2) { (rnd.nextFloat() * 2f - 1f) * 0.3f }
        val out = inp.copyOf()
        e.process(out, 0, n)
        var si = 0.0; var so = 0.0
        for (i in n until n * 2) { si += inp[i].toDouble() * inp[i]; so += out[i].toDouble() * out[i] } // second half only
        val ratio = sqrt(so / si)
        println("INFO  wet (100%%) loudness compared to input, decay 2 s: %.2fx".format(ratio))
        check("Wet loudness is sensible (0.45x to 0.9x)", ratio in 0.45..0.9, "%.2fx".format(ratio))
    }

    // 5. Dry only passes the sound through unchanged
    run {
        val e = ReverbEngine(SR)
        e.setParams(base.copy(wet = 0f, dry = 1f))
        val warm = FloatArray(SR * 2)
        e.process(warm, 0, SR)
        val inp = FloatArray(4000 * 2) { (rnd.nextFloat() * 2f - 1f) * 0.5f }
        val out = inp.copyOf()
        e.process(out, 0, 4000)
        var maxDiff = 0f
        for (i in inp.indices) maxDiff = maxOf(maxDiff, abs(inp[i] - out[i]))
        check("Wet 0% leaves the sound untouched", maxDiff < 1e-4f, "max difference %.6f".format(maxDiff))
    }

    // 6. Bass knob: lows ring longer when bass > 1
    run {
        val normal = lowpass(impulseResponse(base.copy(bass = 1f, decaySec = 1.5f), 8f), 150f)
        val boosted = lowpass(impulseResponse(base.copy(bass = 2.5f, decaySec = 1.5f), 14f), 150f)
        val a = rt60(normal); val b = rt60(boosted)
        check("Bass 2.5 makes the lows ring longer", b > a * 1.4f, "low-band decay %.2f s -> %.2f s".format(a, b))
    }

    // 7. Damping knob: low damping frequency = darker, shorter tail in the highs
    run {
        fun highs(x: FloatArray): FloatArray { val lp = lowpass(x, 4000f); return FloatArray(x.size) { x[it] - lp[it] } }
        val bright = rt60(highs(impulseResponse(base.copy(dampingHz = 20000f, decaySec = 3f), 10f)))
        val dark = rt60(highs(impulseResponse(base.copy(dampingHz = 2000f, decaySec = 3f), 10f)))
        check("Low damping makes highs fade faster", dark < bright * 0.8f, "highs %.2f s -> %.2f s".format(bright, dark))
    }

    // 8. Pan and stereo: left pan = quiet right channel
    run {
        val e = ReverbEngine(SR)
        e.setParams(base.copy(dry = 0f, pan = -1f))
        val n = SR * 3
        val d = FloatArray(n * 2) { (rnd.nextFloat() * 2f - 1f) * 0.3f }
        e.process(d, 0, n)
        var l = 0.0; var r = 0.0
        for (i in n until n * 2 step 2) { l += d[i].toDouble() * d[i]; r += d[i + 1].toDouble() * d[i + 1] }
        check("Pan fully left silences the right side", r < l * 0.01, "right/left energy %.4f".format(r / l))
    }

    // 8b. Worst case: longest decay, most bass, loudest wet, loud noise for 20 s
    run {
        val e = ReverbEngine(SR)
        e.setParams(base.copy(decaySec = 12f, bass = 3f, wet = 2f, dry = 1f, size = 1f, crossHz = 1000f))
        val n = SR * 20
        val d = FloatArray(n * 2) { (rnd.nextFloat() * 2f - 1f) }
        e.process(d, 0, n)
        var peak = 0f
        for (v in d) peak = maxOf(peak, abs(v))
        println("INFO  worst case with full-scale noise: peak %.1f (the player clips softly, so this is safe)".format(peak))
        check("Worst case stays finite and bounded (peak < 60)", peak < 60f)
    }

    // 8c. Settings can be saved and loaded again, and damaged text is rejected
    run {
        val original = ReverbPresets.all.last().second.copy(enabled = true)
        val back = ReverbParams.decode(original.encode())
        check("Settings survive save and load", back == original)
        check("Damaged saved text is ignored", ReverbParams.decode("1,2,three") == null && ReverbParams.decode(null) == null && ReverbParams.decode("") == null)
    }

    // 8d. Every preset: stable, and the tail length is in a believable range
    for ((name, preset) in ReverbPresets.all) {
        val ir = impulseResponse(preset.copy(enabled = true), preset.decaySec * 2.5f + 4f)
        var peak = 0f
        var bad = false
        for (v in ir) { if (v.isNaN() || v.isInfinite()) bad = true; peak = maxOf(peak, abs(v)) }
        val t = rt60(ir)
        check("Preset $name works", !bad && peak < 4f && t > 0.2f * preset.decaySec, "tail %.2f s (knob says %.1f s)".format(t, preset.decaySec))
    }
    check("Preset names are recognised", ReverbPresets.nameOf(ReverbPresets.all[3].second.copy(enabled = true)) == "Cathedral" &&
        ReverbPresets.nameOf(ReverbPresets.all[3].second.copy(size = 0.5f)) == "Custom")

    // 9. Speed: how much of one CPU core does it take?
    run {
        val e = ReverbEngine(SR)
        e.setParams(base.copy(mod = 0.5f))
        val d = FloatArray(SR * 2 * 10) { (rnd.nextFloat() * 2f - 1f) * 0.3f }
        e.process(d.copyOf(), 0, SR) // warm up the JIT
        val t0 = System.nanoTime()
        e.process(d, 0, SR * 10)
        val sec = (System.nanoTime() - t0) / 1e9
        println("INFO  10 s of audio took %.3f s on this computer (%.0fx faster than real time)".format(sec, 10 / sec))
        check("Fast enough (at least 20x real time here)", 10 / sec > 20)
    }

    println()
    println(if (failures == 0) "ALL TESTS PASSED" else "$failures TEST(S) FAILED")
    if (failures != 0) System.exit(1)
}
