// Offline checks for the waveform maths. Runs on a normal computer, no phone needed.
// See tools/wave-test/run.sh
import com.prism.musicplayer.audio.Waveform
import com.prism.musicplayer.audio.WaveformBuilder
import com.prism.musicplayer.audio.WaveformMath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    if (!ok) failures++
}

const val RATE = 11025

/** 3 seconds of silence with a loud 440 Hz beep from 1.0 s to 1.2 s. */
fun beepSong(amplitude: Float = 0.8f): FloatArray = FloatArray(RATE * 3) { i ->
    val t = i.toDouble() / RATE
    if (t in 1.0..1.2) (amplitude * sin(2 * PI * 440 * t)).toFloat() else 0f
}

fun build(samples: FloatArray, chunk: Int = 3000, perSec: Int = 50): Waveform {
    val b = WaveformBuilder(RATE, perSec)
    var i = 0
    while (i < samples.size) { val n = minOf(chunk, samples.size - i); b.push(samples.copyOfRange(i, i + n), n); i += n }
    return b.build(samples.size * 1000L / RATE)
}

fun main() {
    println("Waveform tests\n")

    val wave = build(beepSong())
    check("Right number of slices (50 per second, 3 seconds)", abs(wave.count - 150) <= 1, "${wave.count}")
    check("The beep shows up where it is (1.0 to 1.2 s = slices 50 to 59)",
        (50..59).all { wave.max[it] > 60 && wave.min[it] < -60 } )
    check("Silence stays silent", (0 until 48).all { wave.max[it].toInt() == 0 && wave.min[it].toInt() == 0 } && (62 until wave.count).all { wave.max[it].toInt() == 0 })
    check("Peak is the beep level", abs(wave.peak - 0.8f) < 0.03f, "${wave.peak}")
    check("Sizes don't depend on how the sound arrives in pieces", build(beepSong(), chunk = 1).count == wave.count && build(beepSong(), chunk = 100000).max.contentEquals(wave.max))
    check("A sine has both a high and a low side", wave.max[55] > 0 && wave.min[55] < 0)

    // ---- saving
    val back = Waveform.fromBytes(wave.toBytes())
    check("Save and load give the same waveform", back != null && back.min.contentEquals(wave.min) && back.max.contentEquals(wave.max) && back.durationMs == wave.durationMs && back.peaksPerSec == 50)
    check("Damaged data is refused, not a crash", Waveform.fromBytes(ByteArray(10)) == null && Waveform.fromBytes(wave.toBytes().copyOf(30)) == null && Waveform.fromBytes(ByteArray(0)) == null)
    val broken = wave.toBytes(); broken[0] = 0
    check("Wrong file type is refused", Waveform.fromBytes(broken) == null)

    // ---- drawing
    val cols = WaveformMath.columns(wave, 0, 3000, 30) // 100 ms per column
    fun amp(c: Int) = cols[c * 2 + 1] - cols[c * 2]
    check("Columns: two numbers each", cols.size == 60)
    check("Columns: the beep is in columns 10 and 11, nowhere else", amp(10) > 1.0f && amp(11) > 1.0f && (0 until 30).filter { it != 10 && it != 11 }.all { amp(it) < 0.05f }, (0 until 30).filter { amp(it) > 0.05f }.toString())
    check("Columns: scaled so the loudest part reaches the top", cols[10 * 2 + 1] > 0.9f && cols[10 * 2] < -0.9f)
    val quiet = build(beepSong(0.05f))
    val quietCols = WaveformMath.columns(quiet, 0, 3000, 30)
    check("Columns: a very quiet song is boosted, but not into noise", quietCols[10 * 2 + 1] in 0.2f..1f)
    val zoom = WaveformMath.columns(wave, 900, 1300, 40) // 10 ms per column
    check("Columns, zoomed: the beep fills the middle of the window", (10..19).all { zoom[it * 2 + 1] - zoom[it * 2] > 0.5f } && zoom[1] - zoom[0] < 0.05f)
    val outside = WaveformMath.columns(wave, -2000, 0, 10)
    check("Columns: before the start of the song is silent", outside.all { it == 0f })
    val after = WaveformMath.columns(wave, 5000, 7000, 10)
    check("Columns: after the end of the song is silent", after.all { it == 0f })
    val straddle = WaveformMath.columns(wave, -1000, 4000, 50)
    check("Columns: a window sticking out of the song works", straddle.size == 100 && straddle[0] == 0f)
    check("Columns: odd inputs don't crash", WaveformMath.columns(wave, 100, 100, 5).all { it == 0f } && WaveformMath.columns(wave, 0, 1000, 0).isEmpty() && WaveformMath.columns(wave, 500, 100, 5).all { it == 0f })
    val emptyWave = Waveform(50, ByteArray(0), ByteArray(0), 0)
    check("Columns: an empty waveform is silent", WaveformMath.columns(emptyWave, 0, 1000, 4).all { it == 0f })

    // ---- screen positions
    check("Tap in the middle of the bar = middle of the song", WaveformMath.timeAt(150f, 300f, 200_000) == 100_000L)
    check("Tap beyond the ends is clamped", WaveformMath.timeAt(-20f, 300f, 200_000) == 0L && WaveformMath.timeAt(999f, 300f, 200_000) == 200_000L)
    check("Zero-width bar doesn't crash", WaveformMath.timeAt(5f, 0f, 1000) == 0L)
    check("Dragging the zoomed waveform to the right goes back in time", WaveformMath.timeAfterDrag(60_000, 100f, 400f, 10_000) == 57_500L)
    check("Dragging to the left goes forward", WaveformMath.timeAfterDrag(60_000, -200f, 400f, 10_000) == 65_000L)

    println()
    println(if (failures == 0) "ALL TESTS PASSED" else "$failures TEST(S) FAILED")
    if (failures != 0) System.exit(1)
}
