package com.maiconerick.xgamelauncher.ui.screens

import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import com.maiconerick.xgamelauncher.ui.components.focusRing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.data.PlaytimeStats
import com.maiconerick.xgamelauncher.ui.LibraryState
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.components.LaunchButton
import com.maiconerick.xgamelauncher.ui.components.PlaceholderArt
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import com.maiconerick.xgamelauncher.util.TimeFormat

@Composable
fun GameDetailScreen(
    platform: Platform,
    game: GameEntry,
    state: LibraryState,
    playtime: (String, String) -> PlaytimeStats?,
    onEnsureArt: (Platform, GameEntry) -> Unit,
    onLaunch: () -> Unit,
    onEndSession: () -> Unit,
    onBack: () -> Unit,
    onEditGame: () -> Unit,
    /** Players available for this game (Windows variants or platform players). */
    players: List<com.maiconerick.xgamelauncher.ui.PickerOption> = emptyList(),
    currentPlayerId: String? = null,
    /** Launch with a specific player (also becomes the new default). */
    onLaunchWith: (String) -> Unit = {},
    /** IGDB info (cached per game). */
    loadInfo: suspend () -> com.maiconerick.xgamelauncher.art.GameInfo? = { null },
    /** Closed sessions for this game, oldest first. */
    loadSessions: suspend () -> List<com.maiconerick.xgamelauncher.data.SessionRow> = { emptyList() },
    /** Opens the playtime editor filtered to this game. */
    onEditSessions: () -> Unit = {},
) {
    val neon = LocalNeon.current
    val stats = playtime(game.name, platform.name)
    val override = state.overrideByGameId[game.id]
    val heroFromArt = state.artByGameId[game.id]?.heroPath ?: state.artByGameId[game.id]?.boxPath
    val heroPath = override?.heroArtOverride?.takeIf { it.isNotBlank() } ?: heroFromArt

    LaunchedEffect(game.id) { onEnsureArt(platform, game) }
    val profile = LocalDeviceProfile.current

    var info by remember(game.id) { mutableStateOf<com.maiconerick.xgamelauncher.art.GameInfo?>(null) }
    var infoLoading by remember(game.id) { mutableStateOf(true) }
    var sessions by remember(game.id) { mutableStateOf<List<com.maiconerick.xgamelauncher.data.SessionRow>>(emptyList()) }
    LaunchedEffect(game.id) { sessions = loadSessions() }
    LaunchedEffect(game.id) { info = loadInfo(); infoLoading = false }

    // One screen, no scrolling: the art fills the whole screen behind everything, the
    // platform bar is hidden, and About + the playtime chart open in a side panel (DETAILS).
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.maiconerick.xgamelauncher.ui.AppChrome.hideBottomBar = true
        onDispose { com.maiconerick.xgamelauncher.ui.AppChrome.hideBottomBar = false }
    }
    var showMore by remember(game.id) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showMore) { showMore = false }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(neon.surface)) {
        val bandH = maxHeight * (if (profile.compact) 0.42f else 0.32f)
        if (heroPath != null) {
            AsyncImage(
                model = heroPath, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PlaceholderArt(title = game.name, labelSize = 32)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color(0xB3000000), 0.55f to Color(0x55000000), 1f to Color.Transparent),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x55000000), 0.45f to Color(0x11000000), 0.7f to Color(0x99000000), 1f to Color(0xF2000000)),
            ),
        )
        BackChip(onBack = onBack)
        EditIconChip(onClick = onEditGame)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                HeroTitle(platform = platform, game = game)
            }
            Box(Modifier.fillMaxWidth().height(bandH)) {
                val sessionKey = com.maiconerick.xgamelauncher.playtime.PlaytimeReader
                    .keyFor(game.name, platform.name)
                val isSessionOpen = sessionKey in state.openSessionsKey
                val picker = com.maiconerick.xgamelauncher.ui.LocalPicker.current
                val playerChip: (@Composable () -> Unit)? = if (players.size > 1) ({
                    PlayerChip(
                        label = players.firstOrNull { it.id == currentPlayerId }?.label ?: players.first().label,
                        onClick = {
                            picker(
                                com.maiconerick.xgamelauncher.ui.PickerRequest(
                                    title = "Play with…",
                                    options = players.map {
                                        if (it.id == currentPlayerId) it.copy(subtitle = listOfNotNull("last used", it.subtitle).joinToString(" · ")) else it
                                    },
                                    onPick = { id -> picker(null); onLaunchWith(id) },
                                    onCancel = { picker(null) },
                                )
                            )
                        },
                    )
                }) else null
                val extras: @Composable () -> Unit = {
                    playerChip?.invoke()
                    DetailsChip(open = showMore, onClick = { showMore = !showMore })
                }
                MetaBand(
                    stats = stats,
                    platform = platform,
                    game = game,
                    playerChip = extras,
                    onLaunch = onLaunch,
                    onEdit = onEditGame,
                    isSessionOpen = isSessionOpen,
                    onEndSession = onEndSession,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // DETAILS side panel: IGDB info + playtime chart, scrollable on its own.
        androidx.compose.animation.AnimatedVisibility(
            visible = showMore,
            enter = androidx.compose.animation.slideInHorizontally { it } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally { it } + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                Modifier.fillMaxHeight().fillMaxWidth(if (profile.compact) 0.9f else 0.46f)
                    .background(Color(0xEB05070A))
                    .border(1.dp, neon.accent.copy(alpha = 0.4f))
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
            ) {
                AboutCard(info = info, loading = infoLoading)
                PlaytimeChartCard(sessions = sessions, onEdit = onEditSessions)
            }
        }
    }
}

