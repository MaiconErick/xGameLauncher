package com.maiconerick.xgamelauncher.ui.screens

import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Android
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.luminance
import com.maiconerick.xgamelauncher.ui.components.focusRing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maiconerick.xgamelauncher.data.LaunchIntent
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.prefs.PrefsStore
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen platform editor. Opened from Y-button on Home/Games, or from Settings →
 * Platforms → EDIT. Gamepad-navigable; top bar carries SAVE / CANCEL / DELETE. Body has
 * grouped sections. Icon + background picker is inline (state-swap, not modal).
 */
@Composable
fun PlatformEditScreen(
    initial: Platform,
    /** "players" opens straight on the Players tab (used by the no-player launch prompt). */
    initialTab: String? = null,
    /** Library (games + art) for the background maker overlay; null hides the maker. */
    library: com.maiconerick.xgamelauncher.ui.LibraryState? = null,
    prefs: PrefsStore,
    isNew: Boolean,
    onSave: (Platform) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit,
    // Android-only extras: filled by MainActivity when editing the synthetic Android
    // platform so we can render the app roster inline + expose the "Manage apps" jump.
    androidApps: List<com.maiconerick.xgamelauncher.data.AndroidGameRow> = emptyList(),
    onManageAndroid: (() -> Unit)? = null,
    /** Total playtime of this platform's games (shown in the background maker preview). */
    playtimeSec: Long = 0L,
) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initial.name) }
    // New platforms start with no folder: the old ROMS-root default made the scanner swallow
    // every system's files. The folder is auto-suggested from the name below.
    var romsPath by remember {
        mutableStateOf(if (com.maiconerick.xgamelauncher.rom.RomFolders.isRootLike(initial.romsPath)) "" else initial.romsPath)
    }
    var romsPathTouched by remember { mutableStateOf(!isNew && romsPath.isNotBlank()) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var extraPaths by remember { mutableStateOf(initial.extraPaths) }
    var extRegex by remember { mutableStateOf(initial.fileExtensionsRegex ?: "") }
    var artSearchName by remember { mutableStateOf(initial.artSearchName) }
    var intents by remember { mutableStateOf(initial.intents) }
    var defaultIntentId by remember { mutableStateOf(initial.defaultIntentId) }
    var colsWithoutPanel by remember { mutableStateOf(initial.columnsWithoutPanel) }
    var colsWithPanel by remember { mutableStateOf(initial.columnsWithPanel) }
    var accentHex by remember { mutableStateOf(initial.accentColorHex ?: "") }
    var backgroundPath by remember { mutableStateOf(initial.backgroundImagePath ?: "") }
    var iconPath by remember { mutableStateOf(initial.iconImagePath ?: "") }
    // Background maker overlay (keeps the editor and its unsaved edits alive underneath).
    var makingBackground by remember { mutableStateOf(false) }
    val onMakeBackground: (() -> Unit)? =
        if (library != null && (library.gamesByPlatform[initial.id]?.isNotEmpty() == true)) ({ makingBackground = true }) else null
    // Bottom-strip icon lives in prefs (not the platform row), keyed by platform id.
    var barIconPath by remember { mutableStateOf("") }
    var barIconLoaded by remember { mutableStateOf("") }
    LaunchedEffect(initial.id) {
        barIconLoaded = prefs.barIcons.first()[initial.id].orEmpty()
        barIconPath = barIconLoaded
    }
    var miniIconPath by remember { mutableStateOf("") }
    var miniIconLoaded by remember { mutableStateOf("") }
    LaunchedEffect(initial.id) {
        miniIconLoaded = prefs.miniIcons.first()[initial.id].orEmpty()
        miniIconPath = miniIconLoaded
    }

    var iconsRoot by remember { mutableStateOf(PrefsStore.DEFAULT_ICONS_ROOT) }
    LaunchedEffect(Unit) { iconsRoot = prefs.platformIconsRoot.first() }

    // Picker state: null = form, "icon"/"background" = browser is active for that field.
    var browsing by remember { mutableStateOf<String?>(null) }
    // Hoisted so the form's scroll position survives a trip into the icon browser and back.
    val bodyScroll = rememberScrollState()
    // Persists the browser's last folder across close/reopen too.
    var browserPath by remember { mutableStateOf(iconsRoot) }
    LaunchedEffect(iconsRoot) {
        if (browserPath == PrefsStore.DEFAULT_ICONS_ROOT) browserPath = iconsRoot
    }

    val currentPlatform: () -> Platform = {
        initial.copy(
            name = name.trim().ifBlank { "Untitled" },
            romsPath = romsPath.trim(),
            extraPaths = extraPaths.map { it.trim() }.filter { it.isNotBlank() },
            fileExtensionsRegex = extRegex.trim().ifBlank { null },
            intents = intents.map { it.copy(name = it.name.trim().ifBlank { "Launcher" }) },
            defaultIntentId = defaultIntentId ?: intents.firstOrNull()?.id,
            artSearchName = artSearchName.trim().ifBlank { name.trim() },
            columnsWithoutPanel = colsWithoutPanel,
            columnsWithPanel = colsWithPanel,
            accentColorHex = accentHex.trim().ifBlank { null },
            backgroundImagePath = backgroundPath.trim().ifBlank { null },
            iconImagePath = iconPath.trim().ifBlank { null },
        )
    }

    // null = the section list; a tab = that section's page (Daijishō-style list → page).
    var activeTab by remember(initial.id) {
        mutableStateOf(if (initialTab == "players") PlatformEditTab.Launchers else null as PlatformEditTab?)
    }
    var lastTab by remember(initial.id) { mutableStateOf<PlatformEditTab?>(null) }

    // Name → folder suggestion (GBA → ROMS/gba) until the user edits the path themselves.
    val isFolderPlatform = initial.id != com.maiconerick.xgamelauncher.windows.WindowsSource.PLATFORM_ID &&
        initial.id != com.maiconerick.xgamelauncher.android.AndroidAppSource.PLATFORM_ID
    LaunchedEffect(name) {
        if (!isFolderPlatform || romsPathTouched) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        com.maiconerick.xgamelauncher.rom.RomFolders.suggest(ctx, name)?.let { romsPath = it }
    }

    /** Saves unless the folder is missing / a root; returns false and shows why otherwise. */
    fun trySave(): Boolean {
        val p = currentPlatform()
        if (isFolderPlatform && com.maiconerick.xgamelauncher.rom.RomFolders.isRootLike(p.romsPath)) {
            saveError = "Pick this platform's own ROM folder (Basics → PICK FOLDER) — the ROMS root would pull in every system's files."
            activeTab = PlatformEditTab.Basics
            return false
        }
        saveError = null
        if (barIconPath != barIconLoaded) scope.launch { prefs.setBarIcon(p.id, barIconPath.ifBlank { null }) }
        if (miniIconPath != miniIconLoaded) scope.launch { prefs.setMiniIcon(p.id, miniIconPath.ifBlank { null }) }
        onSave(p)
        return true
    }

    // Unsaved-changes guard: B / Back with edits asks before throwing them away.
    val baseline = remember(initial.id) { currentPlatform() }
    val picker = com.maiconerick.xgamelauncher.ui.LocalPicker.current
    val keyboardCtl = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusMgr = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.activity.compose.BackHandler {
        when {
            makingBackground -> makingBackground = false
            browsing != null -> browsing = null
            activeTab != null -> {
                keyboardCtl?.hide(); focusMgr.clearFocus(force = true)
                lastTab = activeTab; activeTab = null
            }
            currentPlatform() == baseline -> onBack()
            else -> picker(
                com.maiconerick.xgamelauncher.ui.PickerRequest(
                    title = "Save changes to ${name.ifBlank { "this platform" }}?",
                    message = describePlatformChanges(baseline, currentPlatform()),
                    options = listOf(
                        com.maiconerick.xgamelauncher.ui.PickerOption("save", "Save"),
                        com.maiconerick.xgamelauncher.ui.PickerOption("discard", "Discard changes"),
                        com.maiconerick.xgamelauncher.ui.PickerOption("stay", "Keep editing"),
                    ),
                    onPick = { id ->
                        picker(null)
                        when (id) {
                            "save" -> trySave()
                            "discard" -> onBack()
                        }
                    },
                    onCancel = { picker(null) },
                )
            )
        }
    }

    val isAndroid = onManageAndroid != null

    Column(Modifier.fillMaxSize().background(neon.surface)) {
        // The background maker is full-screen: no editor header above it.
        if (!makingBackground) {
        EditTopBar(
            title = if (isNew) "New platform" else "Edit · $name",
            onSave = { trySave() },
            onCancel = onBack,
            onDelete = onDelete?.let { del ->
                {
                    picker(
                        com.maiconerick.xgamelauncher.ui.PickerRequest(
                            title = "Delete ${name.ifBlank { "this platform" }}?",
                            message = "It leaves the library (restore it from Settings → Platforms). ROM files are not touched.",
                            options = listOf(
                                com.maiconerick.xgamelauncher.ui.PickerOption("cancel", "Cancel"),
                                com.maiconerick.xgamelauncher.ui.PickerOption("delete", "Delete platform"),
                            ),
                            onPick = { id -> picker(null); if (id == "delete") del() },
                            onCancel = { picker(null) },
                        )
                    )
                }
            },
        )
        }
        if (!makingBackground) saveError?.let {
            Text(
                it, color = Color(0xFFFFB74D), fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().background(Color(0x33FFB74D))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        // Always compose the body — hide it when browsing so its scroll state survives.
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().let { if (browsing != null || makingBackground) it.height(0.dp) else it },
            ) {
                val tab = activeTab
                if (tab == null) {
                    PlatformEditIndex(
                        isAndroid = isAndroid,
                        focusTab = lastTab,
                        summary = { t ->
                            when (t) {
                                PlatformEditTab.Basics -> listOf(name.ifBlank { "Unnamed" },
                                    romsPath.ifBlank { "no folder picked" }).joinToString(" · ")
                                PlatformEditTab.Skin -> listOfNotNull(
                                    accentHex.ifBlank { "theme accent" },
                                    "icon".takeIf { iconPath.isNotBlank() },
                                    "background".takeIf { backgroundPath.isNotBlank() },
                                ).joinToString(" · ")
                                PlatformEditTab.Launchers -> when (intents.size) {
                                    0 -> "No players yet — add or import from Daijishō"
                                    else -> intents.joinToString(", ") { it.name }
                                }
                                PlatformEditTab.AndroidApps -> "${androidApps.size} apps"
                            }
                        },
                        onOpen = { activeTab = it },
                    )
                } else Column(Modifier.fillMaxSize()) {
                    com.maiconerick.xgamelauncher.ui.components.PageHeader(icon = tab.icon, title = tab.label)
                    val enter = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(tab) {
                        kotlinx.coroutines.delay(50)
                        runCatching { enter.requestFocus() }
                    }
                    Box(Modifier.fillMaxSize().focusRequester(enter).focusGroup()) {
                    EditBody(
                        activeTab = tab,
                        scrollState = bodyScroll,
                        name = name, onName = { name = it },
                        artSearchName = artSearchName, onArtSearch = { artSearchName = it },
                        romsPath = romsPath, onRomsPath = { romsPath = it; romsPathTouched = true },
                        extraPaths = extraPaths, onExtraPaths = { extraPaths = it },
                        extRegex = extRegex, onExtRegex = { extRegex = it },
                        colsWithoutPanel = colsWithoutPanel, onColsNoPanel = { colsWithoutPanel = it },
                        colsWithPanel = colsWithPanel, onColsPanel = { colsWithPanel = it },
                        accentHex = accentHex, onAccent = { accentHex = it },
                        iconPath = iconPath, onIconPath = { iconPath = it },
                        backgroundPath = backgroundPath, onBgPath = { backgroundPath = it },
                        onBrowseIcon = { browsing = "icon" },
                        onBrowseBackground = { browsing = "background" },
                        barIconPath = barIconPath, onBarIcon = { barIconPath = it },
                        onBrowseBarIcon = { browsing = "baricon" },
                        miniIconPath = miniIconPath, onMiniIcon = { miniIconPath = it },
                        onBrowseMiniIcon = { browsing = "miniicon" },
                        onMakeBackground = onMakeBackground,
                        intents = intents, defaultIntentId = defaultIntentId,
                        onIntentsChange = { intents = it },
                        onDefaultChange = { defaultIntentId = it },
                        androidApps = androidApps,
                        onManageAndroid = onManageAndroid,
                    )
                    }
                }
            }
            if (makingBackground && library != null) {
                BackgroundMakerScreen(
                    platform = currentPlatform(), state = library, playtimeSec = playtimeSec,
                    onDone = { path, backdrop ->
                        backgroundPath = path; makingBackground = false
                        scope.launch { prefs.setHeroBackdrop(initial.id, backdrop) }
                    },
                    onCancel = { makingBackground = false },
                )
            }
            if (browsing != null) {
                IconBrowser(
                    initialPath = browserPath,
                    onPathChange = { browserPath = it },
                    onPick = { pickedPath ->
                        when (browsing) {
                            "icon" -> iconPath = pickedPath
                            "baricon" -> barIconPath = pickedPath
                            "miniicon" -> miniIconPath = pickedPath
                            else -> backgroundPath = pickedPath
                        }
                        browsing = null
                    },
                    onCancel = { browsing = null },
                )
            }
        }
    }
}

