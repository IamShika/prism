package com.prism.musicplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prism.musicplayer.PlayerUiState
import com.prism.musicplayer.PlayerViewModel

/** "128" for whole numbers, "128.5" otherwise. */
fun formatBpm(bpm: Float): String =
    if (bpm == bpm.toInt().toFloat()) bpm.toInt().toString() else "%.1f".format(bpm)

/**
 * The tempo of the current song. Detect it automatically, or tap along to the beat.
 * The result is saved for the song.
 */
@Composable
fun BpmPanel(state: PlayerUiState, vm: PlayerViewModel) {
    val song = state.currentSong ?: return
    if (song.id < 0) return // a file opened from another app: nothing to save the tempo under

    val bpm = state.bpms[song.id]
    val check = state.bpmCheck?.takeIf { it.songId == song.id }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tempo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (bpm != null) {
                        Text(
                            text = "${formatBpm(bpm)} BPM",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        // the tempo you hear is faster or slower when the speed slider is moved
                        if (kotlin.math.abs(state.speed - 1f) > 0.01f) {
                            Text(
                                text = "${formatBpm(bpm * state.speed)} BPM at ${"%.2f".format(state.speed)}x speed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = "Not measured yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(onClick = vm::detectBpm, enabled = !state.bpmBusy) {
                    Text(if (bpm == null) "Detect" else "Detect again")
                }
            }

            if (state.bpmBusy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { state.bpmProgress }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Listening to the song…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (check != null && check.confidence < 0.45f && bpm != null) {
                Text(
                    text = "Not very sure about this one. If it sounds wrong, try ÷2, ×2, or tap along.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val small = PaddingValues(horizontal = 4.dp)
                OutlinedButton(onClick = vm::halveBpm, enabled = bpm != null, modifier = Modifier.weight(1f), contentPadding = small) { Text("÷2") }
                OutlinedButton(onClick = vm::doubleBpm, enabled = bpm != null, modifier = Modifier.weight(1f), contentPadding = small) { Text("×2") }
                OutlinedButton(onClick = vm::tapBpm, modifier = Modifier.weight(1f), contentPadding = small) { Text("Tap") }
                OutlinedButton(onClick = vm::clearBpm, enabled = bpm != null, modifier = Modifier.weight(1f), contentPadding = small) { Text("Clear") }
            }
            Text(
                text = "Tap along to the beat with \"Tap\" and the tempo follows your taps. Saved for this song.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
