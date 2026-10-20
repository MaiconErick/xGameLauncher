package com.maiconerick.xgamelauncher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlin.random.Random
import com.maiconerick.xgamelauncher.ui.theme.Sprites
import com.maiconerick.xgamelauncher.ui.theme.drawSprite

/** Animated background for the top/bottom bars (Settings → Appearance). */
data class BarFxStyle(
    val kind: String = NONE,
    /** 0..1 → 0.25x .. 3x speed. */
    val speed: Float = 0.35f,
    /** 0..1 → particle count / density. */
    val amount: Float = 0.5f,
) {
    val speedMul: Float get() = 0.25f + speed * 2.75f

    companion object {
        const val NONE = "none"
        val KINDS = listOf(
            NONE to "Off",
            "pixels" to "Falling pixels",
            "katakana" to "Katakana rain",
            "stars" to "Starfield",
            "sparkles" to "Sparkles",
            "scanline" to "Scanline",
            "bats" to "Bats",
            "embers" to "Embers",
            "fog" to "Ghostly fog",
        )
    }
}

val LocalBarFx = staticCompositionLocalOf { BarFxStyle() }

private class P(var x: Float, var y: Float, var v: Float, var life: Float, var ch: Char = ' ', var size: Float = 1f)

private const val KATAKANA = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン"

/**
 * Draws the chosen bar effect over whatever is behind it (place it as the first child of a
 * bar's Box). Runs a frame loop only while composed and never in low-power mode.
 */
