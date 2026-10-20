package com.maiconerick.xgamelauncher.ui.screens

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.graphicsLayer
import com.maiconerick.xgamelauncher.ui.components.PlatformHeroOverlay
import com.maiconerick.xgamelauncher.ui.components.TextBackdrop
import kotlinx.coroutines.flow.first
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.ui.LibraryState
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.components.PageHeader
import com.maiconerick.xgamelauncher.ui.components.focusRing
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.tan

/** Separator styles between collage panels. */
enum class SepStyle(val label: String) {
    STRAIGHT("Straight"), SLANT("Slanted"), BOLT("Lightning bolt"), RED_BLACK("Red & black"), NEON("Neon glow"), GAP("Gap"),
}

/** Per-panel image transform: scale ≥ 1 on top of "cover", offset as a fraction of the panel box. */
data class PanelXf(val scale: Float = 1f, val dx: Float = 0f, val dy: Float = 0f)

/** Defaults reproduce the user's Windows wallpaper: vertical strips, thin black gaps, black frame. */
data class CollageStyle(
    val sep: SepStyle = SepStyle.GAP,
    /** Slant angle in degrees from vertical (0 = straight strips). */
    val angle: Float = 0f,
    /** Separator thickness as a fraction of the output height. */
    val thickness: Float = 0.0033f,
    val color: Int = 0xFF00E5FF.toInt(),
    /** "auto" = black gaps / accent lines, "accent" = always the accent, "custom" = [color]. */
    val colorMode: String = "auto",
    /** Black frame: top/bottom bars as a fraction of the height (sides get a tenth of it). */
    val frame: Float = 0f,
    /** Vignette: "none" | "panels" (each card) | "whole" (the whole background). */
    val vignette: String = "none",
    /** 0..1 darkness of the vignette edges. */
    val vignetteAmount: Float = 0.6f,
)

/** Separator polyline (top → bottom) at normalized x in a W×H canvas. */
private fun sepPoints(style: CollageStyle, xNorm: Float, w: Float, h: Float): List<PointF> {
    val x = xNorm * w
    val skew = if (style.sep == SepStyle.STRAIGHT) 0f else tan(Math.toRadians(style.angle.toDouble())).toFloat() * h / 2f
    return when (style.sep) {
        SepStyle.BOLT -> listOf(
            PointF(x + skew, 0f), PointF(x - skew * 0.25f, h * 0.46f),
            PointF(x + skew * 0.55f, h * 0.54f), PointF(x - skew, h),
        )
        else -> listOf(PointF(x + skew, 0f), PointF(x - skew, h))
    }
}

/** Polygon for panel i of n. */
private fun panelPath(style: CollageStyle, i: Int, n: Int, w: Float, h: Float): android.graphics.Path {
    val left = if (i == 0) listOf(PointF(-w, 0f), PointF(-w, h)) else sepPoints(style, i / n.toFloat(), w, h)
    val right = if (i == n - 1) listOf(PointF(w * 2, 0f), PointF(w * 2, h)) else sepPoints(style, (i + 1) / n.toFloat(), w, h)
    return android.graphics.Path().apply {
        moveTo(left.first().x, left.first().y)
        left.drop(1).forEach { lineTo(it.x, it.y) }
        right.reversed().forEach { lineTo(it.x, it.y) }
        close()
    }
}

/**
 * Draws the collage on a native canvas — used by both the live preview and the PNG export,
 * so what you see is exactly what gets saved.
 */
fun renderCollage(
    c: android.graphics.Canvas, w: Float, h: Float,
    images: List<Bitmap?>, xfs: List<PanelXf>, style: CollageStyle, selected: Int = -1,
) {
    val n = images.size.coerceAtLeast(1)
    c.drawColor(android.graphics.Color.BLACK)
    // Black frame: draw the strips inside an inset rect.
    val my = style.frame * h
    val mx = my * 0.12f
    c.save()
    c.translate(mx, my)
    c.clipRect(0f, 0f, w - 2 * mx, h - 2 * my)
    drawStrips(c, w - 2 * mx, h - 2 * my, images, xfs, style, selected, n, frameH = h)
    c.restore()
}

