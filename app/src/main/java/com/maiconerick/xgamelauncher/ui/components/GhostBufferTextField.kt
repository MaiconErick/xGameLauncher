package com.maiconerick.xgamelauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon

/**
 * Fixed-width text field wrapped in a click/focus box. Whole box is one big touch
 * target that focuses the inner input on tap.
 *
 * "Ghost buffer": when the user erases the tail of the value (backspaces from the
 * end or shortens by a strict suffix), the erased characters remain visible dimmed
 * after the cursor until the user types (or pastes / edits in the middle) — then
 * the ghost clears. Example: value "abcdefg" → delete 4 → visible "abc" + faded
 * "defg" → type "X" → visible "abcX".
 */
@Composable
fun GhostBufferTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }

    var ghost by remember { mutableStateOf("") }
    var previous by remember { mutableStateOf(value) }

    if (value != previous) {
        if (!isSuffixErase(from = previous, to = value)) ghost = ""
        previous = value
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color(0xFF12191E) else Color(0xFF0B1014))
            .border(
                width = 1.dp,
                color = if (focused) neon.accent else neon.stroke,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable { runCatching { requester.requestFocus() } },
    ) {
        com.maiconerick.xgamelauncher.ui.components.GamepadTextField(
            value = value,
            onValueChange = { new ->
                when {
                    new == value -> Unit
                    isSuffixErase(from = value, to = new) -> {
                        ghost = value.substring(new.length) + ghost
                    }
                    else -> ghost = ""
                }
                onValueChange(new)
            },
            // Padding inside the field: tapping anywhere in the box starts editing.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged { focused = it.isFocused },
            textStyle = TextStyle(
                color = neon.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
            singleLine = true,
            cursorBrush = SolidColor(neon.accent),
            visualTransformation = remember(ghost) {
                GhostSuffixTransformation(ghost, neon.inkMuted)
            },
        )
    }
}

private fun isSuffixErase(from: String, to: String): Boolean =
    to.length < from.length && from.startsWith(to)

private class GhostSuffixTransformation(
    private val ghost: String,
    private val ghostColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val combined = buildAnnotatedString {
            append(text)
            if (ghost.isNotEmpty()) {
                withStyle(SpanStyle(color = ghostColor)) { append(ghost) }
            }
        }
        return TransformedText(combined, OneToOneMapping(text.length))
    }
}

private class OneToOneMapping(private val realLen: Int) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = offset.coerceAtMost(realLen)
    override fun transformedToOriginal(offset: Int): Int = offset.coerceAtMost(realLen)
}
