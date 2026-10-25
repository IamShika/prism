package com.prism.musicplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.prism.musicplayer.PlayerViewModel
import com.prism.musicplayer.audio.ParamSpec
import com.prism.musicplayer.audio.ReverbParams
import com.prism.musicplayer.audio.ReverbPresets
import com.prism.musicplayer.audio.ReverbSpecs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The reverb control panel: on/off switch, preset menu and two tabs of knobs and sliders.
 * Drag a knob up or down to change it. Double-tap any control to put it back to its default.
 */
@Composable
fun ReverbPanel(params: ReverbParams, vm: PlayerViewModel) {
    val defaults = ReverbPresets.default
    var tab by rememberSaveable { mutableStateOf(0) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            // ---- header: switch, title, presets
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = params.enabled,
                    onCheckedChange = { on -> vm.updateReverb { it.copy(enabled = on) } },
                )
                Spacer(Modifier.width(12.dp))
                Text("Reverb", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                PresetMenu(current = ReverbPresets.nameOf(params), onPick = vm::setReverbPreset)
            }

            Column(Modifier.alpha(if (params.enabled) 1f else 0.45f)) {
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("MAIN") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("TUNE") })
                }
                Spacer(Modifier.height(14.dp))

                if (tab == 0) MainTab(params, defaults, vm) else TuneTab(params, defaults, vm)
            }
        }
    }
}

// ----------------------------------------------------------------------- tabs

@Composable
private fun MainTab(p: ReverbParams, d: ReverbParams, vm: PlayerViewModel) {
    // Round knobs
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ParamKnob("Size", ReverbSpecs.size, p.size, d.size, ::percent) { v -> vm.updateReverb { it.copy(size = v) } }
        ParamKnob("Decay", ReverbSpecs.decay, p.decaySec, d.decaySec, ::seconds) { v -> vm.updateReverb { it.copy(decaySec = v) } }
        ParamKnob("Diffusion", ReverbSpecs.diffusion, p.diffusion, d.diffusion, ::percent) { v -> vm.updateReverb { it.copy(diffusion = v) } }
        ParamKnob("Damping", ReverbSpecs.damping, p.dampingHz, d.dampingHz, ::hertz) { v -> vm.updateReverb { it.copy(dampingHz = v) } }
    }
    Spacer(Modifier.height(16.dp))
    // Vertical sliders
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ParamSlider("High Cut", ReverbSpecs.highCut, p.highCutHz, d.highCutHz, ::hertz) { v -> vm.updateReverb { it.copy(highCutHz = v) } }
        ParamSlider("Low Cut", ReverbSpecs.lowCut, p.lowCutHz, d.lowCutHz, ::hertz) { v -> vm.updateReverb { it.copy(lowCutHz = v) } }
        ParamSlider("Dry", ReverbSpecs.dry, p.dry, d.dry, ::percent) { v -> vm.updateReverb { it.copy(dry = v) } }
        ParamSlider("ER", ReverbSpecs.early, p.earlyLevel, d.earlyLevel, ::percent) { v -> vm.updateReverb { it.copy(earlyLevel = v) } }
        ParamSlider("Wet", ReverbSpecs.wet, p.wet, d.wet, ::percent) { v -> vm.updateReverb { it.copy(wet = v) } }
    }
}

@Composable
private fun TuneTab(p: ReverbParams, d: ReverbParams, vm: PlayerViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ParamSlider("Delay", ReverbSpecs.preDelay, p.preDelayMs, d.preDelayMs, ::millis) { v -> vm.updateReverb { it.copy(preDelayMs = v) } }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ParamKnob("Mod", ReverbSpecs.mod, p.mod, d.mod, ::percent) { v -> vm.updateReverb { it.copy(mod = v) } }
                ParamKnob("Speed", ReverbSpecs.modSpeed, p.modSpeed, d.modSpeed, ::percent) { v -> vm.updateReverb { it.copy(modSpeed = v) } }
                ParamKnob("Bass", ReverbSpecs.bass, p.bass, d.bass, ::ratio) { v -> vm.updateReverb { it.copy(bass = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ParamKnob("Cross", ReverbSpecs.cross, p.crossHz, d.crossHz, ::hertz) { v -> vm.updateReverb { it.copy(crossHz = v) } }
                ParamKnob("Separation", ReverbSpecs.separation, p.separation, d.separation, ::percent) { v -> vm.updateReverb { it.copy(separation = v) } }
                ParamKnob("Pan", ReverbSpecs.pan, p.pan, d.pan, ::panText) { v -> vm.updateReverb { it.copy(pan = v) } }
            }
        }
    }
}

