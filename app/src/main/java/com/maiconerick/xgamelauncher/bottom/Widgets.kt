package com.maiconerick.xgamelauncher.bottom

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maiconerick.xgamelauncher.data.AppDatabase
import com.maiconerick.xgamelauncher.data.SessionRow
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.components.SpaceInvader
import com.maiconerick.xgamelauncher.ui.components.focusRing
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Neon card shell shared by every built-in widget (focusable, A = [onClick]). */
@Composable
fun WidgetCard(
    title: String? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .focusRing(focused, shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xE60A1016), Color(0xE6050709))))
            .border(1.dp, neon.accent.copy(alpha = if (focused) 0.9f else 0.35f), shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .let { m -> if (onClick != null) m.onGamepadActivate(onClick).clickable(onClick = onClick) else m }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (title != null) Text(
            title.uppercase(), color = neon.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp,
        )
        content()
    }
}

private fun hms(secs: Long): String {
    val s = secs.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

private fun hm(secs: Long): String {
    val s = secs.coerceAtLeast(0)
    return if (s >= 3600) "%dh %02dm".format(s / 3600, (s % 3600) / 60) else "%dm".format(s / 60)
}

private fun ago(unix: Long): String {
    val d = System.currentTimeMillis() / 1000 - unix
    return when {
        d < 3600 -> "${d / 60} min ago"
        d < 86_400 -> "${d / 3600} h ago"
        d < 7 * 86_400 -> "${d / 86_400} days ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(unix * 1000))
    }
}

// ---------------------------------------------------------------- clock

@Composable
fun ClockWidget() {
    val neon = LocalNeon.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    WidgetCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), color = neon.ink,
                fontSize = 46.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(SimpleDateFormat(":ss", Locale.getDefault()).format(now), color = neon.accent,
                fontSize = 20.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            SpaceInvader(height = 26.dp)
        }
        Text(SimpleDateFormat("EEEE · MMMM d", Locale.getDefault()).format(now), color = neon.inkDim, fontSize = 14.sp)
    }
}

// ---------------------------------------------------------------- live session

/** What's playing right now: session time ticking every second + all-time total. */
@Composable
fun LiveSessionWidget() {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<SessionRow?>(null) }
    var pastSecs by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            if (tick % 5 == 0) withContext(Dispatchers.IO) {
                val db = AppDatabase.get(ctx).sessionDao()
                val o = db.openSessions().maxByOrNull { it.startedUnix }
                open = o
                pastSecs = if (o == null) 0 else db.all()
                    .filter { !it.isOpen && it.gameName.equals(o.gameName, true) && it.platform.equals(o.platform, true) }
                    .sumOf { it.timePlayedSeconds ?: 0L }
            }
            now = System.currentTimeMillis() / 1000
            tick++
            delay(1000)
        }
    }
    val o = open
    WidgetCard(title = if (o != null) "● Now playing" else "Now playing") {
        if (o == null) {
            Text("Nothing running", color = neon.inkDim, fontSize = 15.sp)
        } else {
            val session = now - o.startedUnix
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                o.artPath?.let {
                    AsyncImage(it, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 54.dp, height = 72.dp).clip(RoundedCornerShape(6.dp)))
                }
                Column(Modifier.weight(1f)) {
                    Text(o.gameName, color = neon.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${o.platform} · ${o.emulator}", color = neon.inkDim, fontSize = 12.sp, maxLines = 1)
                    Text(hms(session), color = neon.accent, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("All time ${hm(pastSecs + session)}", color = neon.inkDim, fontSize = 12.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- last played

@Composable
fun LastPlayedWidget(onPlay: (SessionRow) -> Unit) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    var last by remember { mutableStateOf<SessionRow?>(null) }
    var total by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                val all = AppDatabase.get(ctx).sessionDao().all()
                val l = all.firstOrNull { !it.isOpen }
                // Older rows were saved without art: find it by the game's name.
                last = if (l != null && l.artPath.isNullOrBlank()) l.copy(artPath = artForName(ctx, l.gameName)) else l
                total = if (l == null) 0 else all.filter { it.gameName.equals(l.gameName, true) && it.platform.equals(l.platform, true) }
                    .sumOf { it.timePlayedSeconds ?: 0L }
            }
            delay(30_000)
        }
    }
    val l = last
    WidgetCard(title = "Last played", onClick = l?.let { { onPlay(it) } }) {
        if (l == null) Text("No sessions yet", color = neon.inkDim, fontSize = 15.sp)
        else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 54.dp, height = 72.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x22FFFFFF))) {
                l.artPath?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Column(Modifier.weight(1f)) {
                Text(l.gameName, color = neon.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("${l.platform} · ${ago(l.endedUnix ?: l.startedUnix)}", color = neon.inkDim, fontSize = 12.sp)
                Text("${hm(total)} in total", color = neon.inkDim, fontSize = 12.sp)
            }
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(neon.accent).padding(horizontal = 14.dp, vertical = 10.dp),
            ) { Text("▶ PLAY AGAIN", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black) }
        }
    }
}