private enum class PlatformEditTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Basics("Basics", androidx.compose.material.icons.Icons.Filled.FolderOpen),
    Skin("Skin", androidx.compose.material.icons.Icons.Filled.Palette),
    Launchers("Players", androidx.compose.material.icons.Icons.Filled.PlayCircle),
    AndroidApps("Android apps", androidx.compose.material.icons.Icons.Filled.Android),
}

@Composable
private fun PlatformEditIndex(
    isAndroid: Boolean,
    focusTab: PlatformEditTab?,
    summary: (PlatformEditTab) -> String,
    onOpen: (PlatformEditTab) -> Unit,
) {
    val tabs = PlatformEditTab.values().filter { it != PlatformEditTab.AndroidApps || isAndroid }
    val requesters = remember { tabs.associateWith { androidx.compose.ui.focus.FocusRequester() } }
    LaunchedEffect(focusTab) {
        kotlinx.coroutines.delay(50)
        runCatching { requesters.getValue(focusTab?.takeIf { it in tabs } ?: tabs.first()).requestFocus() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        tabs.forEach { t ->
            com.maiconerick.xgamelauncher.ui.components.ListRow(
                icon = t.icon, title = t.label, subtitle = summary(t),
                focusRequester = requesters.getValue(t),
                onClick = { onOpen(t) },
            )
        }
    }
}

// ---------------- TOP BAR ----------------

@Composable
private fun EditTopBar(
    title: String,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val neon = LocalNeon.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact) 44.dp else 56.dp)
            .background(Color(0xCC000000))
            .padding(horizontal = if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact) 10.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = neon.ink,
            fontSize = if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact) 14.sp else 18.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, maxLines = 1,
            modifier = Modifier.weight(1f))
        TopBarChip("SAVE", accent = true, onClick = onSave)
        Spacer(Modifier.width(8.dp))
        TopBarChip("CANCEL", accent = false, onClick = onCancel)
        if (onDelete != null) {
            Spacer(Modifier.width(8.dp))
            TopBarChip("DELETE", accent = false, danger = true, onClick = onDelete)
        }
    }
}

