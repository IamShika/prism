package com.prism.musicplayer.audio

import kotlin.math.abs
import kotlin.math.exp

/**
 * Turns the player's position, which is reported in small uneven steps, into a position that moves
 * evenly on every screen frame. Used by the zoomed waveform so it scrolls without stuttering.
 *
 * Between reports it moves along with the clock. Each new report pulls it gently towards the truth;
 * if the report is far away (a seek, a new song) it jumps straight there.
 * No Android code in here, so it can be tested on a normal computer (tools/wave-test/run.sh).
 */
class PlayheadSmoother {
    private var positionMs = 0.0
    private var lastNanos = 0L
    private var running = false

    /**
     * Call once per frame. [reportedMs] is what the player says, [nowNanos] the clock, [speed] the playback speed
     * (0 when paused). Returns the position to draw.
     */
    fun update(reportedMs: Long, nowNanos: Long, speed: Double): Double {
        if (speed <= 0.0) { // stopped: draw exactly what the player says
            positionMs = reportedMs.toDouble()
            running = false
            return positionMs
        }
        if (!running) {
            positionMs = reportedMs.toDouble()
            lastNanos = nowNanos
            running = true
            return positionMs
        }
        val dtMs = ((nowNanos - lastNanos) / 1_000_000.0).coerceIn(0.0, 250.0)
        lastNanos = nowNanos
        positionMs += dtMs * speed
        val error = reportedMs - positionMs
        if (abs(error) > JUMP_MS) {
            positionMs = reportedMs.toDouble()
        } else {
            positionMs += error * (1.0 - exp(-dtMs / PULL_MS))
        }
        return positionMs
    }

    /** Forget everything (the next update starts fresh). */
    fun reset() {
        running = false
    }

    private companion object {
        /** A report further away than this is a seek, not a wobble. */
        const val JUMP_MS = 120.0
        /** How quickly reports correct drift. Bigger = smoother but slower to agree with the player. */
        const val PULL_MS = 200.0
    }
}
