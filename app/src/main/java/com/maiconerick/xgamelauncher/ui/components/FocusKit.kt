package com.maiconerick.xgamelauncher.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import com.maiconerick.xgamelauncher.ui.theme.Sprites
import com.maiconerick.xgamelauncher.ui.theme.drawSprite
import androidx.compose.ui.unit.dp
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Focus animation settings (Settings → Appearance): which effect, and its colours
 * (null = accent / white).
 */
data class FocusStyle(
    val base: Color? = null,
    val highlight: Color? = null,
    val kind: String = SNAKE,
) {
    companion object {
        const val SNAKE = "snake"
        val KINDS = listOf(
            SNAKE to "Snake — a bright segment orbits the border",
            "pulse" to "Pulse — the border breathes with a glow",
            "pixels" to "Pixel burst — pixels explode past the edges",
            "corners" to "Corners — animated corner brackets",
            "rainbow" to "Rainbow — colour-cycling border",
            "candle" to "Candlelight — a flickering pumpkin-to-witch glow",
            "batorbit" to "Bat orbit — two pixel bats circle the border",
            "slime" to "Slime — the border oozes and drips",
        )
    }
}

val LocalFocusStyle = staticCompositionLocalOf { FocusStyle() }

private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val size: Float)

/**
 * The ONE focus indicator for gamepad navigation, drawn only on the focused element in the
 * style chosen in Settings (snake by default). Low-power mode (RG353M) always gets a static
 * 2dp outline. "Selected" (current tab / section / swatch) is shown with accent text, an
 * accent bar or a check mark, never with this border. Place it before inner padding.
 */
@Composable
fun Modifier.focusRing(focused: Boolean, shape: Shape = RoundedCornerShape(6.dp)): Modifier {
    if (!focused) return this
    LaunchedEffect(Unit) { com.maiconerick.xgamelauncher.ui.NavSounds.move() }
    return this.focusEffect(LocalFocusStyle.current, shape, animate = com.maiconerick.xgamelauncher.ui.animationsOn())
}

/** Draws one focus style; also used by the Settings previews (always "focused"). */
@Composable
fun Modifier.focusEffect(style: FocusStyle, shape: Shape, animate: Boolean = true): Modifier {
    val base = style.base ?: LocalNeon.current.accent
    val shine = style.highlight ?: Color.White
    if (!animate) return this.drawWithContent {
        drawContent()
        outline(shape, 2.dp.toPx()) { path, w -> drawPath(path, base, style = Stroke(w)) }
    }
    return when (style.kind) {
        "pulse" -> pulse(shape, base, shine)
        "pixels" -> pixels(shape, base, shine)
        "corners" -> corners(base, shine)
        "rainbow" -> rainbow(shape)
        "candle" -> candle(shape, style.base, style.highlight)
        "batorbit" -> batOrbit(shape, base, shine)
        "slime" -> slime(shape, style.base)
        else -> snake(shape, base, shine)
    }
}

/** Builds the inset outline path and hands it to [block]. */
private fun DrawScope.outline(shape: Shape, stroke: Float, block: DrawScope.(Path, Float) -> Unit) {
    val inset = stroke / 2f
    val s = Size(size.width - inset * 2, size.height - inset * 2)
    if (s.width <= 0f || s.height <= 0f) return
    val path = Path().apply { addOutline(shape.createOutline(s, layoutDirection, this@outline)) }
    translate(inset, inset) { block(path, stroke) }
}

/** Animated 0..1 phase as a State — read it ONLY inside draw lambdas, so the animation
 *  redraws the border without recomposing the focused element every frame. */
@Composable
private fun phase(periodMs: Int): androidx.compose.runtime.State<Float> {
    val t = rememberInfiniteTransition(label = "focus-fx")
    return t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart), label = "focus-fx-phase")
}

@Composable
private fun Modifier.snake(shape: Shape, base: Color, shine: Color): Modifier {
    val phaseState = phase(1800)
    return drawWithContent {
        drawContent()
        val phase = phaseState.value
        outline(shape, 2.dp.toPx()) { path, stroke ->
            drawPath(path, base.copy(alpha = 0.45f), style = Stroke(stroke * 0.75f))
            val pm = PathMeasure().apply { setPath(path, false) }
            val len = pm.length
            if (len <= 0f) return@outline
            val segLen = len * 0.32f
            val start = phase * len
            val seg = Path()
            if (start + segLen <= len) pm.getSegment(start, start + segLen, seg, true)
            else {
                pm.getSegment(start, len, seg, true)
                val tail = Path(); pm.getSegment(0f, start + segLen - len, tail, true); seg.addPath(tail)
            }
            drawPath(seg, brush = Brush.linearGradient(listOf(base, shine, base)), style = Stroke(stroke * 1.4f))
        }
    }
}

