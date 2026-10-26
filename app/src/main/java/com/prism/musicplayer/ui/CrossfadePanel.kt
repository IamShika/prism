package com.prism.musicplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.musicplayer.PlayerViewModel
import com.prism.musicplayer.audio.CrossfadeSettings
import com.prism.musicplayer.audio.FadeCurve
import kotlin.math.roundToInt

private val OutgoingColor = Color(0xFFFF6B6B)
private val IncomingColor = Color(0xFF4ECDC4)

/**
 * Crossfade settings: on/off, fade curve with a live graph, duration, and when to blend.
 * Tap "Edit" to open the details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrossfadePanel(settings: CrossfadeSettings, vm: PlayerViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            // ---- header: switch, title, short summary, edit button
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { on -> vm.updateCrossfade { it.copy(enabled = on) } },
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Crossfade", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (settings.enabled) "${settings.seconds} seconds · ${settings.curve.label}" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Close" else "Edit") }
            }

            if (expanded) {
                Column(Modifier.alpha(if (settings.enabled) 1f else 0.45f)) {
                    Spacer(Modifier.height(8.dp))

                    Text("Fade curve", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FadeCurve.values().forEach { curve ->
                            FilterChip(
                                selected = settings.curve == curve,
                                onClick = { vm.updateCrossfade { it.copy(curve = curve) } },
                                label = { Text(curve.label) },
                            )
                        }
                    }

                    FadeGraph(curve = settings.curve, seconds = settings.seconds)

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Crossfade duration", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (settings.seconds == 1) "1 second" else "${settings.seconds} seconds",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = settings.seconds.toFloat(),
                        onValueChange = { v -> vm.updateCrossfade { it.copy(seconds = v.roundToInt()) } },
                        valueRange = 1f..12f,
                        steps = 10,
                    )

                    SettingSwitch(
                        title = "Crossfade on manual skip",
                        description = "Blends when you skip to the next song or pick another one",
                        checked = settings.onManualSkip,
                        onChecked = { on -> vm.updateCrossfade { it.copy(onManualSkip = on) } },
                    )
                    SettingSwitch(
                        title = "Crossfade on auto-advance",
                        description = "Blends when a song ends by itself",
                        checked = settings.onAutoAdvance,
                        onChecked = { on -> vm.updateCrossfade { it.copy(onAutoAdvance = on) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** A small chart of how loud the old song (red) and the new song (green) are during the blend. */
@Composable
private fun FadeGraph(curve: FadeCurve, seconds: Int) {
    val gridColor = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labels = listOf("100%", "75%", "50%", "25%", "0%")

    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(OutgoingColor)
                Text("  Outgoing", fontSize = 11.sp, color = textColor)
                Spacer(Modifier.width(16.dp))
                LegendDot(IncomingColor)
                Text("  Incoming", fontSize = 11.sp, color = textColor)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(120.dp)) {
                // percent labels; their centres line up with the grid lines drawn below
                Column(
                    modifier = Modifier.fillMaxHeight().width(34.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    labels.forEach { label ->
                        Text(label, fontSize = 10.sp, lineHeight = 16.sp, color = textColor, modifier = Modifier.height(16.dp))
                    }
                }
                Canvas(Modifier.weight(1f).fillMaxHeight()) {
                    val pad = 8.dp.toPx()
                    val plotHeight = size.height - 2 * pad

                    for (i in 0..4) {
                        val y = pad + plotHeight * i / 4f
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    }

                    fun drawCurve(level: (Float) -> Float, color: Color) {
                        val path = Path()
                        val steps = 60
                        for (k in 0..steps) {
                            val t = k / steps.toFloat()
                            val x = size.width * t
                            val y = pad + plotHeight * (1f - level(t))
                            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                    }
                    drawCurve(curve::outgoing, OutgoingColor)
                    drawCurve(curve::incoming, IncomingColor)
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 34.dp, top = 4.dp)) {
                Text("0s", fontSize = 10.sp, color = textColor)
                Spacer(Modifier.weight(1f))
                Text("${seconds}s", fontSize = 10.sp, color = textColor)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}
