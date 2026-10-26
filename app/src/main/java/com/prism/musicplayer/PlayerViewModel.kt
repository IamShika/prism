package com.prism.musicplayer

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.prism.musicplayer.audio.AudioEngine
import com.prism.musicplayer.audio.CrossfadeSettings
import com.prism.musicplayer.audio.ReverbParams
import com.prism.musicplayer.audio.ReverbPresets
import com.prism.musicplayer.data.AlbumMover
import com.prism.musicplayer.data.AlbumNaming
import com.prism.musicplayer.data.ExternalAudio
import com.prism.musicplayer.data.Song
import com.prism.musicplayer.data.SongRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

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
    val playError: String? = null,
    // Sound controls
    val speed: Float = 1f,
    val pitchSemitones: Float = 0f,
    val reverb: ReverbParams = ReverbPresets.default,
    val crossfade: CrossfadeSettings = CrossfadeSettings(),
    // Album creator
    val selectedIds: Set<Long> = emptySet(),
    val albumBusy: Boolean = false,
    // Theme
    val accent: Int = DEFAULT_ACCENT,
) {
    /** True while the user is picking songs for an album. */
    val selecting: Boolean get() = selectedIds.isNotEmpty()

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

    /** The sound engine is shared with the notification and the "open with" overlay. */
    private val engine = AudioEngine.get(app)
    private val player: Player = engine.player

    private val _state = MutableStateFlow(
        PlayerUiState(
            accent = prefs.getInt(KEY_ACCENT, DEFAULT_ACCENT),
            reverb = engine.reverb,
            crossfade = engine.crossfade,
            speed = player.playbackParameters.speed,
            pitchSemitones = semitonesOf(player.playbackParameters.pitch),
            isPlaying = player.isPlaying,
        )
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var saveReverbJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncCurrent()

        override fun onPlaybackStateChanged(playbackState: Int) = syncCurrent()

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(playError = "This file can't be played.") }
        }
    }

    init {
        player.addListener(listener)
        syncCurrent() // the music may already be playing (started from the notification or the overlay)

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
        _state.update { it.copy(playError = null) }
        player.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
        player.prepare()
        player.play()
        engine.startService()
        syncCurrent()
    }

    /** Plays a music file that another app asked us to open. */
    fun playExternal(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(playError = null) }
            val opened = try {
                ExternalAudio.resolve(getApplication(), uri)
            } catch (e: Exception) {
                _state.update { it.copy(playError = "Couldn't open this file.") }
                return@launch
            }
            val item = MediaItem.Builder()
                .setMediaId(opened.mediaId)
                .setUri(opened.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(opened.title).setArtist(opened.artist).build())
                .build()
            player.setMediaItem(item)
            player.prepare()
            player.play()
            engine.startService()
            syncCurrent()
        }
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
        engine.setSpeedAndPitch(s.speed, pitchFactor)
    }

    private fun semitonesOf(pitchFactor: Float): Float =
        (12.0 * ln(pitchFactor.toDouble()) / ln(2.0)).roundToInt().toFloat()

    // --------------------------------------------------------------- crossfade

    /** Changes some crossfade settings, e.g. `updateCrossfade { it.copy(seconds = 8) }`. */
    fun updateCrossfade(change: (CrossfadeSettings) -> CrossfadeSettings) {
        val updated = change(_state.value.crossfade)
        _state.update { it.copy(crossfade = updated) }
        engine.setCrossfade(updated)
    }

    // ----------------------------------------------------------------- reverb

    /** Changes some of the reverb settings, e.g. `updateReverb { it.copy(size = 0.8f) }`. */
    fun updateReverb(change: (ReverbParams) -> ReverbParams) {
        _state.update { it.copy(reverb = change(it.reverb)) }
        engine.setReverb(_state.value.reverb)
        // Remember the settings, but wait until the knob stops moving before writing to storage.
        saveReverbJob?.cancel()
        saveReverbJob = viewModelScope.launch {
            delay(400)
            engine.saveReverb()
        }
    }

    /** Loads a preset but keeps the on/off switch where it was. */
    fun setReverbPreset(name: String) {
        val preset = ReverbPresets.all.firstOrNull { it.first == name }?.second ?: return
        updateReverb { current -> preset.copy(enabled = current.enabled) }
    }

    // ------------------------------------------------------------------ albums

    private class AlbumPlan(val folder: String, val songs: List<Song>)

    /** The album waiting for the user's OK in Android's "allow Prism to modify these files?" dialog. */
    private var plan: AlbumPlan? = null

    private val _writeRequests = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    /** The screen listens to this and shows Android's permission dialog. */
    val writeRequests: SharedFlow<IntentSender> = _writeRequests.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Short messages for the screen to show ("Moved 5 songs to ..."). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun say(message: String) {
        _messages.tryEmit(message)
    }

    fun toggleSelected(song: Song) {
        _state.update {
            val ids = it.selectedIds
            it.copy(selectedIds = if (song.id in ids) ids - song.id else ids + song.id)
        }
    }

    fun selectAllVisible() {
        _state.update { it.copy(selectedIds = it.visibleSongs.map { song -> song.id }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet()) }
    }

    /** Moves the selected songs into a new folder called [rawName] inside Music. */
    fun createAlbum(rawName: String) {
        val current = _state.value
        if (current.albumBusy) return
        val folder = AlbumNaming.sanitize(rawName)
        if (folder == null) {
            say("Please type a name for the album.")
            return
        }
        val songs = current.allSongs.filter { it.id in current.selectedIds }
        if (songs.isEmpty()) return

        plan = AlbumPlan(folder, songs)
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+: one dialog where the user allows Prism to change these files.
            val resolver = getApplication<Application>().contentResolver
            val request = MediaStore.createWriteRequest(resolver, songs.map { it.uri })
            _writeRequests.tryEmit(request.intentSender)
        } else {
            runMove()
        }
    }

    /** Called with the user's answer to Android's dialog. */
    fun onWriteRequestResult(allowed: Boolean) {
        if (!allowed) {
            plan = null
            say("Cancelled. Nothing was moved.")
            return
        }
        runMove()
    }

    private fun runMove() {
        val album = plan ?: return
        plan = null
        _state.update { it.copy(albumBusy = true) }
        viewModelScope.launch {
            val result = AlbumMover.move(getApplication(), album.songs, album.folder)
            _state.update { it.copy(albumBusy = false, selectedIds = if (result.moved > 0) emptySet() else it.selectedIds) }
            say(AlbumNaming.summary(result.moved, result.failed, album.folder))
            loadSongs()
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
        val item = player.currentMediaItem
        val duration = when {
            player.duration != C.TIME_UNSET -> player.duration
            else -> item?.mediaMetadata?.durationMs ?: 0L
        }
        val song = item?.let { current ->
            Song(
                id = current.mediaId.toLongOrNull() ?: -1L,
                title = current.mediaMetadata.title?.toString() ?: "Unknown title",
                artist = current.mediaMetadata.artist?.toString() ?: "Unknown artist",
                durationMs = duration,
                uri = current.localConfiguration?.uri ?: Uri.EMPTY,
            )
        }
        _state.update {
            it.copy(currentSong = song, durationMs = duration, positionMs = player.currentPosition)
        }
    }

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setDurationMs(durationMs)
                    .build()
            )
            .build()

    override fun onCleared() {
        // The player is NOT released: it belongs to the app and keeps playing in the background.
        player.removeListener(listener)
        engine.saveReverb()
        super.onCleared()
    }

    private companion object {
        const val KEY_ACCENT = "accent_color"
    }
}