@Composable
private fun TopBarChip(
    label: String, accent: Boolean, danger: Boolean = false, onClick: () -> Unit,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val active = when {
        danger -> Color(0xFFFF5555)
        accent -> neon.accent
        else -> neon.ink
    }
    val bg = when {
        focused && accent -> neon.accent.copy(alpha = 0.28f)
        focused && danger -> Color(0x44FF5555)
        focused -> Color(0x33FFFFFF)
        accent -> neon.accent.copy(alpha = 0.14f)
        else -> Color(0x22FFFFFF)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(1.dp, if (focused) active else Color.Transparent, RoundedCornerShape(4.dp))
            .focusRing(focused, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = active, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp)
    }
}

// ---------------- BODY ----------------

@Composable
private fun EditBody(
    activeTab: PlatformEditTab,
    scrollState: androidx.compose.foundation.ScrollState,
    name: String, onName: (String) -> Unit,
    artSearchName: String, onArtSearch: (String) -> Unit,
    romsPath: String, onRomsPath: (String) -> Unit,
    extraPaths: List<String>, onExtraPaths: (List<String>) -> Unit,
    extRegex: String, onExtRegex: (String) -> Unit,
    colsWithoutPanel: Int, onColsNoPanel: (Int) -> Unit,
    colsWithPanel: Int, onColsPanel: (Int) -> Unit,
    accentHex: String, onAccent: (String) -> Unit,
    iconPath: String, onIconPath: (String) -> Unit,
    backgroundPath: String, onBgPath: (String) -> Unit,
    onBrowseIcon: () -> Unit,
    onBrowseBackground: () -> Unit,
    barIconPath: String = "", onBarIcon: (String) -> Unit = {}, onBrowseBarIcon: () -> Unit = {},
    miniIconPath: String = "", onMiniIcon: (String) -> Unit = {}, onBrowseMiniIcon: () -> Unit = {},
    onMakeBackground: (() -> Unit)? = null,
    intents: List<LaunchIntent>, defaultIntentId: String?,
    onIntentsChange: (List<LaunchIntent>) -> Unit,
    onDefaultChange: (String?) -> Unit,
    androidApps: List<com.maiconerick.xgamelauncher.data.AndroidGameRow> = emptyList(),
    onManageAndroid: (() -> Unit)? = null,
) {
    val neon = LocalNeon.current
    val compact = com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (compact) 10.dp else 32.dp, vertical = if (compact) 10.dp else 20.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (activeTab) {
            PlatformEditTab.AndroidApps -> if (onManageAndroid != null) {
                AndroidAppsSection(apps = androidApps, onManage = onManageAndroid)
            }

            PlatformEditTab.Basics -> BasicsTab(
                name = name, onName = onName,
                artSearchName = artSearchName, onArtSearch = onArtSearch,
                romsPath = romsPath, onRomsPath = onRomsPath,
                extraPaths = extraPaths, onExtraPaths = onExtraPaths,
                extRegex = extRegex, onExtRegex = onExtRegex,
            )


            PlatformEditTab.Skin -> SkinTab(
                accentHex = accentHex, onAccent = onAccent,
                iconPath = iconPath, onIconPath = onIconPath,
                backgroundPath = backgroundPath, onBgPath = onBgPath,
                onBrowseIcon = onBrowseIcon,
                onBrowseBackground = onBrowseBackground,
                barIconPath = barIconPath, onBarIcon = onBarIcon, onBrowseBarIcon = onBrowseBarIcon,
                miniIconPath = miniIconPath, onMiniIcon = onMiniIcon, onBrowseMiniIcon = onBrowseMiniIcon,
                onMakeBackground = onMakeBackground,
            )

            PlatformEditTab.Launchers -> PlayersTab(
                intents = intents, defaultIntentId = defaultIntentId,
                onIntentsChange = onIntentsChange,
                onDefaultChange = onDefaultChange,
                platformName = name, romsPath = romsPath,
            )
        }
    }
}

