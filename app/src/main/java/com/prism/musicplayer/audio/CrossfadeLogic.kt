package com.prism.musicplayer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How the two songs trade places during a crossfade. */
enum class FadeCurve(val label: String) {
    /** Keeps the total loudness steady. The usual choice. */
    EQUAL_POWER("Equal power"),

    /** A straight line. Dips slightly in the middle. */
    LINEAR("Linear"),

    /** Starts and ends gently, changes faster in the middle. */
    SMOOTH("Smooth");

    /** Loudness of the new song, 0..1, at time [t] (0 = start of the blend, 1 = end). */
    fun incoming(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            EQUAL_POWER -> sin(x * (PI / 2.0)).toFloat()
            LINEAR -> x
            SMOOTH -> x * x * (3f - 2f * x)
        }
    }

    /** Loudness of the old song, 0..1. */
    fun outgoing(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            EQUAL_POWER -> cos(x * (PI / 2.0)).toFloat()
            LINEAR -> 1f - x
            SMOOTH -> 1f - x * x * (3f - 2f * x)
        }
    }
}

data class CrossfadeSettings(
    val enabled: Boolean = false,
    val seconds: Int = 6,
    val curve: FadeCurve = FadeCurve.EQUAL_POWER,
    val onManualSkip: Boolean = true,
    val onAutoAdvance: Boolean = true,
) {
    val fadeMs: Long get() = if (enabled) seconds.coerceIn(1, 12) * 1000L else 0L
}

/**
 * The brain of the crossfade. It contains no Android code, so it can be tested on a normal computer.
 *
 * How the crossfade works:
 *  - The normal player ("main") plays the playlist.
 *  - A second player ("ghost") plays a copy of the OLD song from where it is, while main jumps to the NEW song.
 *  - Main fades in as the ghost fades out. When the fade is over the ghost stops.
 *
 * It happens in two situations:
 *  - Auto-advance: shortly before a song ends, the ghost is prepared with the last seconds of it,
 *    then main jumps to the next song at the right moment.
 *  - Manual skip: the ghost is prepared with the song at its current spot, and only then does the
 *    skip happen (a fraction of a second later), so the old song never has a gap.
 *
 * Every ~20-250 ms the glue code gives this class a [Snapshot] (what the players look like right now)
 * and gets back a list of [Action]s to carry out.
 */
class CrossfadeLogic {

    enum class Phase { IDLE, PREPARED, WAITING, FADING }

    class Snapshot(
        val nowMs: Long,
        val itemId: String?,
        val hasNext: Boolean,
        val nextItemId: String?,
        val repeatOff: Boolean,
        val isPlaying: Boolean,
        val playWhenReady: Boolean,
        val durationMs: Long,        // length of the current song, 0 or less if unknown yet
        val positionMs: Long,
        val speed: Float,
        val ghostReady: Boolean,
        val ghostPositionMs: Long,
    )

    sealed class Action {
        /** Load the current song into the ghost, paused, starting at [startPositionMs]. */
        class Prepare(val startPositionMs: Long) : Action()

        /**
         * Play the ghost, then make main jump to the next song.
         * If [manual] the jump is the user's skip that was being held back, otherwise it's "next song".
         */
        class Start(val seekGhostToMs: Long?, val manual: Boolean) : Action()

        /** Set the loudness of both players (0..1). */
        class Volumes(val main: Float, val ghost: Float) : Action()

        /** Stop the ghost and put main back to full volume. */
        object Release : Action()

        /** Carry out the user's skip that was being held back, without any blend. */
        object RunPending : Action()
    }

    /** What [onManualSkip] decided. If [deferred], hold the skip until [Action.Start] or [Action.RunPending]. */
    class ManualResult(val actions: List<Action>, val deferred: Boolean)

    var settings: CrossfadeSettings = CrossfadeSettings()

    var phase: Phase = Phase.IDLE
        private set

    private var sourceId: String? = null
    private var targetId: String? = null
    private var fadeLengthMs = 0L
    private var elapsedMs = 0L
    private var lastTickMs = 0L
    private var waitStartMs = 0L

    /** How long to wait before the next [tick]. Fast when something is about to happen. */
    fun nextDelayMs(): Long = when (phase) {
        Phase.IDLE -> 250L
        Phase.PREPARED -> 20L
        Phase.WAITING -> 20L
        Phase.FADING -> 33L
    }

    fun tick(s: Snapshot): List<Action> {
        if (settings.fadeMs <= 0L) return if (phase == Phase.WAITING) abortWaiting() else reset()
        return when (phase) {
            Phase.IDLE -> idle(s)
            Phase.PREPARED -> prepared(s)
            Phase.WAITING -> waiting(s)
            Phase.FADING -> fading(s)
        }
    }

