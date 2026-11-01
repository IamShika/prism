package com.prism.musicplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prism.musicplayer.PlayerUiState
import com.prism.musicplayer.PlayerViewModel
import com.prism.musicplayer.audio.PlayheadSmoother
import com.prism.musicplayer.audio.WaveformMath
import kotlin.math.max

/** How much of the song fits on screen in the zoomed view. */
private const val ZOOM_WINDOW_MS = 10_000L

/** The zoomed view is drawn in columns of this length (a multiple of the waveform's 20 ms slices, so the picture stays steady while it scrolls). */
private const val ZOOM_COLUMN_MS = 40L

/**
 * The song as a picture of its sound. Tap or drag to jump around: you hear the sound as you drag, even
 * when the song is paused. "Zoom" shows 10 seconds that scroll under a fixed line; drag it like a record.
 * The two buttons rewind and fast-forward while they are held.
 */
@Composable
fun WaveformPanel(state: PlayerUiState, vm: PlayerViewModel) {
    val song = state.currentSong ?: return
    if (song.id < 0 || state.fullDurationMs <= 0L) return // a file opened from another app

    LaunchedEffect(song.id, state.fullDurationMs) { vm.ensureWaveform() }
    val wave = state.waveform?.takeIf { state.waveformSongId == song.id }

    var zoom by rememberSaveable { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableLongStateOf(0L) }
    var live by remember { mutableLongStateOf(0L) }

    // the scratch sound is made ready once the waveform is done (both read the whole song, one after the other)
    LaunchedEffect(song.id, state.waveformSongId, state.waveformBusy) {
        if (state.waveformSongId == song.id && !state.waveformBusy) vm.ensureScratch()
    }

    // while the zoomed view is moving it is redrawn on every screen frame; the smoother evens out
    // the player's uneven position reports so the picture glides
    val smoother = remember { PlayheadSmoother() }
    val speedNow by rememberUpdatedState(state.speed.toDouble())
    LaunchedEffect(zoom, state.isPlaying, state.scratching, song.id) {
        smoother.reset()
        live = vm.livePositionMs()
        if (zoom && (state.isPlaying || state.scratching)) {
            while (true) {
                val now = withFrameNanos { it }
                val reported = vm.livePositionMs()
                live = if (state.scratching) reported else smoother.update(reported, now, speedNow).toLong()
            }
        }
    }

    val clipStart = state.clipStartMs
    val clipEnd = state.clipStartMs + state.durationMs
    val shown = when {
        scrubbing -> scrubPos
        zoom && (state.isPlaying || state.scratching) -> live
        else -> clipStart + state.positionMs
    }
    val shownNow by rememberUpdatedState(shown)

    val played = MaterialTheme.colorScheme.primary
    val upcoming = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val dim = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val line = MaterialTheme.colorScheme.onSurface

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Waveform",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                TextButton(onClick = { zoom = !zoom }, enabled = wave != null) {
                    Text(if (zoom) "Whole song" else "Zoom")
                }
            }

            if (wave == null) {
                Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                    if (state.waveformBusy) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Drawing the waveform…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { state.waveformProgress }, modifier = Modifier.fillMaxWidth(0.6f))
                        }
                    } else {
                        Text("Couldn't draw the waveform for this song.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                val full = wave.durationMs

                if (!zoom) {
                    // ---- the whole song: tap to jump, drag to scrub
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .pointerInput(wave) {
                                detectTapGestures { o -> vm.seekAbsolute(WaveformMath.timeAt(o.x, size.width.toFloat(), full)) }
                            }
                            .pointerInput(wave) {
                                detectHorizontalDragGestures(
                                    onDragStart = { o ->
                                        scrubbing = true
                                        vm.beginScrub()
                                        scrubPos = WaveformMath.timeAt(o.x, size.width.toFloat(), full)
                                        vm.scrubTo(scrubPos)
                                    },
                                    onDragEnd = { scrubbing = false; vm.endScrub() },
                                    onDragCancel = { scrubbing = false; vm.endScrub() },
                                    onHorizontalDrag = { change, _ ->
                                        change.consume()
                                        scrubPos = WaveformMath.timeAt(change.position.x, size.width.toFloat(), full)
                                        vm.scrubTo(scrubPos)
                                    },
                                )
                            }
                    ) {
                        val count = max((size.width / 3.dp.toPx()).toInt(), 1)
                        val cols = WaveformMath.columns(wave, 0L, full, count)
                        drawColumns(cols, count, count, 0f, { c -> full * c / count }, shown, clipStart, clipEnd, played, upcoming, dim)
                        val x = size.width * (shown.toFloat() / full).coerceIn(0f, 1f)
                        drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                    }
                } else {
                    // ---- zoomed: 10 seconds scrolling under a fixed line; drag like a record
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .pointerInput(wave) {
                                detectTapGestures { o ->
                                    val offset = (o.x - size.width / 2f) * ZOOM_WINDOW_MS / size.width
                                    vm.seekAbsolute(shownNow + offset.toLong())
                                }
                            }
                            .pointerInput(wave) {
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        scrubbing = true
                                        scrubPos = shownNow
                                        vm.beginScrub()
                                    },
                                    onDragEnd = { scrubbing = false; vm.endScrub() },
                                    onDragCancel = { scrubbing = false; vm.endScrub() },
                                    onHorizontalDrag = { change, dx ->
                                        change.consume()
                                        scrubPos = WaveformMath
                                            .timeAfterDrag(scrubPos, dx, size.width.toFloat(), ZOOM_WINDOW_MS)
                                            .coerceIn(0L, full)
                                        vm.scrubTo(scrubPos)
                                    },
                                )
                            }
                    ) {
                        // columns sit on a fixed grid in song time and slide sideways, so the picture glides instead of shimmering
                        val grid = WaveformMath.gridStart(shown - ZOOM_WINDOW_MS / 2, ZOOM_COLUMN_MS)
                        val visible = (ZOOM_WINDOW_MS / ZOOM_COLUMN_MS).toInt()
                        val from = grid.firstColumn * ZOOM_COLUMN_MS
                        val cols = WaveformMath.columns(wave, from, from + (visible + 1) * ZOOM_COLUMN_MS, visible + 1)
                        drawColumns(cols, visible + 1, visible, grid.fraction, { c -> from + c * ZOOM_COLUMN_MS }, shown, clipStart, clipEnd, played, upcoming, dim)
                        val x = size.width / 2f
                        drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldButton("◀◀  Rewind", Modifier.weight(1f), onPress = { vm.startSeekHold(-1) }, onRelease = vm::stopSeekHold)
                HoldButton("Forward  ▶▶", Modifier.weight(1f), onPress = { vm.startSeekHold(1) }, onRelease = vm::stopSeekHold)
            }
            Text(
                text = when {
                    state.scratchReady && state.scratchSongId == song.id ->
                        "Drag the waveform like a record: forwards or backwards, you hear it. Hold a button to rewind or fast-forward."
                    state.scratchBusy && state.scratchSongId == song.id ->
                        "Getting the scratch sound ready (first time for this song only). Until then, scrubbing uses the normal player."
                    else -> "Drag the waveform to scrub. Hold a button to rewind or fast-forward."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
    }
}

