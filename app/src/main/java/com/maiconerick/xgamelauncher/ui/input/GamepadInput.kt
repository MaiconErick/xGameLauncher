package com.maiconerick.xgamelauncher.ui.input

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * The Thor's driver maps its physical A-button to `Key.ButtonA` (KEYCODE_BUTTON_A = 96) — which
 * `clickable()` does NOT auto-treat as an activate. Compose's built-in click keys are only
 * Enter / DirectionCenter / NumPadEnter / Space, so we listen for A explicitly and forward it
 * to the same handler.
 */
fun Modifier.onGamepadActivate(onClick: () -> Unit): Modifier = this.onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
    when (e.key) {
        Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
            com.maiconerick.xgamelauncher.ui.NavSounds.select()
            onClick(); true
        }
        else -> false
    }
}

/**
 * Like [onGamepadActivate] but holding A (≥500ms) triggers [onLong] instead — the gamepad
 * version of a long-press. Pair with `combinedClickable(onLongClick = …)` for touch.
 */
fun Modifier.onGamepadActivateOrHold(onClick: () -> Unit, onLong: () -> Unit): Modifier {
    var downAt = 0L
    return this.onPreviewKeyEvent { e ->
        val isA = e.key == Key.ButtonA || e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.DirectionCenter
        if (!isA) return@onPreviewKeyEvent false
        when (e.type) {
            KeyEventType.KeyDown -> { if (downAt == 0L) downAt = android.os.SystemClock.uptimeMillis(); true }
            KeyEventType.KeyUp -> {
                val held = android.os.SystemClock.uptimeMillis() - downAt
                downAt = 0L
                com.maiconerick.xgamelauncher.ui.NavSounds.select()
                if (held >= 500) onLong() else onClick()
                true
            }
            else -> false
        }
    }
}

/** Gamepad B / physical Back → invoke `onBack`. Return true if handled. */
fun Modifier.onGamepadBack(onBack: () -> Unit): Modifier = this.onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
    when (e.key) {
        Key.ButtonB, Key.Back, Key.Escape -> {
            onBack(); true
        }
        else -> false
    }
}