/** Art for a game known only by name: its library entry's override, else fetched art. */
internal suspend fun artForName(ctx: android.content.Context, name: String): String? = runCatching {
    val db = AppDatabase.get(ctx)
    val ids = db.romDao().all().filter { it.name.equals(name, true) }.map { it.id } +
        db.androidGameDao().all().filter { it.label.equals(name, true) }.map { it.pkg }
    val arts = db.artDao().all()
    ids.firstNotNullOfOrNull { id ->
        val o = db.gameOverrideDao().get(id)
        val a = arts.firstOrNull { it.gameId == id }
        o?.boxArtOverride?.ifBlank { null } ?: a?.boxPath ?: o?.heroArtOverride?.ifBlank { null } ?: a?.heroPath
    } ?: arts.firstOrNull { it.sourceName?.equals(name, true) == true }?.let { it.boxPath ?: it.heroPath }
}.getOrNull()

// ---------------------------------------------------------------- playtime

private fun periodStart(period: String): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    when (period) {
        "month" -> c.set(Calendar.DAY_OF_MONTH, 1)
        "year" -> c.set(Calendar.DAY_OF_YEAR, 1)
        else -> { c.firstDayOfWeek = Calendar.MONDAY; c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }
    }
    return c.timeInMillis / 1000
}

/** Total playtime this week / month / year + top games. A cycles the period. */
@Composable
fun PlaytimeWidget(period: String, onCycle: () -> Unit) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    var total by remember { mutableLongStateOf(0L) }
    var top by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    LaunchedEffect(period) {
        while (true) {
            withContext(Dispatchers.IO) {
                val from = periodStart(period)
                val now = System.currentTimeMillis() / 1000
                val rows = AppDatabase.get(ctx).sessionDao().all().filter { it.startedUnix >= from }
                val secs = rows.associateWith { r -> if (r.isOpen) now - r.startedUnix else (r.timePlayedSeconds ?: 0L) }
                total = secs.values.sum()
                top = rows.groupBy { it.gameName }.mapValues { (_, l) -> l.sumOf { secs[it] ?: 0L } }
                    .entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
            }
            delay(60_000)
        }
    }
    val label = when (period) { "month" -> "This month"; "year" -> "This year"; else -> "This week" }
    WidgetCard(title = "Playtime · $label  (A: change)", onClick = onCycle) {
        Text(hm(total), color = neon.ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        val max = top.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        top.forEach { (name, s) ->
            Column {
                Row {
                    Text(name, color = neon.inkDim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(hm(s), color = neon.inkDim, fontSize = 12.sp)
                }
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x22FFFFFF))) {
                    Box(Modifier.fillMaxWidth(s.toFloat() / max).height(4.dp).background(neon.accent))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- system stats

/** Latest vitals, sampled once for every stat card on the bottom home. */
val LocalSysSnap = androidx.compose.runtime.compositionLocalOf<SysSnapshot?> { null }

/** One tiny stat card: big value, label, optional bar (0..1). */
@Composable
fun StatCard(label: String, value: String, sub: String? = null, bar: Float? = null, modifier: Modifier = Modifier) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .focusRing(focused, shape).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xE60A1016), Color(0xE6050709))))
            .border(1.dp, neon.accent.copy(alpha = if (focused) 0.9f else 0.35f), shape)
            .onFocusChanged { focused = it.isFocused }.focusable()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = neon.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text(value, color = neon.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
        if (sub != null) Text(sub, color = neon.inkDim, fontSize = 10.sp, maxLines = 1)
        if (bar != null) Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x22FFFFFF))) {
            Box(Modifier.fillMaxWidth(bar.coerceIn(0f, 1f)).height(3.dp).background(neon.accent))
        }
    }
}

