package com.maiconerick.xgamelauncher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.prefs.PrefsStore
import com.maiconerick.xgamelauncher.rom.RomFolders
import com.maiconerick.xgamelauncher.tools.ClipboardHub
import com.maiconerick.xgamelauncher.tools.RomCatalog
import com.maiconerick.xgamelauncher.tools.RomDownloader
import com.maiconerick.xgamelauncher.tools.Tool
import com.maiconerick.xgamelauncher.tools.ToolKeys
import com.maiconerick.xgamelauncher.tools.ToolsService
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.components.GamepadTextField
import com.maiconerick.xgamelauncher.ui.components.ListDivider
import com.maiconerick.xgamelauncher.ui.components.PageHeader
import com.maiconerick.xgamelauncher.ui.components.TopBar
import com.maiconerick.xgamelauncher.ui.components.TopTab
import com.maiconerick.xgamelauncher.ui.components.focusRing
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun Tool.icon(): ImageVector = when (this) {
    Tool.Roms -> Icons.Filled.Download
    Tool.Watcher -> Icons.Filled.CloudUpload
    Tool.Clipboard -> Icons.Filled.ContentPaste
}

/** Tools tab: one row per tool — enable toggle on the left, the row opens its settings. */
@Composable
fun ToolsScreen(prefs: PrefsStore, onTab: (TopTab) -> Unit, onOpen: (Tool) -> Unit, focusToolId: String?) {
    val requesters = remember { Tool.values().associateWith { FocusRequester() } }
    LaunchedEffect(focusToolId) {
        delay(50)
        runCatching { requesters.getValue(Tool.byId(focusToolId) ?: Tool.Roms).requestFocus() }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(activeTab = TopTab.Tools, onTab = onTab)
        PageHeader(icon = Icons.Filled.Build, title = "Tools")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Tool.values().forEach { t -> ToolRow(prefs, t, requesters.getValue(t)) { onOpen(t) } }
        }
    }
}

@Composable
private fun ToolRow(prefs: PrefsStore, tool: Tool, fr: FocusRequester, onOpen: () -> Unit) {
    val compact = LocalDeviceProfile.current.compact
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by prefs.toolFlag(tool.enabledKey, tool.defaultEnabled).collectAsState(initial = tool.defaultEnabled)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 6.dp else 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Enable toggle on the LEFT.
        ToolSwitch(enabled) {
            scope.launch { prefs.setToolFlag(tool.enabledKey, !enabled); ToolsService.refresh(ctx) }
        }
        Box(Modifier.weight(1f)) {
            com.maiconerick.xgamelauncher.ui.components.ListRow(
                icon = tool.icon(), title = tool.title,
                subtitle = tool.subtitle + if (!enabled) " · off" else "",
                focusRequester = fr, onClick = onOpen,
            )
        }
    }
}

@Composable
private fun ToolSwitch(on: Boolean, onToggle: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(width = 50.dp, height = 28.dp)
            .focusRing(focused, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) neon.accent else Color(0x33FFFFFF))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onToggle)
            .clickable(onClick = onToggle)
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (on) Color.Black else Color(0xCCFFFFFF)))
    }
}

