package com.prism.musicplayer

import android.app.Application
import android.content.Context
import android.media.audiofx.EnvironmentalReverb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.prism.musicplayer.data.Song
import com.prism.musicplayer.data.SongRepository
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
    val reverb: Float = 0f,
    val reverbAvailable: Boolean = true,
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
        PlayerUiState(accent = prefs.getInt(KEY_ACCENT, DEFAULT_ACCENT))
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** The audio engine. */
    private val player: ExoPlayer = ExoPlayer.Builder(app)
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

    private var reverbEffect: EnvironmentalReverb? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncCurrent()

            override fun onPlaybackStateChanged(playbackState: Int) = syncCurrent()
        })

        setupReverb()

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

    fun setReverb(level: Float) {
        _state.update { it.copy(reverb = level) }
        val effect = reverbEffect ?: return
        player.setAuxEffectInfo(AuxEffectInfo(effect.id, level))
    }

    private fun setupReverb() {
        try {
            // Priority 0 and session 0 = a global "aux" effect that ExoPlayer can send audio into.
            val fx = EnvironmentalReverb(0, 0)
            // A medium concert-hall style room
            fx.setRoomLevel((-1000).toShort())
            fx.setRoomHFLevel((-600).toShort())
            fx.setDecayTime(3000)
            fx.setDecayHFRatio(700.toShort())
            fx.setReflectionsLevel((-1200).toShort())
            fx.setReflectionsDelay(20)
            fx.setReverbLevel((-200).toShort())
            fx.setReverbDelay(30)
            fx.setDiffusion(1000.toShort())
            fx.setDensity(1000.toShort())
            fx.setEnabled(true)
            reverbEffect = fx
            player.setAuxEffectInfo(AuxEffectInfo(fx.id, 0f))
        } catch (e: Exception) {
            // Some phones don't offer this effect. The app keeps working without reverb.
            reverbEffect = null
            _state.update { it.copy(reverbAvailable = false) }
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
        reverbEffect?.release()
        super.onCleared()
    }

    private companion object {
        const val KEY_ACCENT = "accent_color"
    }
}