@Composable
private fun BasicsTab(
    name: String, onName: (String) -> Unit,
    artSearchName: String, onArtSearch: (String) -> Unit,
    romsPath: String, onRomsPath: (String) -> Unit,
    extraPaths: List<String>, onExtraPaths: (List<String>) -> Unit,
    extRegex: String, onExtRegex: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel("IDENTITY")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EditField(modifier = Modifier.weight(1f), label = "NAME",
                value = name, onChange = onName, placeholder = "e.g. Sega Genesis")
            EditField(modifier = Modifier.weight(1f), label = "ART SEARCH NAME",
                value = artSearchName, onChange = onArtSearch, placeholder = "Genesis")
        }

        SectionLabel("ROM FOLDERS")
        // Folder browser target: -1 = primary, n = extra folder n, null = closed.
        var pickFor by remember { mutableStateOf<Int?>(null) }
        pickFor?.let { target ->
            com.maiconerick.xgamelauncher.ui.dialogs.FolderPickerDialog(
                title = if (target < 0) "Primary folder" else "Extra folder ${target + 1}",
                initialPath = (if (target < 0) romsPath else extraPaths.getOrNull(target))?.ifBlank { null },
                onPick = { path ->
                    if (target < 0) onRomsPath(path)
                    else onExtraPaths(extraPaths.toMutableList().also { it[target] = path })
                    pickFor = null
                },
                onDismiss = { pickFor = null },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            EditField(modifier = Modifier.weight(1f), label = "PRIMARY", value = romsPath,
                onChange = onRomsPath, placeholder = "auto-picked from the name, e.g. …/ROMS/gba")
            SmallBtn("PICK FOLDER", accent = true, onClick = { pickFor = -1 })
        }
        if (romsPath.isNotBlank()) {
            // Drop the pre-filled / current primary folder; the first extra (if any) takes over.
            SmallBtn(if (extraPaths.isNotEmpty()) "REMOVE PRIMARY (next folder takes over)" else "REMOVE PRIMARY",
                onClick = {
                    if (extraPaths.isNotEmpty()) {
                        onRomsPath(extraPaths.first())
                        onExtraPaths(extraPaths.drop(1))
                    } else onRomsPath("")
                })
        }
        extraPaths.forEachIndexed { idx, p ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom) {
                EditField(modifier = Modifier.weight(1f),
                    label = "EXTRA ${idx + 1}", value = p,
                    onChange = { v -> onExtraPaths(extraPaths.toMutableList().also { it[idx] = v }) })
                SmallBtn("PICK", accent = true, onClick = { pickFor = idx })
                SmallBtn("REMOVE", onClick = {
                    onExtraPaths(extraPaths.toMutableList().also { it.removeAt(idx) })
                })
            }
        }
        SmallBtn("+ ADD EXTRA FOLDER", onClick = { onExtraPaths(extraPaths + ""); pickFor = extraPaths.size })

        SectionLabel("WHICH FILES ARE GAMES")
        // Extensions are stored as the regex they generate, so both views stay in sync.
        val parsedExts = extensionsFromRegex(extRegex)
        var extText by remember { mutableStateOf(parsedExts?.joinToString(", ") ?: "") }
        LaunchedEffect(extRegex) {
            val p2 = extensionsFromRegex(extRegex)
            if (p2 != null && p2 != extensionsOf(extText)) extText = p2.joinToString(", ")
        }
        EditField(
            label = "FILE EXTENSIONS (comma separated, blank = every file)",
            value = extText,
            placeholder = "gba, zip, 7z",
            onChange = { t ->
                extText = t
                val exts = extensionsOf(t)
                onExtRegex(if (exts.isEmpty()) "" else "^.*\\.(" + exts.joinToString("|") + ")$")
            },
        )
        EditField(
            label = if (extRegex.isNotBlank() && parsedExts == null) "FILENAME REGEX (custom — overrides extensions)"
                    else "FILENAME REGEX (advanced, optional)",
            value = extRegex, onChange = onExtRegex,
            placeholder = "^(.*)\\.(md|gen|smd|zip)$",
        )
    }
}

