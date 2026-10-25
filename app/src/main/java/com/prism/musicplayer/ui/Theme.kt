package com.prism.musicplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

private val Surface = Color(0xFF14141C)

/** The whole app is dark. Only the accent colour changes, and the user picks it. */
@Composable
fun PrismTheme(accent: Color, content: @Composable () -> Unit) {
    // Light accents (yellow...) need dark text on top, dark accents need white text.
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White

    val colors = darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = lerp(Surface, accent, 0.30f),
        onPrimaryContainer = Color.White,
        secondary = accent,
        background = Color(0xFF0E0E14),
        onBackground = Color(0xFFECECF4),
        surface = Surface,
        onSurface = Color(0xFFECECF4),
        surfaceVariant = Color(0xFF1E1E2A),
        onSurfaceVariant = Color(0xFFA9A9BC),
        outline = Color(0xFF3A3A4C),
    )

    MaterialTheme(colorScheme = colors, content = content)
}
