package com.maiconerick.xgamelauncher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class NeonPalette(
    val accent: Color,
    val accentSoft: Color,
    val accentGlow: Color,
    val ink: Color,
    val inkDim: Color,
    val inkMuted: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val stroke: Color,
) {
    companion object {
        val Cyan = NeonPalette(
            accent = Color(0xFF00E5FF),
            accentSoft = Color(0xFF00B8CC),
            accentGlow = Color(0x6600E5FF),
            ink = Color(0xFFF2F5F7),
            inkDim = Color(0xFFB6C0C8),
            inkMuted = Color(0xFF6E7A85),
            surface = Color(0xFF000000),
            surfaceElevated = Color(0xFF0B0F12),
            stroke = Color(0xFF1C2731),
        )
    }
}

val LocalNeon = staticCompositionLocalOf { NeonPalette.Cyan }

@Composable
fun XGameLauncherTheme(
    palette: NeonPalette = NeonPalette.Cyan,
    content: @Composable () -> Unit,
) {
    val scheme = darkColorScheme(
        primary = palette.accent,
        onPrimary = palette.surface,
        secondary = palette.accentSoft,
        background = palette.surface,
        onBackground = palette.ink,
        surface = palette.surface,
        onSurface = palette.ink,
        surfaceVariant = palette.surfaceElevated,
        onSurfaceVariant = palette.inkDim,
        outline = palette.stroke,
    )
    CompositionLocalProvider(LocalNeon provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