@Composable
fun BarFx(modifier: Modifier, style: BarFxStyle = LocalBarFx.current) {
    if (style.kind == BarFxStyle.NONE || LocalDeviceProfile.current.lowPower) return
    val accent = LocalNeon.current.accent
    val parts = remember(style.kind) { ArrayList<P>() }
    var now by remember { mutableLongStateOf(0L) }
    val lastFrame = remember { LongArray(1) }   // plain holder: no state writes during draw
    // Paused: no frame loop — the particles stay frozen where they are.
    val running = com.maiconerick.xgamelauncher.ui.animationsOn()
    LaunchedEffect(style.kind, running) {
        // ~30fps is plenty for these particles; the display's full 120Hz just burns battery/heat.
        if (running) while (true) { withFrameMillis { now = it }; kotlinx.coroutines.delay(28) }
    }
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }
    // Clip: particles spawn above the bar and katakana glyphs are taller than their row.
    Canvas(modifier.clipToBounds()) {
        val t = now
        val dt = if (lastFrame[0] == 0L) 16f else (t - lastFrame[0]).coerceIn(1, 64).toFloat()
        lastFrame[0] = t
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val sp = style.speedMul
        val target = (4 + style.amount * 60).toInt()
        when (style.kind) {
            "pixels" -> {
                while (parts.size < target) parts += P(Random.nextFloat() * w, -Random.nextFloat() * h, 0.02f + Random.nextFloat() * 0.05f, 1f, size = 2f + Random.nextFloat() * 3f)
                parts.forEach { p ->
                    p.y += p.v * dt * sp * h / 12f
                    if (p.y > h) { p.y = -p.size; p.x = Random.nextFloat() * w }
                    drawRect(accent.copy(alpha = 0.55f), Offset(p.x, p.y), Size(p.size, p.size))
                }
            }
            "katakana" -> {
                val fs = (h / 3.2f).coerceIn(8f, 22f)
                paint.textSize = fs
                val cols = (w / (fs * 0.9f)).toInt().coerceAtLeast(1)
                val want = (cols * (0.15f + style.amount * 0.85f)).toInt().coerceAtLeast(1)
                while (parts.size < want) parts += P((Random.nextInt(cols)) * fs * 0.9f, -Random.nextFloat() * h * 2, 0.4f + Random.nextFloat() * 0.8f, 1f,
                    KATAKANA[Random.nextInt(KATAKANA.length)])
                while (parts.size > want) parts.removeAt(parts.lastIndex)
                parts.forEach { p ->
                    p.y += p.v * dt * sp * fs / 40f
                    if (Random.nextFloat() < 0.02f * sp) p.ch = KATAKANA[Random.nextInt(KATAKANA.length)]
                    if (p.y > h + fs) { p.y = -fs; p.x = Random.nextInt(cols) * fs * 0.9f }
                    // head bright, two fading trail glyphs above it
                    for (k in 0..2) {
                        paint.color = (if (k == 0) Color.White else accent).copy(alpha = if (k == 0) 0.75f else 0.45f / k).toArgb()
                        drawContext.canvas.nativeCanvas.drawText(p.ch.toString(), p.x, p.y - k * fs, paint)
                    }
                }
            }
            "stars" -> {
                while (parts.size < target) parts += P(Random.nextFloat() * w, Random.nextFloat() * h, 0.2f + Random.nextFloat() * 0.8f, 1f)
                parts.forEach { p ->
                    p.x -= p.v * dt * sp * 0.25f
                    if (p.x < -12f) { p.x = w + 12f; p.y = Random.nextFloat() * h }
                    val len = 3f + p.v * 10f
                    drawLine(accent.copy(alpha = 0.25f + p.v * 0.5f), Offset(p.x, p.y), Offset(p.x + len, p.y), strokeWidth = 1.5f)
                }
            }
            "sparkles" -> {
                while (parts.size < target) parts += P(Random.nextFloat() * w, Random.nextFloat() * h, 0.3f + Random.nextFloat(), Random.nextFloat())
                parts.forEach { p ->
                    p.life -= dt * 0.0012f * sp * p.v
                    if (p.life <= 0f) { p.life = 1f; p.x = Random.nextFloat() * w; p.y = Random.nextFloat() * h }
                    val a = kotlin.math.sin(p.life * Math.PI).toFloat()
                    drawCircle(Color.White.copy(alpha = 0.7f * a), radius = 1.2f + a * 1.6f, center = Offset(p.x, p.y))
                    drawCircle(accent.copy(alpha = 0.25f * a), radius = 4f + a * 3f, center = Offset(p.x, p.y))
                }
            }
            "bats" -> {
                val bh = (h * 0.42f).coerceIn(10f, 26f * density)
                val want = 1 + (style.amount * 5).toInt()
                while (parts.size < want) parts += P(Random.nextFloat() * w, bh * 0.3f + Random.nextFloat() * (h - bh * 1.3f),
                    0.4f + Random.nextFloat() * 0.8f, Random.nextFloat() * 6.28f, size = if (Random.nextBoolean()) 1f else -1f)
                while (parts.size > want) parts.removeAt(parts.lastIndex)
                val flap = (t / 130L) % 2L == 0L
                parts.forEachIndexed { i, p ->
                    p.x += p.size * p.v * dt * sp * 0.06f * density
                    if (p.x > w + bh * 2) p.x = -bh * 2 else if (p.x < -bh * 2) p.x = w + bh * 2
                    val y = p.y + kotlin.math.sin(t / 260.0 + p.life).toFloat() * h * 0.12f
                    drawSprite(if (flap xor (i % 2 == 1)) Sprites.bat1 else Sprites.bat2,
                        Offset(p.x, y), bh * 0.56f, alpha = 0.9f, flipX = p.size < 0f)
                }
            }
            "embers" -> {
                while (parts.size < target) parts += P(Random.nextFloat() * w, h + Random.nextFloat() * h, 0.3f + Random.nextFloat(), Random.nextFloat() * 6.28f,
                    size = 1.2f + Random.nextFloat() * 2.2f)
                parts.forEach { p ->
                    p.y -= p.v * dt * sp * h / 900f
                    p.x += kotlin.math.sin(t / 400.0 + p.life).toFloat() * 0.3f
                    if (p.y < -4f) { p.y = h + 4f; p.x = Random.nextFloat() * w }
                    val a = (p.y / h).coerceIn(0f, 1f)
                    val c = if (p.v > 0.9f) Color(0xFFFFD54A) else Color(0xFFFF7A18)
                    drawCircle(c.copy(alpha = 0.18f * a), radius = p.size * 3f, center = Offset(p.x, p.y))
                    drawCircle(c.copy(alpha = 0.85f * a), radius = p.size, center = Offset(p.x, p.y))
                }
            }
            "fog" -> {
                val n = 3 + (style.amount * 5).toInt()
                for (i in 0 until n) {
                    val period = (14000f + i * 3700f) / sp
                    val q = ((t + i * 5100L) % period.toLong()) / period
                    val r = w * (0.18f + 0.04f * (i % 3))
                    val x = -r + q * (w + r * 2)
                    val y = h * (0.3f + 0.5f * ((i * 37) % 10) / 10f)
                    val c = if (i % 2 == 0) Color(0xFF8B3DFF) else Color(0xFFB9A8E0)
                    drawOval(androidx.compose.ui.graphics.Brush.radialGradient(listOf(c.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(x, y), radius = r), topLeft = Offset(x - r, y - h * 0.6f), size = Size(r * 2, h * 1.2f))
                }
            }
            "scanline" -> {
                val period = 2600f / sp
                val x = ((t % period.toLong()) / period) * (w + 120f) - 60f
                val bands = 1 + (style.amount * 3).toInt()
                for (b in 0 until bands) {
                    val bx = (x - b * (w / bands)).let { if (it < -60f) it + w + 120f else it }
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Color.Transparent, accent.copy(alpha = 0.35f), Color.White.copy(alpha = 0.5f), accent.copy(alpha = 0.35f), Color.Transparent),
                            startX = bx - 50f, endX = bx + 50f,
                        ),
                        topLeft = Offset(bx - 50f, 0f), size = Size(100f, h),
                    )
                }
            }
        }
    }
}
