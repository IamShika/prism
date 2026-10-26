package com.prism.musicplayer.audio

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.prism.musicplayer.PlaybackService

/**
 * Everything that makes sound, in one place that lives as long as the app process.
 * The screen, the "open with" overlay and the notification all share this single engine,
 * so the music keeps playing when you leave the app.
 *
 * It owns two players: [player] (the one you hear and control) and a hidden helper
 * that only exists to make crossfades possible.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AudioEngine private constructor(private val app: Context) {

    private val prefs = app.getSharedPreferences("prism_prefs", Context.MODE_PRIVATE)

    private val reverbMain = ReverbAudioProcessor()
    private val reverbGhost = ReverbAudioProcessor()
    private val exoMain: ExoPlayer = buildPlayer(reverbMain, isMain = true)
    private val exoGhost: ExoPlayer = buildPlayer(reverbGhost, isMain = false)
    private val crossfadeController = CrossfadeController(exoMain, exoGhost)

    /** Use this player for everything: play, pause, skip, seek. */
    val player: Player = CrossfadePlayer(exoMain, crossfadeController)

    var reverb: ReverbParams = ReverbParams.decode(prefs.getString(KEY_REVERB, null)) ?: ReverbPresets.default
        private set

    var crossfade: CrossfadeSettings = loadCrossfade()
        private set

    init {
        reverbMain.setParams(reverb)
        reverbGhost.setParams(reverb)
        crossfadeController.settings = crossfade
    }

    // ------------------------------------------------------------------ settings

    fun setReverb(params: ReverbParams) {
        reverb = params
        reverbMain.setParams(params)
        reverbGhost.setParams(params)
    }

    fun saveReverb() {
        prefs.edit().putString(KEY_REVERB, reverb.encode()).apply()
    }

    fun setCrossfade(settings: CrossfadeSettings) {
        crossfade = settings
        crossfadeController.settings = settings
        prefs.edit()
            .putBoolean(KEY_FADE_ON, settings.enabled)
            .putInt(KEY_FADE_SEC, settings.seconds)
            .putInt(KEY_FADE_CURVE, settings.curve.ordinal)
            .putBoolean(KEY_FADE_MANUAL, settings.onManualSkip)
            .putBoolean(KEY_FADE_AUTO, settings.onAutoAdvance)
            .apply()
    }

    fun setSpeedAndPitch(speed: Float, pitchFactor: Float) {
        exoMain.playbackParameters = PlaybackParameters(speed, pitchFactor) // the helper player follows automatically
    }

    /**
     * Starts the background service, which shows the notification and the controls in the phone's
     * media panel. Call it when playback starts, while the app is on screen.
     */
    fun startService() {
        try {
            app.startService(Intent(app, PlaybackService::class.java))
        } catch (e: Exception) {
            // The music still plays; only the notification is missing.
        }
    }

    // ------------------------------------------------------------------- internals

    private fun loadCrossfade() = CrossfadeSettings(
        enabled = prefs.getBoolean(KEY_FADE_ON, false),
        seconds = prefs.getInt(KEY_FADE_SEC, 6).coerceIn(1, 12),
        curve = FadeCurve.values().getOrElse(prefs.getInt(KEY_FADE_CURVE, 0)) { FadeCurve.EQUAL_POWER },
        onManualSkip = prefs.getBoolean(KEY_FADE_MANUAL, true),
        onAutoAdvance = prefs.getBoolean(KEY_FADE_AUTO, true),
    )

    /**
     * Builds a player whose sound passes through our own reverb.
     * The helper player must not fight over audio focus or the headphone-unplugged pause.
     */
    private fun buildPlayer(reverb: ReverbAudioProcessor, isMain: Boolean): ExoPlayer {
        val audioSink = DefaultAudioSink.Builder(app)
            .setAudioProcessorChain(ReverbProcessorChain(reverb))
            .build()
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(app, MediaCodecSelector.DEFAULT, handler, audioListener, audioSink)
            )
        }
        return ExoPlayer.Builder(app, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ isMain,
            )
            .setHandleAudioBecomingNoisy(isMain) // pause when headphones are unplugged
            .setWakeMode(if (isMain) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NONE)
            .build()
    }

    companion object {
        private const val KEY_REVERB = "reverb_params"
        private const val KEY_FADE_ON = "crossfade_enabled"
        private const val KEY_FADE_SEC = "crossfade_seconds"
        private const val KEY_FADE_CURVE = "crossfade_curve"
        private const val KEY_FADE_MANUAL = "crossfade_manual"
        private const val KEY_FADE_AUTO = "crossfade_auto"

        @Volatile
        private var instance: AudioEngine? = null

        fun get(context: Context): AudioEngine =
            instance ?: synchronized(this) {
                instance ?: AudioEngine(context.applicationContext).also { instance = it }
            }
    }
}
