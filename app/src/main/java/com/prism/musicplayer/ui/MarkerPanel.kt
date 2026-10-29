package com.prism.musicplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prism.musicplayer.PlayerUiState
import com.prism.musicplayer.PlayerViewModel
import com.prism.musicplayer.data.TrackMarker

/** "1:05.3" - minutes, seconds and tenths, because markers are set to a tenth of a second. */
private fun formatMarker(ms: Long): String {
    val tenths = (ms / 100) % 10
    val totalSec = ms / 1000
    return "%d:%02d.%d".format(totalSec / 60, totalSec % 60, tenths)
}

/**
 * Start and end markers for the current song: skip the silence before the music starts
 * or after it ends. Saved for each song, so it only has to be set once.
 */
@Composable
fun MarkerPanel(state: PlayerUiState, vm: PlayerViewModel) {
    val song = state.currentSong ?: return
    if (song.id < 0 || state.fullDurationMs <= 0L) return // a file opened from another app: nothing to save it under

    var expanded by rememberSaveable { mutableStateOf(false) }
    val markers = state.markers
    val full = state.fullDurationMs

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("Start and end markers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (markers.isSet) {
                            "Starts at ${formatMarker(markers.startMs)} · ends at " +
                                if (markers.endMs > 0) formatMarker(markers.endMs) else "the end"
                        } else {
                            "Not set. The whole song plays."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Close" else "Edit") }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Timeline(markers = markers, full = full, playheadMs = state.clipStartMs + state.positionMs)

                Spacer(Modifier.height(12.dp))
                MarkerRow(
                    label = "Start",
                    time = formatMarker(markers.startMs),
                    setLabel = "Set start here",
                    onSetHere = vm::setStartHere,
                    onNudge = vm::nudgeStart,
                )
                Spacer(Modifier.height(10.dp))
                MarkerRow(
                    label = "End",
                    time = formatMarker(markers.effectiveEnd(full)),
                    setLabel = "Set end here",
                    onSetHere = vm::setEndHere,
                    onNudge = vm::nudgeEnd,
                )

                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = vm::previewEnd, enabled = markers.endMs > 0) { Text("Hear the end") }
                    TextButton(onClick = vm::clearMarkers, enabled = markers.isSet) { Text("Reset") }
                }
                Text(
                    text = "Play the song, then tap \"Set start here\" the moment the music begins. " +
                        "Use the small buttons to fine-tune. It's saved for this song automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The whole song as a bar: the part that plays is bright, the skipped parts are dim, the line is where you are now. */
@Composable
private fun Timeline(markers: TrackMarker, full: Long, playheadMs: Long) {
    val bright = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    val line = MaterialTheme.colorScheme.onSurface

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
    ) {
        fun x(ms: Long) = size.width * (ms.toFloat() / full).coerceIn(0f, 1f)

        drawRect(dim.copy(alpha = 0.35f), Offset.Zero, size)
        val start = x(markers.startMs)
        val end = x(markers.effectiveEnd(full))
        drawRect(bright.copy(alpha = 0.55f), Offset(start, 0f), Size((end - start).coerceAtLeast(0f), size.height))
        drawRect(bright, Offset(start - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), size.height))
        drawRect(bright, Offset(end - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), size.height))
        drawRect(line, Offset(x(playheadMs) - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
    }
}

@Composable
private fun MarkerRow(
    label: String,
    time: String,
    setLabel: String,
    onSetHere: () -> Unit,
    onNudge: (Long) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(time, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            Button(onClick = onSetHere, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(setLabel) }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("−1s" to -1000L, "−0.1s" to -100L, "+0.1s" to 100L, "+1s" to 1000L).forEach { (text, delta) ->
                OutlinedButton(
                    onClick = { onNudge(delta) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) { Text(text) }
            }
        }
    }
}
