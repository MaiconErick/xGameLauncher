package com.maiconerick.xgamelauncher.ui.components

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.XGameLauncherApp
import com.maiconerick.xgamelauncher.ui.LocalArtFetchingCount
import com.maiconerick.xgamelauncher.ui.LocalMissingArtGameIds
import com.maiconerick.xgamelauncher.ui.LocalOnArtPicker
import com.maiconerick.xgamelauncher.ui.LocalOnSearch
import com.maiconerick.xgamelauncher.ui.LocalOnSync
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
fun TopBar(
    activeTab: TopTab,
    onTab: (TopTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fetching = LocalArtFetchingCount.current()
    val neon = LocalNeon.current
    val onSync = LocalOnSync.current
    val onSearch = LocalOnSearch.current
    val profile = com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
    Box(Modifier.background(com.maiconerick.xgamelauncher.ui.theme.LocalAppTheme.current.barColor)) {
    BarFx(Modifier.matchParentSize())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(profile.dp(48.dp, 40.dp))
            .padding(horizontal = profile.dp(24.dp, 8.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpaceInvader(height = profile.dp(20.dp, 16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (profile.compact) 2.dp else 4.dp),
            modifier = Modifier.padding(start = profile.dp(20.dp, 6.dp)).weight(1f),
        ) {
            TopTab.values().forEach { tab ->
                TabItem(
                    label = tab.label,
                    active = tab == activeTab,
                    onClick = { onTab(tab) },
                )
            }
        }
        val gap = profile.dp(8.dp, 4.dp)
        if (fetching > 0) {
            FetchingChip(count = fetching)
            Spacer(Modifier.width(gap))
        }
        // Top/bottom screen toggle only makes sense with a second display (Thor).
        if (profile.multiDisplay) {
            DisplayTargetChip()
            Spacer(Modifier.width(gap))
        }
        // "!" chip — only rendered when at least one game lacks hero or box art.
        // Clicking opens the art picker over those games.
        val missingArtIds = LocalMissingArtGameIds.current()
        val onArtPicker = LocalOnArtPicker.current
        if (missingArtIds.isNotEmpty()) {
            NeedsArtChip(count = missingArtIds.size, onClick = { onArtPicker(missingArtIds) })
            Spacer(Modifier.width(gap))
        }
        // Pause / resume every decorative animation (both screens) — handy while playing.
        val appCtx = LocalContext.current.applicationContext as XGameLauncherApp
        val barScope = rememberCoroutineScope()
        val paused = com.maiconerick.xgamelauncher.ui.AppChrome.animationsPaused
        IconChip(
            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            description = if (paused) "Resume animations" else "Pause animations",
            onClick = {
                com.maiconerick.xgamelauncher.ui.AppChrome.animationsPaused = !paused
                barScope.launch { appCtx.prefs.setToolFlag("animations_paused", !paused) }
            },
        )
        Spacer(Modifier.width(gap))
        IconChip(icon = Icons.Filled.Search, description = "Search", onClick = onSearch)
        Spacer(Modifier.width(gap))
        IconChip(icon = Icons.Filled.Refresh, description = "Sync", onClick = onSync)
        Spacer(Modifier.width(profile.dp(12.dp, 6.dp)))
        SystemChips()
    }
    }
    // Accent separator, matching the bottom bar.
    Box(Modifier.fillMaxWidth().height(1.dp).background(neon.accent.copy(alpha = 0.55f)))
    }
}

@Composable
private fun FetchingChip(count: Int) {
    val neon = LocalNeon.current
    val alpha = if (com.maiconerick.xgamelauncher.ui.animationsOn()) {
        val transition = rememberInfiniteTransition(label = "fetch-pulse")
        val a by transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "fetch-alpha",
        )
        a
    } else 1f
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(neon.accent.copy(alpha = 0.14f))
            .border(1.dp, neon.accent.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(neon.accent.copy(alpha = alpha)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact) "$count"
                   else "FETCHING ART · $count",
            color = neon.accent,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TabItem(label: String, active: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val textColor = when {
        active -> neon.accent
        focused -> neon.ink
        else -> neon.inkDim
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused && !active) Color(0x1AFFFFFF) else Color.Transparent)
            .border(
                width = if (active || focused) 1.dp else 0.dp,
                color = when {
                    active -> neon.accent
                    focused -> neon.accent.copy(alpha = 0.5f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(4.dp),
            )
            .focusRing(focused, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(
                horizontal = com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.dp(14.dp, 7.dp),
                vertical = 6.dp,
            ),
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.sp(13.sp, 12.sp),
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = 0.2.sp,
        )
    }
}

/**
 * Toggle chip showing the default launch display for the Apps drawer. Up arrow = top
 * screen; Down arrow = bottom screen. Persists via [PrefsStore.appsDefaultDisplay];
 * launches from the Apps tab route through this to pick their `setLaunchDisplayId`.
 */
@Composable
private fun DisplayTargetChip() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as XGameLauncherApp
    val scope = rememberCoroutineScope()
    val target by app.prefs.appsDefaultDisplay.collectAsState(initial = "top")
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) neon.accent.copy(alpha = 0.24f) else Color(0x22FFFFFF))
            .border(
                width = 1.dp,
                color = if (focused) neon.accent else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .focusRing(focused, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate {
                scope.launch { app.prefs.setAppsDefaultDisplay(if (target == "top") "bottom" else "top") }
            }
            .clickable {
                scope.launch { app.prefs.setAppsDefaultDisplay(if (target == "top") "bottom" else "top") }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (target == "top") Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
            contentDescription = "Launch on ${if (target == "top") "top" else "bottom"} screen",
            tint = neon.accent,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun NeedsArtChip(count: Int, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val warn = Color(0xFFFFC400)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) warn.copy(alpha = 0.32f) else warn.copy(alpha = 0.16f))
            .border(1.dp, warn, RoundedCornerShape(6.dp))
            .focusRing(focused, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "!",
            color = warn,
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact) "$count"
                   else "ART · $count",
            color = warn,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun IconChip(icon: ImageVector, description: String, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) neon.accent.copy(alpha = 0.24f) else Color(0x22FFFFFF))
            .border(
                width = 1.dp,
                color = if (focused) neon.accent else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .focusRing(focused, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (focused) neon.accent else neon.inkDim,
            modifier = Modifier.size(16.dp),
        )
    }
}

enum class TopTab(val label: String) {
    Home("Home"),
    Apps("Apps"),
    Tools("Tools"),
    Settings("Settings"),
}
