// Offline checks for the crossfade logic. Runs on a normal computer, no phone needed.
// See tools/crossfade-test/run.sh
import com.prism.musicplayer.audio.CrossfadeLogic
import com.prism.musicplayer.audio.CrossfadeLogic.Action
import com.prism.musicplayer.audio.CrossfadeSettings
import com.prism.musicplayer.audio.FadeCurve
import kotlin.math.abs
import kotlin.math.sqrt

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    if (!ok) failures++
}

/** A tiny pretend music player + ghost, driven by the real logic, on a simulated clock. */
class Sim(
    var durationMs: Long,
    settings: CrossfadeSettings,
    val speed: Float = 1f,
    var hasNext: Boolean = true,
    val ghostReadyAfterMs: Long = 150L,
) {
    val logic = CrossfadeLogic().also { it.settings = settings }
    var now = 0L
    var item = "A"
    var next = "B"
    var pos = 0L
    var playing = true
    var mainVol = 1f
    var ghostVol = 0f
    var ghostPlaying = false
    var ghostPos = 0L
    var ghostPreparedAt = -1L
    var ghostStopped = true
    var skipsRun = 0
    val events = mutableListOf<Triple<Long, String, Long>>() // time, name, song position
    val volumes = mutableListOf<Triple<Long, Float, Float>>()
    var pendingSkips = mutableListOf<() -> Unit>()

    val ghostReady get() = ghostPreparedAt >= 0 && now - ghostPreparedAt >= ghostReadyAfterMs

    fun snapshot() = CrossfadeLogic.Snapshot(
        nowMs = now, itemId = item, hasNext = hasNext, nextItemId = if (hasNext) next else null, repeatOff = true,
        isPlaying = playing, playWhenReady = playing, durationMs = durationMs, positionMs = pos, speed = speed,
        ghostReady = ghostReady, ghostPositionMs = ghostPos,
    )

    private fun jump() {
        item = next; pos = 0; durationMs = 10 * 60_000L; hasNext = false; skipsRun++
    }

    fun apply(a: Action) {
        when (a) {
            is Action.Prepare -> { ghostPreparedAt = now; ghostPos = a.startPositionMs; ghostStopped = false; ghostPlaying = false; events += Triple(now, "prepare", a.startPositionMs) }
            is Action.Start -> {
                a.seekGhostToMs?.let { ghostPos = it }
                ghostPlaying = true
                mainVol = 0f
                events += Triple(now, if (a.manual) "start-manual" else "start-auto", pos)
                if (a.manual) { val p = pendingSkips.toList(); pendingSkips.clear(); p.forEach { it() } } else jump()
            }
            is Action.Volumes -> { mainVol = a.main; ghostVol = a.ghost; volumes += Triple(now, a.main, a.ghost) }
            Action.Release -> { ghostPlaying = false; ghostStopped = true; ghostPreparedAt = -1; mainVol = 1f; events += Triple(now, "release", pos) }
            Action.RunPending -> { val p = pendingSkips.toList(); pendingSkips.clear(); p.forEach { it() }; events += Triple(now, "run-pending", pos) }
        }
    }

    fun advance(dt: Long) {
        now += dt
        if (playing) pos += (dt * speed).toLong()
        if (ghostPlaying) ghostPos += (dt * speed).toLong()
    }

    /** Runs until [untilMs] using the logic's own tick rate. */
    fun run(untilMs: Long) {
        while (now < untilMs) {
            advance(logic.nextDelayMs())
            logic.tick(snapshot()).forEach(::apply)
        }
    }

    fun userSkip() {
        val r = logic.onManualSkip(snapshot())
        r.actions.forEach(::apply)
        if (r.deferred) pendingSkips += { jump() } else jump()
    }

    fun count(name: String) = events.count { it.second == name }
    fun time(name: String) = events.first { it.second == name }
}