/** Opens / closes the DETAILS side panel. */
@Composable
private fun DetailsChip(open: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.focusRing(focused, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))
            .background(if (open || focused) neon.accent.copy(alpha = 0.24f) else Color(0x22FFFFFF))
            .border(1.dp, if (open) neon.accent else Color.Transparent, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }.focusable()
            .onGamepadActivate(onClick).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(if (open) "HIDE DETAILS" else "DETAILS", color = if (open) neon.accent else neon.ink,
            fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    }
}

/** IGDB facts + summary. Focusable so the D-pad can scroll down to it. */
@Composable
private fun AboutCard(info: com.maiconerick.xgamelauncher.art.GameInfo?, loading: Boolean) {
    val neon = LocalNeon.current
    val compact = LocalDeviceProfile.current.compact
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 12.dp else 48.dp, vertical = 8.dp)
            .focusRing(focused, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x14FFFFFF))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(if (compact) 12.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("ABOUT", color = neon.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        when {
            info == null && loading -> Text("Looking this game up on IGDB…", color = neon.inkDim, fontSize = 13.sp)
            info == null -> Text("No IGDB data (add IGDB keys in Settings → Art providers, or the name didn't match).",
                color = neon.inkDim, fontSize = 13.sp)
            else -> {
                val facts = buildList {
                    info.releaseUnix?.let {
                        add("Released " + java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
                            .format(java.util.Date(it * 1000)))
                    }
                    if (info.genres.isNotEmpty()) add(info.genres.take(3).joinToString(", "))
                    if (info.developers.isNotEmpty()) add("by " + info.developers.take(2).joinToString(", "))
                    info.rating?.let { add("★ %.0f".format(it)) }
                }
                if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), color = neon.ink, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
                info.summary?.let { Text(it, color = neon.inkDim, fontSize = 13.sp, lineHeight = 19.sp) }
            }
        }
    }
}

/**
 * Playtime since the first session: one bar per day (per week for long histories), so the
 * gaps between sessions are visible at a glance.
 */
