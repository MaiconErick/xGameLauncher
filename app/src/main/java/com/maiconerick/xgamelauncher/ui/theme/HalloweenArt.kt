package com.maiconerick.xgamelauncher.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Pixel-art sprite: one string per row, one palette char per pixel ('.' = transparent). */
class Sprite(val id: String, val rows: List<String>) {
    val width: Int = rows.maxOf { it.length }
    val height: Int = rows.size
}

/** Halloween pixel sprites (ported from xVideoWatcher). */
object Sprites {
    val palette: Map<Char, Long> = mapOf(
        'o' to 0xFFFF7A18, 'O' to 0xFFC24E00, 'd' to 0xFF7A2E00,
        'y' to 0xFFFFD54A, 'Y' to 0xFFFFF3B0,
        'g' to 0xFF5BD44A, 'G' to 0xFF2E7D32,
        'w' to 0xFFF4F0FF, 's' to 0xFFB9A8E0, 'k' to 0xFF140A1C,
        'p' to 0xFF8B3DFF, 'P' to 0xFF4B1C99,
        'r' to 0xFFFF2E4D, 'R' to 0xFF9E0F2A,
        'b' to 0xFF7A4FB0, 'B' to 0xFF3E2366, 'e' to 0xFFFF2E4D,
        'c' to 0xFF7CFF4F, 'C' to 0xFF2FA81C,
        'm' to 0xFFFFF7D6, 'M' to 0xFFE8D9A0,
        'n' to 0xFF6E6E80, 'N' to 0xFF3F3F4F, 'l' to 0xFFA0A0B8, 'h' to 0xFF5A3A1A,
    )

    val pumpkin = Sprite("pumpkin", listOf(
        ".......GG.......",
        "......GgG.......",
        "...OOOoGGoOOO...",
        "..OoooOooOoooO..",
        ".OoooOooooOoooO.",
        ".OooyyOooOyyooO.",
        "OooyyyyOOyyyyooO",
        "OoooooOooOoooooO",
        "OoooooOyyOoooooO",
        "OoyooOooooOooyoO",
        "OoyyyyyyyyyyyyoO",
        ".OoyyOyyyyOyyoO.",
        ".OoooOOyyOOoooO.",
        "..OOooOooOooOO..",
        "....OOOOOOOO....",
    ))

    val ghost = Sprite("ghost", listOf(
        ".....wwwww......",
        "...wwwwwwwws....",
        "..wwwwwwwwwss...",
        ".wwwwwwwwwwwss..",
        ".wwkkwwwkkwwss..",
        ".wwkkwwwkkwwss..",
        "wwwwwwwwwwwwwss.",
        "wwwwwwkkkwwwwss.",
        "wwwwwkkkkkwwwss.",
        "wwwwwwkkkwwwwss.",
        "wwwwwwwwwwwwwss.",
        "wwwwwwwwwwwwwss.",
        "wwwwwwwwwwwwwss.",
        "ww.wwww.wwww.ss.",
        "w...ww...ww...s.",
    ))

    val bat1 = Sprite("bat1", listOf(
        "B..............B",
        "BB............BB",
        "BbB....bb....BbB",
        "BbbB..bBBb..BbbB",
        "BbbbBbBBBBbBbbbB",
        ".BbbbBBeBeBBbbB.",
        "..BBbBBBBBBbBB..",
        "....B.BBBB.B....",
        ".......BB.......",
    ))

    val bat2 = Sprite("bat2", listOf(
        "................",
        "................",
        ".......bb.......",
        "......bBBb......",
        ".....BBBBBB.....",
        "..BBBBBeBeBBBB..",
        ".BbbbBBBBBBbbbB.",
        "BbbB.B.BB.B.BbbB",
        "BB......BB....BB",
    ))

    val skull = Sprite("skull", listOf(
        "....mmmmmm....",
        "..mmmmmmmmmm..",
        ".mmmmmmmmmmmM.",
        ".mmmmmmmmmmmM.",
        "mmkkkmmmmkkkmM",
        "mmkkkmmmmkkkmM",
        "mmkkkmmmmkkkmM",
        ".mmmmmkkmmmmM.",
        "..mmmmkkmmmM..",
        "...mmmmmmmM...",
        "...mkmkmkmM...",
        "...mmmmmmmM...",
        "....MMMMMM....",
    ))

    val spider = Sprite("spider", listOf(
        "..P...PPP...P..",
        "...P.PpppP.P...",
        "P...PppppppP..P",
        ".PPPPpepepPPPP.",
        "....PpppppP....",
        ".PPPPPpppPPPPP.",
        "P...PPpppPP...P",
        "...P.PPPPP.P...",
        "..P...PPP...P..",
    ))

