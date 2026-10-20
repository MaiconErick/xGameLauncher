package com.maiconerick.xgamelauncher.ui.screens

import com.maiconerick.xgamelauncher.ui.components.focusRing
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.data.PlaytimeStats
import com.maiconerick.xgamelauncher.ui.LibraryState
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.components.AnimatedDotsBackground
import com.maiconerick.xgamelauncher.ui.components.TopBar
import com.maiconerick.xgamelauncher.ui.components.TopTab
import com.maiconerick.xgamelauncher.ui.components.neonFocusStroke
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import com.maiconerick.xgamelauncher.util.TimeFormat
import kotlinx.coroutines.launch
import com.maiconerick.xgamelauncher.ui.components.PlatformHeroOverlay

/**
 * ES-DE style hub: one focused platform, big hero backdrop from the first game, big
 * platform title (serves as the "art" until we have proper platform logos), game
 * count + total playtime metadata, and a horizontal system carousel pinned at the
 * bottom. L/R cycles the focused platform.
 */
@Composable
fun HomeScreen(
    state: LibraryState,
    focusedPlatformId: String?,
    onFocusPlatform: (String) -> Unit,
    onOpenPlatform: (Platform) -> Unit,
    playtime: (String, String) -> PlaytimeStats?,
    onTab: (TopTab) -> Unit,
) {
    val neon = LocalNeon.current
    val platforms = state.platforms
    val focused = platforms.firstOrNull { it.id == focusedPlatformId } ?: platforms.firstOrNull()

    val totalPlaytime = remember(focused?.id, state.gamesByPlatform) {
        focused?.let { p ->
            state.gamesByPlatform[p.id].orEmpty().sumOf {
                playtime(it.name, p.name)?.totalSeconds ?: 0L
            }
        } ?: 0L
    }

    // Fade dots to the focused platform's accent (or theme accent).
    val targetAccent = parseAccent(focused?.accentColorHex) ?: neon.accent
    val animatedAccent by androidx.compose.animation.animateColorAsState(
        targetValue = targetAccent,
        animationSpec = androidx.compose.animation.core.tween(800),
        label = "hub-accent-fade",
    )

    // Global starfield lives in AppRoot; this screen just paints its content on top.
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(activeTab = TopTab.Home, onTab = onTab)

            if (platforms.isEmpty()) {
                EmptyHub()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    FocusedPlatformCard(
                        platform = focused,
                        heroPath = null,           // unused now — kept in sig for compat
                        totalPlaytimeSec = totalPlaytime,
                        onOpen = { focused?.let(onOpenPlatform) },
                    )
                }

            }
        }
    }
}

private fun firstHeroFor(state: LibraryState, platformId: String): String? {
    val games = state.gamesByPlatform[platformId].orEmpty()
    for (g in games) {
        val art = state.artByGameId[g.id] ?: continue
        val h = art.heroPath ?: art.boxPath
        if (h != null) return h
    }
    return null
}