private fun drawStrips(
    c: android.graphics.Canvas, w: Float, h: Float,
    images: List<Bitmap?>, xfs: List<PanelXf>, style: CollageStyle, selected: Int, n: Int, frameH: Float,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    for (i in 0 until n) {
        val path = panelPath(style, i, n, w, h)
        val bounds = RectF().also { path.computeBounds(it, true) }
        bounds.intersect(0f, 0f, w, h)
        val bmp = images.getOrNull(i) ?: continue
        val xf = xfs.getOrElse(i) { PanelXf() }
        val cover = max(bounds.width() / bmp.width, bounds.height() / bmp.height) * xf.scale
        val dw = bmp.width * cover
        val dh = bmp.height * cover
        val cx = bounds.centerX() + xf.dx * bounds.width()
        val cy = bounds.centerY() + xf.dy * bounds.height()
        c.save()
        c.clipPath(path)
        c.drawBitmap(bmp, null, RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2), paint)
        if (style.vignette == "panels") drawVignette(c, bounds, style.vignetteAmount)
        c.restore()
    }
    if (style.vignette == "whole") drawVignette(c, RectF(0f, 0f, w, h), style.vignetteAmount)
    // Separators on top (thickness relative to the full output height).
    val t = style.thickness * frameH
    for (i in 1 until n) {
        val pts = sepPoints(style, i / n.toFloat(), w, h)
        val line = android.graphics.Path().apply {
            moveTo(pts.first().x, pts.first().y); pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER }
        when (style.sep) {
            SepStyle.RED_BLACK -> {
                stroke.color = android.graphics.Color.BLACK; stroke.strokeWidth = t * 2.6f; c.drawPath(line, stroke)
                stroke.color = 0xFFE0162B.toInt(); stroke.strokeWidth = t * 1.1f; c.drawPath(line, stroke)
            }
            SepStyle.NEON, SepStyle.BOLT -> {
                stroke.color = style.color; stroke.strokeWidth = t * 3f
                stroke.maskFilter = BlurMaskFilter(t * 2.5f, BlurMaskFilter.Blur.NORMAL); c.drawPath(line, stroke)
                stroke.maskFilter = null; stroke.color = android.graphics.Color.WHITE; stroke.strokeWidth = t * 0.8f; c.drawPath(line, stroke)
            }
            SepStyle.GAP -> {
                stroke.color = if (style.colorMode == "auto") android.graphics.Color.BLACK else style.color
                stroke.strokeWidth = t * 3f; c.drawPath(line, stroke)
            }
            else -> { stroke.color = style.color; stroke.strokeWidth = t; c.drawPath(line, stroke) }
        }
    }
    if (selected in 0 until n) {
        val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE; strokeWidth = max(3f, h * 0.006f); color = android.graphics.Color.WHITE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
        }
        c.save(); c.clipRect(0f, 0f, w, h); c.drawPath(panelPath(style, selected, n, w, h), sel); c.restore()
    }
}