/** Bullet list of what differs between the platform as opened and as edited. */
private fun describePlatformChanges(a: Platform, b: Platform): String = buildList {
    if (a.name != b.name) add("Name: “${a.name}” → “${b.name}”")
    if (a.romsPath != b.romsPath) add("Primary folder → ${b.romsPath.ifBlank { "(none)" }}")
    if (a.extraPaths != b.extraPaths) add("Extra folders (${a.extraPaths.size} → ${b.extraPaths.size})")
    if (a.fileExtensionsRegex != b.fileExtensionsRegex) add("Which files are games")
    if (a.artSearchName != b.artSearchName) add("Art search name → “${b.artSearchName}”")
    if (a.intents != b.intents) add(
        if (a.intents.size != b.intents.size) "Players (${a.intents.size} → ${b.intents.size})" else "Players edited"
    )
    if (a.defaultIntentId != b.defaultIntentId) add("Default player")
    if (a.columnsWithoutPanel != b.columnsWithoutPanel || a.columnsWithPanel != b.columnsWithPanel) add("Grid columns")
    if (a.accentColorHex != b.accentColorHex) add("Accent colour → ${b.accentColorHex ?: "theme"}")
    if (a.iconImagePath != b.iconImagePath) add("Icon")
    if (a.backgroundImagePath != b.backgroundImagePath) add("Background image")
}.joinToString("\n") { "• $it" }