/** Draws the vertical lines of the waveform. Played part bright, coming part grey, trimmed-away parts faint. */
private fun DrawScope.drawColumns(
    columns: FloatArray,
    count: Int,          // columns to draw
    visible: Int,        // how many fit across the width (a scrolling view draws one extra, partly off the edge)
    shift: Float,        // how far the view is scrolled into the first column, 0 to 1
    timeOf: (Int) -> Long,
    shown: Long,
    clipStart: Long,
    clipEnd: Long,
    played: Color,
    upcoming: Color,
    dim: Color,
) {
    val middle = size.height / 2f
    val half = size.height / 2f - 3.dp.toPx()
    val step = size.width / visible
    val stroke = max(step * 0.7f, 1f)
    for (c in 0 until count) {
        val t = timeOf(c)
        val color = when {
            t < clipStart || t > clipEnd -> dim
            t <= shown -> played
            else -> upcoming
        }
        var top = middle - columns[c * 2 + 1] * half
        var bottom = middle - columns[c * 2] * half
        if (bottom - top < 2f) { // silence still shows as a thin line
            top = middle - 1f
            bottom = middle + 1f
        }
        val x = (c + 0.5f - shift) * step
        drawLine(color, Offset(x, top), Offset(x, bottom), strokeWidth = stroke)
    }
}

/** A button that does something for as long as the finger is on it. */
@Composable
private fun HoldButton(label: String, modifier: Modifier, onPress: () -> Unit, onRelease: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(50),
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    onPress()
                    try {
                        tryAwaitRelease()
                    } finally {
                        onRelease()
                    }
                }
            )
        },
    ) {
        Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
    }
}
