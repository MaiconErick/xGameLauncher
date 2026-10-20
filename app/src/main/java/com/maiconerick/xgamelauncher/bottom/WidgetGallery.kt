package com.maiconerick.xgamelauncher.bottom

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.maiconerick.xgamelauncher.ui.components.GamepadTextField
import com.maiconerick.xgamelauncher.ui.components.focusRing
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen widget picker with previews: xGL widgets render live (scaled, non-interactive);
 * Android widgets show the provider's preview image (or its app icon when it has none).
 */
@Composable
fun WidgetGallery(
    act: BottomHomeActivity,
    onBuiltin: (String) -> Unit,
    onAndroid: (AppWidgetProviderInfo) -> Unit,
    onClose: () -> Unit,
) {
    val neon = LocalNeon.current
    var tab by remember { mutableStateOf("xgl") }
    var query by remember { mutableStateOf("") }
    val providers = remember {
        AppWidgetManager.getInstance(act).installedProviders.sortedBy {
            (appLabel(act, it) + it.loadLabel(act.packageManager)).lowercase()
        }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(Color(0xF2050709))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add widget", color = neon.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TabChip("xGL", tab == "xgl") { tab = "xgl" }
            TabChip("ANDROID", tab == "android") { tab = "android" }
            TabChip("CLOSE", false, onClose)
        }
        if (tab == "android") Box(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(0x22FFFFFF)),
        ) {
            if (query.isEmpty()) Text("Search widgets… (A to type)", color = neon.inkMuted, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
            GamepadTextField(query, { query = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = neon.ink, fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(neon.accent), modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp))
        }
        if (tab == "xgl") {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(260.dp), modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(BuiltinWidgets.ALL, key = { it.first }) { (type, label) ->
                    GalleryCard(label, onClick = { onBuiltin(type) }) {
                        // Live preview, scaled down and not interactive.
                        Box(
                            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp))
                                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.fillMaxWidth().graphicsLayer { scaleX = 0.8f; scaleY = 0.8f }) {
                                WidgetView(null, WidgetSpec(type, option = "week"), onPlayAgain = {}, onOption = {})
                            }
                        }
                    }
                }
            }
        } else {
            val shown = providers.filter { p ->
                query.isBlank() || (appLabel(act, p) + " " + p.loadLabel(act.packageManager)).contains(query, true)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(200.dp), modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.provider.flattenToString() }) { p ->
                    val cols = ((p.minWidth / act.resources.displayMetrics.density) / 70).toInt().coerceAtLeast(1)
                    val rows = ((p.minHeight / act.resources.displayMetrics.density) / 70).toInt().coerceAtLeast(1)
                    GalleryCard(p.loadLabel(act.packageManager), sub = "${appLabel(act, p)} · ${cols}×$rows", onClick = { onAndroid(p) }) {
                        var img by remember(p) { mutableStateOf<ImageBitmap?>(null) }
                        LaunchedEffect(p) {
                            img = withContext(Dispatchers.IO) {
                                runCatching {
                                    (p.loadPreviewImage(act, act.resources.displayMetrics.densityDpi) ?: p.loadIcon(act, act.resources.displayMetrics.densityDpi))
                                        ?.toPreviewBitmap(480, 360)?.asImageBitmap()
                                }.getOrNull()
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)),
                            contentAlignment = Alignment.Center) {
                            img?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp)) }
                        }
                    }
                }
            }
        }
    }
}

private fun appLabel(act: BottomHomeActivity, p: AppWidgetProviderInfo): String = runCatching {
    act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(p.provider.packageName, 0)).toString()
}.getOrDefault(p.provider.packageName)

private fun android.graphics.drawable.Drawable.toPreviewBitmap(maxWidth: Int, maxHeight: Int): android.graphics.Bitmap {
    val w = intrinsicWidth.takeIf { it > 0 } ?: maxWidth
    val h = intrinsicHeight.takeIf { it > 0 } ?: maxHeight
    val s = minOf(1f, maxWidth.toFloat() / w, maxHeight.toFloat() / h)
    return toBitmap((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
}

@Composable
private fun GalleryCard(title: String, sub: String? = null, onClick: () -> Unit, preview: @Composable () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.focusRing(focused, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
            .background(Color(0x1AFFFFFF)).border(1.dp, neon.accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }.focusable()
            .onGamepadActivate(onClick).clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        preview()
        Text(title, color = neon.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        if (sub != null) Text(sub, color = neon.inkDim, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.focusRing(focused, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(if (selected) neon.accent.copy(alpha = 0.25f) else Color(0x22FFFFFF))
            .onFocusChanged { focused = it.isFocused }.focusable()
            .onGamepadActivate(onClick).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(label, color = if (selected) neon.accent else neon.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}