fun main() {
    println("Crossfade logic tests\n")
    val six = CrossfadeSettings(enabled = true, seconds = 6)

    // 1. Curves
    for (c in FadeCurve.values()) {
        val ends = abs(c.incoming(0f)) < 1e-6 && abs(c.incoming(1f) - 1f) < 1e-6 && abs(c.outgoing(0f) - 1f) < 1e-6 && abs(c.outgoing(1f)) < 1e-6
        var mono = true
        var prevIn = -1f; var prevOut = 2f
        for (i in 0..100) { val t = i / 100f; if (c.incoming(t) < prevIn - 1e-6 || c.outgoing(t) > prevOut + 1e-6) mono = false; prevIn = c.incoming(t); prevOut = c.outgoing(t) }
        check("Curve ${c.label}: starts at 0/100%, ends at 100/0%, never goes the wrong way", ends && mono)
    }
    run {
        var worst = 0.0
        for (i in 0..100) { val t = i / 100f; val p = FadeCurve.EQUAL_POWER.incoming(t).toDouble().let { it * it } + FadeCurve.EQUAL_POWER.outgoing(t).toDouble().let { it * it }; worst = maxOf(worst, abs(p - 1.0)) }
        check("Equal power keeps total loudness steady", worst < 1e-5, "max error %.6f".format(worst))
        val mid = FadeCurve.EQUAL_POWER.incoming(0.5f)
        check("Equal power crosses at about 71%", abs(mid - 0.7071f) < 0.001f, "%.3f".format(mid))
    }

    // 2. Off means nothing ever happens
    run {
        val sim = Sim(30_000, CrossfadeSettings(enabled = false))
        sim.run(40_000)
        check("Crossfade off: no actions at all", sim.events.isEmpty() && sim.skipsRun == 0)
        sim.userSkip()
        check("Crossfade off: skip happens immediately", sim.skipsRun == 1 && sim.events.isEmpty())
    }

    // 3. Auto-advance, normal case
    run {
        val sim = Sim(30_000, six)
        sim.run(40_000)
        val prep = sim.time("prepare"); val start = sim.time("start-auto"); val rel = sim.time("release")
        check("Auto: ghost prepared about 3 s before the blend", prep.first in 20_900..21_300, "prepared at ${prep.first} ms")
        check("Auto: ghost is set to the last 6 s of the song", prep.third in 23_900..24_100, "ghost starts at ${prep.third} ms of 30000")
        check("Auto: blend starts 6 s before the end", start.third in 23_900..24_100, "at ${start.third} ms of 30000")
        val lenMs = rel.first - start.first
        check("Auto: blend lasts 6 s", lenMs in 5_900..6_150, "$lenMs ms")
        check("Auto: exactly one prepare, start and release", sim.count("prepare") == 1 && sim.count("start-auto") == 1 && sim.count("release") == 1)
        check("Auto: next song started once, main back to full volume", sim.skipsRun == 1 && sim.mainVol == 1f && !sim.ghostPlaying)
        var power = true; var rising = true; var prevM = -1f
        for ((_, m, g) in sim.volumes) { if (abs(m * m + g * g - 1f) > 0.001f) power = false; if (m < prevM - 1e-6) rising = false; prevM = m }
        check("Auto: loudness steady and new song only gets louder", power && rising)
    }

    // 4. Auto-advance switched off, manual still on
    run {
        val sim = Sim(30_000, six.copy(onAutoAdvance = false))
        sim.run(40_000)
        check("Auto-advance off: no automatic blend", sim.events.isEmpty())
    }

    // 5. Last song / repeat: no blend
    run {
        val sim = Sim(30_000, six, hasNext = false)
        sim.run(40_000)
        check("No next song: no blend", sim.events.isEmpty())
    }

    // 6. Short songs get a shorter blend
    run {
        val sim = Sim(6_000, six)
        sim.run(10_000)
        val len = sim.time("release").first - sim.time("start-auto").first
        check("Short song (6 s): blend limited to a third (2 s)", len in 1_900..2_150, "$len ms")
    }

    // 7. Speed 2x: the blend still takes 6 real seconds but covers 12 s of song
    run {
        val sim = Sim(60_000, six, speed = 2f)
        sim.run(60_000)
        val start = sim.time("start-auto")
        val len = sim.time("release").first - start.first
        check("2x speed: blend starts 12 s of song before the end", start.third in 47_800..48_300, "at ${start.third} ms of 60000")
        check("2x speed: blend still lasts 6 real seconds", len in 5_900..6_150, "$len ms")
    }

    // 8. User jumps back while prepared: prepares again later
    run {
        val sim = Sim(60_000, six)
        sim.run(52_000)                    // prepared (remaining ~8 s)
        check("Seek test: prepared first", sim.count("prepare") == 1)
        sim.pos = 5_000                    // user drags the bar back
        sim.run(sim.now + 100)
        check("Seek back cancels the preparation", sim.count("release") == 1 && !sim.ghostPlaying)
        sim.run(sim.now + 60_000)
        check("...and the blend still happens at the real end", sim.count("start-auto") == 1 && sim.skipsRun == 1)
    }

    // 9. Skip during an auto blend ends it at once
    run {
        val sim = Sim(30_000, six)
        sim.run(27_000)                    // 3 s into the blend
        check("Skip test: blend is running", sim.logic.phase == CrossfadeLogic.Phase.FADING)
        sim.item = "C"                     // user pressed next again
        sim.run(sim.now + 100)
        check("Skipping again ends the blend immediately", sim.count("release") == 1 && sim.mainVol == 1f && !sim.ghostPlaying)
    }

    // 10. Pause in the middle of a blend freezes it
    run {
        val sim = Sim(30_000, six)
        sim.run(27_000)
        val before = sim.volumes.last().second
        sim.playing = false
        sim.run(sim.now + 4_000)
        val during = sim.volumes.last().second
        check("Paused blend stays where it was", abs(before - during) < 0.05f && sim.count("release") == 0, "main volume %.2f -> %.2f".format(before, during))
        sim.playing = true
        sim.run(sim.now + 6_000)
        check("Blend finishes after resuming", sim.count("release") == 1 && sim.mainVol == 1f)
    }

    // 11. Manual skip
    run {
        val sim = Sim(120_000, six)
        sim.run(30_000)
        sim.userSkip()
        check("Manual: skip is held back at first", sim.skipsRun == 0 && sim.logic.phase == CrossfadeLogic.Phase.WAITING)
        val t0 = sim.now
        sim.run(sim.now + 1_000)
        val start = sim.time("start-manual")
        check("Manual: skip happens once the ghost is ready (~150 ms later)", sim.skipsRun == 1 && start.first - t0 in 100..300, "${start.first - t0} ms later")
        check("Manual: ghost carries on from where the old song was", abs(sim.ghostPos - 31_000L) < 1_500, "ghost at ${sim.ghostPos}")
        sim.run(sim.now + 7_000)
        check("Manual: blend ends and ghost stops", sim.count("release") == 1 && sim.mainVol == 1f && !sim.ghostPlaying)
    }

    // 12. Manual skip switched off
    run {
        val sim = Sim(120_000, six.copy(onManualSkip = false))
        sim.run(30_000)
        sim.userSkip()
        check("Manual crossfade off: skip is immediate", sim.skipsRun == 1 && sim.events.isEmpty())
    }

    // 13. Several quick skips are all done, in order, once
    run {
        val sim = Sim(120_000, six)
        sim.run(30_000)
        sim.userSkip(); sim.userSkip(); sim.userSkip()
        sim.run(sim.now + 1_000)
        check("Three quick taps run three skips and one blend", sim.skipsRun == 3 && sim.count("start-manual") == 1, "skips ${sim.skipsRun}")
    }

    // 14. Paused or not playing: skip is immediate
    run {
        val sim = Sim(120_000, six)
        sim.run(30_000)
        sim.playing = false
        sim.userSkip()
        check("Skipping while paused is immediate and silent", sim.skipsRun == 1 && sim.events.isEmpty())
    }

    // 15. Ghost is slow to load: do not wait forever
    run {
        val sim = Sim(120_000, six, ghostReadyAfterMs = 5_000)
        sim.run(30_000)
        sim.userSkip()
        val t0 = sim.now
        sim.run(sim.now + 2_000)
        val waited = sim.time("start-manual").first - t0
        check("Slow ghost: skip happens after at most ~0.8 s anyway", sim.skipsRun == 1 && waited in 700..900, "$waited ms")
    }

    // 16. Turning crossfade off during a wait still runs the skip
    run {
        val sim = Sim(120_000, six)
        sim.run(30_000)
        sim.userSkip()
        sim.logic.settings = CrossfadeSettings(enabled = false)
        sim.run(sim.now + 200)
        check("Switching crossfade off mid-wait does not lose the skip", sim.skipsRun == 1 && sim.count("run-pending") == 1)
    }

    println()
    println(if (failures == 0) "ALL TESTS PASSED" else "$failures TEST(S) FAILED")
    if (failures != 0) System.exit(1)
}
