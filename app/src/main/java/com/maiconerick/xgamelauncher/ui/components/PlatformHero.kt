package com.maiconerick.xgamelauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon

/**
 * What sits behind the hub's platform logo and GAMES / PLAYTIME stats so they stay readable.
 * [color]: "black" | "white" | "accent" | "#RRGGBB". [size] grows the area around them.
 */
data class TextBackdrop(
    val kind: String = NONE,
    /** Art (logo) strength / size. */
    val strength: Float = 1f,
    val size: Float = 0f,
    val color: String = "black",
    /** GAMES / PLAYTIME text strength / size; -1 = same as the art's. */
    val textStrength: Float = 1f,
    val textSize: Float = 0f,
) {
    val textS: Float get() = if (textStrength < 0f) strength else textStrength
    val textZ: Float get() = if (textSize < 0f) size else textSize
    fun encode() = "$kind|$strength|$size|$color|$textStrength|$textSize"

    fun resolveColor(accent: Color): Color = when (color) {
        "white" -> Color.White
        "accent" -> accent
        "black", "" -> Color.Black
        else -> runCatching { Color(android.graphics.Color.parseColor(color)) }.getOrDefault(Color.Black)
    }

    companion object {
        const val NONE = "none"
        const val SHADOW = "shadow"
        const val DIM = "dim"
        const val BLUR = "blur"
        val KINDS = listOf(NONE to "None", SHADOW to "Glow", DIM to "Panel", BLUR to "Blur")
        fun decode(s: String): TextBackdrop {
            val p = s.split('|')
            fun f(i: Int, d: Float) = p.getOrNull(i)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: d
            // Older saves lack the text fields: they follow the art's values.
            return TextBackdrop(p.getOrNull(0)?.ifBlank { null } ?: NONE, f(1, 1f), f(2, 0f),
                p.getOrNull(3)?.ifBlank { null } ?: "black",
                p.getOrNull(4)?.toFloatOrNull()?.coerceIn(-1f, 1f) ?: -1f,
                p.getOrNull(5)?.toFloatOrNull()?.coerceIn(-1f, 1f) ?: -1f)
        }
    }
}

/**
 * The hub's platform overlay: bottom scrim, logo (or name) in the middle, GAMES / PLAYTIME
 * along the bottom, plus the chosen [backdrop]. [blurSource] redraws the background for the
 * BLUR backdrop. Used by the Home card and the background maker preview, so they match.
 */
