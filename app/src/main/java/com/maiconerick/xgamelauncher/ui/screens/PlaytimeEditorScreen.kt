package com.maiconerick.xgamelauncher.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.data.SessionRow
import com.maiconerick.xgamelauncher.ui.LauncherViewModel
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.LocalPicker
import com.maiconerick.xgamelauncher.ui.PickerOption
import com.maiconerick.xgamelauncher.ui.PickerRequest
import com.maiconerick.xgamelauncher.ui.components.GamepadTextField
import com.maiconerick.xgamelauncher.ui.components.PageHeader
import com.maiconerick.xgamelauncher.ui.components.focusRing
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Playtime editor: every tracked session row, filterable, with a form that edits any
 * column, adds rows by hand and deletes them (one or all shown). Each change refreshes the
 * playtime totals and is mirrored to the Dropbox backup a few seconds later
 * ([LauncherViewModel.saveSession] / [LauncherViewModel.deleteSessions]).
 *
 * B leaves the form (asking first when it has unsaved changes), then the screen.
 */
@Composable
fun PlaytimeEditorScreen(
    vm: LauncherViewModel,
    initialFilter: String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<SessionRow>?>(null) }
    var filter by remember { mutableStateOf(initialFilter.orEmpty()) }
    /** Row being edited: (original or null for a new row, draft). */
    var editing by remember { mutableStateOf<Pair<SessionRow?, SessionRow>?>(null) }
    var focusId by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    suspend fun reload() { rows = vm.allSessions() }
    LaunchedEffect(Unit) { reload() }

    val current = editing
    if (current == null) {
        BackHandler { onBack() }
        SessionList(
            rows = rows,
            filter = filter,
            onFilter = { filter = it },
            focusId = focusId,
            status = status,
            onOpen = { r -> editing = r to r },
            onNew = {
                val now = System.currentTimeMillis() / 1000
                val template = rows.orEmpty().firstOrNull { matches(it, filter) }
                editing = null to SessionRow(
                    gameName = template?.gameName ?: filter.trim(),
                    platform = template?.platform ?: "",
                    emulator = template?.emulator ?: "",
                    pkg = template?.pkg ?: "",
                    startedUnix = now - 3600, endedUnix = now, timePlayedSeconds = 3600,
                    endReason = "Added by hand",
                    user = template?.user ?: "MaiconErick",
                    device = template?.device ?: com.maiconerick.xgamelauncher.ui.DeviceProfile.defaultDeviceName(),
                    artPath = template?.artPath,
                    isOpen = false, source = "manual",
                )
            },
            onDeleteShown = { shown ->
                scope.launch {
                    val n = vm.deleteSessions(shown)
                    status = "Deleted $n session${if (n == 1) "" else "s"} · syncing to Dropbox"
                    reload()
                }
            },
        )
    } else {
        SessionForm(
            original = current.first,
            draft = current.second,
            onCancel = { focusId = current.first?.id; editing = null },
            onSave = { row ->
                scope.launch {
                    val id = vm.saveSession(row, current.first)
                    status = "Saved #$id · syncing to Dropbox"
                    reload(); focusId = id; editing = null
                }
            },
            onDelete = current.first?.let { orig ->
                {
                    scope.launch {
                        vm.deleteSessions(listOf(orig))
                        status = "Deleted #${orig.id} · syncing to Dropbox"
                        reload(); focusId = null; editing = null
                    }
                }
            },
        )
    }
}

private fun matches(r: SessionRow, filter: String): Boolean {
    val q = filter.trim()
    if (q.isEmpty()) return true
    return q.split(Regex("\\s+")).all { w ->
        r.gameName.contains(w, true) || r.platform.contains(w, true) || r.emulator.contains(w, true) ||
            r.device.contains(w, true) || (r.endReason?.contains(w, true) == true) || r.id.toString() == w
    }
}

private val listFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private fun editFmt() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { isLenient = false }

private fun duration(secs: Long?): String {
    val s = (secs ?: 0L).coerceAtLeast(0)
    return when {
        s >= 3600 -> "%dh %02dm".format(s / 3600, (s % 3600) / 60)
        s >= 60 -> "%dm %02ds".format(s / 60, s % 60)
        else -> "${s}s"
    }
}

// ---------------- list ----------------

