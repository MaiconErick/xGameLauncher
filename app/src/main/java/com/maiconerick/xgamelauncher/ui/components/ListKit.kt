package com.maiconerick.xgamelauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon

/*
 * Daijishō-style list pages: a header (icon + title), a divider, then full-width rows with
 * an icon column, a title and a muted subtitle. Used by Settings and the editors instead
 * of side-rail tabs — one column reads better with a gamepad and fits the RG353M.
 */

@Composable
fun PageHeader(icon: ImageVector, title: String, trailing: @Composable () -> Unit = {}) {
    val neon = LocalNeon.current
    val compact = LocalDeviceProfile.current.compact
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 12.dp else 28.dp, vertical = if (compact) 8.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = neon.ink, modifier = Modifier.size(if (compact) 22.dp else 30.dp))
            Spacer(Modifier.width(if (compact) 10.dp else 16.dp))
            val display = com.maiconerick.xgamelauncher.ui.theme.LocalAppTheme.current.displayFont
            Text(title, color = neon.ink, fontSize = (if (compact) 18.sp else 26.sp) * (if (display != null) 1.15f else 1f),
                fontWeight = if (display != null) FontWeight.Normal else FontWeight.SemiBold, fontFamily = display,
                letterSpacing = if (display != null) 1.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                maxLines = 1, modifier = Modifier.weight(1f))
            trailing()
        }
        ListDivider()
    }
}

@Composable
fun ListDivider() {
    val compact = LocalDeviceProfile.current.compact
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 8.dp else 16.dp)
            .height(1.dp)
            .background(Color(0x26FFFFFF)),
    )
}

/** Accent group label, aligned with the row titles (like Daijishō's "All settings"). */
@Composable
fun ListSectionLabel(text: String) {
    val neon = LocalNeon.current
    val compact = LocalDeviceProfile.current.compact
    Text(
        text, color = neon.accent, fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(
            start = if (compact) 52.dp else 84.dp, top = if (compact) 10.dp else 18.dp, bottom = 4.dp,
        ),
    )
}

@Composable
fun ListRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    selected: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val neon = LocalNeon.current
    val compact = LocalDeviceProfile.current.compact
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 4.dp else 10.dp, vertical = 1.dp)
            .focusRing(focused, shape)
            .clip(shape)
            .background(if (focused) Color(0x1FFFFFFF) else Color.Transparent)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(vertical = if (compact) 8.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(if (compact) 48.dp else 74.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(
                icon, contentDescription = null,
                tint = if (selected) neon.accent else neon.inkDim,
                modifier = Modifier.size(if (compact) 22.dp else 30.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title, color = if (selected) neon.accent else neon.ink,
                fontSize = if (compact) 15.sp else 19.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
            if (!subtitle.isNullOrBlank()) Text(
                subtitle, color = neon.inkMuted, fontSize = if (compact) 11.sp else 14.sp,
                lineHeight = if (compact) 14.sp else 18.sp, maxLines = 2,
            )
        }
        trailing()
        Spacer(Modifier.width(if (compact) 8.dp else 16.dp))
    }
}
