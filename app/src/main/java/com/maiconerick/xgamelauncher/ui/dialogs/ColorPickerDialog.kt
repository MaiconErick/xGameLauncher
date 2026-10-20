package com.maiconerick.xgamelauncher.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.components.focusRing
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon

/** Neon-friendly presets shown under the picker. */
val ACCENT_PRESETS = listOf(
    "#00E5FF", "#18FFFF", "#64FFDA", "#00E676", "#76FF03", "#FFEA00", "#FFC400", "#FF9100",
    "#FF3D00", "#FF1744", "#F50057", "#D500F9", "#651FFF", "#3D5AFE", "#2979FF", "#FFFFFF",
)

fun parseHex(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> null
    }
}

private fun hsvToHex(h: Float, s: Float, v: Float): String {
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    return "#%06X".format(argb and 0xFFFFFF)
}

private fun hexToHsv(hex: String): FloatArray {
    val c = parseHex(hex) ?: return floatArrayOf(186f, 1f, 1f)
    val out = FloatArray(3)
    android.graphics.Color.RGBToHSV((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), out)
    return out
}

/**
 * Gamepad-first HSV colour picker. Touch: drag in the saturation/brightness square and on
 * the bars. Buttons: D-pad up/down moves between the Hue / Saturation / Brightness bars,
 * left/right adjusts the focused one (hold to sweep). A = apply on buttons, B = cancel.
 * `onApply(null)` means "use the theme default".
 */
@Composable
fun ColorPickerDialog(
    initialHex: String?,
    fallback: Color,
    onApply: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val neon = LocalNeon.current
    val compact = LocalDeviceProfile.current.compact
    val start = remember {
        val hex = initialHex?.takeIf { parseHex(it) != null }
            ?: "#%06X".format(android.graphics.Color.argb(
                255, (fallback.red * 255).toInt(), (fallback.green * 255).toInt(), (fallback.blue * 255).toInt(),
            ) and 0xFFFFFF)
        hexToHsv(hex)
    }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val hex = hsvToHex(hue, sat, value)
    var hexDraft by remember { mutableStateOf(hex) }
    var editingHex by remember { mutableStateOf(false) }
    LaunchedEffect(hex) { if (!editingHex) hexDraft = hex }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (compact) 0.96f else 0.7f)
                .widthIn(max = 640.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF20A0F14))
                .border(1.dp, neon.accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(if (compact) 12.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        ) {
            Text("Accent colour", color = neon.ink, fontSize = if (compact) 16.sp else 20.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Saturation × brightness square (touch).
                Box(
                    Modifier
                        .weight(1f)
                        .aspectRatio(if (compact) 2.2f else 1.9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        .pointerInput(Unit) {
                            fun set(o: Offset) {
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                                editingHex = false
                            }
                            detectTapGestures { set(it) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                editingHex = false
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = Offset(sat * size.width, (1f - value) * size.height)
                        drawCircle(Color.Black, radius = 9.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
                        drawCircle(Color.White, radius = 9.dp.toPx(), center = c, style = Stroke(1.5.dp.toPx()))
                    }
                }
                // Old / new preview.
                Column(Modifier.width(if (compact) 56.dp else 84.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("NEW", color = neon.inkMuted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                    Box(Modifier.fillMaxWidth().height(if (compact) 34.dp else 48.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color.hsv(hue, sat, value)))
                    Text("OLD", color = neon.inkMuted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                    Box(Modifier.fillMaxWidth().height(if (compact) 34.dp else 48.dp).clip(RoundedCornerShape(6.dp))
                        .background(parseHex(initialHex) ?: fallback))
                }
            }

            HsvBar("HUE", hue / 360f, Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }),
                step = 2f / 360f, focusRequester = firstFocus) { hue = (it * 360f).coerceIn(0f, 359.9f); editingHex = false }
            HsvBar("SATURATION", sat, Brush.horizontalGradient(listOf(Color.hsv(hue, 0f, value), Color.hsv(hue, 1f, value))),
                step = 0.02f) { sat = it; editingHex = false }
            HsvBar("BRIGHTNESS", value, Brush.horizontalGradient(listOf(Color.Black, Color.hsv(hue, sat, 1f))),
                step = 0.02f) { value = it; editingHex = false }

            // Presets (one tap / A).
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ACCENT_PRESETS.take(if (compact) 8 else 16).forEach { p ->
                    PresetSwatch(p) {
                        val v = hexToHsv(p); hue = v[0]; sat = v[1]; value = v[2]; editingHex = false
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HEX", color = neon.inkMuted, fontSize = 10.sp, letterSpacing = 1.4.sp)
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(Color(0x66000000))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp)),
                ) {
                    com.maiconerick.xgamelauncher.ui.components.GamepadTextField(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                        value = hexDraft,
                        onValueChange = { t ->
                            editingHex = true
                            hexDraft = t
                            val norm = if (t.startsWith("#")) t else "#$t"
                            if (parseHex(norm) != null && norm.length == 7) {
                                val v = hexToHsv(norm); hue = v[0]; sat = v[1]; value = v[2]
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = neon.ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(neon.accent),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton("APPLY", accent = true) { onApply(hex) }
                DialogButton("THEME DEFAULT") { onApply(null) }
                Spacer(Modifier.weight(1f))
                DialogButton("CANCEL", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun HsvBar(
    label: String,
    fraction: Float,
    brush: Brush,
    step: Float,
    focusRequester: FocusRequester? = null,
    onChange: (Float) -> Unit,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            (if (focused) "◀ $label ▶" else label) + "  ${(fraction * 100).toInt()}%",
            color = if (focused) neon.ink else neon.inkMuted, fontSize = 10.sp, letterSpacing = 1.4.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .focusRing(focused, RoundedCornerShape(6.dp))
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { onChange((fraction - step).coerceIn(0f, 1f)); true }
                        Key.DirectionRight -> { onChange((fraction + step).coerceIn(0f, 1f)); true }
                        else -> false
                    }
                }
                .focusable()
                .clip(RoundedCornerShape(6.dp))
                .background(brush)
                .pointerInput(Unit) {
                    detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ -> onChange((change.position.x / size.width).coerceIn(0f, 1f)) }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val x = fraction * size.width
                drawLine(Color.Black, Offset(x, 0f), Offset(x, size.height), strokeWidth = 5.dp.toPx())
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

@Composable
private fun PresetSwatch(hex: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(26.dp)
            .focusRing(focused, RoundedCornerShape(5.dp))
            .clip(RoundedCornerShape(5.dp))
            .background(parseHex(hex) ?: Color.Transparent)
            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(5.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun DialogButton(label: String, accent: Boolean = false, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .focusRing(focused, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(if (accent) neon.accent.copy(alpha = if (focused) 0.3f else 0.16f) else Color(if (focused) 0x33FFFFFF else 0x22FFFFFF))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (accent) neon.accent else neon.ink, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    }
}
