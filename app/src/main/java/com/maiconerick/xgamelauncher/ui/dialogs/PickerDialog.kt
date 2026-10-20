package com.maiconerick.xgamelauncher.ui.dialogs

import com.maiconerick.xgamelauncher.ui.components.focusRing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.ui.PickerRequest
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon

/**
 * Full-screen modal overlay. B/back cancels. First option auto-focused; A activates.
 * Cancel-tap on the backdrop dismisses.
 */
@Composable
fun PickerHost(request: PickerRequest) {
    val neon = LocalNeon.current
    val compact = com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact
    val firstFocus = remember { FocusRequester() }
    var filter by remember(request) { mutableStateOf("") }
    val shown = remember(request, filter) {
        val q = filter.trim()
        if (q.isEmpty()) request.options
        else request.options.filter {
            it.label.contains(q, ignoreCase = true) || it.subtitle?.contains(q, ignoreCase = true) == true
        }
    }
    LaunchedEffect(request) { runCatching { firstFocus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = request.onCancel)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp && (e.key == Key.ButtonB || e.key == Key.Back || e.key == Key.Escape)) {
                    request.onCancel(); true
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                // Fits the RG353M's ~427dp width; capped at the Thor's 520dp.
                .fillMaxWidth(if (compact) 0.94f else 0.6f)
                .widthIn(max = 520.dp)
                .fillMaxHeight(if (compact) 0.94f else 0.85f)
                .wrapContentHeight(Alignment.CenterVertically)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xF00A0F14))
                .border(1.dp, neon.accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                // Swallow clicks so tapping the card doesn't cancel.
                .clickable(enabled = true, onClick = {})
                .padding(horizontal = if (compact) 12.dp else 24.dp, vertical = if (compact) 12.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
        ) {
            Text(
                text = request.title,
                color = neon.ink,
                fontSize = if (compact) 16.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
            )
            request.message?.let {
                Text(it, color = neon.inkMuted, fontSize = 11.sp, lineHeight = 15.sp)
            }
            if (request.filterable) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x66000000))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp)),
                ) {
                    if (filter.isEmpty()) Text("Type to filter…", color = neon.inkMuted, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    com.maiconerick.xgamelauncher.ui.components.GamepadTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        singleLine = true,
                        textStyle = TextStyle(color = neon.ink, fontSize = 13.sp),
                        cursorBrush = SolidColor(neon.accent),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
            ) {
                itemsIndexed(shown, key = { idx, o -> o.id + "#" + idx }) { idx, opt ->
                    PickerRow(
                        label = opt.label,
                        subtitle = opt.subtitle,
                        focusRequester = if (idx == 0) firstFocus else null,
                        onClick = { request.onPick(opt.id) },
                    )
                }
                if (shown.isEmpty()) item {
                    Text("Nothing matches.", color = neon.inkMuted, fontSize = 12.sp)
                }
            }
            Text(
                text = "A to select · B to cancel",
                color = neon.inkMuted,
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    subtitle: String?,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) neon.accent.copy(alpha = 0.20f) else Color(0x22FFFFFF)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(
                width = 1.dp,
                color = if (focused) neon.accent else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .focusRing(focused, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = if (focused) neon.accent else neon.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = neon.inkMuted,
                fontSize = 11.sp,
            )
        }
    }
}