@Composable
fun BoxScope.PlatformHeroOverlay(
    iconPath: String?,
    name: String,
    accent: Color,
    games: String,
    playtime: String,
    backdrop: TextBackdrop,
    blurSource: (@Composable () -> Unit)? = null,
    /** Extra playtime rows (TODAY / WEEK / MONTH / YEAR) shown when [expanded]. */
    periods: List<Pair<String, String>> = emptyList(),
    expanded: Boolean = false,
    onPlaytimeClick: (() -> Unit)? = null,
) {
    val neon = LocalNeon.current
    val profile = LocalDeviceProfile.current
    var logoBox by remember { mutableStateOf(Rect.Zero) }
    var logoImg by remember(iconPath) { mutableStateOf<Size?>(null) }   // intrinsic size once loaded
    var statsRect by remember { mutableStateOf(Rect.Zero) }
    // Blur needs API 31 (RenderEffect); low-power devices get the cheaper panel.
    val kind = if (backdrop.kind == TextBackdrop.BLUR && (profile.lowPower || blurSource == null ||
            android.os.Build.VERSION.SDK_INT < 31)) TextBackdrop.DIM else backdrop.kind
    val s = backdrop.strength          // art (logo)
    val ts = backdrop.textS            // text (stats row)
    val tz = backdrop.textZ
    val tint = backdrop.resolveColor(accent)
    // The logo's drawn area (Fit inside its box), not the whole box.
    val logoRect = logoImg?.takeIf { it.width > 0 && it.height > 0 && logoBox != Rect.Zero }?.let { img ->
        val k = minOf(logoBox.width / img.width, logoBox.height / img.height)
        Rect(androidx.compose.ui.geometry.Offset(logoBox.center.x - img.width * k / 2, logoBox.center.y - img.height * k / 2),
            Size(img.width * k, img.height * k))
    } ?: logoBox

    // Faint bottom scrim so the stats stay readable over any image.
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color(0xB3000000)),
        ),
    )

    if (kind == TextBackdrop.BLUR || kind == TextBackdrop.DIM) {
        // One offscreen layer (blurred background and/or a colour fill), cut to soft regions
        // around the logo and stats. The mask is the full layer, so the effect can extend
        // past the logo as far as SIZE asks. Max strength ≈ solid colour.
        // Heavy curve: Panel is fully solid from ~75%; Blur's tint reaches 95%.
        fun alphaFor(v: Float) = when (kind) {
            TextBackdrop.BLUR -> v * 0.95f
            TextBackdrop.SHADOW -> (0.1f + v * 0.9f).coerceAtMost(1f)
            else -> (0.15f + v * 1.1f).coerceAtMost(1f)
        }
        // Each region carries its own strength (art vs text) in the mask's alpha.
        // (rect, strength, size) — the art and the text each have their own.
        val regions = listOf(Triple(logoRect, s, backdrop.size), Triple(statsRect, ts, tz))
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithCache {
                    // SIZE: how far the solid core reaches past the logo / stats; the feather
                    // fades outward from there (independent of strength, so every strength
                    // covers the same area).
                    val w = size.width.toInt().coerceAtLeast(1)
                    val h = size.height.toInt().coerceAtLeast(1)
                    val mask = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    val mc = android.graphics.Canvas(mask)
                    val region = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8)
                    val rc = android.graphics.Canvas(region)
                    val merge = android.graphics.Paint()
                    regions.filter { it.first != Rect.Zero }.forEach { (r, v, z) ->
                        // SIZE: how far the solid core reaches past the art / text; the feather
                        // fades outward from there (independent of strength).
                        val pad = (6f + z * 420f) * density
                        val feather = (12f + z * 60f) * density
                        paint.maskFilter = android.graphics.BlurMaskFilter(feather, android.graphics.BlurMaskFilter.Blur.NORMAL)
                        // Several passes saturate the soft edge so higher strengths stay dense;
                        // then the region lands in the mask at its own strength.
                        region.eraseColor(0)
                        val px = pad + feather
                        val py = pad * 0.6f + feather
                        repeat(1 + Math.round(v * 3f)) {
                            rc.drawRoundRect(r.left - px, r.top - py, r.right + px, r.bottom + py, px, px, paint)
                        }
                        merge.alpha = (alphaFor(v) * 255).toInt().coerceIn(0, 255)
                        mc.drawBitmap(region, 0f, 0f, merge)
                    }
                    region.recycle()
                    val img = mask.asImageBitmap()
                    onDrawWithContent {
                        drawContent()
                        drawRect(tint)
                        drawImage(img, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    }
                },
        ) {
            if (kind == TextBackdrop.BLUR && blurSource != null) {
                Box(Modifier.fillMaxSize().blur((8f + s * 72f).dp)) { blurSource() }
            }
        }
    }

    val glow = kind == TextBackdrop.SHADOW
    // The platform NAME (no logo image) is "art": its shadow follows the art's values.
    val textShadow = if (glow)
        Shadow(tint.copy(alpha = (0.6f + s * 0.4f)), blurRadius = 6f + backdrop.size * 160f) else null
    // Stats text glow: an outline around every letter (like the logo's at size 0), growing
    // and softening with the text SIZE; stacked layers for strength.
    val statGlow = if (glow) StatGlow(tint, ts, tz) else null

    val logoMod = Modifier.align(Alignment.Center).onGloballyPositioned { logoBox = it.boundsInParent() }
    if (!iconPath.isNullOrBlank()) {
        Box(logoMod.fillMaxWidth(0.45f).fillMaxHeight(0.5f), contentAlignment = Alignment.Center) {
            if (glow) {
                // Glow in the logo's shape: the logo tinted, scaled up by SIZE and blurred
                // without clipping (Unbounded), stacked twice at high strength.
                val layers = 1 + Math.round(s * 5f)
                repeat(layers) {
                    AsyncImage(
                        model = iconPath, contentDescription = null, contentScale = ContentScale.Fit,
                        // Opacity goes in the tint, not a layer alpha: a layer alpha < 1 renders
                        // offscreen at the logo's size and cropped the blurred glow.
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint.copy(alpha = (0.5f + s * 0.5f).coerceAtMost(1f))),
                        modifier = Modifier.fillMaxSize()
                            .graphicsLayer {
                                val k = 1f + backdrop.size * 1.2f
                                scaleX = k; scaleY = k
                            }
                            .let {
                                if (android.os.Build.VERSION.SDK_INT >= 31)
                                    it.blur((6f + backdrop.size * 70f).dp, BlurredEdgeTreatment.Unbounded) else it
                            },
                    )
                }
            }
            AsyncImage(
                model = iconPath, contentDescription = name, contentScale = ContentScale.Fit,
                onSuccess = { st -> logoImg = st.painter.intrinsicSize.takeIf { it.isSpecified() } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        Column(logoMod, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PLATFORM", color = neon.inkMuted, fontSize = 11.sp, letterSpacing = 2.4.sp,
                fontWeight = FontWeight.Medium, style = TextStyle(shadow = textShadow))
            Spacer(Modifier.height(2.dp))
            Text(
                text = name.ifBlank { "—" }.uppercase(), color = accent,
                fontSize = profile.sp(48.sp, 28.sp), fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                textAlign = TextAlign.Center, lineHeight = profile.sp(52.sp, 32.sp),
                style = TextStyle(shadow = textShadow),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }

    Row(
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = profile.dp(18.dp, 8.dp))
            .onGloballyPositioned { statsRect = it.boundsInParent() },
        horizontalArrangement = Arrangement.spacedBy(profile.dp(24.dp, 14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeroStat("GAMES", games, statGlow)
        Divider()
        // Tapping PLAYTIME toggles TODAY / WEEK / MONTH / YEAR next to the total.
        val tap = onPlaytimeClick?.let { cb ->
            Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .clickable(onClick = cb).padding(horizontal = 6.dp, vertical = 2.dp)
        } ?: Modifier
        Row(tap, horizontalArrangement = Arrangement.spacedBy(profile.dp(24.dp, 14.dp)),
            verticalAlignment = Alignment.CenterVertically) {
            if (expanded) periods.forEach { (label, value) -> HeroStat(label, value, statGlow); Divider() }
            HeroStat(if (expanded) "TOTAL" else "PLAYTIME", playtime, statGlow)
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(38.dp).background(LocalNeon.current.stroke))
}

private fun Size.isSpecified() = width.isFinite() && height.isFinite() && width > 0f && height > 0f

private class StatGlow(val color: Color, val strength: Float, val size: Float)

@Composable
private fun HeroStat(label: String, value: String, glow: StatGlow?) {
    val neon = LocalNeon.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GlowText(value, neon.ink, LocalDeviceProfile.current.sp(30.sp, 20.sp), FontWeight.Bold, (-0.8).sp, glow)
        GlowText(label, neon.inkMuted, 10.sp, FontWeight.Medium, 1.8.sp, glow)
    }
}

@Composable
private fun GlowText(
    text: String, color: Color, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight,
    spacing: androidx.compose.ui.unit.TextUnit, glow: StatGlow?,
) {
    Box(contentAlignment = Alignment.Center) {
        if (glow != null) {
            val stroke = with(androidx.compose.ui.platform.LocalDensity.current) { (2f + glow.size * 10f).dp.toPx() }
            val layers = 1 + Math.round(glow.strength * 4f)
            repeat(layers) {
                Text(
                    text, fontSize = size, fontWeight = weight, letterSpacing = spacing,
                    style = TextStyle(
                        color = glow.color.copy(alpha = (0.5f + glow.strength * 0.5f).coerceAtMost(1f)),
                        drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round),
                    ),
                    modifier = if (glow.size > 0f && android.os.Build.VERSION.SDK_INT >= 31)
                        Modifier.blur((glow.size * 40f).dp, BlurredEdgeTreatment.Unbounded) else Modifier,
                )
            }
        }
        Text(text, color = color, fontSize = size, fontWeight = weight, letterSpacing = spacing)
    }
}