@Composable
private fun PresetMenu(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$current  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReverbPresets.all.forEach { (name, _) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onPick(name)
                        open = false
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------ value formatting

private fun percent(v: Float) = "${(v * 100).roundToInt()}%"
private fun seconds(v: Float) = "%.1fs".format(v)
private fun millis(v: Float) = "${v.roundToInt()}ms"
private fun ratio(v: Float) = "%.1f".format(v)
private fun hertz(v: Float) = if (v >= 1000f) "%.1fkHz".format(v / 1000f) else "${v.roundToInt()}Hz"
private fun panText(v: Float): String = when {
    abs(v) < 0.02f -> "C"
    v < 0f -> "L${(-v * 100).roundToInt()}"
    else -> "R${(v * 100).roundToInt()}"
}

// ------------------------------------------------------- controls tied to a setting

@Composable
private fun ParamKnob(
    label: String,
    spec: ParamSpec,
    value: Float,
    default: Float,
    format: (Float) -> String,
    onValue: (Float) -> Unit,
) {
    Knob(
        label = label,
        valueText = format(value),
        norm = spec.toNorm(value),
        defaultNorm = spec.toNorm(default),
        onNorm = { n -> onValue(spec.fromNorm(n)) },
    )
}

@Composable
private fun ParamSlider(
    label: String,
    spec: ParamSpec,
    value: Float,
    default: Float,
    format: (Float) -> String,
    onValue: (Float) -> Unit,
) {
    VerticalSlider(
        label = label,
        valueText = format(value),
        norm = spec.toNorm(value),
        defaultNorm = spec.toNorm(default),
        onNorm = { n -> onValue(spec.fromNorm(n)) },
    )
}

// ------------------------------------------------------------------------ knob

@Composable
private fun Knob(
    label: String,
    valueText: String,
    norm: Float,
    defaultNorm: Float,
    onNorm: (Float) -> Unit,
) {
    val currentNorm by rememberUpdatedState(norm)
    val currentDefault by rememberUpdatedState(defaultNorm)
    val currentOnNorm by rememberUpdatedState(onNorm)
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(66.dp)) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .pointerInput(Unit) {
                    var value = 0f
                    detectDragGestures(
                        onDragStart = { value = currentNorm },
                    ) { change, dragAmount ->
                        change.consume()
                        // dragging up turns the knob up; a full turn is about 220 dp of dragging
                        value = (value - dragAmount.y / 220.dp.toPx()).coerceIn(0f, 1f)
                        currentOnNorm(value)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { currentOnNorm(currentDefault) })
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 6.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2f, stroke / 2f)
                val startAngle = 135f
                val sweep = 270f
                drawArc(
                    color = track, startAngle = startAngle, sweepAngle = sweep, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = accent, startAngle = startAngle, sweepAngle = sweep * norm.coerceIn(0f, 1f),
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                // little white dot at the current position
                val angle = Math.toRadians((startAngle + sweep * norm.coerceIn(0f, 1f)).toDouble())
                val radius = arcSize.width / 2f
                drawCircle(
                    color = Color.White,
                    radius = stroke * 0.35f,
                    center = Offset(
                        center.x + radius * cos(angle).toFloat(),
                        center.y + radius * sin(angle).toFloat(),
                    ),
                )
            }
            Text(valueText, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

// --------------------------------------------------------------- vertical slider

@Composable
private fun VerticalSlider(
    label: String,
    valueText: String,
    norm: Float,
    defaultNorm: Float,
    onNorm: (Float) -> Unit,
) {
    val currentDefault by rememberUpdatedState(defaultNorm)
    val currentOnNorm by rememberUpdatedState(onNorm)
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(130.dp)
                // touching or dragging anywhere on the slider puts the handle at your finger
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            currentOnNorm(1f - (offset.y / size.height).coerceIn(0f, 1f))
                        },
                    ) { change, _ ->
                        change.consume()
                        currentOnNorm(1f - (change.position.y / size.height).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset -> currentOnNorm(1f - (offset.y / size.height).coerceIn(0f, 1f)) },
                        onDoubleTap = { currentOnNorm(currentDefault) },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val trackWidth = 8.dp.toPx()
                val handleRadius = 11.dp.toPx()
                val x = size.width / 2f
                // keep the handle fully inside the box at both ends
                val top = handleRadius
                val bottom = size.height - handleRadius
                val handleY = bottom - (bottom - top) * norm.coerceIn(0f, 1f)

                drawRoundRect(
                    color = track,
                    topLeft = Offset(x - trackWidth / 2f, top),
                    size = Size(trackWidth, bottom - top),
                    cornerRadius = CornerRadius(trackWidth / 2f),
                )
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(x - trackWidth / 2f, handleY),
                    size = Size(trackWidth, bottom - handleY),
                    cornerRadius = CornerRadius(trackWidth / 2f),
                )
                drawCircle(color = accent, radius = handleRadius, center = Offset(x, handleY))
                drawCircle(color = Color.White, radius = handleRadius * 0.35f, center = Offset(x, handleY))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(valueText, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
