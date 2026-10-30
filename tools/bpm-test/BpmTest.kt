// Offline checks for the BPM detector. Runs on a normal computer, no phone needed.
// It builds made-up drum tracks at known tempos and checks what the detector says.
// See tools/bpm-test/run.sh
import com.prism.musicplayer.audio.BpmDetector
import com.prism.musicplayer.audio.BpmStore
import com.prism.musicplayer.audio.Fft
import com.prism.musicplayer.audio.Resampler
import com.prism.musicplayer.audio.TapTempo
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    if (!ok) failures++
}

const val RATE = 22050

enum class Style { FOUR_ON_FLOOR, SPARSE, SIXTEENTH_HATS, BREAKBEAT, NO_KICK }

/** A made-up song: drums + a steady pad, [seconds] long. [jitterMs] makes the playing slightly human. */
fun makeSong(bpm: Double, style: Style, seconds: Int, seed: Long, jitterMs: Double = 6.0, noise: Double = 0.02): FloatArray {
    val rnd = Random(seed)
    val n = seconds * RATE
    val out = FloatArray(n)
    val beat = 60.0 / bpm
    fun add(startSec: Double, sound: FloatArray, gain: Double) {
        val s = ((startSec + rnd.nextGaussian() * jitterMs / 1000.0) * RATE).toInt()
        for (i in sound.indices) {
            val p = s + i
            if (p in 0 until n) out[p] += (sound[i] * gain).toFloat()
        }
    }
    fun kick() = FloatArray((0.25 * RATE).toInt()) { i ->
        val t = i.toDouble() / RATE
        val f = 45 + 75 * exp(-t / 0.03)
        (sin(2 * PI * f * t) * exp(-t / 0.12)).toFloat()
    }
    fun snare(): FloatArray { val r = Random(7); return FloatArray((0.2 * RATE).toInt()) { i ->
        val t = i.toDouble() / RATE
        ((r.nextGaussian() * 0.5 + sin(2 * PI * 190 * t) * 0.5) * exp(-t / 0.07)).toFloat() } }
    fun hat(): FloatArray { val r = Random(11); var prev = 0.0; return FloatArray((0.06 * RATE).toInt()) { i ->
        val t = i.toDouble() / RATE
        val x = r.nextGaussian(); val hp = x - prev; prev = x
        (hp * exp(-t / 0.02) * 0.5).toFloat() } }
    val k = kick(); val s = snare(); val h = hat()

    var b = 0
    while (b * beat < seconds) {
        val t = b * beat
        val inBar = b % 4
        when (style) {
            Style.FOUR_ON_FLOOR -> {
                add(t, k, 0.9); if (inBar == 1 || inBar == 3) add(t, s, 0.6)
                add(t + beat / 2, h, 0.35)
            }
            Style.SPARSE -> {
                if (inBar == 0 || inBar == 2) add(t, k, 0.9)
                if (inBar == 1 || inBar == 3) add(t, s, 0.6)
            }
            Style.SIXTEENTH_HATS -> {
                if (inBar == 0 || inBar == 2) add(t, k, 0.9)
                if (inBar == 1 || inBar == 3) add(t, s, 0.6)
                for (q in 0 until 4) add(t + q * beat / 4, h, if (q == 0) 0.4 else 0.25)
            }
            Style.BREAKBEAT -> {
                if (inBar == 0) { add(t, k, 0.9); add(t + beat * 0.75, k, 0.7) }
                if (inBar == 1) add(t, s, 0.6)
                if (inBar == 2) { add(t + beat * 0.5, k, 0.8) }
                if (inBar == 3) add(t, s, 0.6)
                add(t, h, 0.3); add(t + beat / 2, h, 0.3)
            }
            Style.NO_KICK -> { // only snare and hats, e.g. a light acoustic song
                if (inBar == 1 || inBar == 3) add(t, s, 0.5)
                add(t, h, 0.4); add(t + beat / 2, h, 0.3)
            }
        }
        b++
    }
    // steady pad (no beat) + a little noise
    for (i in 0 until n) {
        val t = i.toDouble() / RATE
        out[i] += (0.08 * (sin(2 * PI * 220 * t) + sin(2 * PI * 277.2 * t) + sin(2 * PI * 330 * t)) / 3 + rnd.nextGaussian() * noise).toFloat()
    }
    var peak = 0f
    for (v in out) peak = max(peak, abs(v))
    for (i in out.indices) out[i] /= peak * 1.1f
    return out
}

