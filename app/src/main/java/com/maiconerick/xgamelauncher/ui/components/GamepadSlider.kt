package com.maiconerick.xgamelauncher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon

/**
 * Horizontal 0..1 slider for gamepad + touch: D-pad left/right steps (hold to sweep),
 * up/down leaves; tap or drag to set. `valueLabel` renders the right-hand readout.
 */
@Composable
fun GamepadSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    step: Float = 0.05f,
    valueLabel: (Float) -> String = { "${(it * 100).toInt()}%" },
    modifier: Modifier = Modifier,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(onChange)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            (if (focused) "◀ $label ▶" else label) + "   " + valueLabel(value),
            color = if (focused) neon.ink else neon.inkDim, fontSize = 11.sp, letterSpacing = 1.2.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
        )
        // − / + step buttons either side of the track (touch-friendly fine tuning).
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StepButton("−") { latest((value - step).coerceIn(0f, 1f)) }
            Box(
                Modifier
                    .weight(1f)
                    .height(22.dp)
                    .focusRing(focused, RoundedCornerShape(11.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { latest((value - step).coerceIn(0f, 1f)); true }
                            Key.DirectionRight -> { latest((value + step).coerceIn(0f, 1f)); true }
                            else -> false
                        }
                    }
                    .focusable()
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color(0x22FFFFFF))
                    .pointerInput(Unit) { detectTapGestures { latest((it.x / size.width).coerceIn(0f, 1f)) } }
                    .pointerInput(Unit) {
                        detectDragGestures { c, _ -> latest((c.position.x / size.width).coerceIn(0f, 1f)) }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.height / 2f
                    drawRoundRect(neon.accent.copy(alpha = 0.55f), size = Size(size.width * value, size.height),
                        cornerRadius = CornerRadius(r, r))
                    drawCircle(Color.White, radius = r * 0.8f, center = Offset((size.width * value).coerceIn(r, size.width - r), r))
                }
            }
            StepButton("+") { latest((value + step).coerceIn(0f, 1f)) }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    val neon = LocalNeon.current
    val latestClick by rememberUpdatedState(onClick)
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(13.dp))
            .background(neon.accent.copy(alpha = 0.16f))
            // Touch only (not focusable): the gamepad already steps with D-pad left / right.
            .pointerInput(Unit) { detectTapGestures { latestClick() } },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(label, color = neon.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
