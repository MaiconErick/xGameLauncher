package com.maiconerick.xgamelauncher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Focus glow used on every focusable tile / card: a soft cyan halo drawn *behind* the
 * shape via drawBehind (so it can bleed past bounds), plus a 1dp accent border on top.
 * Alpha-animates on focus change.
 */
@Composable
fun Modifier.neonFocusStroke(
    focused: Boolean,
    accent: Color,
    cornerDp: Int = 12,
): Modifier {
    val alpha: Float by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "neon-focus-alpha",
    )
    return this
        .drawBehind {
            if (alpha <= 0f) return@drawBehind
            val glow = accent.copy(alpha = 0.55f * alpha)
            val fadedGlow = accent.copy(alpha = 0f)
            val radius = size.minDimension * 0.7f
            drawRect(
                brush = Brush.radialGradient(
                    0f to glow,
                    0.5f to accent.copy(alpha = 0.25f * alpha),
                    1f to fadedGlow,
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = radius,
                ),
                topLeft = Offset(-radius * 0.35f, -radius * 0.35f),
                size = Size(size.width + radius * 0.7f, size.height + radius * 0.7f),
            )
        }
        .border(
            width = 1.dp,
            color = accent.copy(alpha = 0.85f * alpha),
            shape = RoundedCornerShape(cornerDp.dp),
        )
        // The shared white focus ring, so tiles read the same as every other control.
        .focusRing(focused, RoundedCornerShape(cornerDp.dp))
}
