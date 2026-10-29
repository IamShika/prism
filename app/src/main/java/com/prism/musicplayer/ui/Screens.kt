package com.prism.musicplayer.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.prism.musicplayer.LibraryView
import com.prism.musicplayer.MAX_FILTER_SEC
import com.prism.musicplayer.PlayerUiState
import com.prism.musicplayer.PlayerViewModel
import com.prism.musicplayer.R
import com.prism.musicplayer.data.AlbumFolder
import com.prism.musicplayer.data.LibraryOrdering
import com.prism.musicplayer.data.Move
import com.prism.musicplayer.data.Song
import com.prism.musicplayer.data.SortMode
import com.prism.musicplayer.formatTime
import kotlin.math.roundToInt

/** The whole app screen: song list on top, mini player at the bottom. */
@Composable
fun PrismApp(state: PlayerUiState, vm: PlayerViewModel) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    // Ask for permission on first launch; load the songs as soon as we have it.
    // Android 13+ also needs permission to show the music notification (the controls outside the app).
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* if refused, the music still plays, only the notification is missing */ }

    LaunchedEffect(granted) {
        if (granted) {
            vm.loadSongs()
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            permissionLauncher.launch(permission)
        }
    }

    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var showAlbumDialog by rememberSaveable { mutableStateOf(false) }

    // ---- album creator plumbing
    val snackbar = remember { SnackbarHostState() }

    // Android 11+: the system asks "Allow Prism to modify these files?" before songs can be moved.
    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onWriteRequestResult(result.resultCode == Activity.RESULT_OK) }

    // Android 9 and older: a normal storage permission is needed instead.
    var pendingAlbumName by remember { mutableStateOf<String?>(null) }
    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        val name = pendingAlbumName
        pendingAlbumName = null
        if (allowed && name != null) vm.createAlbum(name) else vm.say("Cancelled. Nothing was moved.")
    }

    LaunchedEffect(Unit) {
        vm.writeRequests.collect { writeLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            val folder = message.openFolderSuffix
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = if (folder != null) "Open" else null,
            )
            if (result == SnackbarResult.ActionPerformed && folder != null) vm.openAlbumBySuffix(folder)
        }
    }

    fun startAlbum(name: String) {
        showAlbumDialog = false
        val needsLegacyPermission = Build.VERSION.SDK_INT < 30 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsLegacyPermission) {
            pendingAlbumName = name
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.createAlbum(name)
        }
    }

    // Back button, in this order: leave selection mode, stop arranging, close an opened album
    BackHandler(enabled = state.openFolder != null && state.view == LibraryView.ALBUMS) { vm.closeAlbum() }
    BackHandler(enabled = state.arranging) { vm.stopArranging() }
    BackHandler(enabled = state.selecting) { vm.clearSelection() }

    // The lists on the two pages. While arranging, the length filter and the search are ignored so no song is hidden.
    val arrangingMusic = state.arranging && state.view == LibraryView.SONGS
    val arrangingAlbum = state.arranging && state.view == LibraryView.ALBUMS
    val musicSongs = remember(
        state.allSongs, state.minSec, state.maxSec, state.query, state.sort, state.customOrders, arrangingMusic,
    ) { state.songsIn(null, everything = arrangingMusic) }
    val albumSongs = remember(
        state.allSongs, state.minSec, state.maxSec, state.query, state.sort, state.customOrders, state.openFolder, arrangingAlbum,
    ) { state.openFolder?.let { state.songsIn(it, everything = arrangingAlbum) } ?: emptyList() }
    val albums = remember(state.allSongs, state.sort) { state.albums }
    val shownAlbums = remember(albums, state.query) { albums.filter { LibraryOrdering.matches(it, state.query) } }

    // An opened album that no longer exists (all its songs were moved away) closes itself.
    LaunchedEffect(albums, state.openFolder, state.loading) {
        val open = state.openFolder
        if (open != null && !state.loading && state.allSongs.isNotEmpty() && albums.none { it.path == open }) vm.closeAlbum()
    }

    // Swiping between "All music" and "Albums". The pager and the tabs stay in step.
    val pager = rememberPagerState { 2 }
    LaunchedEffect(pager.currentPage) {
        vm.setView(if (pager.currentPage == 0) LibraryView.SONGS else LibraryView.ALBUMS)
    }
    LaunchedEffect(state.view) {
        val target = if (state.view == LibraryView.SONGS) 0 else 1
        if (pager.currentPage != target) pager.animateScrollToPage(target)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (state.selecting) {
                SelectionBar(
                    count = state.selectedIds.size,
                    busy = state.albumBusy,
                    onCancel = vm::clearSelection,
                    onSelectAll = vm::selectAllVisible,
                    onCreate = { showAlbumDialog = true },
                )
            } else {
                TopBar(onTheme = { showTheme = true })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // The player is always there. With nothing playing it says so.
        bottomBar = { MiniPlayer(state, onToggle = vm::togglePlayPause, onOpen = { showPlayer = true }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!granted) {
                PermissionPrompt(onAllow = { permissionLauncher.launch(permission) })
            } else {
                SearchRow(
                    query = state.query,
                    onQuery = vm::setQuery,
                    sort = state.sort,
                    onSort = vm::setSort,
                    canArrange = state.view == LibraryView.SONGS || state.openFolder != null,
                    onArrange = vm::startArranging,
                )
                LibraryTabs(view = state.view, onView = vm::setView, sortLabel = state.sort.label)
                if (state.arranging) ArrangeBar(onDone = vm::stopArranging)

                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    userScrollEnabled = !state.arranging,
                ) { page ->
                    if (page == 0) {
                        SongsPage(
                            state = state,
                            vm = vm,
                            songs = musicSongs,
                            totalInScope = state.allSongs.size,
                            arranging = arrangingMusic,
                        )
                    } else if (state.openFolder == null) {
                        AlbumList(albums = shownAlbums, loading = state.loading, query = state.query, onOpen = vm::openAlbum)
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            AlbumHeader(
                                name = albums.firstOrNull { it.path == state.openFolder }?.name
                                    ?: LibraryOrdering.folderName(state.openFolder),
                                onBack = vm::closeAlbum,
                            )
                            SongsPage(
                                state = state,
                                vm = vm,
                                songs = albumSongs,
                                totalInScope = state.allSongs.count { it.folderPath == state.openFolder },
                                arranging = arrangingAlbum,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPlayer && state.currentSong != null) {
        NowPlayingSheet(state, vm, onDismiss = { showPlayer = false })
    }
    if (showAlbumDialog) {
        CreateAlbumDialog(
            count = state.selectedIds.size,
            onCreate = { name -> startAlbum(name) },
            onDismiss = { showAlbumDialog = false },
        )
    }
    if (showTheme) {
        ThemeDialog(accent = state.accent, onPick = vm::setAccent, onDismiss = { showTheme = false })
    }
}

// ------------------------------------------------------------------ top bar

@Composable
private fun TopBar(onTheme: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Prism",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        // A colour dot that shows the current accent. Tap it to change the theme.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onTheme)
        )
    }
}

// ------------------------------------------------------------------- albums

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTabs(view: LibraryView, onView: (LibraryView) -> Unit, sortLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = view == LibraryView.SONGS, onClick = { onView(LibraryView.SONGS) }, label = { Text("All music") })
        FilterChip(selected = view == LibraryView.ALBUMS, onClick = { onView(LibraryView.ALBUMS) }, label = { Text("Albums") })
        Spacer(Modifier.weight(1f))
        Text(
            text = sortLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The search box and the sort menu. */
@Composable
private fun SearchRow(
    query: String,
    onQuery: (String) -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    canArrange: Boolean,
    onArrange: () -> Unit,
) {
    val focus = LocalFocusManager.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text("Search songs, artists, albums") },
            trailingIcon = {
                if (query.isNotEmpty()) TextButton(onClick = { onQuery("") }) { Text("✕") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        Spacer(Modifier.width(4.dp))
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("Sort ▼") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                SortMode.values().forEach { mode ->
                    DropdownMenuItem(
                        text = { Text((if (mode == sort) "✓  " else "      ") + mode.label) },
                        onClick = {
                            menuOpen = false
                            onSort(mode)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Arrange my own order…") },
                    enabled = canArrange,
                    onClick = {
                        menuOpen = false
                        onArrange()
                    },
                )
            }
        }
    }
}

/** Shown while the songs are being rearranged by hand. */
@Composable
private fun ArrangeBar(onDone: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Arranging. Use ▲ ▼ and Top to move songs. Saved for this list.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

/** One page of songs: the length filter on top, then the list. */
@Composable
private fun SongsPage(
    state: PlayerUiState,
    vm: PlayerViewModel,
    songs: List<Song>,
    totalInScope: Int,
    arranging: Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        if (!arranging) {
            LengthFilter(
                minSec = state.minSec,
                maxSec = state.maxSec,
                shown = songs.size,
                total = totalInScope,
                onChange = vm::setLengthFilter,
            )
        }
        SongList(
            songs = songs,
            current = state.currentSong,
            loading = state.loading,
            total = totalInScope,
            query = state.query,
            selectedIds = state.selectedIds,
            arranging = arranging,
            onPlay = { song -> vm.playSong(song, songs) },
            onToggleSelected = vm::toggleSelected,
            onMove = vm::moveSong,
        )
    }
}

/** Every folder that has music in it. Tap one to open it. */
@Composable
private fun AlbumList(albums: List<AlbumFolder>, loading: Boolean, query: String, onOpen: (String) -> Unit) {
    when {
        loading -> CenteredMessage("Loading your music…")
        albums.isEmpty() && query.isNotBlank() -> CenteredMessage("No albums match \"${query.trim()}\".")
        albums.isEmpty() -> CenteredMessage("No albums yet.\nPress and hold songs in All music to make one.")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
        ) {
            items(albums, key = { it.path }) { album ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(album.path) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = (if (album.songCount == 1) "1 song" else "${album.songCount} songs") +
                                " · " + formatTime(album.totalMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumHeader(name: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("← Albums") }
        Text(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Replaces the top bar while songs are being picked for an album. */
@Composable
private fun SelectionBar(
    count: Int,
    busy: Boolean,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Text(
            text = "$count selected",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onSelectAll) { Text("All") }
        Button(onClick = onCreate, enabled = !busy) { Text("New album") }
    }
}

// ------------------------------------------------------------- length filter

@Composable
private fun LengthFilter(
    minSec: Int,
    maxSec: Int,
    shown: Int,
    total: Int,
    onChange: (Int, Int) -> Unit,
) {
    val maxLabel = if (maxSec >= MAX_FILTER_SEC) "10:00+" else formatTime(maxSec * 1000L)

    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Song length", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                text = "${formatTime(minSec * 1000L)} – $maxLabel",
                color = MaterialTheme.colorScheme.primary,
            )
        }
        RangeSlider(
            value = minSec.toFloat()..maxSec.toFloat(),
            onValueChange = { range -> onChange(range.start.roundToInt(), range.endInclusive.roundToInt()) },
            valueRange = 0f..MAX_FILTER_SEC.toFloat(),
            steps = 119, // 5-second steps
        )
        Text(
            text = "Showing $shown of $total songs · press and hold a song to make an album",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- song list

@Composable
private fun SongList(
    songs: List<Song>,
    current: Song?,
    loading: Boolean,
    total: Int,
    query: String,
    selectedIds: Set<Long>,
    arranging: Boolean,
    onPlay: (Song) -> Unit,
    onToggleSelected: (Song) -> Unit,
    onMove: (Song, Move) -> Unit,
) {
    val selecting = selectedIds.isNotEmpty()
    when {
        loading -> CenteredMessage("Loading your music…")
        total == 0 -> CenteredMessage("No music found on this phone.\nCopy some songs onto it and reopen Prism.")
        songs.isEmpty() && query.isNotBlank() -> CenteredMessage("No songs match \"${query.trim()}\".")
        songs.isEmpty() -> CenteredMessage("No songs match this length filter.\nTry widening the range.")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
        ) {
            items(songs, key = { it.id }) { song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == current?.id,
                    selecting = selecting,
                    selected = song.id in selectedIds,
                    arranging = arranging,
                    onClick = { if (selecting) onToggleSelected(song) else onPlay(song) },
                    onLongClick = { onToggleSelected(song) },
                    onMove = { how -> onMove(song, how) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: Song,
    isCurrent: Boolean,
    selecting: Boolean,
    selected: Boolean,
    arranging: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMove: (Move) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(enabled = !arranging, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = null)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = song.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (arranging) {
            // simple move buttons: no dragging, so it works the same on every phone
            listOf("▲" to Move.UP, "▼" to Move.DOWN, "Top" to Move.TOP).forEach { (label, how) ->
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { onMove(how) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        } else {
            Text(
                text = formatTime(song.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionPrompt(onAllow: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Prism needs access to the music on your phone.\nIt never uploads anything.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAllow) { Text("Allow access") }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "If nothing happens, enable it in Settings > Apps > Prism > Permissions.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -------------------------------------------------------------- mini player

@Composable
private fun MiniPlayer(state: PlayerUiState, onToggle: () -> Unit, onOpen: () -> Unit) {
    val song = state.currentSong
    val progress = if (song != null && state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outline,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = song != null, onClick = onOpen)
                    .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song?.title ?: "Nothing to play",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        color = if (song != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = song?.artist ?: "Pick a song to start",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // With nothing loaded, the button starts the first song of the list
                IconButton(onClick = onToggle, enabled = song != null || state.allSongs.isNotEmpty()) {
                    Icon(
                        painter = painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------- now playing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingSheet(state: PlayerUiState, vm: PlayerViewModel, onDismiss: () -> Unit) {
    val song = state.currentSong ?: return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(16.dp))
            SeekBar(state, onSeek = vm::seekTo)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::previous, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_previous), contentDescription = "Previous song")
                }
                Spacer(Modifier.width(16.dp))
                IconButton(
                    onClick = vm::togglePlayPause,
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        painter = painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = vm::next, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_next), contentDescription = "Next song")
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Sound", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            ControlSlider(
                label = "Speed",
                valueText = "%.2fx".format(state.speed),
                value = state.speed,
                range = 0.5f..2f,
                steps = 29, // 0.05 steps
                onChange = vm::setSpeed,
                onReset = { vm.setSpeed(1f) },
            )
            ControlSlider(
                label = "Pitch",
                valueText = "%+d semitones".format(state.pitchSemitones.roundToInt()),
                value = state.pitchSemitones,
                range = -12f..12f,
                steps = 23, // whole semitones
                onChange = vm::setPitch,
                onReset = { vm.setPitch(0f) },
            )
            Spacer(Modifier.height(20.dp))
            MarkerPanel(state = state, vm = vm)
            Spacer(Modifier.height(12.dp))
            CrossfadePanel(settings = state.crossfade, vm = vm)
            Spacer(Modifier.height(12.dp))
            ReverbPanel(params = state.reverb, vm = vm)
        }
    }
}

@Composable
internal fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val max = state.durationMs.coerceAtLeast(1L).toFloat()
    val position = if (dragging) dragValue else state.positionMs.toFloat()

    Column {
        Slider(
            value = position.coerceIn(0f, max),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek(dragValue.toLong())
                dragging = false
            },
            valueRange = 0f..max,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(
                formatTime(position.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatTime(state.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ControlSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(valueText, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReset) { Text("Reset") }
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

// ------------------------------------------------------------- theme picker

private val presetColors = listOf(
    "Blue" to Color(0xFF4C8DFF),
    "Purple" to Color(0xFF9B6BFF),
    "Green" to Color(0xFF3DDC84),
    "Teal" to Color(0xFF2EC4B6),
    "Pink" to Color(0xFFFF6BB5),
    "Red" to Color(0xFFFF5C5C),
    "Orange" to Color(0xFFFF9F43),
    "Yellow" to Color(0xFFFFD166),
)

@Composable
private fun ThemeDialog(accent: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    // Start the custom colour slider at the hue of the current accent.
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(accent, it) }
    var hue by remember { mutableFloatStateOf(hsv[0]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Accent colour") },
        text = {
            Column {
                presetColors.chunked(4).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        row.forEach { (_, color) ->
                            val selected = color.toArgb() == accent
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (selected) 3.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape,
                                    )
                                    .clickable { onPick(color.toArgb()) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Pick your own", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = hue,
                    onValueChange = {
                        hue = it
                        onPick(Color.hsv(it, 0.65f, 0.95f).toArgb())
                    },
                    valueRange = 0f..360f,
                )
            }
        },
    )
}
