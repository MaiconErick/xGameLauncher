package com.maiconerick.xgamelauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue

/**
 * Deterministic gradient + title placeholder used when a game has no art yet. Same shape as
 * the design canvas tiles: two-color diagonal gradient keyed off the game name, dark bottom
 * scrim, title bottom-aligned.
 */
@Composable
fun PlaceholderArt(
    title: String,
    modifier: Modifier = Modifier,
    labelSize: Int = 14,
) {
    val (c1, c2) = colorPair(title)
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    0f to c1,
                    0.55f to c2,
                    1f to c1.copy(alpha = 0.5f),
                    start = Offset(0f, 0f),
                    end = Offset(400f, 600f),
                )
            )
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x22000000),
                    0.4f to Color.Transparent,
                    1f to Color(0xCC000000),
                )
            )
        )
        Text(
            text = title,
            color = Color.White,
            fontSize = labelSize.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        )
    }
}

private fun colorPair(seed: String): Pair<Color, Color> {
    // Deterministic hue from name; two harmonic stops so different games stay distinguishable.
    val h = seed.fold(0) { acc, c -> acc * 31 + c.code }.absoluteValue
    val palette = arrayOf(
        Color(0xFF1A3A5F) to Color(0xFF7DB4EA), // ocean
        Color(0xFF2D1A05) to Color(0xFFFF6B1A), // ember
        Color(0xFF4A1A2F) to Color(0xFFFF8FB3), // rose
        Color(0xFF0F3D1F) to Color(0xFF4ADE80), // moss
        Color(0xFF1A1206) to Color(0xFFD4B06A), // gold
        Color(0xFF201414) to Color(0xFFB06060), // rust
        Color(0xFF0A0A2E) to Color(0xFF4C9EFF), // midnight
        Color(0xFF2A1030) to Color(0xFFB060D4), // amethyst
        Color(0xFF2A3818) to Color(0xFFB8C96A), // olive
        Color(0xFF1A1A24) to Color(0xFFA0A3BC), // slate
    )
    return palette[h % palette.size]
}
