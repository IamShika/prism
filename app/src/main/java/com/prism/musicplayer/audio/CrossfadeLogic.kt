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
 *  - A second player ("ghost") plays a copy of the OLD song while main jumps to the NEW song.
 *  - Main fades in as the ghost fades out. When the fade is over the ghost stops.
 *
 * The tricky part is the moment the old song moves from main to the ghost. A player that was
 * paused needs a moment before it makes sound again, which would be heard as a short pause in the
 * old song. So the ghost is started EARLY and SILENTLY ("rolling"), and lined up with main's position.
 * At the handover only the volumes change, so the old song never stops.
 *
 * Phases:  IDLE -> PREPARED (ghost loaded, paused) -> ROLLING (ghost playing silently, lining up)
 *          -> FADING (the blend) -> IDLE
 *
 * It happens in two situations:
 *  - Auto-advance: a few seconds before the blend is due.
 *  - Manual skip: the skip is held back for a fraction of a second while the ghost warms up.
 *
 * Every ~20-250 ms the glue code gives this class a [Snapshot] (what the players look like right now)
 * and gets back a list of [Action]s to carry out.
 */
class CrossfadeLogic {

    enum class Phase { IDLE, PREPARED, ROLLING, FADING }

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
        val ghostReady: Boolean,     // loaded and able to play
        val ghostPlaying: Boolean,   // actually producing sound right now (not loading or seeking)
        val ghostPositionMs: Long,
    )

    sealed class Action {
        /** Load the current song into the ghost, paused, near [startPositionMs]. */
        class Prepare(val startPositionMs: Long) : Action()

        /** Start the ghost, silent, at [seekToMs]. */
        class StartGhost(val seekToMs: Long) : Action()

        /** Move the (silent) ghost to [toMs] to line it up with main. */
        class SeekGhost(val toMs: Long) : Action()

        /**
         * The handover: main goes quiet and jumps on, the ghost becomes the old song.
         * If [manual] the jump is the user's skip that was being held back, otherwise it's "next song".
         */
        class Start(val manual: Boolean) : Action()

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

    /**
     * How far ahead of main the ghost must be sent so that it is level with main by the time it
     * actually makes sound. Learned from each attempt, because it differs per phone and per file.
     * Starting cold and jumping to a new spot take different amounts of time, so there are two.
     */
    var startLagMs: Long = 150L
        private set
    var seekLagMs: Long = 100L
        private set

    /** How many corrections the last blend needed. For tests. */
    var lastSeekCount: Int = 0
        private set

    private var manual = false
    private var sourceId: String? = null
    private var targetId: String? = null
    private var fadeLengthMs = 0L
    private var elapsedMs = 0L
    private var lastTickMs = 0L
    private var requestMs = 0L
    private var lastSeekMs = 0L
    private var seeks = 0
    private var alignedTicks = 0
    private var lastMoveWasSeek = false

    /** How long to wait before the next [tick]. Fast when something is about to happen. */
    fun nextDelayMs(): Long = when (phase) {
        Phase.IDLE -> 250L
        Phase.PREPARED -> 50L
        Phase.ROLLING -> 25L
        Phase.FADING -> 33L
    }

    fun tick(s: Snapshot): List<Action> {
        if (settings.fadeMs <= 0L) return abort()
        return when (phase) {
            Phase.IDLE -> idle(s)
            Phase.PREPARED -> prepared(s)
            Phase.ROLLING -> rolling(s)
            Phase.FADING -> fading(s)
        }
    }

    /**
     * The user asked to jump to another song ([next], [previous] or tapping a song in the list).
     * Call this BEFORE doing the jump.
     */
    fun onManualSkip(s: Snapshot): ManualResult {
        // several quick taps while we are already warming up: do them all, in order, at the handover
        if (manual && (phase == Phase.PREPARED || phase == Phase.ROLLING)) return ManualResult(emptyList(), deferred = true)

        if (!settings.enabled || !settings.onManualSkip || phase == Phase.FADING) return ManualResult(reset(), deferred = false)
        if (!s.isPlaying || s.itemId == null || s.durationMs <= 0) return ManualResult(reset(), deferred = false)
        val real = fadeRealMs(s)
        if (real < MIN_FADE_MS) return ManualResult(reset(), deferred = false)

        val before = reset() // drops an automatic preparation if there was one
        manual = true
        sourceId = s.itemId
        fadeLengthMs = real
        requestMs = s.nowMs
        phase = Phase.PREPARED
        return ManualResult(before + Action.Prepare(s.positionMs), deferred = true)
    }

    // ------------------------------------------------------------------ phases

    private fun idle(s: Snapshot): List<Action> {
        if (!settings.onAutoAdvance) return emptyList()
        if (!s.isPlaying || !canFade(s)) return emptyList()
        val real = fadeRealMs(s)
        if (real < MIN_FADE_MS) return emptyList()
        val media = toMediaMs(real, s)
        val remaining = s.durationMs - s.positionMs
        if (remaining > media + ROLL_LEAD_MS + PREPARE_AHEAD_MS) return emptyList()

        manual = false
        sourceId = s.itemId
        phase = Phase.PREPARED
        return listOf(Action.Prepare(max(s.durationMs - media - ROLL_LEAD_MS, s.positionMs)))
    }

    private fun prepared(s: Snapshot): List<Action> {
        // the song changed under us: forget it (a held-back skip still has to happen)
        if (s.itemId != sourceId) return abort()

        if (manual) {
            if (!s.playWhenReady) return abort() // paused: just skip, no blend
            if (s.ghostReady) return startRolling(s)
            if (s.nowMs - requestMs >= MANUAL_PREPARE_MAX_MS) return abort() // too slow: plain skip
            return emptyList()
        }

        if (!canFade(s)) return reset()
        val real = fadeRealMs(s)
        val media = toMediaMs(real, s)
        val remaining = s.durationMs - s.positionMs
        // the user jumped back in the song: the ghost is in the wrong place now
        if (remaining > media + ROLL_LEAD_MS + PREPARE_AHEAD_MS + REWIND_TOLERANCE_MS) return reset()

        if (!s.isPlaying) return emptyList() // paused: wait
        if (remaining > media + ROLL_LEAD_MS) return emptyList() // not yet
        return startRolling(s)
    }

    private fun startRolling(s: Snapshot): List<Action> {
        phase = Phase.ROLLING
        lastSeekMs = s.nowMs
        seeks = 0
        alignedTicks = 0
        lastMoveWasSeek = false
        // Aim ahead of main by the expected start delay; the ghost will be level when it begins to sound.
        return listOf(Action.StartGhost(s.positionMs + startLagMs))
    }

    private fun rolling(s: Snapshot): List<Action> {
        if (s.itemId != sourceId) return abort()
        if (!s.playWhenReady) {
            // paused: a manual skip just happens, an automatic blend waits (the ghost pauses too)
            return if (manual) abort() else emptyList()
        }

        val real = if (manual) fadeLengthMs else fadeRealMs(s)
        val media = toMediaMs(real, s)
        val remaining = s.durationMs - s.positionMs
        if (!manual) {
            if (!canFade(s)) return reset()
            if (remaining > media + ROLL_LEAD_MS + PREPARE_AHEAD_MS + REWIND_TOLERANCE_MS) return reset() // seeked back
        }

        val drift = s.ghostPositionMs - s.positionMs // positive: the ghost is ahead
        val settled = s.ghostPlaying && s.nowMs - lastSeekMs >= SEEK_SETTLE_MS
        alignedTicks = if (settled && abs(drift) <= ALIGN_TOLERANCE_MS) alignedTicks + 1 else 0

        // The ghost is not level with main yet: move it, silently, and remember what we learned.
        if (settled && abs(drift) > ALIGN_TOLERANCE_MS && s.nowMs - lastSeekMs >= SEEK_COOLDOWN_MS && seeks < MAX_SEEKS) {
            // learn from what we just saw, then move the ghost silently to where it should be
            if (lastMoveWasSeek) seekLagMs = (seekLagMs - drift).coerceIn(0L, MAX_LAG_MS)
            else startLagMs = (startLagMs - drift).coerceIn(0L, MAX_LAG_MS)
            seeks++
            lastSeekMs = s.nowMs
            alignedTicks = 0
            lastMoveWasSeek = true
            return listOf(Action.SeekGhost(s.positionMs + seekLagMs))
        }

        val aligned = alignedTicks >= ALIGNED_TICKS_NEEDED
        val gaveUp = seeks >= MAX_SEEKS && settled && abs(drift) <= ACCEPT_ANYWAY_DRIFT_MS

        if (manual) {
            if (aligned || gaveUp) return handover(s, real)
            if (s.nowMs - requestMs >= MANUAL_TOTAL_MAX_MS) {
                // out of patience: blend if the ghost is close, otherwise just skip
                return if (s.ghostPlaying && abs(drift) <= ACCEPT_ANYWAY_DRIFT_MS) handover(s, real) else abort()
            }
            return emptyList()
        }

        if (remaining <= media) {
            if (aligned || gaveUp) return handover(s, real)
            if (remaining <= media - AUTO_LATE_MS) return handover(s, real) // never let the song run out
        }
        return emptyList()
    }

    private fun handover(s: Snapshot, real: Long): List<Action> {
        lastSeekCount = seeks
        fadeLengthMs = real
        elapsedMs = 0L
        lastTickMs = s.nowMs
        targetId = if (manual) null else s.nextItemId // after a manual skip we learn the new id on the next tick
        phase = Phase.FADING
        return listOf(Action.Start(manual), Action.Volumes(main = 0f, ghost = 1f))
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
        manual = false
        sourceId = null
        targetId = null
        return if (wasActive) listOf(Action.Release) else emptyList()
    }

    /** Give up on the blend. If a user skip was being held back, it happens now, without a blend. */
    private fun abort(): List<Action> {
        val heldSkip = manual && (phase == Phase.PREPARED || phase == Phase.ROLLING)
        val actions = reset()
        return if (heldSkip) actions + Action.RunPending else actions
    }

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

        /** How early the ghost is loaded, and how early it starts rolling, before the blend is due. */
        const val PREPARE_AHEAD_MS = 2000L
        const val ROLL_LEAD_MS = 2500L
        const val REWIND_TOLERANCE_MS = 1500L

        /** The ghost counts as level with main if it is within this many milliseconds. */
        const val ALIGN_TOLERANCE_MS = 30L
        const val ALIGNED_TICKS_NEEDED = 2
        const val SEEK_SETTLE_MS = 150L
        const val SEEK_COOLDOWN_MS = 250L
        const val MAX_SEEKS = 4
        const val MAX_LAG_MS = 1500L
        const val ACCEPT_ANYWAY_DRIFT_MS = 150L

        const val AUTO_LATE_MS = 600L
        const val MANUAL_PREPARE_MAX_MS = 1000L
        const val MANUAL_TOTAL_MAX_MS = 1800L
    }
}
