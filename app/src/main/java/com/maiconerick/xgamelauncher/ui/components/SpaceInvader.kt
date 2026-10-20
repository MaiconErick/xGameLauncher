package com.maiconerick.xgamelauncher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.delay

// Classic 11x8 "crab" invader, two animation frames.
private val FRAME_A = listOf(
    "..X.....X..",
    "...X...X...",
    "..XXXXXXX..",
    ".XX.XXX.XX.",
    "XXXXXXXXXXX",
    "X.XXXXXXX.X",
    "X.X.....X.X",
    "...XX.XX...",
)
private val FRAME_B = listOf(
    "..X.....X..",
    "X..X...X..X",
    "X.XXXXXXX.X",
    "XXX.XXX.XXX",
    "XXXXXXXXXXX",
    ".XXXXXXXXX.",
    "..X.....X..",
    ".X.......X.",
)

/** Animated pixel space invader — the app's logo mark (replaces the old "xGL" text).
 *  Flaps between two frames; static in low-power mode. */
@Composable
fun SpaceInvader(height: Dp = 18.dp, color: Color = LocalNeon.current.accent) {
    val animate = !LocalDeviceProfile.current.lowPower
    var frameB by remember { mutableStateOf(false) }
    if (animate) LaunchedEffect(Unit) {
        while (true) { delay(450); frameB = !frameB }
    }
    val rows = if (frameB) FRAME_B else FRAME_A
    Canvas(Modifier.size(width = height * (11f / 8f), height = height)) {
        val px = size.height / 8f
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == 'X') drawRect(color, Offset(x * px, y * px), Size(px + 0.5f, px + 0.5f))
            }
        }
    }
}
