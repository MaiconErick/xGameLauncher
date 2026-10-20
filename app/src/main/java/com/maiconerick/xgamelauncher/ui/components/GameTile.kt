package com.maiconerick.xgamelauncher.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.launch

data class GameTileState(
    val id: String,
    val title: String,
    val platformShort: String? = null,
    val boxArtPath: String? = null,
    val playtimeText: String? = null,
)

@Composable
fun GameTile(
    state: GameTileState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyFocused: Boolean = false,
    onFocused: () -> Unit = {},
    /** Game name under the box art (Games layout → "Show game names"). */
    showTitle: Boolean = false,
) {
    if (showTitle) {
        androidx.compose.foundation.layout.Column(
            modifier, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GameTile(state, onClick, Modifier.fillMaxWidth(), initiallyFocused, onFocused, showTitle = false)
            Text(
                state.title, color = LocalNeon.current.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            )
        }
        return
    }
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(initiallyFocused) {
        if (initiallyFocused) runCatching { focusRequester.requestFocus() }
    }

    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tile-scale",
    )
    Box(
        modifier = modifier
            .aspectRatio(0.70f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(neon.surfaceElevated)
            .neonFocusStroke(focused = focused, accent = neon.accent, cornerDp = 10)
            .bringIntoViewRequester(bring)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocused()
                    scope.launch { runCatching { bring.bringIntoView() } }
                }
            }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick),
    ) {
        if (state.boxArtPath != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(state.boxArtPath)
                    .crossfade(!com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.lowPower)
                    .scale(Scale.FILL)
                    .build(),
                contentDescription = state.title,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PlaceholderArt(title = state.title, labelSize = 13)
        }

        // Platform badge intentionally hidden — user requested cleaner tiles (2026-09-23).
        if (state.playtimeText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = state.playtimeText,
                    color = neon.accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                )
            }
        }
    }
}