@Composable
private fun Modifier.pulse(shape: Shape, base: Color, shine: Color): Modifier {
    val pState = phase(1400)
    return drawWithContent {
        drawContent()
        val a = (sin(pState.value * 2 * Math.PI).toFloat() + 1f) / 2f
        outline(shape, 2.dp.toPx()) { path, stroke ->
            drawPath(path, base.copy(alpha = 0.25f + 0.35f * a), style = Stroke(stroke * (2.5f + 2.5f * a)))
            drawPath(path, androidx.compose.ui.graphics.lerp(base, shine, a * 0.6f), style = Stroke(stroke))
        }
    }
}

@Composable
private fun Modifier.rainbow(shape: Shape): Modifier {
    val pState = phase(2400)
    return drawWithContent {
        drawContent()
        val p = pState.value
        outline(shape, 2.dp.toPx()) { path, stroke ->
            val colors = (0..6).map { Color.hsv(((p * 360f) + it * 60f) % 360f, 0.9f, 1f) }
            drawPath(path, brush = Brush.sweepGradient(colors, center = Offset(size.width / 2, size.height / 2)), style = Stroke(stroke * 1.3f))
        }
    }
}

@Composable
private fun Modifier.corners(base: Color, shine: Color): Modifier {
    val pState = phase(1200)
    return drawWithContent {
        drawContent()
        val breathe = (sin(pState.value * 2 * Math.PI).toFloat() + 1f) / 2f
        val stroke = 2.5.dp.toPx()
        val len = minOf(size.width, size.height) * (0.18f + 0.1f * breathe)
        val o = -3.dp.toPx() * breathe   // brackets float slightly outward
        val c = androidx.compose.ui.graphics.lerp(base, shine, breathe * 0.5f)
        val w = size.width; val h = size.height
        fun l(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, Offset(x1, y1), Offset(x2, y2), stroke)
        l(o, o, o + len, o); l(o, o, o, o + len)
        l(w - o, o, w - o - len, o); l(w - o, o, w - o, o + len)
        l(o, h - o, o + len, h - o); l(o, h - o, o, h - o - len)
        l(w - o, h - o, w - o - len, h - o); l(w - o, h - o, w - o, h - o - len)
    }
}

/** Constant pixel explosion: squares spawn on the border and fly outward, fading. */
@Composable
private fun Modifier.pixels(shape: Shape, base: Color, shine: Color): Modifier {
    val sparks = remember { ArrayList<Spark>() }
    val lastFrame = remember { LongArray(1) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { withFrameMillis { now = it }; kotlinx.coroutines.delay(28) } }  // ~30fps
    return drawWithContent {
        drawContent()
        val t = now
        val dt = if (lastFrame[0] == 0L) 16f else (t - lastFrame[0]).coerceIn(1, 50).toFloat()
        lastFrame[0] = t
        outline(shape, 2.dp.toPx()) { path, stroke -> drawPath(path, base.copy(alpha = 0.8f), style = Stroke(stroke)) }
        val w = size.width; val h = size.height
        val perim = 2 * (w + h)
        val px = 2.5.dp.toPx()
        repeat(3) {
            // random point on the perimeter, outward direction
            var d = Random.nextFloat() * perim
            val (x, y, nx, ny) = when {
                d < w -> listOf(d, 0f, 0f, -1f)
                d < w + h -> { d -= w; listOf(w, d, 1f, 0f) }
                d < 2 * w + h -> { d -= w + h; listOf(w - d, h, 0f, 1f) }
                else -> { d -= 2 * w + h; listOf(0f, h - d, -1f, 0f) }
            }
            val ang = Random.nextFloat() * 1.2f - 0.6f
            val sp = 0.04f + Random.nextFloat() * 0.08f
            val vx = (nx * cos(ang) - ny * sin(ang)) * sp
            val vy = (nx * sin(ang) + ny * cos(ang)) * sp
            sparks += Spark(x, y, vx, vy, 1f, px * (0.6f + Random.nextFloat()))
        }
        val it = sparks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.x += s.vx * dt * density; s.y += s.vy * dt * density
            s.life -= dt / 700f
            if (s.life <= 0f) { it.remove(); continue }
            val c = if (s.size > px) shine else base
            drawRect(c.copy(alpha = s.life), Offset(s.x - s.size / 2, s.y - s.size / 2), Size(s.size, s.size))
        }
        if (sparks.size > 240) sparks.subList(0, sparks.size - 240).clear()
    }
}

// ---------------- Halloween styles ----------------

private val PumpkinC = Color(0xFFFF7A18)
private val WitchC = Color(0xFF8B3DFF)
private val SlimeC = Color(0xFF7CFF4F)

