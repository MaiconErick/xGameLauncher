package com.maiconerick.xgamelauncher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/**
 * Drifting neon starfield — ported straight from xPTT's `StarfieldBackground`. 90 soft-glow
 * dots slowly float in random directions, wrap at edges, and pulse in size. Colored with
 * the caller's `color` (falls back to the theme accent), which HomeScreen animates as the
 * user L/R-cycles platforms so the whole hub gently shifts palette.
 */
private data class Star(
    var x: Float, var y: Float,
    val vx: Float, val vy: Float,
    val baseR: Float,
    val hue: Color,
    val phase: Float,
)

private const val FRAME_MS = 33L  // ~30fps — full-screen animated bg, no need to burn cycles

@Composable
fun AnimatedDotsBackground(
    modifier: Modifier = Modifier,
    color: Color? = null,
    enabled: Boolean = true,
    /** false = low-power mode: stars are drawn once and never move, so the canvas never
     *  invalidates (no 30fps full-screen redraw behind every screen). */
    animate: Boolean = true,
) {
    val neon = LocalNeon.current
    val dotColor = color ?: neon.accent
    val stars = remember(dotColor) { mutableListOf<Star>() }
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    var step by remember { mutableStateOf(2f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, enabled, animate) {
        if (!enabled || !animate) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = System.currentTimeMillis()
            while (true) {
                delay(FRAME_MS)
                val now = System.currentTimeMillis()
                step = ((now - last) / 16.67f).coerceIn(0.5f, 6f)
                last = now
                tick = now
            }
        }
    }
    Box(modifier = modifier.background(Color.Black)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (!enabled) return@Canvas
            if (stars.isEmpty()) {
                val palette = listOf(dotColor, dotColor, dotColor.copy(alpha = 0.6f), Color.White)
                repeat(90) {
                    stars += Star(
                        x = Random.nextFloat() * size.width,
                        y = Random.nextFloat() * size.height,
                        vx = (Random.nextFloat() - 0.5f) * 0.9f,
                        vy = (Random.nextFloat() - 0.5f) * 0.9f,
                        baseR = 0.8f + Random.nextFloat() * 2.2f,
                        hue = palette.random(),
                        phase = Random.nextFloat() * 6.283f,
                    )
                }
            }
            tick.let { }
            val t = tick / 1000f
            stars.forEach { s ->
                if (animate) {
                    s.x += s.vx * step
                    s.y += s.vy * step
                }
                if (s.x < 0f) s.x += size.width else if (s.x > size.width) s.x -= size.width
                if (s.y < 0f) s.y += size.height else if (s.y > size.height) s.y -= size.height
                val pulse = 0.6f + 0.4f * ((sin(t * 1.8f + s.phase) + 1f) * 0.5f)
                val r = s.baseR * (0.6f + 0.4f * pulse)
                // soft glow: big dim halo + bright core
                drawCircle(
                    color = s.hue.copy(alpha = 0.10f * pulse),
                    radius = r * 4f,
                    center = Offset(s.x, s.y),
                )
                drawCircle(
                    color = s.hue.copy(alpha = 0.9f * pulse),
                    radius = r,
                    center = Offset(s.x, s.y),
                )
            }
        }
    }
}