@Composable
private fun PlaytimeChartCard(sessions: List<com.maiconerick.xgamelauncher.data.SessionRow>, onEdit: () -> Unit) {
    val neon = LocalNeon.current
    val compact = LocalDeviceProfile.current.compact
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 12.dp else 48.dp, vertical = 8.dp)
            .focusRing(focused, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x14FFFFFF))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onEdit)
            .clickable(onClick = onEdit)
            .padding(if (compact) 12.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PLAYTIME HISTORY", color = neon.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp, modifier = Modifier.weight(1f))
            if (focused) Text("A · EDIT SESSIONS", color = neon.inkDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        if (sessions.isEmpty()) {
            Text("No sessions recorded by the tracker yet.", color = neon.inkDim, fontSize = 13.sp)
            return@Column
        }
        val day = 86_400L
        val zone = java.util.TimeZone.getDefault()
        fun dayIndex(unix: Long) = (unix + zone.getOffset(unix * 1000) / 1000) / day
        val first = dayIndex(sessions.first().startedUnix)
        val last = maxOf(dayIndex(System.currentTimeMillis() / 1000), dayIndex(sessions.last().startedUnix))
        val spanDays = (last - first + 1).toInt()
        val bucketDays = if (spanDays > 150) 7 else 1
        val buckets = FloatArray((spanDays + bucketDays - 1) / bucketDays)
        sessions.forEach { s ->
            val b = ((dayIndex(s.startedUnix) - first) / bucketDays).toInt().coerceIn(0, buckets.size - 1)
            buckets[b] += (s.timePlayedSeconds ?: 0L) / 60f
        }
        val maxMin = buckets.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        // Longest gap between consecutive play days.
        val playDays = sessions.map { dayIndex(it.startedUnix) }.distinct().sorted()
        val longestGap = playDays.zipWithNext { a, b -> b - a - 1 }.maxOrNull() ?: 0
        val fmt = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
        Text(
            "${sessions.size} sessions · first played ${fmt.format(java.util.Date(sessions.first().startedUnix * 1000))}" +
                (if (longestGap > 0) " · longest break $longestGap days" else ""),
            color = neon.ink, fontSize = 13.sp,
        )
        val accent = neon.accent
        val grid = neon.stroke
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(if (compact) 90.dp else 130.dp)) {
            val w = size.width
            val h = size.height
            drawLine(grid, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(w, h), 1.dp.toPx())
            val slot = w / buckets.size
            val barW = (slot * 0.7f).coerceAtLeast(1.5f)
            buckets.forEachIndexed { i, v ->
                if (v <= 0f) return@forEachIndexed
                val bh = (v / maxMin) * (h - 4.dp.toPx())
                drawRect(
                    accent,
                    topLeft = androidx.compose.ui.geometry.Offset(i * slot + (slot - barW) / 2f, h - bh),
                    size = androidx.compose.ui.geometry.Size(barW, bh),
                )
            }
        }
        Row {
            Text(fmt.format(java.util.Date(sessions.first().startedUnix * 1000)), color = neon.inkDim, fontSize = 11.sp,
                modifier = Modifier.weight(1f))
            Text("peak ${maxMin.toInt()} min/${if (bucketDays == 7) "week" else "day"}", color = neon.inkDim, fontSize = 11.sp,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("today", color = neon.inkDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun BoxScope.BackChip(onBack: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(LocalDeviceProfile.current.dp(24.dp, 10.dp))
            .clip(RoundedCornerShape(3.dp))
            .background(if (focused) neon.accent.copy(alpha = 0.2f) else Color(0x99000000))
            .border(1.dp, if (focused) neon.accent else Color.Transparent, RoundedCornerShape(3.dp))
            .focusRing(focused, RoundedCornerShape(3.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onBack)
            .clickable(onClick = onBack)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back",
            tint = neon.ink, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("BACK", color = neon.ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun BoxScope.HeroTitle(platform: Platform, game: GameEntry) {
    val neon = LocalNeon.current
    val profile = LocalDeviceProfile.current
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(
                start = profile.dp(48.dp, 14.dp), end = profile.dp(48.dp, 14.dp),
                bottom = profile.dp(32.dp, 8.dp),
            )
            .fillMaxWidth(if (profile.compact) 1f else 0.72f),
        verticalArrangement = Arrangement.spacedBy(profile.dp(10.dp, 6.dp)),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x2200E5FF))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(text = platform.name.uppercase(), color = neon.accent, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        }
        Text(text = game.name, color = neon.ink,
            fontSize = profile.sp(68.sp, 26.sp), lineHeight = profile.sp(66.sp, 28.sp),
            fontWeight = FontWeight.Bold,
            letterSpacing = if (profile.compact) (-0.6).sp else (-2).sp, maxLines = 2)
    }
}

@Composable
private fun MetaBand(
    stats: PlaytimeStats?,
    platform: Platform,
    game: GameEntry,
    onLaunch: () -> Unit,
    onEdit: () -> Unit,
    isSessionOpen: Boolean = false,
    onEndSession: () -> Unit = {},
    modifier: Modifier = Modifier,
    playerChip: (@Composable () -> Unit)? = null,
) {
    val neon = LocalNeon.current
    if (LocalDeviceProfile.current.compact) {
        CompactMetaBand(stats, game, onLaunch, onEdit, isSessionOpen, onEndSession, modifier, playerChip)
        return
    }
    Row(
        modifier = modifier
            .padding(horizontal = 48.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TOTAL PLAYTIME", color = neon.inkMuted, fontSize = 10.sp,
                letterSpacing = 1.8.sp, fontWeight = FontWeight.Medium)
            Text(
                text = TimeFormat.playtime(stats?.totalSeconds ?: 0L),
                color = neon.accent, fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp,
            )
            Text(
                text = if (stats != null)
                    "${stats.sessionCount} sessions · last ${TimeFormat.lastPlayed(stats.lastPlayedUnix)}"
                else "Never played yet",
                color = neon.inkDim, fontSize = 12.sp,
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
            modifier = Modifier.width(400.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LaunchButton(
                    label = "PLAY",
                    onClick = onLaunch,
                    initiallyFocused = true,
                )
                playerChip?.invoke()
            }
            if (isSessionOpen) {
                EndSessionButton(onClick = onEndSession)
            }
            Text(text = game.fileName, color = neon.inkMuted, fontSize = 10.sp,
                letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Stacked variant for small 4:3 screens: playtime line on top, buttons below. */
@Composable
private fun CompactMetaBand(
    stats: PlaytimeStats?,
    game: GameEntry,
    onLaunch: () -> Unit,
    onEdit: () -> Unit,
    isSessionOpen: Boolean,
    onEndSession: () -> Unit,
    modifier: Modifier,
    playerChip: (@Composable () -> Unit)? = null,
) {
    val neon = LocalNeon.current
    Column(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = TimeFormat.playtime(stats?.totalSeconds ?: 0L),
                color = neon.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (stats != null)
                    "${stats.sessionCount} sessions · ${TimeFormat.lastPlayed(stats.lastPlayedUnix)}"
                else "Never played yet",
                color = neon.inkDim, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            LaunchButton(
                label = "PLAY",
                onClick = onLaunch,
                initiallyFocused = true,
            )
            playerChip?.invoke()
            if (isSessionOpen) EndSessionButton(onClick = onEndSession)
        }
        Text(text = game.fileName, color = neon.inkMuted, fontSize = 9.sp,
            letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun EndSessionButton(onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) Color(0x33FF5555) else Color(0x22FF5555))
            .border(1.dp, if (focused) Color(0xFFFF5555) else Color(0x66FF5555),
                RoundedCornerShape(4.dp))
            .focusRing(focused, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFFF5555)),
        )
        Spacer(Modifier.width(8.dp))
        Text("END SESSION · LIVE", color = Color(0xFFFF9E9E), fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
    }
}

/** "Which player" chooser next to Play: shows the current player, A opens the list. */
@Composable
private fun PlayerChip(label: String, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .focusRing(focused, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) Color(0x44FFFFFF) else Color(0x22FFFFFF))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = neon.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.widthIn(max = 180.dp))
        Spacer(Modifier.width(6.dp))
        Text("▾", color = neon.inkMuted, fontSize = 12.sp)
    }
}

/** Edit (pencil) icon chip in the hero's top-right corner. */
@Composable
private fun BoxScope.EditIconChip(onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(LocalDeviceProfile.current.dp(24.dp, 10.dp))
            .size(36.dp)
            .focusRing(focused, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) neon.accent.copy(alpha = 0.26f) else Color(0x99000000))
            .border(1.dp, neon.accent.copy(alpha = if (focused) 1f else 0.45f), RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = androidx.compose.material.icons.Icons.Filled.Edit, contentDescription = "Edit game",
            tint = neon.accent, modifier = Modifier.size(18.dp))
    }
}