    val moon = Sprite("moon", listOf(
        "....YYYYY....",
        "..YYYYYYYYY..",
        ".YYYYyYYYYYY.",
        ".YYYyyYYYYYYY",
        "YYYYYYYYYYYYy",
        "YYYYYYYYyYYYy",
        "YYYYYYYyyYYYy",
        "YYyYYYYYYYYYy",
        ".YyyYYYYYYYy.",
        ".YYYYYYYYYyy.",
        "..YYYYYYYyy..",
        "....YYyyy....",
    ))

    val tomb = Sprite("tomb", listOf(
        "....nnnnnn....",
        "..nnllllllnn..",
        ".nlllllllllln.",
        ".nllllnnllllnN",
        "nllllllnllllnN",
        "nllllnnnnnllnN",
        "nllllllnllllnN",
        "nllllllnllllnN",
        "nlllllllllllnN",
        "nllllllllllnnN",
        "nlllllllllllnN",
        "GgGgGGgGGgGgGG",
    ))

    val hat = Sprite("hat", listOf(
        ".........PP.....",
        "........PpP.....",
        ".......PppP.....",
        "......PpppP.....",
        ".....PppppP.....",
        "....PpppppP.....",
        "....PppppppP....",
        "...PyyyyyyyyP...",
        "...PPPPPPPPPP...",
        "PPPPppppppppPPPP",
        ".PPPPPPPPPPPPPP.",
    ))

    val cauldron = Sprite("cauldron", listOf(
        "...c..c...c...",
        "..c.cccc.c.c..",
        ".CcccccccccccC",
        "NNNNNNNNNNNNNN",
        ".nnNnnnnnnnnN.",
        "nnlnnnnnnnnnnN",
        "nlnnnnnnnnnnnN",
        "nnnnnnnnnnnnnN",
        ".nnnnnnnnnnnN.",
        "..nnnnnnnnnN..",
        "..NN......NN..",
    ))

    private val cache = HashMap<String, ImageBitmap>()

    /** Built once at 1px per pixel; drawing scales it with nearest-neighbour filtering. */
    fun bitmap(s: Sprite): ImageBitmap = synchronized(cache) {
        cache.getOrPut(s.id) {
            val bmp = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
            s.rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, ch -> palette[ch]?.let { bmp.setPixel(x, y, it.toInt()) } }
            }
            bmp.asImageBitmap()
        }
    }
}

/** Draws [s] with its top-left at [topLeft], [heightPx] tall, crisp pixels. */
fun DrawScope.drawSprite(s: Sprite, topLeft: Offset, heightPx: Float, alpha: Float = 1f, flipX: Boolean = false) {
    val img = Sprites.bitmap(s)
    val w = heightPx * s.width / s.height
    val draw = {
        drawImage(
            img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
            dstSize = IntSize(w.toInt().coerceAtLeast(1), heightPx.toInt().coerceAtLeast(1)),
            alpha = alpha.coerceIn(0f, 1f), filterQuality = FilterQuality.None,
        )
    }
    if (flipX) {
        val cx = topLeft.x + w / 2
        drawContext.transform.scale(-1f, 1f, Offset(cx, topLeft.y))
        draw()
        drawContext.transform.scale(-1f, 1f, Offset(cx, topLeft.y))
    } else draw()
}

/** Static sprite as a composable (theme previews, empty states). */
@Composable
fun PixelSprite(s: Sprite, height: Dp, modifier: Modifier = Modifier) {
    val width = height * (s.width.toFloat() / s.height)
    Canvas(modifier.size(width, height)) { drawSprite(s, Offset.Zero, size.height) }
}

/** Corner cobweb: spokes plus sagging rings, anchored at the corner. */
fun DrawScope.drawCobweb(corner: Offset, radius: Float, right: Boolean, color: Color) {
    val spokes = 6; val rings = 4
    val stroke = 1.dp.toPx()
    val sx = if (right) -1f else 1f
    val angles = (0..spokes).map { (Math.PI / 2) * it / spokes }
    for (a in angles) drawLine(color, corner, corner + Offset(sx * (cos(a) * radius).toFloat(), (sin(a) * radius).toFloat()), stroke)
    for (ring in 1..rings) {
        val rr = radius * ring / (rings + 0.3f)
        val path = Path()
        angles.forEachIndexed { i, a ->
            val p = corner + Offset(sx * (cos(a) * rr).toFloat(), (sin(a) * rr).toFloat())
            if (i == 0) path.moveTo(p.x, p.y) else {
                val mid = (angles[i - 1] + a) / 2
                val sag = rr * 0.82f
                val c = corner + Offset(sx * (cos(mid) * sag).toFloat(), (sin(mid) * sag).toFloat())
                path.quadraticBezierTo(c.x, c.y, p.x, p.y)
            }
        }
        drawPath(path, color, style = Stroke(stroke))
    }
}