/** A tool's own settings page. */
@Composable
fun ToolPage(prefs: PrefsStore, tool: Tool, onTab: (TopTab) -> Unit, onBrowse: () -> Unit) {
    val neon = LocalNeon.current
    val compact = LocalDeviceProfile.current.compact
    val enter = remember { FocusRequester() }
    LaunchedEffect(tool) { delay(50); runCatching { enter.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        TopBar(activeTab = TopTab.Tools, onTab = onTab)
        PageHeader(icon = tool.icon(), title = tool.title)
        Column(
            Modifier.fillMaxSize().background(neon.surfaceElevated)
                .focusRequester(enter).focusGroup()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 12.dp else 40.dp, vertical = if (compact) 10.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tool) {
                Tool.Roms -> RomsToolPage(prefs, onBrowse)
                Tool.Watcher -> WatcherToolPage(prefs)
                Tool.Clipboard -> ClipboardToolPage(prefs)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    val neon = LocalNeon.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(neon.accent.copy(alpha = 0.35f)))
        Spacer(Modifier.height(8.dp))
        Text(text, color = neon.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    }
}

@Composable
private fun Body(text: String, color: Color? = null) =
    Text(text, color = color ?: LocalNeon.current.inkDim, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
private fun ToggleLine(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val neon = LocalNeon.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolSwitch(value) { onChange(!value) }
        Text(label, color = neon.ink, fontSize = 14.sp)
    }
}

// ---------------------------------------------------------------- ROM downloader

@Composable
private fun RomsToolPage(prefs: PrefsStore, onBrowse: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(RomCatalog.status(ctx)) }
    var remote by remember { mutableStateOf<RomCatalog.Remote?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    val rootRaw by prefs.toolString(ToolKeys.ROM_ROOT, RomFolders.root()).collectAsState(initial = RomFolders.root())
    val root = RomFolders.migrate(rootRaw)
    val extract by prefs.toolFlag(ToolKeys.ROM_EXTRACT, true).collectAsState(initial = true)
    val jobs by RomDownloader.jobs.collectAsState()
    var pickRoot by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { remote = RomCatalog.remote() }

    Label("CATALOG")
    Body(
        if (status.installed) "Installed: version ${status.version} · ${status.entries} games · ${status.sizeBytes / 1_000_000} MB"
        else "Not downloaded yet. It's romgi's open catalog (≈55 MB download, ≈345 MB on the device)."
    )
    remote?.let { r ->
        if (status.installed && r.version != status.version) Body("Update available: ${r.version}", LocalNeon.current.accent)
    }
    if (busy) {
        ProgressLine("Downloading catalog… ${(progress * 100).toInt()}%", progress)
        Body("Keep this page open until it finishes.")
    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallBtn(if (status.installed) "UPDATE CATALOG" else "DOWNLOAD CATALOG", accent = true, onClick = {
            busy = true; error = null
            scope.launch {
                RomCatalog.download(ctx) { progress = it }.onFailure { error = it.message }
                busy = false
                status = RomCatalog.status(ctx)
            }
        })
        if (status.installed) SmallBtn("BROWSE GAMES", accent = true, onClick = onBrowse)
        if (status.installed) SmallBtn("DELETE CATALOG", onClick = { RomCatalog.delete(ctx); status = RomCatalog.status(ctx) })
    }
    error?.let { Body("⚠ $it", Color(0xFFFFB74D)) }

    Label("DOWNLOADS")
    Body("Downloads go straight into the platform's ROM folder. Without a platform, they go to <folder>/<system>.")
    if (pickRoot) com.maiconerick.xgamelauncher.ui.dialogs.FolderPickerDialog(
        title = "Default download folder", initialPath = root,
        onPick = { p -> scope.launch { prefs.setToolString(ToolKeys.ROM_ROOT, p) }; pickRoot = false },
        onDismiss = { pickRoot = false },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Body("Default folder: $root") }
        SmallBtn("CHANGE", onClick = { pickRoot = true })
    }
    ToggleLine("Extract .zip downloads (and delete the zip)", extract) { v -> scope.launch { prefs.setToolFlag(ToolKeys.ROM_EXTRACT, v) } }

    if (jobs.isNotEmpty()) {
        Label("QUEUE")
        jobs.reversed().forEach { j -> JobRow(j) }
        SmallBtn("CLEAR FINISHED", onClick = { RomDownloader.clearFinished() })
    }
    Label("ABOUT")
    Body("Cartridge systems (NES, SNES, Game Boy, GBA, N64, DS…) come from MiNERVA torrents: only the one file you pick is downloaded. Disc systems mostly download directly over HTTP.")
}

@Composable
private fun ProgressLine(text: String, p: Float) {
    val neon = LocalNeon.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, color = neon.ink, fontSize = 12.sp)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x22FFFFFF))) {
            Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(6.dp).background(neon.accent))
        }
    }
}

@Composable
private fun JobRow(j: RomDownloader.Job) {
    val neon = LocalNeon.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0x14FFFFFF)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(j.title, color = neon.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            Text(
                j.state.name.lowercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = when (j.state) {
                    RomDownloader.State.DONE -> Color(0xFF4ADE80)
                    RomDownloader.State.FAILED -> Color(0xFFFF5555)
                    else -> neon.accent
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x22FFFFFF))) {
            Box(Modifier.fillMaxWidth(j.progress.coerceIn(0f, 1f)).height(4.dp).background(neon.accent))
        }
        Text("${j.platformLabel} · ${j.detail}", color = neon.inkDim, fontSize = 11.sp, maxLines = 2)
    }
}