/** Candle flicker: a pumpkin→witch gradient border slowly rotating, with a jittery glow. */
@Composable
private fun Modifier.candle(shape: Shape, baseOverride: Color?, shineOverride: Color?): Modifier {
    val a = baseOverride ?: PumpkinC
    val b = shineOverride ?: WitchC
    val pState = phase(3600)
    return drawWithContent {
        drawContent()
        val p = pState.value
        // Two out-of-phase sines make an irregular, candle-like flicker.
        val f = 0.55f + 0.25f * sin(p * 2 * Math.PI * 7).toFloat() + 0.2f * sin(p * 2 * Math.PI * 17 + 1.3).toFloat()
        val cx = size.width / 2; val cy = size.height / 2
        // Rotate only the gradient (not the outline) by giving the shader a local matrix.
        val shader = android.graphics.SweepGradient(cx, cy,
            intArrayOf(a.toArgb(), b.toArgb(), a.toArgb(), 0xFFFFD54A.toInt(), a.toArgb()), null,
        ).apply { setLocalMatrix(android.graphics.Matrix().apply { setRotate(p * 360f, cx, cy) }) }
        val sweep = androidx.compose.ui.graphics.ShaderBrush(shader)
        outline(shape, 2.dp.toPx()) { path, stroke ->
            drawPath(path, a.copy(alpha = 0.18f + 0.22f * f), style = Stroke(stroke * (3f + 2f * f)))
            drawPath(path, brush = sweep, style = Stroke(stroke * 1.2f))
        }
    }
}

/** Two pixel bats chase each other around the border over a dim outline. */
@Composable
private fun Modifier.batOrbit(shape: Shape, base: Color, shine: Color): Modifier {
    val pState = phase(3200)
    val flapState = phase(260)
    return drawWithContent {
        drawContent()
        val p = pState.value
        val flap = flapState.value < 0.5f
        outline(shape, 2.dp.toPx()) { path, stroke ->
            drawPath(path, base.copy(alpha = 0.55f), style = Stroke(stroke))
            val pm = PathMeasure().apply { setPath(path, false) }
            val len = pm.length
            if (len <= 0f) return@outline
            val bh = 9.dp.toPx()
            for (k in 0..1) {
                val d = ((p + k * 0.5f) % 1f) * len
                val pos = pm.getPosition(d)
                // short glowing trail behind each bat
                val trail = Path(); pm.getSegment((d - len * 0.08f).coerceAtLeast(0f), d, trail, true)
                drawPath(trail, shine.copy(alpha = 0.6f), style = Stroke(stroke * 1.4f))
                drawSprite(if (flap xor (k == 1)) Sprites.bat1 else Sprites.bat2,
                    Offset(pos.x - bh * 16f / 9f / 2f, pos.y - bh / 2f), bh)
            }
        }
    }
}

private class Drip(val x: Float, var len: Float, var y: Float, var falling: Boolean, val speed: Float)

/** Slime: a wobbling green border with drips that grow on the bottom edge and fall off. */
@Composable
private fun Modifier.slime(shape: Shape, baseOverride: Color?): Modifier {
    val c = baseOverride ?: SlimeC
    val drips = remember { ArrayList<Drip>() }
    val lastFrame = remember { LongArray(1) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { withFrameMillis { now = it }; kotlinx.coroutines.delay(28) } }
    return drawWithContent {
        drawContent()
        val t = now
        val dt = if (lastFrame[0] == 0L) 16f else (t - lastFrame[0]).coerceIn(1, 50).toFloat()
        lastFrame[0] = t
        val wob = 0.5f + 0.5f * sin(t / 300.0).toFloat()
        outline(shape, 2.dp.toPx()) { path, stroke ->
            drawPath(path, c.copy(alpha = 0.22f + 0.12f * wob), style = Stroke(stroke * 4f))
            drawPath(path, c, style = Stroke(stroke * (1.2f + 0.4f * wob)))
        }
        val w = size.width; val h = size.height
        val dw = 3.dp.toPx()
        if (drips.size < 4 && Random.nextFloat() < 0.04f) drips += Drip(dw + Random.nextFloat() * (w - dw * 2), 0f, h, false, 0.6f + Random.nextFloat())
        val it = drips.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (!d.falling) {
                d.len += dt * 0.012f * d.speed * density
                if (d.len > 9.dp.toPx()) d.falling = true
                drawRoundRect(c, Offset(d.x - dw / 2, h - 1f), Size(dw, d.len), androidx.compose.ui.geometry.CornerRadius(dw / 2))
            } else {
                d.y += dt * 0.18f * density
                drawCircle(c, radius = dw * 0.7f, center = Offset(d.x, d.y + d.len))
                if (d.y > h + 40.dp.toPx()) it.remove()
            }
        }
    }
}