fun detect(samples: FloatArray): Pair<Float, Float>? {
    val d = BpmDetector(RATE)
    var i = 0
    val chunk = 4096
    while (i < samples.size) {
        val n = minOf(chunk, samples.size - i)
        d.push(samples.copyOfRange(i, i + n), n)
        i += n
    }
    return d.result()?.let { it.bpm to it.confidence }
}

fun main() {
    println("BPM detector tests\n")

    // ---- building blocks
    run {
        val n = 1024; val fft = Fft(n)
        val re = FloatArray(n) { sin(2 * PI * 50 * it / n).toFloat() }; val im = FloatArray(n)
        fft.transform(re, im)
        var best = 0; var bestMag = 0f
        for (k in 1 until n / 2) { val m = sqrt(re[k] * re[k] + im[k] * im[k]); if (m > bestMag) { bestMag = m; best = k } }
        check("FFT puts a 50-cycle sine in bin 50 with the right size", best == 50 && abs(bestMag - n / 2f) < 1f, "bin $best, size $bestMag")
    }
    run {
        val inp = FloatArray(44100) { sin(2 * PI * 1000 * it / 44100).toFloat() }
        val out = ArrayList<Float>()
        Resampler(44100, 22050).push(inp, inp.size) { out.add(it) }
        var peak = 0f; for (v in out.drop(100)) peak = max(peak, abs(v))
        check("Resampler 44.1k to 22.05k: right amount of sound", abs(out.size - 22050) <= 2, "${out.size} samples")
        check("Resampler keeps a 1 kHz tone loud", peak > 0.9f, "peak $peak")
    }
    run {
        val inp = FloatArray(48000) { sin(2 * PI * 440 * it / 48000).toFloat() }
        val out = ArrayList<Float>()
        Resampler(48000, 22050).push(inp, inp.size) { out.add(it) }
        check("Resampler 48k to 22.05k: right amount of sound", abs(out.size - 22050) <= 2, "${out.size} samples")
    }
    run {
        val inp = FloatArray(22050) { 0.5f }
        val out = ArrayList<Float>()
        Resampler(22050, 22050).push(inp, inp.size) { out.add(it) }
        check("Resampler at the same rate changes nothing", out.size == 22050 && out.all { abs(it - 0.5f) < 1e-4f })
    }
    run {
        val t = TapTempo()
        check("Tap tempo: first tap gives nothing", t.tap(0) == null)
        val second = t.tap(500)
        check("Tap tempo: 500 ms apart is 120 BPM", second == 120f, "$second")
        t.tap(1000); t.tap(1500)
        check("Tap tempo: steady taps stay 120", t.tap(2000) == 120f)
        check("Tap tempo: a long pause starts over", t.tap(9000) == null)
        val u = TapTempo(); u.tap(0); u.tap(461); u.tap(923); val r = u.tap(1384)
        check("Tap tempo: slightly uneven taps average out (about 130 BPM)", r != null && abs(r - 130f) < 0.6f, "$r")
    }
    run {
        val m = mapOf(5L to 128.5f, 9L to 90f)
        check("BPM store: save and load", BpmStore.decode(BpmStore.encode(m)) == m)
        check("BPM store: broken entries are skipped", BpmStore.decode("x;1,2;5,1285;6,99999;-1,1000;7,abc") == mapOf(5L to 128.5f))
        check("BPM store: empty is empty", BpmStore.decode("").isEmpty())
    }

    // ---- silence and too little sound
    check("Silence has no tempo", detect(FloatArray(RATE * 30)) == null)
    check("Too short has no tempo", detect(makeSong(120.0, Style.FOUR_ON_FLOOR, 4, 1)) == null)

    // ---- the real test: made-up songs at many tempos
    val tempos = listOf(80.0, 90.0, 100.0, 110.0, 120.0, 128.0, 140.0, 150.0, 160.0, 174.0)
    var exact = 0; var octave = 0; var wrong = 0; var total = 0
    var usualRange = 0; var usualRangeExact = 0
    val problems = ArrayList<String>()
    val badUsual = ArrayList<String>()
    for (style in Style.values()) {
        val row = StringBuilder(String.format("  %-15s", style.name))
        for ((i, bpm) in tempos.withIndex()) {
            val r = detect(makeSong(bpm, style, 60, 100L * i + style.ordinal))
            total++
            val got = r?.first ?: 0f
            val isExact = abs(got - bpm) <= 0.8
            val isOctave = abs(got - 2 * bpm) <= 1.5 || abs(got - bpm / 2) <= 1.0
            if (bpm <= 140.0) { usualRange++; if (isExact) usualRangeExact++ else badUsual.add("${style.name} ${bpm.toInt()} -> $got") }
            when {
                isExact -> exact++
                isOctave -> { octave++; problems.add("${style.name} ${bpm.toInt()} -> $got (octave)") }
                else -> { wrong++; problems.add("${style.name} ${bpm.toInt()} -> $got") }
            }
            row.append(String.format(" %5.1f%s", got, if (isExact) " " else "!"))
        }
        println(row)
    }
    println("  tempos:         " + tempos.joinToString(" ") { String.format("%5.0f ", it) })
    println("  (! = not the exact tempo. Above 140 BPM the half tempo is a known ambiguity: 160 BPM can be felt as 80.)")
    println()
    check("Usual tempos (80 to 140 BPM): every song in every style is exact", usualRangeExact == usualRange, "$usualRangeExact of $usualRange. ${badUsual.joinToString("; ")}")
    check("Never a nonsense answer: a miss is always exactly half or double", wrong == 0, problems.filter { !it.endsWith("(octave)") }.joinToString("; "))
    check("Fast songs (150+): at least right or exactly half", problems.none { !it.endsWith("(octave)") })

    // ---- accuracy and stability
    run {
        var worst = 0.0
        for (seed in 1L..5L) { val r = detect(makeSong(128.0, Style.FOUR_ON_FLOOR, 60, seed))!!; worst = max(worst, abs(r.first - 128.0)) }
        check("Accuracy: 128 BPM within 0.5 BPM, 5 different performances", worst <= 0.5, "worst error %.2f".format(worst))
    }
    run {
        val r = detect(makeSong(128.0, Style.FOUR_ON_FLOOR, 60, 3, jitterMs = 15.0, noise = 0.15))!!
        check("Noisy and sloppy playing still gives 128 BPM", abs(r.first - 128f) <= 1f, "${r.first}")
    }
    run {
        val r = detect(makeSong(128.0, Style.FOUR_ON_FLOOR, 25, 4))
        check("Works with only 25 seconds of sound", r != null && abs(r.first - 128f) <= 1f, "${r?.first}")
    }
    run {
        val clear = detect(makeSong(128.0, Style.FOUR_ON_FLOOR, 60, 5, jitterMs = 3.0, noise = 0.01))!!
        val sloppy = detect(makeSong(128.0, Style.BREAKBEAT, 60, 6, jitterMs = 20.0, noise = 0.1))!!
        val rnd = Random(9); val noiseOnly = FloatArray(RATE * 40) { (rnd.nextGaussian() * 0.1).toFloat() }
        val noise = detect(noiseOnly)
        println("  confidence: steady beat %.2f, sloppy breakbeat %.2f, pure noise %s".format(clear.second, sloppy.second, noise?.second?.let { "%.2f".format(it) } ?: "none"))
        check("Confidence: a steady beat is high", clear.second >= 0.8, "%.2f".format(clear.second))
        check("Confidence: random noise is low", noise == null || noise.second <= 0.35, "${noise?.second}")
        check("Confidence: a steady beat beats a sloppy one", clear.second >= sloppy.second)
    }
    run {
        // the same song, quieter: the answer must not depend on volume
        val song = makeSong(120.0, Style.SPARSE, 60, 8)
        val quiet = FloatArray(song.size) { song[it] * 0.1f }
        val a = detect(song)!!.first; val b = detect(quiet)!!.first
        check("Volume doesn't change the answer", abs(a - b) <= 0.2f, "$a vs $b")
    }
    run {
        val start = System.nanoTime()
        detect(makeSong(120.0, Style.FOUR_ON_FLOOR, 90, 2))
        val ms = (System.nanoTime() - start) / 1_000_000
        check("Speed: 90 seconds of sound analysed quickly (this computer)", ms < 4000, "$ms ms")
    }

    println()
    println(if (failures == 0) "ALL TESTS PASSED" else "$failures TEST(S) FAILED")
    if (failures != 0) System.exit(1)
}
