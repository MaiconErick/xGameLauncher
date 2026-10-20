package com.maiconerick.xgamelauncher.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

/**
 * BasicTextField that doesn't pop the keyboard just because the D-pad walked onto it.
 * It stays read-only while merely focused; A / Enter (or a tap) starts editing and opens
 * the keyboard, and leaving the field ends editing. Use this for every text input except
 * screens whose whole point is typing (Search).
 */
@Composable
fun GamepadTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
    cursorBrush: Brush = SolidColor(Color.Black),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    /** Padding *inside* the field, so taps anywhere in the padded box start editing. */
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var restart by remember { mutableIntStateOf(0) }
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // The IME session only starts on focus gain, so after flipping readOnly off we bounce
    // focus once to open the keyboard right away.
    LaunchedEffect(restart) {
        if (restart == 0) return@LaunchedEffect
        restarting = true
        delay(16)
        focusManager.clearFocus(force = true)
        runCatching { requester.requestFocus() }
        delay(16)
        restarting = false
    }

    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // A field leaving the screen (page closed with B) must never leave/flash the keyboard.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (focused || editing) keyboard?.hide() }
    }

    fun beginEditing() {
        if (editing) return
        editing = true
        if (focused) restart++
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !editing,
        textStyle = textStyle,
        singleLine = singleLine,
        minLines = minLines,
        cursorBrush = cursorBrush,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        decorationBox = { inner -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(contentPadding)) { inner() } },
        modifier = modifier
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused && !restarting) editing = false
            }
            .onPreviewKeyEvent { e ->
                // D-pad: while not editing, a text field must not trap focus (it would eat
                // the arrows to move its cursor). While editing a single-line field, up/down
                // still leave the field; left/right move the cursor.
                val dir = when (e.key) {
                    Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
                    Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
                    Key.DirectionLeft -> androidx.compose.ui.focus.FocusDirection.Left
                    Key.DirectionRight -> androidx.compose.ui.focus.FocusDirection.Right
                    else -> null
                }
                if (dir != null) {
                    val vertical = dir == androidx.compose.ui.focus.FocusDirection.Up ||
                        dir == androidx.compose.ui.focus.FocusDirection.Down
                    if (!editing || (singleLine && vertical)) {
                        if (e.type == KeyEventType.KeyDown) focusManager.moveFocus(dir)
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                if (editing) return@onPreviewKeyEvent false
                val activate = e.key == Key.ButtonA || e.key == Key.Enter ||
                    e.key == Key.NumPadEnter || e.key == Key.DirectionCenter
                if (!activate) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyUp) beginEditing()
                true
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    beginEditing()
                }
            },
    )
}