/** "gba, .zip ,7z" → [gba, zip, 7z] */
private fun extensionsOf(text: String): List<String> =
    text.split(',', ' ', ';').map { it.trim().removePrefix(".").lowercase() }
        .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
        .distinct()

/** Inverse of the generated regex; null when the regex is custom. */
private fun extensionsFromRegex(regex: String): List<String>? {
    if (regex.isBlank()) return emptyList()
    val m = Regex("""^\^\.\*\\\.\((.+)\)\$$""").find(regex.trim()) ?: return null
    val parts = m.groupValues[1].split('|')
    return if (parts.all { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }) parts else null
}

@Composable
private fun SkinTab(
    accentHex: String, onAccent: (String) -> Unit,
    iconPath: String, onIconPath: (String) -> Unit,
    backgroundPath: String, onBgPath: (String) -> Unit,
    onBrowseIcon: () -> Unit,
    onBrowseBackground: () -> Unit,
    barIconPath: String = "", onBarIcon: (String) -> Unit = {}, onBrowseBarIcon: () -> Unit = {},
    miniIconPath: String = "", onMiniIcon: (String) -> Unit = {}, onBrowseMiniIcon: () -> Unit = {},
    onMakeBackground: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AccentColorPicker(accentHex = accentHex, onAccentChange = onAccent)
        if (onMakeBackground != null) SmallBtn("MAKE A BACKGROUND FROM GAME ART…", accent = true, onClick = onMakeBackground)
        ImagePickerRow(label = "BOTTOM BAR ICON (used when the strip shows icons)", path = barIconPath,
            onChange = onBarIcon, onBrowse = onBrowseBarIcon)
        ImagePickerRow(label = "MINI ICON (small icon beside the name in the bottom bar)", path = miniIconPath,
            onChange = onMiniIcon, onBrowse = onBrowseMiniIcon)
        ImagePickerRow(label = "PLATFORM ICON", path = iconPath,
            onChange = onIconPath, onBrowse = onBrowseIcon)
        ImagePickerRow(label = "BACKGROUND IMAGE", path = backgroundPath,
            onChange = onBgPath, onBrowse = onBrowseBackground)
    }
}

// ---------------- SECTION HELPERS ----------------

@Composable
internal fun SectionLabel(text: String) {
    val neon = LocalNeon.current
    Text(text, color = neon.accent, fontSize = 11.sp, letterSpacing = 2.sp,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
internal fun EditField(
    label: String, value: String, onChange: (String) -> Unit,
    placeholder: String = "", modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    mono: Boolean = false,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotEmpty()) Text(label, color = neon.inkMuted, fontSize = 10.sp,
            letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (focused) Color(0x88000000) else Color(0x55000000))
                .border(1.dp, if (focused) neon.accent else Color(0x22FFFFFF), RoundedCornerShape(4.dp))
                .onFocusChanged { focused = it.isFocused },
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = neon.inkMuted, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
            com.maiconerick.xgamelauncher.ui.components.GamepadTextField(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                value = value,
                onValueChange = onChange,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = neon.ink, fontSize = if (mono) 12.sp else 13.sp, fontWeight = FontWeight.Medium,
                    fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                ),
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 4,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(neon.accent),
            )
        }
    }
}

@Composable
private fun ImagePickerRow(
    label: String, path: String, onChange: (String) -> Unit, onBrowse: () -> Unit,
) {
    val neon = LocalNeon.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = neon.inkMuted, fontSize = 10.sp,
            letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (path.isNotBlank()) {
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x33000000))
                        .border(1.dp, neon.accent.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
                ) {
                    AsyncImage(model = path, contentDescription = null,
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            EditField(modifier = Modifier.weight(1f), label = "", value = path,
                onChange = onChange, placeholder = "/storage/emulated/0/…")
            SmallBtn("BROWSE", accent = true, onClick = onBrowse)
            if (path.isNotBlank()) {
                SmallBtn("CLEAR", onClick = { onChange("") })
            }
        }
    }
}

