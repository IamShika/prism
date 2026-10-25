package com.prism.musicplayer.audio

import kotlin.math.ln
import kotlin.math.pow

/**
 * Every knob of the reverb in one place. All values are plain numbers in "real" units
 * (Hz, seconds, 0..1 fractions) so the sound engine and the screen share the same language.
 */
data class ReverbParams(
    val enabled: Boolean = false,

    // ---- MAIN tab
    val highCutHz: Float = 9000f,   // removes harsh highs from the reverb
    val lowCutHz: Float = 40f,      // removes muddy lows from the reverb
    val size: Float = 0.6f,         // 0..1  room size
    val decaySec: Float = 2.0f,     // how long the tail takes to fade away (RT60)
    val diffusion: Float = 0.8f,    // 0..1  smooth (1) or grainy (0) tail
    val dampingHz: Float = 5000f,   // highs fade faster than lows above this
    val dry: Float = 1.0f,          // 0..1  original sound level
    val earlyLevel: Float = 0.5f,   // 0..1  first echoes off the walls (ER)
    val wet: Float = 0.4f,          // 0..2  reverb level

    // ---- TUNE tab
    val preDelayMs: Float = 20f,    // gap before the reverb starts
    val mod: Float = 0.1f,          // 0..1  slow shimmer in the tail
    val modSpeed: Float = 0.33f,    // 0..1  speed of that shimmer
    val bass: Float = 1.0f,         // lows ring this many times longer than the mids
    val crossHz: Float = 300f,      // where "lows" end and "mids" begin
    val separation: Float = 0.0f,   // 0..1  stereo widening of the tail
    val pan: Float = 0.0f,          // -1..1 shifts the reverb left / right
) {
    /** All numbers in one line of text, so the settings can be remembered between app launches. */
    fun encode(): String = listOf(
        if (enabled) 1f else 0f, highCutHz, lowCutHz, size, decaySec, diffusion, dampingHz, dry, earlyLevel, wet,
        preDelayMs, mod, modSpeed, bass, crossHz, separation, pan,
    ).joinToString(",")

    companion object {
        /** Reads what [encode] wrote. Returns null if the text is missing or damaged. */
        fun decode(text: String?): ReverbParams? {
            val v = text?.split(",")?.map { it.trim().toFloatOrNull() ?: return null } ?: return null
            if (v.size != 17 || v.any { it.isNaN() || it.isInfinite() }) return null
            return ReverbParams(
                enabled = v[0] > 0.5f,
                highCutHz = ReverbSpecs.highCut.clamp(v[1]), lowCutHz = ReverbSpecs.lowCut.clamp(v[2]),
                size = ReverbSpecs.size.clamp(v[3]), decaySec = ReverbSpecs.decay.clamp(v[4]),
                diffusion = ReverbSpecs.diffusion.clamp(v[5]), dampingHz = ReverbSpecs.damping.clamp(v[6]),
                dry = ReverbSpecs.dry.clamp(v[7]), earlyLevel = ReverbSpecs.early.clamp(v[8]),
                wet = ReverbSpecs.wet.clamp(v[9]), preDelayMs = ReverbSpecs.preDelay.clamp(v[10]),
                mod = ReverbSpecs.mod.clamp(v[11]), modSpeed = ReverbSpecs.modSpeed.clamp(v[12]),
                bass = ReverbSpecs.bass.clamp(v[13]), crossHz = ReverbSpecs.cross.clamp(v[14]),
                separation = ReverbSpecs.separation.clamp(v[15]), pan = ReverbSpecs.pan.clamp(v[16]),
            )
        }
    }
}

/** The allowed range of one knob, and how the 0..1 position of a knob maps onto it. */
class ParamSpec(val min: Float, val max: Float, private val log: Boolean = false) {
    fun clamp(value: Float): Float = value.coerceIn(min, max)

    fun toNorm(value: Float): Float {
        val v = value.coerceIn(min, max)
        return if (log) ln(v / min) / ln(max / min) else (v - min) / (max - min)
    }

    fun fromNorm(norm: Float): Float {
        val n = norm.coerceIn(0f, 1f)
        return if (log) min * (max / min).pow(n) else min + n * (max - min)
    }
}

/** Ranges for all knobs. Frequencies and decay use a log scale, which feels natural to turn. */
object ReverbSpecs {
    val highCut = ParamSpec(1000f, 20000f, log = true)
    val lowCut = ParamSpec(20f, 1000f, log = true)
    val size = ParamSpec(0f, 1f)
    val decay = ParamSpec(0.2f, 12f, log = true)
    val diffusion = ParamSpec(0f, 1f)
    val damping = ParamSpec(1000f, 20000f, log = true)
    val dry = ParamSpec(0f, 1f)
    val early = ParamSpec(0f, 1f)
    val wet = ParamSpec(0f, 2f)

    val preDelay = ParamSpec(0f, 250f)
    val mod = ParamSpec(0f, 1f)
    val modSpeed = ParamSpec(0f, 1f)
    val bass = ParamSpec(0.5f, 3f)
    val cross = ParamSpec(100f, 1000f, log = true)
    val separation = ParamSpec(0f, 1f)
    val pan = ParamSpec(-1f, 1f)
}