/** Catalog search: query + system filter; results open the entry page. */
@Composable
fun RomBrowseScreen(
    platforms: List<Platform>,
    platformId: String?,
    onTab: (TopTab) -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val compact = LocalDeviceProfile.current.compact
    val picker = com.maiconerick.xgamelauncher.ui.LocalPicker.current
    val ours = platforms.firstOrNull { it.id == platformId }
    val rPlatforms = remember { RomCatalog.platforms(ctx) }
    var system by remember { mutableStateOf(ours?.let { RomCatalog.romgiPlatformFor(ctx, it) }) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RomCatalog.Entry>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query, system) {
        searching = true
        delay(300)
        results = RomCatalog.search(ctx, query, system)
        searching = false
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(activeTab = TopTab.Tools, onTab = onTab)
        PageHeader(icon = Icons.Filled.Search, title = "Get games" + (ours?.let { " · ${it.name}" } ?: ""))
        if (rPlatforms.isEmpty()) {
            Body("  The romgi catalog isn't downloaded yet — Tools → ROM downloader → DOWNLOAD CATALOG.")
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (compact) 10.dp else 28.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(Color(0x55000000))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp)),
            ) {
                if (query.isEmpty()) Text("Search titles… (A to type)", color = neon.inkMuted, fontSize = 13.sp,
                    modifier = Modifier.padding(10.dp))
                GamepadTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = neon.ink, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(neon.accent),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 36.dp),
                )
                // Spinner while the (debounced) search runs.
                if (searching) androidx.compose.material3.CircularProgressIndicator(
                    color = neon.accent, strokeWidth = 2.dp,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(18.dp),
                )
            }
            SmallBtn(rPlatforms.firstOrNull { it.id == system }?.name ?: "ALL SYSTEMS", accent = true, onClick = {
                picker(
                    com.maiconerick.xgamelauncher.ui.PickerRequest(
                        title = "System", filterable = true,
                        options = listOf(com.maiconerick.xgamelauncher.ui.PickerOption("", "All systems")) +
                            rPlatforms.map { com.maiconerick.xgamelauncher.ui.PickerOption(it.id, it.name, it.brand) },
                        onPick = { id -> picker(null); system = id.ifBlank { null } },
                        onCancel = { picker(null) },
                    )
                )
            })
        }
        ListDivider()
        if (searching && results.isEmpty()) Body("  Searching…")
        else if (!searching && results.isEmpty() && query.isNotBlank()) Body("  No matches.")
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.slug }) { e ->
                ResultRow(e, rPlatforms.firstOrNull { it.id == e.platform }?.name ?: e.platform) { onOpenEntry(e.slug) }
            }
        }
    }
}

