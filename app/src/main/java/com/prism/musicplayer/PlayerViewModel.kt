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
import com.prism.musicplayer.data.AlbumFolder
import com.prism.musicplayer.data.AlbumMover
import com.prism.musicplayer.data.AlbumNaming
import com.prism.musicplayer.data.ExternalAudio
import com.prism.musicplayer.data.LibraryOrdering
import com.prism.musicplayer.data.MarkerRepository
import com.prism.musicplayer.data.Move
import com.prism.musicplayer.data.Song
import com.prism.musicplayer.data.SongRepository
import com.prism.musicplayer.data.SortMode
import com.prism.musicplayer.data.TrackMarker
import com.prism.musicplayer.data.TrackMarkers
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

/** Which list the home screen shows. */
enum class LibraryView { SONGS, ALBUMS }

/** A short message for the screen. If [openFolderSuffix] is set, the message offers an "Open" button for that folder. */
class UiMessage(val text: String, val openFolderSuffix: String? = null)

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
    // Start / end markers of the current song
    val markers: TrackMarker = TrackMarker(),
    val fullDurationMs: Long = 0L,  // the whole file, ignoring markers
    val clipStartMs: Long = 0L,     // where the player is actually starting (position 0 of the seek bar = this spot in the file)
    // Library
    val view: LibraryView = LibraryView.SONGS,
    val openFolder: String? = null,   // the opened album (only matters on the Albums page)
    val query: String = "",
    val sort: SortMode = SortMode.TITLE_AZ,
    /** The orders the user arranged by hand. Key = album folder, or "" for all music. */
    val customOrders: Map<String, List<Long>> = emptyMap(),
    val arranging: Boolean = false,
    // Album creator
    val selectedIds: Set<Long> = emptySet(),
    val albumBusy: Boolean = false,
    // Theme
    val accent: Int = DEFAULT_ACCENT,
) {
    /** True while the user is picking songs for an album. */
    val selecting: Boolean get() = selectedIds.isNotEmpty()

    /**
     * The songs of [folder] (null = all music) that pass the length filter and the search, in the chosen order.
     * With [everything] the filter and search are ignored: used while arranging, so no song is hidden.
     */
    fun songsIn(folder: String?, everything: Boolean = false): List<Song> {
        val inScope = if (folder == null) allSongs else allSongs.filter { it.folderPath == folder }
        val shown = if (everything) inScope else inScope.filter { song ->
            val sec = song.durationMs / 1000
            sec >= minSec && (maxSec >= MAX_FILTER_SEC || sec <= maxSec) && LibraryOrdering.matches(song, query)
        }
        return LibraryOrdering.sort(shown, sort, customOrders[folder ?: ""])
    }

    /** The songs on the "All music" page. */
    val visibleSongs: List<Song> get() = songsIn(null)

    /** The folder whose songs are on screen right now: the opened album, or null for all music. */
    val scopeFolder: String? get() = if (view == LibraryView.ALBUMS) openFolder else null

    /** Every folder with music in it, for the Albums list (search and sort apply). */
    val albums: List<AlbumFolder>
        get() = LibraryOrdering.sortAlbums(
            allSongs
                .filter { it.folderPath.isNotEmpty() }
                .groupBy { it.folderPath }
                .map { (path, songs) ->
                    AlbumFolder(
                        path = path,
                        songCount = songs.size,
                        totalMs = songs.sumOf { it.durationMs },
                        totalBytes = songs.sumOf { it.sizeBytes },
                        newestAddedSec = songs.maxOf { it.addedSec },
                        oldestAddedSec = songs.minOf { it.addedSec },
                    )
                },
            sort,
        )
}

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("prism_prefs", Context.MODE_PRIVATE)
    private val markerRepo = MarkerRepository.get(app)

    /** The sound engine is shared with the notification and the "open with" overlay. */
    private val engine = AudioEngine.get(app)
    private val player: Player = engine.player

    private val _state = MutableStateFlow(
        PlayerUiState(
            accent = prefs.getInt(KEY_ACCENT, DEFAULT_ACCENT),
            sort = SortMode.values().firstOrNull { it.name == prefs.getString(KEY_SORT, null) } ?: SortMode.TITLE_AZ,
            customOrders = LibraryOrdering.decodeOrders(prefs.getString(KEY_ORDERS, "") ?: ""),
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

    fun setView(view: LibraryView) {
        // each page keeps its own place; arranging belongs to one list, so it ends when you change page
        _state.update { if (it.view == view) it else it.copy(view = view, arranging = false) }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun setSort(sort: SortMode) {
        prefs.edit().putString(KEY_SORT, sort.name).apply()
        _state.update { it.copy(sort = sort, arranging = if (sort == SortMode.CUSTOM) it.arranging else false) }
    }

    // ------------------------------------------------- arranging songs by hand

    /** Starts rearranging the songs on screen. The order is saved separately for each album. */
    fun startArranging() {
        val current = _state.value
        val key = current.scopeFolder ?: ""
        // Keep an order the user arranged earlier. Otherwise start from what they see now (e.g. A to Z),
        // so nothing jumps around when arranging begins.
        if (current.customOrders[key].isNullOrEmpty()) {
            saveOrder(key, current.songsIn(current.scopeFolder, everything = true).map { it.id })
        }
        prefs.edit().putString(KEY_SORT, SortMode.CUSTOM.name).apply()
        _state.update { it.copy(sort = SortMode.CUSTOM, arranging = true, query = "") }
    }

    fun stopArranging() {
        _state.update { it.copy(arranging = false) }
    }

    /** Moves one song up, down or to the top of the list being arranged. */
    fun moveSong(song: Song, how: Move) {
        val current = _state.value
        val key = current.scopeFolder ?: ""
        val ids = current.songsIn(current.scopeFolder, everything = true).map { it.id }
        saveOrder(key, LibraryOrdering.moved(ids, song.id, how))
    }

    private fun saveOrder(key: String, ids: List<Long>) {
        val updated = _state.value.customOrders + (key to ids)
        prefs.edit().putString(KEY_ORDERS, LibraryOrdering.encodeOrders(updated)).apply()
        _state.update { it.copy(customOrders = updated) }
    }

    fun openAlbum(path: String) {
        _state.update { it.copy(view = LibraryView.ALBUMS, openFolder = path) }
    }

    fun closeAlbum() {
        _state.update { it.copy(openFolder = null) }
    }

    /** Opens the album whose folder ends with [suffix], e.g. "/Music/Road Trip". Used by the "Open" button. */
    fun openAlbumBySuffix(suffix: String) {
        val path = _state.value.albums.firstOrNull { it.path.endsWith(suffix, ignoreCase = true) }?.path ?: return
        openAlbum(path)
    }

    // --------------------------------------------------------------- playback

    /** Plays [song] and queues the rest of the currently filtered list after it. */
    fun playSong(song: Song, queue: List<Song> = _state.value.visibleSongs) {
        val list = queue
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
            player.currentMediaItem == null -> _state.value.visibleSongs.firstOrNull()?.let { playSong(it) }
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

    // ------------------------------------------------------------ start/end markers

    private enum class Preview { START, END }

    private var markerApplyJob: Job? = null

    /** Makes the current spot the start of the song. */
    fun setStartHere() = editMarkers(Preview.START) { m, full ->
        TrackMarkers.withStart(m, _state.value.clipStartMs + player.currentPosition, full)
    }

    /** Makes the current spot the end of the song. */
    fun setEndHere() = editMarkers(Preview.END) { m, full ->
        TrackMarkers.withEnd(m, _state.value.clipStartMs + player.currentPosition, full)
    }

    fun nudgeStart(deltaMs: Long) = editMarkers(Preview.START) { m, full ->
        TrackMarkers.withStart(m, m.startMs + deltaMs, full)
    }

    fun nudgeEnd(deltaMs: Long) = editMarkers(Preview.END) { m, full ->
        TrackMarkers.withEnd(m, m.effectiveEnd(full) + deltaMs, full)
    }

    fun clearMarkers() = editMarkers(Preview.START) { _, _ -> TrackMarker() }

    /** Jumps to a few seconds before the end, to hear where the song will stop. */
    fun previewEnd() {
        player.seekTo((_state.value.durationMs - PREVIEW_TAIL_MS).coerceAtLeast(0L))
    }

    private fun editMarkers(preview: Preview, change: (TrackMarker, Long) -> TrackMarker) {
        val current = _state.value
        val song = current.currentSong ?: return
        if (song.id < 0 || current.fullDurationMs <= 0L) return // songs opened from other apps have no saved markers
        val updated = change(current.markers, current.fullDurationMs)
        if (updated == current.markers) return

        markerRepo.set(song.id, updated)            // saved right away
        _state.update { it.copy(markers = updated) } // the numbers on screen change right away

        // The player is told once the buttons stop being tapped, because it restarts the song when it hears.
        markerApplyJob?.cancel()
        markerApplyJob = viewModelScope.launch {
            delay(500)
            applyMarkers(song.id, updated, preview)
        }
    }

    /** Tells the player about new markers: it will only play the part between start and end. */
    private fun applyMarkers(songId: Long, markers: TrackMarker, preview: Preview) {
        val text = songId.toString()
        var currentChanged = false
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            if (item.mediaId != text) continue
            if (i == player.currentMediaItemIndex) currentChanged = true
            player.replaceMediaItem(i, item.withMarkers(markers))
        }
        // Replacing the current song restarts it at the new start. After changing the END, jump to the
        // last seconds instead, because that is what the user wants to hear.
        if (currentChanged && preview == Preview.END) {
            val length = markers.clipLengthMs(_state.value.fullDurationMs)
            player.seekTo((length - PREVIEW_TAIL_MS).coerceAtLeast(0L))
        }
        syncCurrent()
    }

    private fun MediaItem.withMarkers(markers: TrackMarker): MediaItem {
        val clip = if (markers.isSet) {
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(markers.startMs)
                .setEndPositionMs(if (markers.endMs > 0L) markers.endMs else C.TIME_END_OF_SOURCE)
                .build()
        } else {
            MediaItem.ClippingConfiguration.UNSET
        }
        return buildUpon().setClippingConfiguration(clip).build()
    }

    // ------------------------------------------------------------------ albums

    private class AlbumPlan(val folder: String, val songs: List<Song>)

    /** The album waiting for the user's OK in Android's "allow Prism to modify these files?" dialog. */
    private var plan: AlbumPlan? = null

    private val _writeRequests = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    /** The screen listens to this and shows Android's permission dialog. */
    val writeRequests: SharedFlow<IntentSender> = _writeRequests.asSharedFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)

    /** Short messages for the screen to show ("Moved 5 songs to ..."). */
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    fun say(message: String, openFolderSuffix: String? = null) {
        _messages.tryEmit(UiMessage(message, openFolderSuffix))
    }

    fun toggleSelected(song: Song) {
        _state.update {
            val ids = it.selectedIds
            it.copy(selectedIds = if (song.id in ids) ids - song.id else ids + song.id)
        }
    }

    fun selectAllVisible() {
        _state.update { it.copy(selectedIds = it.songsIn(it.scopeFolder).map { song -> song.id }.toSet()) }
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
            // after a successful move the message offers to open the new album
            val suffix = if (result.moved > 0) "/${AlbumNaming.BASE_FOLDER}/${album.folder}" else null
            say(AlbumNaming.summary(result.moved, result.failed, album.folder), suffix)
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
        val songId = item?.mediaId?.toLongOrNull()
        val clipStart = item?.clippingConfiguration?.startPositionMs ?: 0L
        val markers = if (songId != null && songId >= 0) markerRepo.get(songId) else TrackMarker()
        val full = item?.mediaMetadata?.durationMs ?: (duration + clipStart)
        _state.update {
            it.copy(
                currentSong = song,
                durationMs = duration,
                positionMs = player.currentPosition,
                // markers are saved the moment they change, so the saved copy is always up to date
                markers = markers,
                fullDurationMs = full,
                clipStartMs = clipStart,
            )
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
            .withMarkers(markerRepo.get(id))

    override fun onCleared() {
        // The player is NOT released: it belongs to the app and keeps playing in the background.
        player.removeListener(listener)
        engine.saveReverb()
        super.onCleared()
    }

    private companion object {
        const val KEY_ACCENT = "accent_color"
        const val KEY_SORT = "sort_mode"
        const val KEY_ORDERS = "custom_orders"
        const val PREVIEW_TAIL_MS = 3000L
    }
}