    /**
     * The user asked to jump to another song ([next], [previous] or tapping a song in the list).
     * Call this BEFORE doing the jump.
     */
    fun onManualSkip(s: Snapshot): ManualResult {
        if (phase == Phase.WAITING) return ManualResult(emptyList(), deferred = true) // several quick taps: do them all in order
        if (!settings.enabled || !settings.onManualSkip || phase == Phase.FADING) return ManualResult(reset(), deferred = false)
        if (!s.isPlaying || s.itemId == null || s.durationMs <= 0) return ManualResult(reset(), deferred = false)
        val real = fadeRealMs(s)
        if (real < MIN_FADE_MS) return ManualResult(reset(), deferred = false)

        sourceId = s.itemId
        fadeLengthMs = real
        waitStartMs = s.nowMs
        phase = Phase.WAITING
        return ManualResult(listOf(Action.Prepare(s.positionMs)), deferred = true)
    }

    // ------------------------------------------------------------------ phases

    private fun idle(s: Snapshot): List<Action> {
        if (!settings.onAutoAdvance) return emptyList()
        if (!s.isPlaying || !canFade(s)) return emptyList()
        val real = fadeRealMs(s)
        if (real < MIN_FADE_MS) return emptyList()
        val media = toMediaMs(real, s)
        val remaining = s.durationMs - s.positionMs
        if (remaining > media + PREPARE_AHEAD_MS) return emptyList()

        sourceId = s.itemId
        phase = Phase.PREPARED
        return listOf(Action.Prepare(max(s.durationMs - media, s.positionMs)))
    }

    private fun prepared(s: Snapshot): List<Action> {
        // the user skipped or the song changed by itself: forget it
        if (s.itemId != sourceId || !canFade(s)) return reset()

        val real = fadeRealMs(s)
        val media = toMediaMs(real, s)
        val remaining = s.durationMs - s.positionMs
        // the user jumped back in the song: the ghost is in the wrong place now
        if (remaining > media + PREPARE_AHEAD_MS + REWIND_TOLERANCE_MS) return reset()

        if (!s.isPlaying) return emptyList() // paused: wait
        if (remaining > media) return emptyList() // not yet
        // give the ghost a moment to be ready, but never wait long
        if (!s.ghostReady && remaining > media - MAX_WAIT_MS) return emptyList()

        fadeLengthMs = real
        elapsedMs = 0L
        lastTickMs = s.nowMs
        targetId = s.nextItemId
        phase = Phase.FADING
        return listOf(Action.Start(ghostSeek(s), manual = false), Action.Volumes(main = 0f, ghost = 1f))
    }

    private fun waiting(s: Snapshot): List<Action> {
        // paused, or the song changed by itself: skip normally
        if (s.itemId != sourceId || !s.isPlaying) return abortWaiting()
        if (!s.ghostReady && s.nowMs - waitStartMs < MANUAL_WAIT_MAX_MS) return emptyList()

        elapsedMs = 0L
        lastTickMs = s.nowMs
        targetId = null // we learn the new song's id on the next tick, after the skip happened
        phase = Phase.FADING
        return listOf(Action.Start(ghostSeek(s), manual = true), Action.Volumes(main = 0f, ghost = 1f))
    }

    private fun fading(s: Snapshot): List<Action> {
        if (targetId == null) {
            // first tick after a manual skip
            if (s.itemId == sourceId) return reset()
            targetId = s.itemId
        }
        // the song changed again (user pressed next or previous): end the blend right away
        if (s.itemId != targetId) return reset()

        val dt = (s.nowMs - lastTickMs).coerceAtLeast(0L)
        lastTickMs = s.nowMs
        if (s.playWhenReady) elapsedMs += dt // paused: the blend waits too

        val t = (elapsedMs.toFloat() / fadeLengthMs).coerceIn(0f, 1f)
        if (t >= 1f) return reset()

        return listOf(Action.Volumes(main = settings.curve.incoming(t), ghost = settings.curve.outgoing(t)))
    }

    // ----------------------------------------------------------------- helpers

    private fun reset(): List<Action> {
        val wasActive = phase != Phase.IDLE
        phase = Phase.IDLE
        sourceId = null
        targetId = null
        return if (wasActive) listOf(Action.Release) else emptyList()
    }

    private fun abortWaiting(): List<Action> = reset() + Action.RunPending

    /** If the ghost is far from where main is, move it, so the old song carries on without a jump. */
    private fun ghostSeek(s: Snapshot): Long? =
        if (abs(s.ghostPositionMs - s.positionMs) > GHOST_SYNC_TOLERANCE_MS) s.positionMs else null

    private fun canFade(s: Snapshot) = s.hasNext && s.repeatOff && s.itemId != null && s.durationMs > 0

    /** The blend in real time: never longer than a third of the song, so short songs still work. */
    private fun fadeRealMs(s: Snapshot): Long {
        val speed = s.speed.coerceAtLeast(0.1f)
        return min(settings.fadeMs, (s.durationMs / speed / 3f).toLong())
    }

    /** At 2x speed, 5 seconds of real time cover 10 seconds of the song. */
    private fun toMediaMs(realMs: Long, s: Snapshot): Long = (realMs * s.speed.coerceAtLeast(0.1f)).toLong()

    private companion object {
        const val MIN_FADE_MS = 500L
        const val PREPARE_AHEAD_MS = 3000L
        const val REWIND_TOLERANCE_MS = 1500L
        const val MAX_WAIT_MS = 600L
        const val MANUAL_WAIT_MAX_MS = 800L
        const val GHOST_SYNC_TOLERANCE_MS = 120L
    }
}
