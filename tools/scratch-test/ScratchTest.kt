// Offline checks for the scratch engine's sound maths. Runs on a normal computer, no phone needed.
// See tools/scratch-test/run.sh
import com.prism.musicplayer.audio.ArrayPcm
import com.prism.musicplayer.audio.PcmFormat
import com.prism.musicplayer.audio.ScratchRenderer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    if (!ok) failures++
}

const val RATE = 44100

/** A song of [seconds] seconds containing a sine tone of [hz]. */
fun tone(seconds: Int, hz: Double, rate: Int = RATE, amp: Double = 0.5): ArrayPcm {
    val n = rate * seconds
    val data = ShortArray(n * 2)
    for (i in 0 until n) {
        val v = (amp * 32767 * sin(2 * PI * hz * i / rate)).toInt().toShort()
        data[i * 2] = v
        data[i * 2 + 1] = v
    }
    return ArrayPcm(rate, data)
}

/** A song whose sound level rises steadily from 0 to 0.9: the level tells where in the song you are. */
fun ramp(seconds: Int, rate: Int = RATE): ArrayPcm {
    val n = rate * seconds
    val data = ShortArray(n * 2)
    for (i in 0 until n) {
        val v = (0.9 * 32767 * i / n).toInt().toShort()
        data[i * 2] = v
        data[i * 2 + 1] = v
    }
    return ArrayPcm(rate, data)
}

/** Renders [frames] frames and returns the left channel as floats. */
fun ScratchRenderer.left(frames: Int, chunk: Int = 256): FloatArray {
    val result = FloatArray(frames)
    val buf = ShortArray(chunk * 2)
    var done = 0
    while (done < frames) {
        val n = minOf(chunk, frames - done)
        render(buf, n)
        for (i in 0 until n) result[done + i] = buf[i * 2] / 32768f
        done += n
    }
    return result
}

fun maxStep(a: FloatArray, from: Int = 1): Float {
    var m = 0f
    for (i in maxOf(from, 1) until a.size) m = maxOf(m, abs(a[i] - a[i - 1]))
    return m
}

fun rms(a: FloatArray, from: Int = 0): Double {
    var s = 0.0
    for (i in from until a.size) s += a[i] * a[i]
    return sqrt(s / (a.size - from))
}