@Composable
private fun SessionList(
    rows: List<SessionRow>?,
    filter: String,
    onFilter: (String) -> Unit,
    focusId: Long?,
    status: String?,
    onOpen: (SessionRow) -> Unit,
    onNew: () -> Unit,
    onDeleteShown: (List<SessionRow>) -> Unit,
) {
    val neon = LocalNeon.current
    val compact = LocalDeviceProfile.current.compact
    val picker = LocalPicker.current
    val shown = remember(rows, filter) { rows.orEmpty().filter { matches(it, filter) } }
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val hPad = if (compact) 10.dp else 32.dp

    Column(Modifier.fillMaxSize().background(neon.surfaceElevated)) {
        PageHeader(icon = Icons.Filled.Timer, title = "Edit playtime")
        Column(
            Modifier.fillMaxWidth().padding(horizontal = hPad, vertical = if (compact) 6.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EditField(label = "FILTER (game, platform, device, reason or #id)", value = filter,
                onChange = onFilter, modifier = Modifier.focusRequester(firstFocus))
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionChip("+ NEW SESSION", accent = true, onClick = onNew)
                if (shown.isNotEmpty()) ActionChip(
                    if (filter.isBlank()) "DELETE ALL (${shown.size})" else "DELETE SHOWN (${shown.size})",
                    danger = true,
                    onClick = {
                        picker(PickerRequest(
                            title = "Delete ${shown.size} session${if (shown.size == 1) "" else "s"}?",
                            message = (if (filter.isBlank()) "This wipes ALL playtime history on this device. "
                                else "Every row matching \"${filter.trim()}\" goes away. ") +
                                "The Dropbox backup is updated to match, and sync won't bring them back.",
                            options = listOf(PickerOption("cancel", "Cancel"), PickerOption("delete", "Delete")),
                            onPick = { id -> picker(null); if (id == "delete") onDeleteShown(shown) },
                            onCancel = { picker(null) },
                        ))
                    },
                )
            }
            val total = shown.sumOf { it.timePlayedSeconds ?: 0L }
            Text(
                if (rows == null) "Loading…" else "${shown.size} session${if (shown.size == 1) "" else "s"} · ${duration(total)}" +
                    (status?.let { " · $it" } ?: ""),
                color = neon.inkDim, fontSize = 12.sp,
            )
        }
        LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
        LaunchedEffect(focusId, shown) {
            val i = shown.indexOfFirst { it.id == focusId }
            if (i >= 0) listState.scrollToItem(i)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = hPad, end = hPad, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(shown, key = { it.id }) { r ->
                SessionCard(r, focus = r.id == focusId, onClick = { onOpen(r) })
            }
        }
    }
}

@Composable
private fun SessionCard(r: SessionRow, focus: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    val compact = LocalDeviceProfile.current.compact
    var focused by remember { mutableStateOf(false) }
    val req = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { runCatching { req.requestFocus() } }
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.fillMaxWidth()
            .focusRing(focused, shape).clip(shape)
            .background(if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .border(1.dp, Color(0x1FFFFFFF), shape)
            .focusRequester(req)
            .onFocusChanged { focused = it.isFocused }
            .focusable().onGamepadActivate(onClick).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.gameName.ifBlank { "(no name)" }, color = neon.ink, fontSize = if (compact) 13.sp else 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            Text(if (r.isOpen) "● LIVE" else duration(r.timePlayedSeconds),
                color = if (r.isOpen) Color(0xFFFF5555) else neon.accent,
                fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "#${r.id} · ${listFmt.format(Date(r.startedUnix * 1000))} · ${r.platform.ifBlank { "—" }}" +
                " · ${r.device} · ${r.endReason ?: "—"}",
            color = neon.inkDim, fontSize = 11.sp, maxLines = 1,
        )
    }
}

// ---------------- form ----------------

/** Text state of every column, so half-typed dates don't fight the parsed row. */
private data class FormText(
    val gameName: String, val platform: String, val emulator: String, val pkg: String,
    val started: String, val ended: String, val played: String, val endReason: String,
    val user: String, val device: String, val artPath: String, val source: String,
) {
    companion object {
        fun of(r: SessionRow): FormText {
            val f = editFmt()
            return FormText(
                r.gameName, r.platform, r.emulator, r.pkg,
                f.format(Date(r.startedUnix * 1000)), r.endedUnix?.let { f.format(Date(it * 1000)) }.orEmpty(),
                r.timePlayedSeconds?.toString().orEmpty(), r.endReason.orEmpty(),
                r.user, r.device, r.artPath.orEmpty(), r.source,
            )
        }
    }
}