/** Radial darkening towards the edges of [r]: clear centre, black corners at [amount] = 1. */
private fun drawVignette(c: android.graphics.Canvas, r: RectF, amount: Float) {
    if (amount <= 0f) return
    val radius = kotlin.math.hypot(r.width(), r.height()) / 2f
    val edge = (amount.coerceIn(0f, 1f) * 255).toInt()
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(
            r.centerX(), r.centerY(), radius,
            intArrayOf(0x00000000, 0x00000000, android.graphics.Color.argb(edge / 2, 0, 0, 0), android.graphics.Color.argb(edge, 0, 0, 0)),
            floatArrayOf(0f, 0.45f - amount * 0.2f, 0.75f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    c.drawRect(r, p)
}

/** Loads any art reference (file path, content uri or URL) as a software bitmap, max [maxPx]. */
private suspend fun loadBitmap(ctx: android.content.Context, model: String, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
    val req = ImageRequest.Builder(ctx).data(model).allowHardware(false).size(maxPx).build()
    (ctx.imageLoader.execute(req) as? SuccessResult)?.drawable?.let { d ->
        (d as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: run {
            val b = Bitmap.createBitmap(d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(b); d.setBounds(0, 0, cv.width, cv.height); d.draw(cv); b
        }
    }
}

/**
 * Platform background maker: pick games (and their art), then arrange them as panels split by
 * separators; each panel's image can be moved/zoomed; SAVE renders a PNG at the screen's
 * resolution and hands the path (plus the chosen text backdrop) back to the platform editor.
 * Full-screen: the editor header and the app's bottom bar are hidden while it's open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackgroundMakerScreen(
    platform: Platform,
    state: LibraryState,
    playtimeSec: Long = 0L,
    onDone: (String, TextBackdrop) -> Unit,
    onCancel: () -> Unit,
) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val compact = LocalDeviceProfile.current.compact
    val scope = rememberCoroutineScope()
    val games = state.gamesByPlatform[platform.id].orEmpty().sortedBy { it.name.lowercase() }
    fun artOf(g: GameEntry): String? {
        val o = state.overrideByGameId[g.id]
        return o?.heroArtOverride?.takeIf { it.isNotBlank() } ?: state.artByGameId[g.id]?.heroPath
            ?: o?.boxArtOverride?.takeIf { it.isNotBlank() } ?: state.artByGameId[g.id]?.boxPath
    }
    val chosen = remember { mutableStateListOf<String>() }                 // game ids, in panel order
    val customArt = remember { mutableStateMapOf<String, String>() }       // gameId → picked art url/path
    var step by remember { mutableStateOf("pick") }
    var searchFor by remember { mutableStateOf<GameEntry?>(null) }
    var backdrop by remember { mutableStateOf(TextBackdrop()) }
    // A saved project for this platform lets the current background be edited, not remade.
    var project by remember { mutableStateOf<MakerProject?>(null) }
    var initialStyle by remember { mutableStateOf<CollageStyle?>(null) }
    var initialXfs by remember { mutableStateOf<List<PanelXf>?>(null) }
    LaunchedEffect(platform.id) { project = withContext(Dispatchers.IO) { loadMakerProject(platform.id) } }
    fun editCurrent(p: MakerProject) {
        chosen.clear(); customArt.clear()
        p.games.forEachIndexed { i, id ->
            val key = if (id in chosen) "$id#$i" else id
            chosen.add(key)
            p.sources.getOrNull(i)?.let { customArt[key] = it }
        }
        initialStyle = p.style; initialXfs = p.xfs
        step = "compose"
    }
    LaunchedEffect(platform.id) {
        val app = ctx.applicationContext as com.maiconerick.xgamelauncher.XGameLauncherApp
        app.prefs.heroBackdrops.first()[platform.id]?.let { backdrop = it }
    }

    DisposableEffect(Unit) {
        com.maiconerick.xgamelauncher.ui.AppChrome.hideBottomBar = true
        onDispose { com.maiconerick.xgamelauncher.ui.AppChrome.hideBottomBar = false }
    }
    androidx.activity.compose.BackHandler { if (step == "compose") step = "pick" else onCancel() }

    searchFor?.let { g ->
        com.maiconerick.xgamelauncher.ui.dialogs.ArtSearchDialog(
            initialQuery = g.name,
            target = com.maiconerick.xgamelauncher.art.ArtTarget.HERO,
            onApply = { r -> (r.heroUrl ?: r.boxUrl)?.let { customArt[g.id] = it }; searchFor = null },
            onCancel = { searchFor = null },
        )
    }

    Column(Modifier.fillMaxSize().background(neon.surface)) {
        if (step == "pick") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("BACKGROUND · ${platform.name.uppercase()}", color = neon.accent, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Pick games in panel order (A toggles)",
                    color = neon.inkDim, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                project?.let { p -> SmallBtn("EDIT CURRENT", accent = true, onClick = { editCurrent(p) }) }
                SmallBtn("NEXT (${chosen.size})", accent = chosen.size >= 1, onClick = { if (chosen.isNotEmpty()) step = "compose" })
                SmallBtn("CANCEL", onClick = onCancel)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(games, key = { it.id }) { g ->
                    val idx = chosen.indexOf(g.id)
                    val art = customArt[g.id] ?: artOf(g)
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
                            .focusRing(focused, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
                            .background(if (idx >= 0) neon.accent.copy(alpha = 0.16f) else Color(0x10FFFFFF))
                            .onFocusChanged { focused = it.isFocused }.focusable()
                            .onGamepadActivate { if (idx >= 0) chosen.remove(g.id) else chosen.add(g.id) }
                            .clickable { if (idx >= 0) chosen.remove(g.id) else chosen.add(g.id) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(if (idx >= 0) "${idx + 1}" else "·", color = neon.accent, fontSize = 16.sp,
                            fontWeight = FontWeight.Black, modifier = Modifier.width(22.dp))
                        Box(Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x22FFFFFF))) {
                            if (art != null) AsyncImage(art, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Text(g.name, color = neon.ink, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            ComposeStep(
                platform = platform,
                gameCount = games.size,
                playtimeSec = playtimeSec,
                models = chosen.map { id -> customArt[id] ?: games.firstOrNull { it.id == id }?.let { artOf(it) } },
                compact = compact,
                backdrop = backdrop,
                onBackdrop = { backdrop = it },
                onBack = { step = "pick" },
                onCancel = onCancel,
                onSwap = { i, j ->
                    if (i in chosen.indices && j in chosen.indices) { val t = chosen[i]; chosen[i] = chosen[j]; chosen[j] = t }
                },
                initialStyle = initialStyle,
                initialXfs = initialXfs,
                onSave = { style, xfs, models ->
                    scope.launch {
                        val path = exportCollage(ctx, platform, chosen.toList(), models, xfs, style)
                        if (path != null) onDone(path, backdrop)
                    }
                },
            )
        }
    }
}

private enum class MakerTab(val label: String) { PANEL("PANEL"), STYLE("SEPARATOR"), COLOR("COLOR"), VIGNETTE("VIGNETTE"), TEXT("TEXT") }

/** Separator colour swatches (the ACCENT / AUTO chips come first). */
private val SWATCHES = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFF00E5FF, 0xFFFF2BD6, 0xFFFFE600, 0xFFFF2B2B,
    0xFF39FF14, 0xFF8A2BE2, 0xFFFF8C00, 0xFF1E90FF,
).map { it.toInt() }

private fun parseHexColor(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#")?.ifBlank { null } ?: return null
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) { 6 -> Color(0xFF000000 or v); 8 -> Color(v); else -> null }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposeStep(
    platform: Platform,
    gameCount: Int,
    playtimeSec: Long,
    models: List<String?>,
    compact: Boolean,
    backdrop: TextBackdrop,
    onBackdrop: (TextBackdrop) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onSwap: (Int, Int) -> Unit,
    initialStyle: CollageStyle? = null,
    initialXfs: List<PanelXf>? = null,
    onSave: (CollageStyle, List<PanelXf>, List<String?>) -> Unit,
) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    // The Home hub area (between the top and bottom bars), so the image fills it exactly.
    val hubH = hubHeightDp(cfg.screenHeightDp, compact)
    val aspect = cfg.screenWidthDp.toFloat() / hubH
    val accent = parseHexColor(platform.accentColorHex) ?: neon.accent
    // Keyed by art reference, so swapping panels doesn't reload anything.
    val previews = remember { mutableStateMapOf<String, Bitmap?>() }
    LaunchedEffect(models) { models.filterNotNull().filter { it !in previews }.forEach { m -> previews[m] = loadBitmap(ctx, m, 900) } }
    val xfs = remember(models.size) {
        mutableStateListOf<PanelXf>().apply {
            repeat(models.size) { i -> add(initialXfs?.takeIf { it.size == models.size }?.get(i) ?: PanelXf()) }
        }
    }
    var selected by remember { mutableStateOf(-1) }   // -1 = no panel selected
    var style by remember { mutableStateOf(initialStyle ?: CollageStyle(color = accent.toArgb())) }
    var saving by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(MakerTab.PANEL) }
    var showText by remember { mutableStateOf(true) }
    var pickBackdropColor by remember { mutableStateOf(false) }
    var pickSepColor by remember { mutableStateOf(false) }
    fun nudge(f: (PanelXf) -> PanelXf) { if (selected in xfs.indices) xfs[selected] = f(xfs[selected]) }
    // "auto"/"accent" follow the platform accent; "custom" keeps the picked swatch.
    val effective = if (style.colorMode == "custom") style else style.copy(color = accent.toArgb())
    val images = models.map { m -> m?.let { previews[it] } }
    /** Moves the selected panel (art + its framing) by [d] places. */
    fun move(d: Int) {
        val j = selected + d
        if (selected !in xfs.indices || j !in xfs.indices) return
        val t = xfs[selected]; xfs[selected] = xfs[j]; xfs[j] = t
        onSwap(selected, j); selected = j
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = if (compact) 6.dp else 12.dp, vertical = 6.dp)
            .onPreviewKeyEvent { e ->
                // L/R pick the panel; ZL/ZR zoom it.
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.ButtonL1 -> { selected = if (selected < 0) 0 else (selected - 1).coerceAtLeast(0); true }
                    Key.ButtonR1 -> { selected = (selected + 1).coerceAtMost(models.lastIndex); true }
                    Key.ButtonL2 -> { nudge { it.copy(scale = (it.scale - 0.08f).coerceAtLeast(1f)) }; true }
                    Key.ButtonR2 -> { nudge { it.copy(scale = (it.scale + 0.08f).coerceAtMost(5f)) }; true }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Live preview (same renderer as the export), as large as the space allows.
        // Tap selects a panel; drag/pinch adjusts it.
        // Tapping anywhere outside a panel (frame, margins) clears the selection.
        Box(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectTapGestures { selected = -1 } },
            contentAlignment = Alignment.Center) {
            BoxWithConstraints(
                Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(6.dp)).border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .pointerInput(models.size) {
                        detectTapGestures { p -> selected = panelAt(effective, models.size, size.width.toFloat(), size.height.toFloat(), p.x, p.y) }
                    }
                    .pointerInput(models.size) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val panelW = size.width / models.size.coerceAtLeast(1)
                            nudge { it.copy(scale = (it.scale * zoom).coerceIn(1f, 5f), dx = it.dx + pan.x / panelW, dy = it.dy + pan.y / size.height) }
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { c -> renderCollage(c.nativeCanvas, size.width, size.height, images, xfs.toList(), effective, selected) }
                }
                if (showText) {
                    // Lay the hub overlay out at full-screen size, then scale it into the
                    // preview, so the logo and stats sit exactly as they will on Home.
                    val fullW = cfg.screenWidthDp.dp
                    val fullH = hubH.dp
                    val sc = maxWidth / fullW
                    Box(Modifier.requiredSize(fullW, fullH).graphicsLayer { scaleX = sc; scaleY = sc }) {
                        PlatformHeroOverlay(
                            iconPath = platform.iconImagePath,
                            name = platform.name,
                            accent = accent,
                            games = gameCount.toString(),
                            playtime = if (playtimeSec > 0) com.maiconerick.xgamelauncher.util.TimeFormat.playtime(playtimeSec) else "—",
                            backdrop = backdrop,
                            blurSource = {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawIntoCanvas { c -> renderCollage(c.nativeCanvas, size.width, size.height, images, xfs.toList(), effective) }
                                }
                            },
                        )
                    }
                }
            }
        }

        // Tabs + actions on one line.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MakerTab.values().forEach { t -> MakerTabChip(t.label, tab == t) { tab = t } }
            Spacer(Modifier.weight(1f))
            if (selected >= 0) {
                Text("PANEL ${selected + 1}/${models.size}", color = accent, fontSize = 11.sp, letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Bold)
                SmallBtn("◀ MOVE", onClick = { move(-1) })
                SmallBtn("MOVE ▶", onClick = { move(1) })
            } else Text("Tap a panel to move it · drag / pinch to frame it", color = neon.inkMuted, fontSize = 11.sp)
            SmallBtn("◀ GAMES", onClick = onBack)
            SmallBtn("CANCEL", onClick = onCancel)
            SmallBtn(if (saving) "SAVING…" else "SAVE", accent = true, onClick = {
                if (!saving) { saving = true; onSave(effective, xfs.toList(), models) }
            })
        }

        Box(Modifier.fillMaxWidth().height(if (compact) 84.dp else 104.dp)) {
            when (tab) {
                MakerTab.PANEL -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        SmallBtn("◀ PANEL", onClick = { selected = if (selected < 0) 0 else (selected - 1).coerceAtLeast(0) })
                        SmallBtn("PANEL ▶", onClick = { selected = if (selected < 0) 0 else (selected + 1).coerceAtMost(models.lastIndex) })
                        Spacer(Modifier.width(6.dp))
                        SmallBtn("◀ MOVE", onClick = { move(-1) })
                        SmallBtn("MOVE ▶", onClick = { move(1) })
                        Spacer(Modifier.width(6.dp))
                        SmallBtn("←", onClick = { nudge { it.copy(dx = it.dx - 0.04f) } })
                        SmallBtn("→", onClick = { nudge { it.copy(dx = it.dx + 0.04f) } })
                        SmallBtn("↑", onClick = { nudge { it.copy(dy = it.dy - 0.04f) } })
                        SmallBtn("↓", onClick = { nudge { it.copy(dy = it.dy + 0.04f) } })
                        SmallBtn("ZOOM −", onClick = { nudge { it.copy(scale = (it.scale - 0.1f).coerceAtLeast(1f)) } })
                        SmallBtn("ZOOM +", onClick = { nudge { it.copy(scale = (it.scale + 0.1f).coerceAtMost(5f)) } })
                        SmallBtn("RESET", onClick = { nudge { PanelXf() } })
                    }
                    Text(if (selected < 0) "Select a panel (tap it or ◀ / ▶ PANEL) · MOVE swaps it with its neighbour"
                        else "Panel ${selected + 1} of ${models.size} · MOVE swaps it with its neighbour · arrows / zoom frame its image",
                        color = neon.inkMuted, fontSize = 11.sp)
                }
                MakerTab.VIGNETTE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("none" to "OFF", "panels" to "EACH PANEL", "whole" to "WHOLE BACKGROUND").forEach { (k, label) ->
                            SmallBtn((if (style.vignette == k) "✓ " else "") + label, accent = style.vignette == k,
                                onClick = { style = style.copy(vignette = k) })
                        }
                    }
                    if (style.vignette != "none") Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        com.maiconerick.xgamelauncher.ui.components.GamepadSlider(
                            label = "DARKNESS", value = style.vignetteAmount,
                            onChange = { style = style.copy(vignetteAmount = it) }, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.weight(1f))
                    }
                }
                MakerTab.STYLE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        SepStyle.values().forEach { s ->
                            SmallBtn((if (style.sep == s) "✓ " else "") + s.label, accent = style.sep == s, onClick = { style = style.copy(sep = s) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        com.maiconerick.xgamelauncher.ui.components.GamepadSlider(
                            label = "ANGLE", value = style.angle / 30f, onChange = { style = style.copy(angle = it * 30f) },
                            valueLabel = { "%.0f°".format(it * 30f) }, modifier = Modifier.weight(1f),
                        )
                        com.maiconerick.xgamelauncher.ui.components.GamepadSlider(
                            label = "THICKNESS", value = style.thickness / 0.03f,
                            onChange = { style = style.copy(thickness = (it * 0.03f).coerceAtLeast(0.001f)) },
                            modifier = Modifier.weight(1f),
                        )
                        com.maiconerick.xgamelauncher.ui.components.GamepadSlider(
                            label = "FRAME", value = style.frame / 0.2f, onChange = { style = style.copy(frame = it * 0.2f) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                MakerTab.COLOR -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                        SmallBtn((if (style.colorMode == "auto") "✓ " else "") + "AUTO", accent = style.colorMode == "auto",
                            onClick = { style = style.copy(colorMode = "auto") })
                        SmallBtn((if (style.colorMode == "accent") "✓ " else "") + "ACCENT", accent = style.colorMode == "accent",
                            onClick = { style = style.copy(colorMode = "accent") })
                        SWATCHES.forEach { c ->
                            Swatch(Color(c), style.colorMode == "custom" && style.color == c) {
                                style = style.copy(colorMode = "custom", color = c)
                            }
                        }
                        SmallBtn("PICK…", onClick = { pickSepColor = true })
                    }
                    Text("AUTO = black gaps, accent lines · ACCENT = the platform accent everywhere (Red & black keeps its colours)",
                        color = neon.inkMuted, fontSize = 11.sp)
                }
                MakerTab.TEXT -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        SmallBtn(if (showText) "✓ PREVIEW" else "PREVIEW", accent = showText, onClick = { showText = !showText })
                        Spacer(Modifier.width(8.dp))
                        TextBackdrop.KINDS.forEach { (k, label) ->
                            SmallBtn((if (backdrop.kind == k) "✓ " else "") + label.uppercase(), accent = backdrop.kind == k,
                                onClick = { onBackdrop(backdrop.copy(kind = k)) })
                        }
                        if (backdrop.kind != TextBackdrop.NONE) {
                            Spacer(Modifier.width(8.dp))
                            listOf("black" to "BLACK", "white" to "WHITE", "accent" to "ACCENT").forEach { (c, label) ->
                                SmallBtn((if (backdrop.color == c) "✓ " else "") + label, accent = backdrop.color == c,
                                    onClick = { onBackdrop(backdrop.copy(color = c)) })
                            }
                            val custom = backdrop.color.startsWith("#")
                            Swatch(if (custom) backdrop.resolveColor(accent) else Color(0xFF444444), custom) { pickBackdropColor = true }
                        }
                    }
                    if (backdrop.kind != TextBackdrop.NONE) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        com.maiconerick.xgamelauncher.ui.components.GamepadSlider(
                            label = "ART STRENGTH", value = backdrop.strength,
                            onChange = { onBackdrop(backdrop.copy(strength = it)) }, modifier = Modifier.weight(1f),
                        )
                        com.maiconerick.xgamelauncher.ui.components.GamepadSlider(
                            label = "ART SIZE", value = backdrop.size,
                            onChange = { onBackdrop(backdrop.copy(size = it)) }, modifier = Modifier.weight(1f),
                        )
                        com.maiconerick.xgamelauncher.ui.components.GamepadSlider(
                            label = "TEXT STRENGTH", value = backdrop.textS,
                            onChange = { onBackdrop(backdrop.copy(textStrength = it)) }, modifier = Modifier.weight(1f),
                        )
                        com.maiconerick.xgamelauncher.ui.components.GamepadSlider(
                            label = "TEXT SIZE", value = backdrop.textZ,
                            onChange = { onBackdrop(backdrop.copy(textSize = it)) }, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    if (pickBackdropColor) com.maiconerick.xgamelauncher.ui.dialogs.ColorPickerDialog(
        initialHex = backdrop.color.takeIf { it.startsWith("#") },
        fallback = accent,
        onApply = { hex -> if (hex != null) onBackdrop(backdrop.copy(color = hex)); pickBackdropColor = false },
        onDismiss = { pickBackdropColor = false },
    )
    if (pickSepColor) com.maiconerick.xgamelauncher.ui.dialogs.ColorPickerDialog(
        initialHex = "#%06X".format(style.color and 0xFFFFFF),
        fallback = accent,
        onApply = { hex ->
            hex?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
                ?.let { style = style.copy(colorMode = "custom", color = it) }
            pickSepColor = false
        },
        onDismiss = { pickSepColor = false },
    )
}

@Composable
private fun MakerTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.focusRing(focused, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
            .background(if (selected) neon.accent.copy(alpha = 0.18f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }.focusable()
            .onGamepadActivate(onClick).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = if (selected) neon.accent else neon.inkDim, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Box(Modifier.padding(top = 3.dp).size(width = 18.dp, height = 2.dp)
            .background(if (selected) neon.accent else Color.Transparent))
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.size(30.dp).focusRing(focused, androidx.compose.foundation.shape.CircleShape)
            .clip(androidx.compose.foundation.shape.CircleShape).background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color(0x55FFFFFF), androidx.compose.foundation.shape.CircleShape)
            .onFocusChanged { focused = it.isFocused }.focusable()
            .onGamepadActivate(onClick).clickable(onClick = onClick),
    )
}

/** Panel under (x, y) in a W×H preview, or -1 for the frame / outside every panel. */
private fun panelAt(style: CollageStyle, n: Int, w: Float, h: Float, x: Float, y: Float): Int {
    val my = style.frame * h
    val mx = my * 0.12f
    val iw = w - 2 * mx
    val ih = h - 2 * my
    val px = x - mx
    val py = y - my
    if (px < 0 || py < 0 || px > iw || py > ih) return -1
    for (i in 0 until n) {
        val path = panelPath(style, i, n, iw, ih)
        val region = android.graphics.Region().apply {
            setPath(path, android.graphics.Region(0, 0, iw.toInt().coerceAtLeast(1), ih.toInt().coerceAtLeast(1)))
        }
        if (region.contains(px.toInt(), py.toInt())) return i
    }
    return -1
}

/** Height (dp) of the Home hub area: the screen minus the top bar and the bottom platform bar. */
internal fun hubHeightDp(screenHeightDp: Int, compact: Boolean): Int =
    (screenHeightDp - if (compact) 78 else 96).coerceAtLeast(100)

private const val BG_DIR = "/storage/emulated/0/xGameLauncher/backgrounds"

/** Everything needed to reopen a background in the maker: games, source images, framing, style. */
data class MakerProject(
    val games: List<String>,
    val sources: List<String?>,
    val xfs: List<PanelXf>,
    val style: CollageStyle,
)

private fun projectFile(platformId: String) = File(BG_DIR, "$platformId.project.json")

fun loadMakerProject(platformId: String): MakerProject? = runCatching {
    val o = org.json.JSONObject(projectFile(platformId).readText())
    val g = o.getJSONArray("games"); val src = o.getJSONArray("sources"); val xa = o.getJSONArray("xfs")
    val st = o.getJSONObject("style")
    MakerProject(
        games = (0 until g.length()).map { g.getString(it) },
        sources = (0 until src.length()).map { if (src.isNull(it)) null else src.getString(it) },
        xfs = (0 until xa.length()).map { i -> xa.getJSONArray(i).let { PanelXf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) } },
        style = CollageStyle(
            sep = runCatching { SepStyle.valueOf(st.getString("sep")) }.getOrDefault(SepStyle.GAP),
            angle = st.optDouble("angle", 0.0).toFloat(), thickness = st.optDouble("thickness", 0.0033).toFloat(),
            color = st.optInt("color", 0xFF00E5FF.toInt()), colorMode = st.optString("colorMode", "auto"),
            frame = st.optDouble("frame", 0.09).toFloat(), vignette = st.optString("vignette", "none"),
            vignetteAmount = st.optDouble("vignetteAmount", 0.6).toFloat(),
        ),
    )
}.getOrNull()