private class Wisp(var x: Float, var y: Float, val vx: Float, val vy: Float, val phase: Float, val green: Boolean, val r: Float)

/**
 * Halloween backdrop for the whole app: purple night sky (black on AMOLED), pixel moon,
 * twinkling stars, drifting fog, glowing wisps, bats flying by, a ghost wandering across,
 * cobwebs with a dangling spider under the top bar, and a graveyard along the bottom.
 * One Canvas at ~30fps; with [animate] false it's drawn once and never invalidates.
 */
@Composable
fun HalloweenBackdrop(
    modifier: Modifier = Modifier,
    amoled: Boolean,
    animate: Boolean = true,
    /** Height of the top bar, so the cobwebs hang below it. */
    topInset: Dp = 48.dp,
    /** Previews: no graveyard/cobwebs, fewer wisps. */
    mini: Boolean = false,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lastFrame = remember { LongArray(1) }
    val wisps = remember { ArrayList<Wisp>() }
    val stars = remember { List(70) { Triple(Random.nextFloat(), Random.nextFloat() * 0.6f, Random.nextFloat()) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(animate, lifecycle) {
        if (!animate) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { withFrameMillis { now = System.currentTimeMillis() }; kotlinx.coroutines.delay(28) }
        }
    }
    Canvas(modifier) {
        val t = now
        val dt = if (lastFrame[0] == 0L) 16f else (t - lastFrame[0]).coerceIn(1, 64).toFloat()
        lastFrame[0] = t
        val w = size.width; val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val dp = density
        val sec = t / 1000f

        // sky
        if (amoled) drawRect(Color.Black)
        else drawRect(Brush.verticalGradient(listOf(Color(0xFF1A0B2E), Spook.Night, Color(0xFF0E0716))))

        // stars
        stars.forEach { (sx, sy, ph) ->
            val a = 0.25f + 0.55f * ((sin((sec * 1.3f + ph * 6.28f).toDouble()).toFloat() + 1f) / 2f)
            drawRect(Color(0xFFE9E0FF).copy(alpha = a * if (amoled) 0.7f else 0.9f),
                Offset(sx * w, sy * h), Size(1.6f * dp, 1.6f * dp))
        }

        // moon + glow
        val moonH = (minOf(w, h) * if (mini) 0.22f else 0.13f)
        val moonPos = Offset(w * 0.84f - moonH / 2, h * (if (mini) 0.12f else 0.1f))
        val mc = moonPos + Offset(moonH * 0.5f, moonH * 0.5f)
        drawCircle(Brush.radialGradient(listOf(Spook.Moon.copy(alpha = if (amoled) 0.16f else 0.28f), Color.Transparent),
            center = mc, radius = moonH * 2.2f), radius = moonH * 2.2f, center = mc)
        drawSprite(Sprites.moon, moonPos, moonH)

        // fog (not on AMOLED: keep the black true black)
        if (!amoled) for (i in 0..2) {
            val fx = ((sec * (6f + i * 3f) * dp + i * w * 0.4f) % (w * 1.6f)) - w * 0.3f
            val fy = h * (0.62f + i * 0.1f)
            val r = w * (0.35f + i * 0.08f)
            drawOval(Brush.radialGradient(listOf(Spook.Fog.copy(alpha = 0.16f), Color.Transparent), center = Offset(fx, fy), radius = r),
                topLeft = Offset(fx - r, fy - r * 0.35f), size = Size(r * 2, r * 0.7f))
        }

        // graveyard silhouette
        if (!mini) {
            val baseY = h - 46f * dp
            val hill = Path().apply {
                moveTo(0f, h); lineTo(0f, baseY + 10f * dp)
                quadraticBezierTo(w * 0.25f, baseY - 14f * dp, w * 0.5f, baseY + 4f * dp)
                quadraticBezierTo(w * 0.78f, baseY + 18f * dp, w, baseY - 6f * dp)
                lineTo(w, h); close()
            }
            drawPath(hill, if (amoled) Color(0xFF0A0610) else Color(0xFF140A20))
            val th = 26f * dp
            listOf(0.08f to Sprites.tomb, 0.17f to Sprites.pumpkin, 0.31f to Sprites.tomb, 0.62f to Sprites.cauldron,
                0.74f to Sprites.tomb, 0.9f to Sprites.pumpkin).forEachIndexed { i, (fx, s) ->
                val sh = if (s == Sprites.pumpkin) th * 0.7f else th
                val gy = baseY + (if (fx < 0.5f) (fx - 0.25f) * (fx - 0.25f) * 40f else (0.78f - fx) * -10f) * dp - sh + 8f * dp
                drawSprite(s, Offset(w * fx, gy), sh, alpha = if (amoled) 0.75f else 0.85f)
                if (s == Sprites.pumpkin) {      // candle flicker inside the pumpkins
                    val f = 0.5f + 0.5f * sin((sec * 9f + i * 2f).toDouble()).toFloat() * sin((sec * 13f + i).toDouble()).toFloat()
                    val c = Offset(w * fx + sh * 0.55f, gy + sh * 0.6f)
                    drawCircle(Brush.radialGradient(listOf(Spook.Pumpkin.copy(alpha = 0.22f + 0.18f * f), Color.Transparent), center = c, radius = sh * 1.4f),
                        radius = sh * 1.4f, center = c)
                }
            }
        }

        // wisps
        val wantWisps = if (mini) 8 else 22
        while (wisps.size < wantWisps) wisps += Wisp(Random.nextFloat() * w, Random.nextFloat() * h,
            (Random.nextFloat() - 0.5f) * 0.012f, -(0.006f + Random.nextFloat() * 0.014f),
            Random.nextFloat() * 6.28f, Random.nextFloat() < 0.6f, (1.2f + Random.nextFloat() * 1.8f))
        wisps.forEach { p ->
            p.x += (p.vx + sin(sec + p.phase) * 0.004f) * dt * dp; p.y += p.vy * dt * dp
            if (p.y < -10f) { p.y = h + 10f; p.x = Random.nextFloat() * w }
            if (p.x < -10f) p.x = w + 10f; if (p.x > w + 10f) p.x = -10f
            val a = 0.35f + 0.45f * ((sin((sec * 2f + p.phase).toDouble()).toFloat() + 1f) / 2f)
            val c = if (p.green) Spook.Slime else Spook.Pumpkin
            drawCircle(c.copy(alpha = a * 0.25f), radius = p.r * 4f * dp, center = Offset(p.x, p.y))
            drawCircle(c.copy(alpha = a), radius = p.r * dp, center = Offset(p.x, p.y))
        }

        // ghost wandering across, bobbing, fading at the edges
        run {
            val period = if (mini) 14f else 38f
            val q = (sec % period) / period
            val gh = (if (mini) 22f else 34f) * dp
            val gx = -gh + q * (w + gh * 2)
            val gy = h * 0.42f + sin(sec * 2.2f) * 8f * dp
            val edge = minOf(q, 1f - q) * 6f
            drawSprite(Sprites.ghost, Offset(gx, gy), gh, alpha = 0.6f * edge.coerceIn(0f, 1f))
        }

        // bats: three, each flying across on its own period, wings flapping
        val flap = ((t / 130L) % 2L) == 0L
        listOf(Triple(11f, 0.22f, 0f), Triple(17f, 0.34f, 0.45f), Triple(23f, 0.15f, 0.8f)).take(if (mini) 2 else 3)
            .forEachIndexed { i, (period, yFrac, off) ->
                val q = ((sec / period) + off) % 1f
                if (q > 0.55f) return@forEachIndexed          // off screen most of the cycle
                val p = q / 0.55f
                val bh = (if (mini) 12f else 16f + i * 4f) * dp
                val leftToRight = i % 2 == 0
                val bx = if (leftToRight) -bh * 2 + p * (w + bh * 4) else w + bh * 2 - p * (w + bh * 4)
                val by = h * yFrac + sin(p * 14f) * 10f * dp
                drawSprite(if (flap xor (i == 1)) Sprites.bat1 else Sprites.bat2, Offset(bx, by), bh * 0.56f, flipX = !leftToRight)
            }

        // cobwebs + dangling spider under the top bar
        if (!mini) {
            val top = topInset.toPx()
            val web = Color(0x40F2E8D5)
            drawCobweb(Offset(0f, top), 96f * dp, right = false, color = web)
            drawCobweb(Offset(w, top), 72f * dp, right = true, color = web)
            val drop = (sin(sec * 0.9f) + 1f) / 2f
            val sway = sin(sec * 1.6f) * 6f * dp
            val ax = 58f * dp
            val len = 46f * dp + drop * 28f * dp
            drawLine(Color(0x88A0A0B8), Offset(ax, top), Offset(ax + sway, top + len), 1f * dp)
            val sh = 15f * dp
            drawSprite(Sprites.spider, Offset(ax + sway - sh * 15f / 9f / 2f, top + len - 2f * dp), sh)
        }
    }
}

/** The app backdrop for the active theme: Halloween night or the Neon starfield. */
@Composable
fun ThemedBackdrop(modifier: Modifier = Modifier, animate: Boolean) {
    val theme = LocalAppTheme.current
    if (theme.halloween) HalloweenBackdrop(modifier, amoled = theme.amoled, animate = animate)
    else com.maiconerick.xgamelauncher.ui.components.AnimatedDotsBackground(modifier = modifier, animate = animate)
}
