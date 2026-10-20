package com.maiconerick.xgamelauncher.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.components.ListRow
import com.maiconerick.xgamelauncher.ui.components.focusRing
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A storage root shown as a quick-jump button. */
private data class Volume(val label: String, val path: String, val icon: ImageVector)

private fun volumes(): List<Volume> {
    val out = arrayListOf(Volume("Internal storage", "/storage/emulated/0", Icons.Filled.PhoneAndroid))
    File("/storage").listFiles()
        ?.filter { it.isDirectory && Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(it.name) && it.canRead() }
        ?.sortedBy { it.name }
        ?.forEachIndexed { i, f ->
            out += Volume(if (i == 0) "SD card" else "SD card ${i + 1}", f.absolutePath, Icons.Filled.SdCard)
        }
    return out
}

/**
 * File-manager style folder picker: storage buttons (internal + every SD card), Up, the
 * current folder's subfolders, and USE THIS FOLDER. Gamepad-first; B = cancel.
 */
@Composable
fun FolderPickerDialog(
    title: String,
    initialPath: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val neon = LocalNeon.current
    val compact = LocalDeviceProfile.current.compact
    val vols = remember { volumes() }
    var dir by remember {
        mutableStateOf(
            initialPath?.let { File(it) }?.takeIf { it.isDirectory }
                ?: File(com.maiconerick.xgamelauncher.rom.RomFolders.ROMS_ROOT).takeIf { it.isDirectory }
                ?: File(vols.first().path)
        )
    }
    var children by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(dir.absolutePath) {
        children = withContext(Dispatchers.IO) {
            dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }.orEmpty()
        }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    val volumeRoot = vols.firstOrNull { dir.absolutePath.startsWith(it.path) }?.path
    val canGoUp = volumeRoot != null && dir.absolutePath != volumeRoot && dir.parentFile != null

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (compact) 0.96f else 0.75f)
                .widthIn(max = 760.dp)
                .fillMaxHeight(if (compact) 0.94f else 0.86f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF20A0F14))
                .border(1.dp, neon.accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(if (compact) 10.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            Text(title, color = neon.ink, fontSize = if (compact) 16.sp else 20.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vols.forEachIndexed { i, v ->
                    PickerButton(
                        label = v.label, icon = v.icon,
                        selected = volumeRoot == v.path,
                        focusRequester = if (i == 0) firstFocus else null,
                        onClick = { dir = File(v.path) },
                    )
                }
                if (canGoUp) PickerButton("Up", Icons.Filled.ArrowUpward, onClick = { dir.parentFile?.let { dir = it } })
            }
            Text(dir.absolutePath, color = neon.accent, fontSize = 12.sp, maxLines = 2)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (children.isEmpty()) {
                    Text("No subfolders here.", color = neon.inkDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                } else LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(children, key = { _, f -> f.absolutePath }) { _, f ->
                        ListRow(
                            icon = Icons.Filled.Folder,
                            title = f.name,
                            subtitle = null,
                            onClick = { dir = f },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PickerButton("USE THIS FOLDER", Icons.Filled.Folder, accent = true, onClick = { onPick(dir.absolutePath) })
                Box(Modifier.weight(1f))
                PickerButton("CANCEL", null, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun PickerButton(
    label: String,
    icon: ImageVector?,
    selected: Boolean = false,
    accent: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .focusRing(focused, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    accent -> neon.accent.copy(alpha = if (focused) 0.32f else 0.18f)
                    selected -> neon.accent.copy(alpha = 0.14f)
                    focused -> Color(0x33FFFFFF)
                    else -> Color(0x1FFFFFFF)
                }
            )
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (accent || selected) neon.accent else neon.ink,
            modifier = Modifier.padding(end = 2.dp))
        Text(label, color = if (accent || selected) neon.accent else neon.ink, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold)
    }
}