private fun saveMakerProject(platformId: String, p: MakerProject) {
    val o = org.json.JSONObject()
        .put("version", 1)
        .put("games", org.json.JSONArray(p.games))
        .put("sources", org.json.JSONArray().apply { p.sources.forEach { put(it ?: org.json.JSONObject.NULL) } })
        .put("xfs", org.json.JSONArray().apply { p.xfs.forEach { put(org.json.JSONArray().put(it.scale.toDouble()).put(it.dx.toDouble()).put(it.dy.toDouble())) } })
        .put("style", org.json.JSONObject()
            .put("sep", p.style.sep.name).put("angle", p.style.angle.toDouble()).put("thickness", p.style.thickness.toDouble())
            .put("color", p.style.color).put("colorMode", p.style.colorMode).put("frame", p.style.frame.toDouble())
            .put("vignette", p.style.vignette).put("vignetteAmount", p.style.vignetteAmount.toDouble()))
    projectFile(platformId).writeText(o.toString(2))
}

/**
 * Renders at the screen's pixel size and saves BG_DIR/<platform>_<time>.png. The source
 * images are copied to BG_DIR/src/<platform>/ and the project (games, sources, framing,
 * style) to <platform>.project.json, so the background can be reopened and edited later.
 */
private suspend fun exportCollage(
    ctx: android.content.Context, platform: Platform, games: List<String>, models: List<String?>, xfs: List<PanelXf>, style: CollageStyle,
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val dm = ctx.resources.displayMetrics
        val w = max(dm.widthPixels, dm.heightPixels)
        val screenHdp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
        val compact = ctx.resources.configuration.let { minOf(it.screenWidthDp, it.screenHeightDp) < 500 }
        val h = (hubHeightDp(screenHdp.toInt(), compact) * dm.density).toInt()
        val full = models.map { m -> m?.let { loadBitmap(ctx, it, max(w, h)) } }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        renderCollage(android.graphics.Canvas(out), w.toFloat(), h.toFloat(), full, xfs, style)
        val dir = File(BG_DIR).apply { mkdirs() }
        val file = File(dir, "${platform.id}_${System.currentTimeMillis()}.png")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Keep the sources locally (URLs can disappear); new names each save so an image
        // shown in the editor is never overwritten while in use.
        val srcDir = File(dir, "src/${platform.id}").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val sources = full.mapIndexed { i, b ->
            b?.let { bmp ->
                File(srcDir, "${stamp}_$i.jpg").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }.absolutePath
            }
        }
        srcDir.listFiles()?.filter { !it.name.startsWith("${stamp}_") }?.forEach { it.delete() }
        runCatching { saveMakerProject(platform.id, MakerProject(games, sources, xfs, style)) }
        file.absolutePath
    }.getOrNull()
}
