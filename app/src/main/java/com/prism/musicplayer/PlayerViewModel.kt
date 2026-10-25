package com.prism.musicplayer

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.prism.musicplayer.audio.ReverbAudioProcessor
import com.prism.musicplayer.audio.ReverbParams
import com.prism.musicplayer.audio.ReverbPresets
import com.prism.musicplayer.audio.ReverbProcessorChain
import com.prism.musicplayer.data.Song
import com.prism.musicplayer.data.SongRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.pow

/** Longest value of the length filter slider. At this value there is no upper limit ("10:00+"). */
const val MAX_FILTER_SEC = 600

val DEFAULT_ACCENT: Int = 0xFF8B7CFF.toInt()

/** Everything the screen needs to know, in one place. The UI just draws this. */
data class PlayerUiState(
    val allSongs: List<Song> = emptyList(),
    val loading: Boolean = false,
    // Length filter
    val minSec: Int = 0,
    val maxSec: Int = MAX_FILTER_SEC,
    // Playback
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    // Sound controls
    val speed: Float = 1f,
    val pitchSemitones: Float = 0f,
    val reverb: ReverbParams = ReverbPresets.default,
    // Theme
    val accent: Int = DEFAULT_ACCENT,
) {
    /** The songs that pass the length filter. */
    val visibleSongs: List<Song>
        get() = allSongs.filter { song ->
            val sec = song.durationMs / 1000
            sec >= minSec && (maxSec >= MAX_FILTER_SEC || sec <= maxSec)
        }
}

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("prism_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        PlayerUiState(
            accent = prefs.getInt(KEY_ACCENT, DEFAULT_ACCENT),
            reverb = ReverbParams.decode(prefs.getString(KEY_REVERB, null)) ?: ReverbPresets.default,
        )
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Our own reverb. It sits inside the audio pipeline, after the speed/pitch changer. */
    private val reverbProcessor = ReverbAudioProcessor()

    /** The audio engine. */
    private val player: ExoPlayer = ExoPlayer.Builder(app, buildRenderersFactory(app))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()

    private var saveReverbJob: Job? = null

    init {
        reverbProcessor.setParams(_state.value.reverb)

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncCurrent()

            override fun onPlaybackStateChanged(playbackState: Int) = syncCurrent()
        })

        // Update the progress bar 4 times per second while music is playing.
        viewModelScope.launch {
            while (true) {
                if (player.isPlaying) {
                    _state.update { it.copy(positionMs = player.currentPosition) }
                }
                delay(250)
            }
        }
    }

    /**
     * Builds the audio part of the player by hand, so we can add our own reverb
     * to the sound pipeline. (Music only: no video or subtitles needed.)
     */
    private fun buildRenderersFactory(context: Context): RenderersFactory {
        val audioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(ReverbProcessorChain(reverbProcessor))
            .build()
        return RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audioListener, audioSink)
            )
        }
    }

    // ---------------------------------------------------------------- library

    fun loadSongs() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val songs = SongRepository.load(getApplication())
            _state.update { it.copy(allSongs = songs, loading = false) }
        }
    }

    fun setLengthFilter(minSec: Int, maxSec: Int) {
        _state.update { it.copy(minSec = minSec, maxSec = maxSec) }
    }

    // --------------------------------------------------------------- playback

    /** Plays [song] and queues the rest of the currently filtered list after it. */
    fun playSong(song: Song) {
        val list = _state.value.visibleSongs
        val index = list.indexOfFirst { it.id == song.id }
        if (index < 0) return
        player.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
        player.prepare()
        player.play()
        syncCurrent()
    }

    fun togglePlayPause() {
        when {
            player.currentMediaItem == null -> _state.value.visibleSongs.firstOrNull()?.let(::playSong)
            player.isPlaying -> player.pause()
            else -> {
                if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                player.play()
            }
        }
    }

    fun next() = player.seekToNext()

    fun previous() = player.seekToPrevious()

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _state.update { it.copy(positionMs = positionMs) }
    }

    // ----------------------------------------------------------- sound effects

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        applyPlaybackParameters()
    }

    fun setPitch(semitones: Float) {
        _state.update { it.copy(pitchSemitones = semitones) }
        applyPlaybackParameters()
    }

    private fun applyPlaybackParameters() {
        val s = _state.value
        // 12 semitones = one octave = double the frequency
        val pitchFactor = 2.0.pow(s.pitchSemitones / 12.0).toFloat()
        player.playbackParameters = PlaybackParameters(s.speed, pitchFactor)
    }

    // ----------------------------------------------------------------- reverb

    /** Changes some of the reverb settings, e.g. `updateReverb { it.copy(size = 0.8f) }`. */
    fun updateReverb(change: (ReverbParams) -> ReverbParams) {
        _state.update { it.copy(reverb = change(it.reverb)) }
        applyReverb()
    }

    /** Loads a preset but keeps the on/off switch where it was. */
    fun setReverbPreset(name: String) {
        val preset = ReverbPresets.all.firstOrNull { it.first == name }?.second ?: return
        updateReverb { current -> preset.copy(enabled = current.enabled) }
    }

    private fun applyReverb() {
        val params = _state.value.reverb
        reverbProcessor.setParams(params)
        // Remember the settings, but wait until the knob stops moving before writing to storage.
        saveReverbJob?.cancel()
        saveReverbJob = viewModelScope.launch {
            delay(400)
            prefs.edit().putString(KEY_REVERB, params.encode()).apply()
        }
    }

    // ------------------------------------------------------------------ theme

    fun setAccent(color: Int) {
        prefs.edit().putInt(KEY_ACCENT, color).apply()
        _state.update { it.copy(accent = color) }
    }

    // ---------------------------------------------------------------- helpers

    /** Copies the player's current song / duration / position into the UI state. */
    private fun syncCurrent() {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        val song = id?.let { songId -> _state.value.allSongs.firstOrNull { it.id == songId } }
        val duration = if (player.duration == C.TIME_UNSET) song?.durationMs ?: 0L else player.duration
        _state.update {
            it.copy(currentSong = song, durationMs = duration, positionMs = player.currentPosition)
        }
    }

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build())
            .build()

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private companion object {
        const val KEY_ACCENT = "accent_color"
        const val KEY_REVERB = "reverb_params"
    }
}
