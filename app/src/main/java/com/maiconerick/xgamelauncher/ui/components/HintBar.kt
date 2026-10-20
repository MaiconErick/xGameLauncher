package com.maiconerick.xgamelauncher.ui.components

import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon

/** One button hint. `buttons` are drawn as glyphs side by side ("L","R" → two bumpers). */
data class ButtonHint(val buttons: List<String>, val label: String) {
    constructor(button: String, label: String) : this(button.split('/'), label)
}

/** A platform entry in the bottom strip. `iconPath` = bar icon (or platform icon). */
data class StripPlatform(val id: String, val name: String, val iconPath: String?, val miniIconPath: String? = null)

/**
 * Bottom bar on every screen: the platform strip (text or icons, horizontally scrollable,
 * focusable) and — unless hidden in Settings — clickable controller hints on the right.
 * Tapping a hint presses that button, so it behaves exactly like the physical one.
 */
@Composable
fun BottomBar(
    platforms: List<StripPlatform>,
    activeId: String?,
    useIcons: Boolean,
    focusActiveOnEnter: Boolean,
    /** Icon height (dp) for bar icons and mini icons. */
    iconDp: Float = 24f,
    miniIcons: Boolean = false,
    onFocusPlatform: (String) -> Unit,
    onClickPlatform: (String) -> Unit,
    hints: List<ButtonHint>,
    showHints: Boolean,
    onPressButton: (String) -> Unit,
) {
    val neon = LocalNeon.current
    val compact = LocalDeviceProfile.current.compact
    val requesters = remember(platforms) { platforms.associate { it.id to androidx.compose.ui.focus.FocusRequester() } }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(focusActiveOnEnter, activeId, platforms.size) {
        val idx = platforms.indexOfFirst { it.id == activeId }
        if (idx >= 0) {
            runCatching { listState.scrollToItem((idx - 1).coerceAtLeast(0)) }
            if (focusActiveOnEnter) { kotlinx.coroutines.delay(30); runCatching { requesters[activeId]?.requestFocus() } }
        }
    }
    Column(Modifier.fillMaxWidth().background(com.maiconerick.xgamelauncher.ui.theme.LocalAppTheme.current.barColor)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(neon.accent.copy(alpha = 0.55f)))
        Box {
        BarFx(Modifier.matchParentSize())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(maxOf(if (compact) 36.dp else 46.dp,
                    (if (useIcons || miniIcons) iconDp.dp else 0.dp) + if (compact) 12.dp else 16.dp))
                .padding(start = if (compact) 0.dp else 4.dp, end = if (compact) 6.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.lazy.LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                // Room for the focus ring: LazyRow clips its items, so the first/last item's
                // ring would be cut off at the edges without this.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(platforms.size, key = { platforms[it].id }) { i ->
                    val p = platforms[i]
                    StripItem(
                        p = p, active = p.id == activeId, useIcons = useIcons, compact = compact,
                        iconDp = iconDp, miniIcons = miniIcons,
                        focusRequester = requesters.getValue(p.id),
                        onFocused = { onFocusPlatform(p.id) },
                        onClick = { onClickPlatform(p.id) },
                    )
                }
            }
            if (showHints) Row(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                hints.forEach { h -> Hint(h, compact, onPressButton) }
            }
        }
        }
    }
}

@Composable
private fun StripItem(
    p: StripPlatform,
    active: Boolean,
    useIcons: Boolean,
    compact: Boolean,
    iconDp: Float,
    miniIcons: Boolean,
    focusRequester: androidx.compose.ui.focus.FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val neon = LocalNeon.current
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        modifier = Modifier
            // No focus ring here: focus shows as brighter text / full-opacity icon.
            .clip(RoundedCornerShape(5.dp))
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 7.dp else 10.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (useIcons && !p.iconPath.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = p.iconPath, contentDescription = p.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.height(iconDp.dp).widthIn(max = (iconDp * 4.5f).dp),
                alpha = if (active || focused) 1f else 0.6f,
            )
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            if (miniIcons && !p.miniIconPath.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = p.miniIconPath, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.size(iconDp.dp),
                    alpha = if (active || focused) 1f else 0.6f,
                )
            } else Text(
                p.name, maxLines = 1,
                color = when { active -> neon.accent; focused -> neon.ink; else -> neon.inkDim },
                fontSize = if (compact) 12.sp else 14.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(Modifier.width(16.dp).height(2.dp).clip(RoundedCornerShape(1.dp))
            .background(if (active) neon.accent else Color.Transparent))
    }
}

@Composable
private fun Hint(h: ButtonHint, compact: Boolean, onPress: (String) -> Unit) {
    val neon = LocalNeon.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onPress(h.buttons.first()) }
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        h.buttons.forEachIndexed { i, b ->
            if (i > 0) Spacer(Modifier.width(3.dp))
            Box(Modifier.clip(RoundedCornerShape(4.dp)).clickable { onPress(b) }) {
                ButtonGlyph(b, if (compact) 15.dp else 20.dp)
            }
        }
        Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
        Text(h.label, color = neon.ink, fontSize = if (compact) 10.sp else 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Controller button glyph: round face buttons, bumper L/R, trigger ZL/ZR, pill +/−. */
@Composable
fun ButtonGlyph(button: String, height: Dp) {
    val face = Color(0xFFE6E6E6)
    val (width, shape: Shape) = when (button.uppercase()) {
        "L", "R", "L1", "R1" -> height * 1.55f to RoundedCornerShape(
            topStart = height * 0.5f, topEnd = height * 0.5f, bottomStart = height * 0.18f, bottomEnd = height * 0.18f,
        )
        "ZL", "ZR", "L2", "R2" -> height * 1.55f to RoundedCornerShape(
            topStart = height * 0.18f, topEnd = height * 0.18f, bottomStart = height * 0.5f, bottomEnd = height * 0.5f,
        )
        "+", "-", "START", "SELECT" -> height * 1.3f to RoundedCornerShape(height * 0.5f)
        else -> height to CircleShape
    }
    Box(
        Modifier.size(width = width, height = height).background(face, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            button.uppercase().take(2),
            color = Color.Black,
            fontSize = (height.value * 0.5f).sp,
            fontWeight = FontWeight.Black,
            lineHeight = (height.value * 0.5f).sp,
        )
    }
}