@Composable
internal fun SmallBtn(label: String, accent: Boolean = false, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        accent && focused -> neon.accent.copy(alpha = 0.28f)
        accent -> neon.accent.copy(alpha = 0.14f)
        focused -> Color(0x33FFFFFF)
        else -> Color(0x22FFFFFF)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .border(1.dp, if (focused) neon.accent else Color.Transparent, RoundedCornerShape(3.dp))
            .focusRing(focused, RoundedCornerShape(3.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (accent) neon.accent else neon.inkDim, fontSize = 10.sp,
            letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- ICON BROWSER ----------------

private const val FILESYSTEM_ROOT = "/storage/emulated/0"
private val JUMP_SHORTCUTS = listOf(
    "Icons library" to "/storage/emulated/0/xGameLauncher/platform_icons",
    "Downloads" to "/storage/emulated/0/Download",
    "Pictures" to "/storage/emulated/0/Pictures",
    "DCIM" to "/storage/emulated/0/DCIM",
    "Storage root" to FILESYSTEM_ROOT,
)

@Composable
private fun IconBrowser(
    initialPath: String,
    onPathChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val neon = LocalNeon.current
    var currentDir by remember {
        mutableStateOf(File(initialPath).takeIf { it.isDirectory } ?: File(FILESYSTEM_ROOT))
    }
    LaunchedEffect(currentDir.absolutePath) { onPathChange(currentDir.absolutePath) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(currentDir.absolutePath) {
        entries = withContext(Dispatchers.IO) {
            currentDir.listFiles()?.toList().orEmpty()
                .filter {
                    it.isDirectory ||
                        (it.isFile && it.name.lowercase().let { n ->
                            n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                                n.endsWith(".webp")
                        })
                }
                .sortedWith(compareByDescending<File> { it.isDirectory }
                    .thenBy { it.name.lowercase() })
        }
    }
    // Walk up as far as / — the user can navigate anywhere on the device.
    val canGoUp = currentDir.parentFile != null

    Column(Modifier.fillMaxSize().background(neon.surface).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallBtn("CLOSE", onClick = onCancel)
            if (canGoUp) SmallBtn("↑ UP", onClick = {
                currentDir.parentFile?.let { currentDir = it }
            })
            Text(
                text = currentDir.absolutePath,
                color = neon.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        // Quick-jump shortcuts (Icons library, Downloads, Pictures, DCIM, Storage root).
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            JUMP_SHORTCUTS.forEach { (label, path) ->
                SmallBtn(label, onClick = {
                    File(path).takeIf { it.isDirectory }?.let { currentDir = it }
                })
            }
        }
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) {
            Text("Empty (or unreadable) — pick a folder above.",
                color = neon.inkMuted, fontSize = 12.sp)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(entries, key = { _, f -> f.absolutePath }) { idx, f ->
                    IconEntry(
                        file = f,
                        initiallyFocused = idx == 0,
                        onClick = {
                            if (f.isDirectory) currentDir = f
                            else onPick(f.absolutePath)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun IconEntry(file: File, initiallyFocused: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(initiallyFocused) {
        if (initiallyFocused) runCatching { focusRequester.requestFocus() }
    }
    Column(
        modifier = Modifier
            .aspectRatio(0.85f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) neon.accent.copy(alpha = 0.18f) else Color(0x33000000))
            .border(1.dp, if (focused) neon.accent else Color(0x22FFFFFF), RoundedCornerShape(8.dp))
            .focusRequester(focusRequester)
            .focusRing(focused, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (file.isDirectory) {
                Text("📁", fontSize = 40.sp)
            } else {
                AsyncImage(model = file.absolutePath, contentDescription = file.name,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            text = file.name.removeSuffix(".png"),
            color = if (focused) neon.accent else neon.ink,
            fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// ACCENT COLOR PICKER
// ---------------------------------------------------------------------------

/**
 * Compact color picker for the platform accent — preview swatch + hex text field, a
 * curated palette of neon/primary swatches (click to apply), and a hue slider for
 * anything in between. `accentHex` is the source of truth; every widget writes back to
 * it via [onAccentChange]. Blank hex means "use theme default" (matches the old field).
 */
@Composable
private fun AccentColorPicker(accentHex: String, onAccentChange: (String) -> Unit) {
    val neon = com.maiconerick.xgamelauncher.ui.theme.LocalNeon.current
    val currentColor = parseHexColor(accentHex) ?: neon.accent

    val presets = listOf(
        "#00E5FF", "#00B8D4", "#18FFFF", "#64FFDA", "#00E676", "#76FF03",
        "#FFEA00", "#FFC400", "#FF9100", "#FF3D00", "#FF1744", "#F50057",
        "#D500F9", "#651FFF", "#3D5AFE", "#2979FF", "#FFFFFF", "#B0BEC5",
        "#78909C", "#212121",
    )

    var showPicker by remember { mutableStateOf(false) }
    if (showPicker) {
        com.maiconerick.xgamelauncher.ui.dialogs.ColorPickerDialog(
            initialHex = accentHex.ifBlank { null },
            fallback = neon.accent,
            onApply = { hex -> onAccentChange(hex ?: ""); showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("ACCENT COLOR")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Preview swatch — opens the full picker.
            var swatchFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .focusRing(swatchFocused, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(currentColor)
                    .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(8.dp))
                    .onFocusChanged { swatchFocused = it.isFocused }
                    .focusable()
                    .onGamepadActivate { showPicker = true }
                    .clickable { showPicker = true },
            )
            Box(modifier = Modifier.weight(1f)) {
                EditField(
                    label = "HEX (leave blank for theme default)",
                    value = accentHex,
                    onChange = onAccentChange,
                    placeholder = "#00E5FF",
                )
            }
        }
        // Preset palette — 10 per row, easy for gamepad navigation
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.chunked(10).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { hex ->
                        ColorSwatch(
                            color = parseHexColor(hex) ?: Color.Transparent,
                            selected = accentHex.equals(hex, ignoreCase = true),
                            onClick = { onAccentChange(hex) },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallBtn("PICK COLOUR…", accent = true, onClick = { showPicker = true })
            val isRandom = accentHex.equals("random", ignoreCase = true)
            SmallBtn(
                (if (isRandom) "✓ " else "") + "RANDOM EACH VISIT", accent = isRandom,
                onClick = { onAccentChange(if (isRandom) "" else "random") },
            )
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val neon = com.maiconerick.xgamelauncher.ui.theme.LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(6.dp))
            .focusRing(focused, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Selected = check mark (white rings are reserved for focus).
        if (selected) Text("✓", color = if (color.luminance() > 0.5f) Color.Black else Color.White,
            fontSize = 16.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun HueSlider(currentColor: Color, onHex: (String) -> Unit) {
    // Preserve S+V while we sweep H — extract from the current color.
    val neon = com.maiconerick.xgamelauncher.ui.theme.LocalNeon.current
    val hsv = FloatArray(3).also {
        android.graphics.Color.colorToHSV(
            android.graphics.Color.rgb(
                (currentColor.red * 255).toInt(),
                (currentColor.green * 255).toInt(),
                (currentColor.blue * 255).toInt(),
            ), it,
        )
    }
    val s = if (hsv[1] < 0.4f) 0.9f else hsv[1]  // avoid stuck-on-white
    val v = if (hsv[2] < 0.4f) 0.9f else hsv[2]
    var hue by remember(hsv[0]) { mutableStateOf(hsv[0]) }
    var userDrove by remember { mutableStateOf(false) }
    // Only push hex updates when the slider actually moved this composition — otherwise
    // we'd overwrite the incoming color on every recomposition.
    LaunchedEffect(hue, userDrove) {
        if (!userDrove) return@LaunchedEffect
        val h = FloatArray(3).apply { this[0] = hue; this[1] = s; this[2] = v }
        val argb = android.graphics.Color.HSVToColor(h)
        onHex("#%06X".format(argb and 0xFFFFFF))
    }

    Text("HUE", color = neon.inkMuted, fontSize = 10.sp, letterSpacing = 1.4.sp,
        fontWeight = FontWeight.Medium)

    val hueGradient = androidx.compose.ui.graphics.Brush.horizontalGradient(
        colors = (0..360 step 30).map { deg ->
            val h = FloatArray(3).apply { this[0] = deg.toFloat(); this[1] = s; this[2] = v }
            Color(android.graphics.Color.HSVToColor(h))
        },
    )
    // Rainbow bar with the slider overlaid — the slider draws only its thumb because
    // both track colors are Transparent.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(hueGradient),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Slider(
            value = hue,
            onValueChange = { userDrove = true; hue = it },
            valueRange = 0f..360f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                disabledActiveTrackColor = Color.Transparent,
                disabledInactiveTrackColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val h = hex.trim().removePrefix("#")
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> null
    }
}

// ---------------------------------------------------------------------------
// ANDROID APPS SECTION — only rendered when editing the synthetic Android platform.
// Shows the icons of every app the user has already added + a jump button to the
// full-selection screen (Settings → Android).
// ---------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AndroidAppsSection(
    apps: List<com.maiconerick.xgamelauncher.data.AndroidGameRow>,
    onManage: () -> Unit,
) {
    val neon = com.maiconerick.xgamelauncher.ui.theme.LocalNeon.current
    val ctx = LocalContext.current
    SectionLabel("ANDROID APPS · ${apps.size} added")
    SmallBtn(
        label = "MANAGE ANDROID APPS",
        accent = true,
        onClick = onManage,
    )
    if (apps.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            apps.forEach { row ->
                AndroidAppIconTile(row = row, ctx = ctx, accent = neon.accent)
            }
        }
    } else {
        Text(
            text = "No Android apps added yet. Tap MANAGE ANDROID APPS to pick some.",
            color = neon.inkDim, fontSize = 12.sp,
        )
    }
}

@Composable
private fun AndroidAppIconTile(
    row: com.maiconerick.xgamelauncher.data.AndroidGameRow,
    ctx: android.content.Context,
    accent: Color,
) {
    val icon = remember(row.pkg) {
        runCatching {
            val d = ctx.packageManager.getApplicationIcon(row.pkg)
            val s = 96
            val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, s, s)
            d.draw(android.graphics.Canvas(bmp))
            bmp.asImageBitmap()
        }.getOrNull()
    }
    Column(
        modifier = Modifier.width(80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x22000000)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = icon,
                    contentDescription = row.label,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = row.label.take(1).uppercase(),
                    color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text = row.label,
            color = Color.White,
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