private fun parseUnix(s: String): Long? =
    runCatching { editFmt().parse(s.trim())!!.time / 1000 }.getOrNull()

@Composable
private fun SessionForm(
    original: SessionRow?,
    draft: SessionRow,
    onCancel: () -> Unit,
    onSave: (SessionRow) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val neon = LocalNeon.current
    val compact = LocalDeviceProfile.current.compact
    val picker = LocalPicker.current
    var text by remember(original?.id) { mutableStateOf(FormText.of(draft)) }
    var isOpen by remember(original?.id) { mutableStateOf(draft.isOpen) }
    var error by remember { mutableStateOf<String?>(null) }

    /** Validates the text: (row, null) or (null, what's wrong). Pure, so composition can call it. */
    fun parse(): Pair<SessionRow?, String?> {
        val started = parseUnix(text.started) ?: return null to "STARTED must look like 2026-10-02 14:58:00"
        val ended = if (text.ended.isBlank()) null else parseUnix(text.ended)
            ?: return null to "ENDED must look like 2026-10-02 15:30:00 (or be empty)"
        val played = if (text.played.isBlank()) null else text.played.trim().toLongOrNull()
            ?: return null to "PLAYED must be a whole number of seconds"
        if (text.gameName.isBlank()) return null to "GAME NAME can't be empty"
        return draft.copy(
            gameName = text.gameName.trim(), platform = text.platform.trim(), emulator = text.emulator.trim(),
            pkg = text.pkg.trim(), startedUnix = started, endedUnix = ended, timePlayedSeconds = played,
            endReason = text.endReason.ifBlank { null }, user = text.user.trim(), device = text.device.trim(),
            artPath = text.artPath.ifBlank { null }, source = text.source.trim().ifBlank { "manual" }, isOpen = isOpen,
        ) to null
    }
    /** For SAVE: the row, or null after showing the problem. */
    fun build(): SessionRow? { val (row, err) = parse(); error = err; return row }
    val dirty = original == null || parse().first != original

    BackHandler {
        if (!dirty) { onCancel(); return@BackHandler }
        picker(PickerRequest(
            title = "Discard changes?",
            message = "This session has unsaved edits.",
            options = listOf(PickerOption("save", "Save"), PickerOption("discard", "Discard"), PickerOption("stay", "Keep editing")),
            onPick = { id ->
                picker(null)
                when (id) { "save" -> build()?.let(onSave); "discard" -> onCancel() }
            },
            onCancel = { picker(null) },
        ))
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(original?.id) { runCatching { firstFocus.requestFocus() } }
    fun set(t: FormText) { text = t; error = null }

    Column(Modifier.fillMaxSize().background(neon.surfaceElevated)) {
        PageHeader(icon = Icons.Filled.Timer, title = if (original == null) "New session" else "Session #${original.id}")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 10.dp else 32.dp, vertical = if (compact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        ) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionChip("SAVE", accent = true, modifier = Modifier.focusRequester(firstFocus),
                    onClick = { build()?.let(onSave) })
                ActionChip("RECALC PLAYED (END − START)", onClick = {
                    val s = parseUnix(text.started); val e = parseUnix(text.ended)
                    if (s != null && e != null) set(text.copy(played = (e - s).coerceAtLeast(0).toString()))
                    else error = "Set valid STARTED and ENDED first"
                })
                ActionChip("SET END = START + PLAYED", onClick = {
                    val s = parseUnix(text.started); val p = text.played.trim().toLongOrNull()
                    if (s != null && p != null) set(text.copy(ended = editFmt().format(Date((s + p) * 1000))))
                    else error = "Set valid STARTED and PLAYED first"
                })
                if (onDelete != null) ActionChip("DELETE", danger = true, onClick = {
                    picker(PickerRequest(
                        title = "Delete session #${original?.id}?",
                        message = "${original?.gameName} · ${duration(original?.timePlayedSeconds)}. Dropbox is updated to match.",
                        options = listOf(PickerOption("cancel", "Cancel"), PickerOption("delete", "Delete")),
                        onPick = { id -> picker(null); if (id == "delete") onDelete() },
                        onCancel = { picker(null) },
                    ))
                })
                ActionChip("CANCEL", onClick = onCancel)
            }
            error?.let { Text("⚠  $it", color = Color(0xFFFFB74D), fontSize = 12.sp) }
            if (original?.isOpen == true) Text(
                "⚠  This session is live: the tracker will overwrite END / PLAYED when the game closes.",
                color = Color(0xFFFFB74D), fontSize = 12.sp,
            )

            EditField("GAME NAME", text.gameName, { set(text.copy(gameName = it)) })
            EditField("PLATFORM", text.platform, { set(text.copy(platform = it)) })
            Pair2(compact,
                { EditField("STARTED (yyyy-MM-dd HH:mm:ss)", text.started, { set(text.copy(started = it)) }, it) },
                { EditField("ENDED (empty = still open)", text.ended, { set(text.copy(ended = it)) }, it) })
            EditField(
                "PLAYED (seconds) · ${duration(text.played.trim().toLongOrNull())}", text.played,
                { set(text.copy(played = it.filter(Char::isDigit))) }, number = true,
            )
            Pair2(compact,
                { EditField("EMULATOR", text.emulator, { set(text.copy(emulator = it)) }, it) },
                { EditField("PACKAGE", text.pkg, { set(text.copy(pkg = it)) }, it) })
            EditField("END REASON", text.endReason, { set(text.copy(endReason = it)) })
            Pair2(compact,
                { EditField("USER", text.user, { set(text.copy(user = it)) }, it) },
                { EditField("DEVICE", text.device, { set(text.copy(device = it)) }, it) })
            Pair2(compact,
                { EditField("SOURCE", text.source, { set(text.copy(source = it)) }, it) },
                { OpenToggle(isOpen, it) { v -> isOpen = v } })
            EditField("ART PATH", text.artPath, { set(text.copy(artPath = it)) })
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Two fields side by side, stacked on the compact layout. */
@Composable
private fun Pair2(
    compact: Boolean,
    a: @Composable (Modifier) -> Unit,
    b: @Composable (Modifier) -> Unit,
) {
    if (compact) {
        a(Modifier); b(Modifier)
    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        a(Modifier.weight(1f)); b(Modifier.weight(1f))
    }
}

@Composable
private fun OpenToggle(value: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("IS OPEN (live)", color = neon.inkMuted, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium)
        Row(
            Modifier.fillMaxWidth().focusRing(focused, shape).clip(shape)
                .background(if (focused) Color(0x99000000) else Color(0x66000000))
                .onFocusChanged { focused = it.isFocused }.focusable()
                .onGamepadActivate { onChange(!value) }.clickable { onChange(!value) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(40.dp).height(22.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (value) neon.accent else Color(0x33FFFFFF)),
                contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
            ) { Box(Modifier.padding(2.dp).size(18.dp).clip(RoundedCornerShape(9.dp)).background(Color.Black)) }
            Spacer(Modifier.width(10.dp))
            Text(if (value) "yes" else "no", color = neon.ink, fontSize = 13.sp)
        }
    }
}

// ---------------- bits ----------------

@Composable
private fun EditField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    number: Boolean = false,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.fillMaxWidth().then(modifier), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = neon.inkMuted, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Box(
            Modifier.fillMaxWidth().focusRing(focused, shape).clip(shape)
                .background(if (focused) Color(0x99000000) else Color(0x66000000))
                .onFocusChanged { focused = it.hasFocus },
        ) {
            GamepadTextField(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(color = neon.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                singleLine = true,
                cursorBrush = SolidColor(neon.accent),
                keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            )
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    accent: Boolean = false,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    val tint = when { danger -> Color(0xFFFF5A6E); accent -> neon.accent; else -> neon.ink }
    Box(
        modifier.focusRing(focused, shape).clip(shape)
            .background(if (focused) tint.copy(alpha = 0.28f) else tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = if (focused) 0.9f else 0.45f), shape)
            .onFocusChanged { focused = it.isFocused }.focusable()
            .onGamepadActivate(onClick).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
    }
}