@Composable
private fun ResultRow(e: RomCatalog.Entry, systemName: String, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
            .focusRing(focused, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color(0x1FFFFFFF) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }.focusable()
            .onGamepadActivate(onClick).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(width = 40.dp, height = 52.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x22FFFFFF))) {
            if (e.boxart != null) AsyncImage(model = e.boxart, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(e.title, color = neon.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(listOfNotNull(systemName, e.regions).joinToString(" · "), color = neon.inkDim, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** One catalog entry: its download links and where the file will go. */
@Composable
fun RomEntryScreen(
    prefs: PrefsStore,
    slug: String,
    platforms: List<Platform>,
    platformId: String?,
    onTab: (TopTab) -> Unit,
) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val compact = LocalDeviceProfile.current.compact
    var links by remember { mutableStateOf<List<RomCatalog.Link>>(emptyList()) }
    var entry by remember { mutableStateOf<RomCatalog.Entry?>(null) }
    val rootRaw by prefs.toolString(ToolKeys.ROM_ROOT, RomFolders.root()).collectAsState(initial = RomFolders.root())
    val root = RomFolders.migrate(rootRaw)
    var customDest by remember { mutableStateOf<String?>(null) }
    var pickDest by remember { mutableStateOf(false) }
    val jobs by RomDownloader.jobs.collectAsState()
    LaunchedEffect(slug) {
        links = RomCatalog.links(ctx, slug)
        entry = RomCatalog.entry(ctx, slug)
    }
    val system = entry?.platform
    // Destination: the platform we came from, else a platform mapped to this system, else root/<system>.
    val target: Platform? = platforms.firstOrNull { it.id == platformId }
        ?: platforms.firstOrNull { p -> system != null && RomCatalog.romgiPlatformFor(ctx, p) == system }
    val dest = customDest ?: target?.romsPath?.takeIf { it.isNotBlank() } ?: "$root/${system ?: "roms"}"
    if (pickDest) com.maiconerick.xgamelauncher.ui.dialogs.FolderPickerDialog(
        title = "Save to", initialPath = dest,
        onPick = { customDest = it; pickDest = false }, onDismiss = { pickDest = false },
    )
    Column(Modifier.fillMaxSize()) {
        TopBar(activeTab = TopTab.Tools, onTab = onTab)
        PageHeader(icon = Icons.Filled.Download, title = entry?.title ?: slug)
        Column(
            Modifier.fillMaxSize().background(neon.surfaceElevated).verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 12.dp else 40.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                entry?.boxart?.let {
                    AsyncImage(model = it, contentDescription = null, modifier = Modifier.width(if (compact) 70.dp else 110.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Body(listOfNotNull(entry?.platform, entry?.regions).joinToString(" · "))
                    Body("Saves to: $dest" + (target?.let { "  (platform ${it.name})" } ?: ""), neon.ink)
                    SmallBtn("CHANGE FOLDER", onClick = { pickDest = true })
                }
            }
            Label("DOWNLOADS")
            if (links.isEmpty()) Body("No links for this entry.")
            links.forEach { l ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0x14FFFFFF)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(l.filename.ifBlank { l.name }, color = neon.ink, fontSize = 13.sp, maxLines = 2)
                        Text(
                            listOfNotNull(l.type, l.format.ifBlank { null }, l.sizeStr.ifBlank { null }, l.host,
                                if (l.isTorrent) "torrent" else "direct",
                                if (l.requiresAuth) "needs login (unsupported)" else null).joinToString(" · "),
                            color = neon.inkDim, fontSize = 11.sp,
                        )
                    }
                    // The newest job for this link: progress / result shown right here.
                    val job = jobs.lastOrNull { it.link == l }
                    val busy = job != null && job.state in setOf(RomDownloader.State.QUEUED,
                        RomDownloader.State.DOWNLOADING, RomDownloader.State.EXTRACTING)
                    if (l.downloadable && !busy) SmallBtn(
                        when (job?.state) { RomDownloader.State.FAILED -> "RETRY"; RomDownloader.State.DONE -> "AGAIN"; else -> "DOWNLOAD" },
                        accent = true, onClick = {
                            RomDownloader.enqueue(ctx, entry?.title ?: l.name, target?.name ?: (system ?: "ROMs"), dest, l)
                        })
                }
                jobs.lastOrNull { it.link == l }?.let { job -> JobStatus(job) }
            }
        }
    }
}

/** Inline download state under a link: progress bar + detail, or the error / saved message. */
@Composable
private fun JobStatus(job: RomDownloader.Job) {
    val neon = LocalNeon.current
    val failed = job.state == RomDownloader.State.FAILED
    val done = job.state == RomDownloader.State.DONE
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!failed && !done) {
            if (job.progress > 0f) androidx.compose.material3.LinearProgressIndicator(
                progress = { job.progress }, color = neon.accent, trackColor = neon.accent.copy(alpha = 0.18f),
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            ) else androidx.compose.material3.LinearProgressIndicator(
                color = neon.accent, trackColor = neon.accent.copy(alpha = 0.18f),
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
        Text(
            when {
                failed -> "✕ " + job.detail
                done -> "✓ " + job.detail
                job.state == RomDownloader.State.QUEUED -> "Queued…"
                else -> listOfNotNull(if (job.progress > 0f) "${(job.progress * 100).toInt()}%" else null, job.detail.ifBlank { null }).joinToString(" · ")
            },
            color = when { failed -> Color(0xFFFF6B6B); done -> neon.accent; else -> neon.inkDim },
            fontSize = 11.sp,
        )
    }
}

// ---------------------------------------------------------------- folder → Dropbox

@Composable
private fun WatcherToolPage(prefs: PrefsStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val folder by prefs.toolString(ToolKeys.WATCH_FOLDER, "").collectAsState(initial = "")
    val dest by prefs.toolString(ToolKeys.WATCH_DEST, ToolKeys.DEFAULT_WATCH_DEST).collectAsState(initial = ToolKeys.DEFAULT_WATCH_DEST)
    val delete by prefs.toolFlag(ToolKeys.WATCH_DELETE, true).collectAsState(initial = true)
    val log by ToolsService.log.collectAsState()
    var pick by remember { mutableStateOf(false) }
    var destDraft by remember(dest) { mutableStateOf(dest) }
    if (pick) com.maiconerick.xgamelauncher.ui.dialogs.FolderPickerDialog(
        title = "Folder to watch", initialPath = folder.ifBlank { null },
        onPick = { p -> scope.launch { prefs.setToolString(ToolKeys.WATCH_FOLDER, p); ToolsService.refresh(ctx) }; pick = false },
        onDismiss = { pick = false },
    )
    Label("WATCHED FOLDER")
    Body(folder.ifBlank { "None picked yet." })
    SmallBtn("PICK FOLDER", accent = true, onClick = { pick = true })
    Label("DROPBOX DESTINATION")
    EditField(label = "FOLDER IN DROPBOX", value = destDraft, onChange = { destDraft = it }, placeholder = ToolKeys.DEFAULT_WATCH_DEST)
    if (destDraft != dest) SmallBtn("SAVE", accent = true, onClick = {
        scope.launch { prefs.setToolString(ToolKeys.WATCH_DEST, "/" + destDraft.trim().trim('/')) }
    })
    ToggleLine("Delete files from the device after a successful upload", delete) { v ->
        scope.launch { prefs.setToolFlag(ToolKeys.WATCH_DELETE, v) }
    }
    Body("New files (including subfolders) are uploaded once they stop changing. Existing Dropbox files are never overwritten.")
    Label("ACTIVITY")
    if (log.isEmpty()) Body("Nothing uploaded yet in this session.")
    log.forEach { Body(it) }
}

// ---------------------------------------------------------------- clipboard

@Composable
private fun ClipboardToolPage(prefs: PrefsStore) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hub by prefs.toolString(ToolKeys.CLIP_HUB, ToolKeys.DEFAULT_HUB).collectAsState(initial = ToolKeys.DEFAULT_HUB)
    val auto by prefs.toolFlag(ToolKeys.CLIP_AUTOCOPY, false).collectAsState(initial = false)
    var hubDraft by remember(hub) { mutableStateOf(hub) }
    var clips by remember { mutableStateOf<List<ClipboardHub.Clip>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hub) {
        while (true) { clips = ClipboardHub.list(hub, 50); delay(3_000) }
    }
    fun copy(c: ClipboardHub.Clip) {
        ctx.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("From ${c.source}", c.text))
        note = "Copied."
    }
    Label("HUB")
    EditField(label = "PI HUB ADDRESS", value = hubDraft, onChange = { hubDraft = it }, placeholder = ToolKeys.DEFAULT_HUB)
    if (hubDraft != hub) SmallBtn("SAVE", accent = true, onClick = { scope.launch { prefs.setToolString(ToolKeys.CLIP_HUB, hubDraft.trim()) } })
    Body(if (clips == null) "⚠ Can't reach the hub (also tried Tailscale)." else "Connected · ${clips!!.size} clips",
        if (clips == null) Color(0xFFFFB74D) else null)
    ToggleLine("Copy new PC clips onto this device automatically", auto) { v ->
        scope.launch { prefs.setToolFlag(ToolKeys.CLIP_AUTOCOPY, v); ToolsService.refresh(ctx) }
    }
    SmallBtn("SEND THIS DEVICE'S CLIPBOARD", accent = true, onClick = {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (text.isNullOrBlank()) note = "Clipboard is empty."
        else scope.launch {
            note = if (ClipboardHub.send(hub, text, com.maiconerick.xgamelauncher.ui.DeviceProfile.defaultDeviceName())) "Sent."
                   else "Couldn't reach the hub."
        }
    })
    note?.let { Body(it, neon.accent) }
    Label("HISTORY  ·  A / tap = copy to this device")
    clips.orEmpty().forEach { c ->
        var focused by remember(c.id) { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().focusRing(focused, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
                .background(if (focused) Color(0x26FFFFFF) else Color(0x14FFFFFF))
                .onFocusChanged { focused = it.isFocused }.focusable()
                .onGamepadActivate { copy(c) }
                .clickable { copy(c) }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(c.text, color = neon.ink, fontSize = 13.sp, maxLines = 3)
                Text(
                    "${c.source} · " + java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date((c.ts * 1000).toLong())),
                    color = neon.inkDim, fontSize = 11.sp,
                )
            }
            SmallBtn("✕", onClick = { scope.launch { ClipboardHub.delete(hub, c.id); clips = ClipboardHub.list(hub, 50) } })
        }
    }
}