fun main() {
    println("Scratch engine tests\n")

    // ---- running at a speed
    run {
        val r = ScratchRenderer(ramp(20), RATE)
        r.start(5000)
        r.run(1.0)
        r.left(RATE) // one second
        check("Normal speed: 1 second of sound moves 1 second", abs(r.positionMs - 6000) < 15, "${r.positionMs} ms")
    }
    run {
        val r = ScratchRenderer(ramp(20), RATE)
        r.start(2000); r.run(2.0); r.left(RATE)
        check("Double speed moves 2 seconds per second", abs(r.positionMs - 4000) < 25, "${r.positionMs} ms")
    }
    run {
        val r = ScratchRenderer(ramp(20), RATE)
        r.start(2000); r.run(0.5); r.left(RATE)
        check("Half speed moves half a second per second", abs(r.positionMs - 2500) < 10, "${r.positionMs} ms")
    }
    run {
        val r = ScratchRenderer(ramp(20), RATE)
        r.start(10000); r.run(-1.0)
        val out = r.left(RATE / 2)
        check("Backwards: the position goes back", abs(r.positionMs - 9500) < 15, "${r.positionMs} ms")
        check("Backwards: the sound (a rising ramp) is heard falling", out.last() < out[RATE / 4] && out[RATE / 4] < out[600])
    }
    run {
        // a song recorded at 44.1 kHz played out through a 48 kHz speaker must still take the same time
        val r = ScratchRenderer(ramp(20, 44100), 48000)
        r.start(0); r.run(1.0); r.left(48000)
        check("Different sample rates: still real time", abs(r.positionMs - 1000) < 15, "${r.positionMs} ms")
    }

    // ---- following a finger
    run {
        val r = ScratchRenderer(ramp(60), RATE)
        r.start(10000); r.follow(10400)
        var top = 0L
        val buf = ShortArray(512)
        for (i in 0 until 100) { r.render(buf, 256); top = maxOf(top, r.positionMs) }
        check("Follow: arrives at the finger", abs(r.positionMs - 10400) < 10, "${r.positionMs} ms")
        check("Follow: does not overshoot", top <= 10405, "highest ${top} ms")
    }
    run {
        val r = ScratchRenderer(ramp(60), RATE)
        r.start(10000); r.follow(10000)
        val out = r.left(RATE / 2).copyOfRange(1000, RATE / 2) // skip the 8 ms fade-in
        val spread = out.max() - out.min()
        check("Finger held still = sound stops (a held record makes no sound)", spread < 0.0005f, "spread $spread")
    }
    run {
        val r = ScratchRenderer(ramp(60), RATE)
        r.start(20000); r.follow(19500)
        val out = r.left(RATE / 4)
        check("Finger moving back plays the song backwards", out[out.size - 1] < out[800] && r.positionMs < 19600, "${r.positionMs} ms")
    }
    run {
        val r = ScratchRenderer(ramp(240), RATE)
        r.start(0); r.follow(180_000)
        r.left(RATE / 10)
        check("A far jump doesn't leave the sound crawling (it is at most half a second behind)", 180_000 - r.positionMs <= 520, "${r.positionMs} ms")
        r.left(RATE / 2)
        check("...and catches up quickly", abs(r.positionMs - 180_000) < 20, "${r.positionMs} ms")
    }

    // ---- the ends of the song
    run {
        val r = ScratchRenderer(ramp(3), RATE)
        r.start(500); r.run(-1.0)
        val out = r.left(RATE * 2)
        check("Rewinding past the start stops at 0", r.positionMs == 0L)
        check("...and goes quiet there instead of looping or crashing", abs(out.last()) < 0.001f)
    }
    run {
        val r = ScratchRenderer(ramp(3), RATE)
        r.start(2500); r.run(3.0)
        r.left(RATE * 2)
        check("Fast-forwarding past the end stops at the end", r.positionMs in 2990..3000, "${r.positionMs} ms")
    }

    // ---- no clicks
    run {
        val r = ScratchRenderer(tone(10, 440.0), RATE)
        r.start(1000); r.run(1.0)
        val out = r.left(RATE / 4)
        // a 440 Hz tone at 0.5 level changes by at most ~0.035 per sample by itself
        check("Starting is smooth (no click)", maxStep(out, 0) < 0.05f && abs(out[0]) < 0.01f, "biggest step ${maxStep(out, 0)}")
    }
    run {
        val r = ScratchRenderer(tone(10, 440.0), RATE)
        r.start(3000); r.run(1.0); r.left(RATE / 10)
        r.run(-1.0)
        val out = r.left(RATE / 4)
        check("Changing direction is smooth (no click)", maxStep(out, 0) < 0.06f, "biggest step ${maxStep(out, 0)}")
    }
    run {
        val r = ScratchRenderer(tone(10, 440.0), RATE)
        r.start(3000); r.run(1.0); r.left(RATE / 10)
        r.fadeOut()
        val out = r.left(RATE / 20)
        check("Fade out is smooth (no click)", maxStep(out, 0) < 0.05f, "biggest step ${maxStep(out, 0)}")
        check("After fading out it is silent, so the audio thread can stop", r.silent && out.last() == 0f)
        r.start(0)
        check("Starting again wakes it up", !r.silent)
    }

    // ---- fast playback must not sound harsh
    run {
        val song = tone(20, 8000.0)
        val slow = ScratchRenderer(song, RATE); slow.start(1000); slow.run(1.0)
        val fast = ScratchRenderer(song, RATE); fast.start(1000); fast.run(5.0)
        val a = rms(slow.left(RATE / 2), 3000)
        val b = rms(fast.left(RATE / 2), 3000)
        check("Fast forward averages high notes instead of folding them back (no metallic whine)", b < a * 0.35, "normal $a, fast $b")
    }

    // ---- chunk size doesn't matter, and nothing can break it
    run {
        val song = tone(10, 330.0)
        val a = ScratchRenderer(song, RATE); a.start(2000); a.follow(2300)
        val b = ScratchRenderer(song, RATE); b.start(2000); b.follow(2300)
        val x = a.left(2000, 2000)
        val y = b.left(2000, 128)
        check("Same sound however it is cut into pieces", x.indices.all { x[it] == y[it] })
    }
    run {
        val song = tone(5, 220.0)
        val r = ScratchRenderer(song, RATE)
        val rnd = Random(7)
        var ok = true
        r.start(100)
        val buf = ShortArray(512)
        for (i in 0 until 2000) {
            when (rnd.nextInt(6)) {
                0 -> r.follow(rnd.nextLong(-5000, 20000))
                1 -> r.run(rnd.nextDouble(-20.0, 20.0))
                2 -> r.run(Double.NaN)
                3 -> r.start(rnd.nextLong(-1000, 9000))
                4 -> r.fadeOut()
                else -> {}
            }
            r.render(buf, rnd.nextInt(1, 256))
            if (r.positionMs < 0 || r.positionMs > 5000) ok = false
        }
        check("Random abuse (jumps, NaN, absurd speeds) never leaves the song", ok)
    }
    run {
        val r = ScratchRenderer(ArrayPcm(RATE, ShortArray(2)), RATE) // a one-frame song
        r.start(0); r.run(1.0)
        r.left(1000)
        check("A one-frame song doesn't crash", r.positionMs == 0L)
    }
    run {
        val r = ScratchRenderer(ramp(5), RATE)
        r.start(1000); r.run(1.0)
        val t0 = System.nanoTime()
        r.left(RATE * 10)
        val ms = (System.nanoTime() - t0) / 1_000_000
        check("Fast enough (10 s of sound well inside 1 s here)", ms < 1000, "$ms ms")
    }

    // ---- the stored file
    run {
        val h = PcmFormat.header(44100, 1000)
        val parsed = PcmFormat.parseHeader(h, 12L + 1000 * 4)
        check("File header round trip", parsed != null && parsed.sampleRate == 44100 && parsed.frames == 1000)
        check("A file cut short is rejected", PcmFormat.parseHeader(h, 12L + 999 * 4) == null)
        check("A file with extra junk is rejected", PcmFormat.parseHeader(h, 12L + 1001 * 4) == null)
        check("A header that was never finished (0 frames) is rejected", PcmFormat.parseHeader(PcmFormat.header(44100, 0), 12) == null)
        val bad = h.copyOf(); bad[0] = 0
        check("Not our file is rejected", PcmFormat.parseHeader(bad, 12L + 4000) == null)
        check("Too short to be a header is rejected", PcmFormat.parseHeader(ByteArray(5), 5) == null)
        check("Silly sample rate is rejected", PcmFormat.parseHeader(PcmFormat.header(5, 10), 12L + 40) == null)
    }
    run {
        val out = ShortArray(6)
        PcmFormat.toStereo(shortArrayOf(1, 2, 3), 3, 1, out)
        check("Mono becomes the same on both sides", out.toList() == listOf<Short>(1, 1, 2, 2, 3, 3))
        PcmFormat.toStereo(shortArrayOf(1, 2, 3, 4, 5, 6), 3, 2, out)
        check("Stereo is copied as it is", out.toList() == listOf<Short>(1, 2, 3, 4, 5, 6))
        val six = shortArrayOf(1, 2, 90, 91, 92, 93, 4, 5, 90, 91, 92, 93)
        PcmFormat.toStereo(six, 2, 6, out)
        check("Surround keeps front left and right", out.take(4) == listOf<Short>(1, 2, 4, 5))
        PcmFormat.toStereo(floatArrayOf(0.5f, -0.5f, 2f, -2f, 0f, 0f), 3, 2, out)
        check("Floating-point sound is scaled and can't overflow", out[0].toInt() in 16380..16390 && out[1].toInt() in -16390..-16380 && out[2].toInt() == 32767 && out[3].toInt() == -32768)
    }

    println()
    if (failures == 0) println("ALL TESTS PASSED") else { println("$failures TEST(S) FAILED"); System.exit(1) }
}
