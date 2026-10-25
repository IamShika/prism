package com.prism.musicplayer.audio

/** Ready-made reverb settings. Pick one, then tweak the knobs if you like. */
object ReverbPresets {

    private val smallRoom = ReverbParams(
        highCutHz = 8000f, lowCutHz = 80f, size = 0.25f, decaySec = 0.6f, diffusion = 0.7f, dampingHz = 6000f,
        dry = 1.0f, earlyLevel = 0.7f, wet = 0.45f,
        preDelayMs = 5f, mod = 0f, modSpeed = 0.33f, bass = 1.0f, crossHz = 300f, separation = 0f, pan = 0f,
    )

    private val studio = ReverbParams(
        highCutHz = 12000f, lowCutHz = 120f, size = 0.45f, decaySec = 1.6f, diffusion = 1.0f, dampingHz = 8000f,
        dry = 0.9f, earlyLevel = 0.3f, wet = 0.6f,
        preDelayMs = 15f, mod = 0.15f, modSpeed = 0.33f, bass = 0.9f, crossHz = 300f, separation = 0.2f, pan = 0f,
    )

    private val concertHall = ReverbParams(
        highCutHz = 9000f, lowCutHz = 40f, size = 0.75f, decaySec = 2.8f, diffusion = 0.9f, dampingHz = 5000f,
        dry = 0.6f, earlyLevel = 0.6f, wet = 0.8f,
        preDelayMs = 30f, mod = 0.1f, modSpeed = 0.33f, bass = 1.2f, crossHz = 250f, separation = 0.3f, pan = 0f,
    )

    /** The exact numbers from the reference screenshot (Reverb 2, "Cathedral"). */
    private val cathedral = ReverbParams(
        highCutHz = 4100f, lowCutHz = 33f, size = 0.9f, decaySec = 4.5f, diffusion = 1.0f, dampingHz = 3100f,
        dry = 0.2f, earlyLevel = 1.0f, wet = 1.1f,
        preDelayMs = 70f, mod = 0f, modSpeed = 0.33f, bass = 1.5f, crossHz = 298f, separation = 0f, pan = 0f,
    )

    private val dreamy = ReverbParams(
        highCutHz = 6000f, lowCutHz = 100f, size = 1.0f, decaySec = 9f, diffusion = 1.0f, dampingHz = 2500f,
        dry = 0.5f, earlyLevel = 0.3f, wet = 1.0f,
        preDelayMs = 60f, mod = 0.5f, modSpeed = 0.2f, bass = 1.3f, crossHz = 300f, separation = 0.6f, pan = 0f,
    )

    val all: List<Pair<String, ReverbParams>> = listOf(
        "Small Room" to smallRoom,
        "Studio" to studio,
        "Concert Hall" to concertHall,
        "Cathedral" to cathedral,
        "Dreamy" to dreamy,
    )

    /** What the reverb starts with, and what double-tapping a knob returns it to. */
    val default: ReverbParams = concertHall

    /** The preset these settings match exactly, or "Custom" if a knob was moved. */
    fun nameOf(params: ReverbParams): String {
        val p = params.copy(enabled = false)
        return all.firstOrNull { it.second == p }?.first ?: "Custom"
    }
}