@Composable
private fun FocusedPlatformCard(
    platform: Platform?,
    @Suppress("UNUSED_PARAMETER") heroPath: String?,
    totalPlaytimeSec: Long,
    onOpen: () -> Unit,
) {
    val neon = LocalNeon.current
    val profile = LocalDeviceProfile.current
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.maiconerick.xgamelauncher.XGameLauncherApp
    val fullBleed by app.prefs.hubFullBleed.collectAsState(initial = false)
    val backdrops by app.prefs.heroBackdrops.collectAsState(initial = emptyMap())
    val ptExpanded by app.prefs.hubPlaytimeExpanded.collectAsState(initial = false)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var periods by remember(platform?.id) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(platform?.id, ptExpanded) {
        val p = platform ?: return@LaunchedEffect
        if (!ptExpanded) return@LaunchedEffect
        periods = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { periodPlaytime(app, p.name) }
    }
    val accent = parseAccent(platform?.accentColorHex) ?: neon.accent
    var focused by remember { mutableStateOf(false) }
    val canOpen = platform != null
    val shape = RoundedCornerShape(if (fullBleed) 0.dp else 14.dp)
    Box(
        modifier = Modifier
            .let {
                if (fullBleed) it.fillMaxSize()
                else it.fillMaxWidth(if (profile.compact) 0.97f else 0.95f)
                    .fillMaxHeight(if (profile.compact) 0.95f else 0.98f)
            }
            // Ring before the clip: it draws outside the card and must not be cut off.
            .let { if (fullBleed) it else it.focusRing(focused, shape) }
            .clip(shape)
            .background(neon.surfaceElevated)
            .let {
                // Full-bleed: no card border or focus ring — the image is the whole area.
                if (fullBleed) it else it.border(
                    width = if (focused) 3.dp else 1.5.dp,
                    color = if (focused) accent else accent.copy(alpha = 0.6f),
                    shape = shape,
                )
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = canOpen)
            .onGamepadActivate(onOpen)
            .clickable(enabled = canOpen, onClick = onOpen),
    ) {
        // Whole-card background — Crop so the image fills the card in both dimensions.
        val bg = platform?.backgroundImagePath
        val bgImage: @Composable () -> Unit = {
            if (!bg.isNullOrBlank()) AsyncImage(
                model = bg, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        bgImage()
        PlatformHeroOverlay(
            iconPath = platform?.iconImagePath,
            name = platform?.name.orEmpty(),
            accent = accent,
            games = platform?.gameCount?.toString() ?: "0",
            playtime = if (totalPlaytimeSec > 0L) TimeFormat.playtime(totalPlaytimeSec) else "—",
            backdrop = platform?.id?.let { backdrops[it] } ?: com.maiconerick.xgamelauncher.ui.components.TextBackdrop(),
            blurSource = if (bg.isNullOrBlank()) null else bgImage,
            periods = periods,
            expanded = ptExpanded,
            onPlaytimeClick = { scope.launch { app.prefs.setHubPlaytimeExpanded(!ptExpanded) } },
        )
    }
}

/** TODAY / WEEK / MONTH / YEAR playtime for a platform, from the session log. */
private suspend fun periodPlaytime(app: com.maiconerick.xgamelauncher.XGameLauncherApp, platformName: String): List<Pair<String, String>> {
    val rows = runCatching { com.maiconerick.xgamelauncher.data.AppDatabase.get(app).sessionDao().all() }.getOrDefault(emptyList())
        .filter { it.platform.equals(platformName, ignoreCase = true) }
    fun start(field: Int?): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        when (field) {
            java.util.Calendar.DAY_OF_WEEK -> { c.firstDayOfWeek = java.util.Calendar.MONDAY; c.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY) }
            java.util.Calendar.DAY_OF_MONTH -> c.set(java.util.Calendar.DAY_OF_MONTH, 1)
            java.util.Calendar.DAY_OF_YEAR -> c.set(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis / 1000
    }
    fun sum(from: Long) = rows.filter { it.startedUnix >= from }.sumOf { it.timePlayedSeconds ?: 0L }
        .let { if (it > 0) TimeFormat.playtime(it) else "—" }
    return listOf(
        "TODAY" to sum(start(null)),
        "WEEK" to sum(start(java.util.Calendar.DAY_OF_WEEK)),
        "MONTH" to sum(start(java.util.Calendar.DAY_OF_MONTH)),
        "YEAR" to sum(start(java.util.Calendar.DAY_OF_YEAR)),
    )
}

private fun parseAccent(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val h = hex.trim().removePrefix("#")
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> null
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    val neon = LocalNeon.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = neon.ink,
            fontSize = LocalDeviceProfile.current.sp(30.sp, 20.sp),
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.8).sp,
        )
        Text(
            text = label,
            color = neon.inkMuted,
            fontSize = 10.sp,
            letterSpacing = 1.8.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Divider() {
    val neon = LocalNeon.current
    Box(
        Modifier
            .width(1.dp)
            .height(38.dp)
            .background(neon.stroke),
    )
}

@Composable
private fun LrHint() {
    val neon = LocalNeon.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        LrChip("L")
        Spacer(Modifier.width(4.dp))
        Text("/", color = neon.inkMuted, fontSize = 10.sp)
        Spacer(Modifier.width(4.dp))
        LrChip("R")
        Spacer(Modifier.width(8.dp))
        Text(
            text = "SWITCH",
            color = neon.inkMuted,
            fontSize = 9.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LrChip(label: String) {
    val neon = LocalNeon.current
    Text(
        text = label,
        color = neon.accent,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier
            .background(Color(0x2200E5FF), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun EmptyHub() {
    val neon = LocalNeon.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No platforms yet",
                color = neon.inkDim,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Add one in Settings → Platforms, or install Daijishō to import.",
                color = neon.inkMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