@Composable
fun StatWidget(type: String, modifier: Modifier = Modifier) {
    val s = LocalSysSnap.current
    fun pct(v: Int?) = v?.let { "$it%" } ?: "—"
    when (type) {
        BuiltinWidgets.STAT_FPS -> StatCard("FPS", s?.fps?.toString() ?: "—", s?.let { if (it.drops > 0) "${it.drops} dropped" else "smooth" }, modifier = modifier)
        BuiltinWidgets.STAT_CPU -> StatCard("CPU", pct(s?.cpuPct), "busiest core", s?.cpuPct?.div(100f), modifier)
        BuiltinWidgets.STAT_GPU -> StatCard("GPU", pct(s?.gpuPct), null, s?.gpuPct?.div(100f), modifier)
        BuiltinWidgets.STAT_RAM -> StatCard("RAM", s?.let { "%.1fG".format(it.ramUsedMb / 1024f) } ?: "—",
            s?.let { "of %.1f GB".format(it.ramTotalMb / 1024f) }, s?.let { it.ramUsedMb.toFloat() / it.ramTotalMb.coerceAtLeast(1) }, modifier)
        BuiltinWidgets.STAT_TEMP -> StatCard("TEMP", s?.tempC?.let { "$it°C" } ?: "—", "SoC", s?.tempC?.let { it / 100f }, modifier)
        BuiltinWidgets.STAT_BATTERY -> StatCard("BATTERY", pct(s?.batteryPct) + if (s?.charging == true) "⚡" else "",
            when { s == null -> null; s.charging -> "charging"; s.minutesLeft != null -> "%dh %02dm left".format(s.minutesLeft / 60, s.minutesLeft % 60); else -> null },
            s?.batteryPct?.div(100f), modifier)
        BuiltinWidgets.STAT_POWER -> StatCard("POWER", s?.amps?.let { "%.2fA".format(it) } ?: "—", if (s?.charging == true) "charging" else "draw", modifier = modifier)
    }
}

// ---------------------------------------------------------------- decorative

@Composable
fun SeparatorWidget() {
    val neon = LocalNeon.current
    val animate = com.maiconerick.xgamelauncher.ui.animationsOn()
    val p = if (animate) {
        val t = rememberInfiniteTransition(label = "sep")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "sep-p")
        v
    } else 0.5f
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val y = size.height / 2
        drawLine(neon.accent.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
        val x = p * size.width
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, neon.accent, Color.White, neon.accent, Color.Transparent),
                startX = x - 120f, endX = x + 120f),
            Offset(x - 120f, y), Offset(x + 120f, y), strokeWidth = 3.dp.toPx(),
        )
        // pixel end caps
        drawRect(neon.accent, Offset(0f, y - 4.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx()))
        drawRect(neon.accent, Offset(size.width - 8.dp.toPx(), y - 4.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx()))
    }
}

/** A row of invaders marching side to side. */
@Composable
fun InvadersWidget() {
    val animate = com.maiconerick.xgamelauncher.ui.animationsOn()
    val p = if (animate) {
        val t = rememberInfiniteTransition(label = "inv")
        val v by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse), label = "inv-p")
        v
    } else 0f
    val neon = LocalNeon.current
    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.padding(start = (40 + 40 * p).dp),
        ) {
            listOf(neon.accent, Color.White, neon.accent, Color.White, neon.accent).forEach { c ->
                SpaceInvader(height = 22.dp, color = c)
            }
        }
    }
    Spacer(Modifier.width(1.dp))
}
