package com.maiconerick.xgamelauncher.ui.screens

import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Edit

import androidx.compose.material.icons.filled.SystemUpdate
import com.maiconerick.xgamelauncher.ui.components.focusEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.focusGroup
import com.maiconerick.xgamelauncher.ui.components.focusRing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.android.AndroidAppSource
import com.maiconerick.xgamelauncher.data.LaunchIntent
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.prefs.PrefsStore
import com.maiconerick.xgamelauncher.ui.LauncherViewModel
import com.maiconerick.xgamelauncher.ui.components.TopBar
import com.maiconerick.xgamelauncher.ui.components.TopTab
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal enum class SettingsSection(
    val label: String,
    val deeplinkId: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val subtitle: String,
) {
    Platforms("Platforms", "platforms", androidx.compose.material.icons.Icons.Filled.VideogameAsset,
        "Add platforms, pick folders, set up players and skins."),
    Players("Players", "players", androidx.compose.material.icons.Icons.Filled.PlayCircle,
        "Every emulator configured across your platforms."),
    AndroidApps("Android apps", "android", androidx.compose.material.icons.Icons.Filled.Android,
        "Pick which installed apps show up as games."),
    Art("Art providers", "art", androidx.compose.material.icons.Icons.Filled.Image,
        "SteamGridDB, IGDB, RAWG and ScreenScraper keys."),
    Playtime("Playtime", "playtime", androidx.compose.material.icons.Icons.Filled.Timer,
        "Tracker, overlay, Dropbox backup, history import, edit sessions."),
    Theme("Theme", "theme", androidx.compose.material.icons.Icons.Filled.Brush,
        "Neon or Halloween (normal / AMOLED), each with its own animations."),
    Appearance("Appearance", "appearance", androidx.compose.material.icons.Icons.Filled.Palette,
        "Bottom bar, focus border and other visual options."),
    Device("Device", "device", androidx.compose.material.icons.Icons.Filled.Tune,
        "Compact layout, ROM folder access, this handheld."),
    Updates("Updates", "updates", androidx.compose.material.icons.Icons.Filled.SystemUpdate,
        "Check for a new version and install it."),
    About("About", "about", androidx.compose.material.icons.Icons.Filled.Info,
        "Version and credits.");

    companion object {
        fun byDeeplink(id: String?): SettingsSection? =
            id?.let { key -> values().firstOrNull { it.deeplinkId == key } }
    }
}

/**
 * Daijishō-style settings: an index page of rows (icon · title · subtitle); each row opens
 * its section as its own page (a separate route, so B returns to the index).
 */
@Composable
fun SettingsScreen(
    vm: LauncherViewModel,
    prefs: PrefsStore,
    onTab: (TopTab) -> Unit,
    onRefreshLibrary: () -> Unit,
    initialSectionId: String? = null,
    /** Opens the full-screen platform editor (null = new platform). */
    onEditPlatform: (String?) -> Unit = {},
    /** Opens a section page (pushes Route.Settings(id)). */
    onOpenSection: (String) -> Unit = {},
    /** Row to focus when coming back to the index. */
    focusSectionId: String? = null,
    /** Opens the playtime editor (Route.PlaytimeEditor). */
    onEditPlaytime: () -> Unit = {},
) {
    val section = SettingsSection.byDeeplink(initialSectionId)
    Column(Modifier.fillMaxSize()) {
        TopBar(activeTab = TopTab.Settings, onTab = onTab)
        if (section == null) {
            SettingsIndex(focusSectionId = focusSectionId, onOpen = { onOpenSection(it.deeplinkId) })
        } else {
            com.maiconerick.xgamelauncher.ui.components.PageHeader(icon = section.icon, title = section.label)
            // Put the gamepad cursor on the section's first control as soon as it opens.
            val enter = remember { FocusRequester() }
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            LaunchedEffect(section) {
                kotlinx.coroutines.delay(50)
                val ok = runCatching { enter.requestFocus() }.isSuccess
                if (!ok) focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
            }
            // Readability: opaque panel (no starfield behind text) and brighter greys.
            val base = LocalNeon.current
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .background(base.surfaceElevated)
                    .focusRequester(enter)
                    .focusGroup(),
            ) {
              CompositionLocalProvider(
                  LocalNeon provides base.copy(inkMuted = base.inkDim, inkDim = Color(0xFFD9E0E6)),
              ) {
                when (section) {
                    SettingsSection.Platforms -> PlatformsSection(vm, onEditPlatform)
                    SettingsSection.Players -> PlayersSection(vm)
                    SettingsSection.Device -> DeviceSection(prefs)
                    SettingsSection.Theme -> ThemeSection(prefs)
                    SettingsSection.Appearance -> AppearanceSection(prefs)
                    SettingsSection.AndroidApps -> AndroidAppsSection(vm)
                    SettingsSection.Art -> ArtSection(prefs)
                    SettingsSection.Playtime -> PlaytimeSection(vm, prefs, onRefreshLibrary, onEditPlaytime)
                    SettingsSection.Updates -> UpdatesSection(prefs)
                    SettingsSection.About -> AboutSection()
                }
              }
            }
        }
    }
}

@Composable
private fun SettingsIndex(focusSectionId: String?, onOpen: (SettingsSection) -> Unit) {
    val ctx = LocalContext.current
    val requesters = remember { SettingsSection.values().associateWith { FocusRequester() } }
    val systemRequester = remember { FocusRequester() }
    LaunchedEffect(focusSectionId) {
        val target = SettingsSection.byDeeplink(focusSectionId)?.let { requesters[it] } ?: systemRequester
        runCatching { target.requestFocus() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        com.maiconerick.xgamelauncher.ui.components.PageHeader(
            icon = androidx.compose.material.icons.Icons.Filled.Settings, title = "Settings",
        )
        com.maiconerick.xgamelauncher.ui.components.ListRow(
            icon = androidx.compose.material.icons.Icons.Filled.Android,
            title = "System settings",
            subtitle = "Open Android system settings.",
            focusRequester = systemRequester,
            onClick = {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
        com.maiconerick.xgamelauncher.ui.components.ListDivider()
        val groups = listOf(
            "Library" to listOf(SettingsSection.Platforms, SettingsSection.Players,
                SettingsSection.AndroidApps),
            "Artwork & playtime" to listOf(SettingsSection.Art, SettingsSection.Playtime),
            "Look & feel" to listOf(SettingsSection.Theme, SettingsSection.Appearance),
            "This device" to listOf(SettingsSection.Device, SettingsSection.About),
        )
        groups.forEach { (label, sections) ->
            com.maiconerick.xgamelauncher.ui.components.ListSectionLabel(label)
            sections.forEach { sec ->
                com.maiconerick.xgamelauncher.ui.components.ListRow(
                    icon = sec.icon, title = sec.label, subtitle = sec.subtitle,
                    focusRequester = requesters.getValue(sec),
                    onClick = { onOpen(sec) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- PLATFORMS ----------------

@Composable
private fun PlatformsSection(vm: LauncherViewModel, onEditPlatform: (String?) -> Unit) {
    val neon = LocalNeon.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsState()

    var userPlatforms by remember { mutableStateOf<List<Platform>>(emptyList()) }

    var hiddenIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(state.platforms) {
        userPlatforms = vm.userPlatforms()
        hiddenIds = vm.hiddenPlatformIds()
    }
    val picker = com.maiconerick.xgamelauncher.ui.LocalPicker.current
    fun confirmDelete(p: Platform) = picker(
        com.maiconerick.xgamelauncher.ui.PickerRequest(
            title = "Delete ${p.name.ifBlank { "this platform" }}?",
            message = "It leaves the library (restore it below). ROM files are not touched.",
            options = listOf(
                com.maiconerick.xgamelauncher.ui.PickerOption("cancel", "Cancel"),
                com.maiconerick.xgamelauncher.ui.PickerOption("delete", "Delete platform"),
            ),
            onPick = { id -> picker(null); if (id == "delete") vm.deletePlatform(p.id) },
            onCancel = { picker(null) },
        )
    )

    SectionPane(title = "Platforms", subtitle = "Add platforms, or tap EDIT to set up players, skin and layout for any platform.") {
        // Platforms discovered from Daijishō / the ROMS folder
        if (state.platforms.isNotEmpty()) {
            Text(
                "DISCOVERED",
                color = neon.inkMuted,
                fontSize = 10.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Medium,
            )
            state.platforms.filter { p -> userPlatforms.none { it.id == p.id } }.forEach { p ->
                PlatformRow(p, editable = true, onEdit = { onEditPlatform(p.id) }, onDelete = { confirmDelete(p) })
            }
            Spacer(Modifier.height(4.dp))
        }

        // User platforms (editable)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "CUSTOM",
                color = neon.inkMuted,
                fontSize = 10.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            AddPlatformButton(onClick = { onEditPlatform(null) })
        }

        if (userPlatforms.isEmpty()) {
            Text(
                text = "None yet. Tap “+ Add platform” to configure one.",
                color = neon.inkMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        userPlatforms.forEach { p ->
            PlatformRow(
                platform = p,
                editable = true,
                onEdit = { onEditPlatform(p.id) },
                onDelete = { confirmDelete(p) },
            )
        }

        if (hiddenIds.isNotEmpty()) {
            GroupLabel("DELETED (HIDDEN)")
            hiddenIds.sorted().forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(id, color = neon.inkDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    SmallChip(label = "RESTORE", accent = true, onClick = { vm.restorePlatform(id) })
                }
            }
        }

    }
}

@Composable
private fun PlatformRow(
    platform: Platform,
    editable: Boolean,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val neon = LocalNeon.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x22000000))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = platform.name.ifBlank { "(unnamed)" },
                color = neon.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = platform.romsPath,
                color = neon.inkMuted,
                fontSize = 11.sp,
            )
        }
        if (editable) {
            SmallChip(label = "EDIT", accent = true, onClick = onEdit)
            if (onDelete != null) {
                Spacer(Modifier.width(6.dp))
                SmallChip(label = "DELETE", accent = false, onClick = onDelete)
            }
        } else {
            Text(
                text = "AUTO",
                color = neon.inkMuted,
                fontSize = 9.sp,
                letterSpacing = 1.4.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SmallChip(label: String, accent: Boolean, onClick: () -> Unit) {
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
            .focusRing(focused, RoundedCornerShape(3.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (accent) neon.accent else neon.inkDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
        )
    }
}

@Composable
private fun AddPlatformButton(onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (focused) neon.accent.copy(alpha = 0.24f)
                else neon.accent.copy(alpha = 0.14f)
            )
            .focusRing(focused, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "+ ADD PLATFORM",
            color = neon.accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
        )
    }
}

// ---------------- PLAYERS ----------------

@Composable
private fun PlayersSection(vm: LauncherViewModel) {
    val neon = LocalNeon.current
    val state by vm.state.collectAsState()
    val players = state.discoveredEmulators
    val auto = players.filter { it.source == "auto-winlator" }
    val fromPlatforms = players.filter { it.source == "platform-intent" }
    SectionPane(
        title = "Players",
        subtitle = "Emulators / launchers detected across your platforms. Players are edited per platform: open a platform's editor (Y, or Settings → Platforms → EDIT) → Players, where you can pick an app, its activity and extras, or import from Daijishō's database.",
    ) {
        if (auto.isNotEmpty()) {
            GroupLabel("AUTO-DETECTED WINLATORS")
            auto.forEach { PlayerRow(it) }
            Spacer(Modifier.height(8.dp))
        }
        if (fromPlatforms.isNotEmpty()) {
            GroupLabel("FROM PLATFORM INTENTS")
            fromPlatforms.forEach { PlayerRow(it) }
        }
        if (players.isEmpty()) {
            Text("None detected yet. Add a platform in Platforms, or drop a .desktop file in the Windows forwarder folder.",
                color = neon.inkMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlayerRow(p: com.maiconerick.xgamelauncher.data.DiscoveredEmulator) {
    val neon = LocalNeon.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x22000000))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.displayName, color = neon.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = buildString {
                    append(p.platformNames.joinToString("·"))
                    if (p.pkg != null) append("  ·  ").append(p.pkg)
                },
                color = neon.inkMuted, fontSize = 10.sp,
            )
        }
        Text(
            text = if (p.source == "auto-winlator") "AUTO" else "PLATFORM",
            color = neon.inkMuted,
            fontSize = 9.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------------- ANDROID APPS ----------------

private enum class AndroidAppSort(val label: String) {
    NameAsc("Name ↑"),
    NameDesc("Name ↓"),
    RecentlyInstalled("Recently installed"),
    IncludedFirst("Included first"),
}

@Composable
private fun AndroidAppsSection(vm: LauncherViewModel) {
    val neon = LocalNeon.current
    val context = LocalContext.current
    val state by vm.state.collectAsState()

    var installed by remember { mutableStateOf<List<AndroidAppSource.InstalledApp>>(emptyList()) }
    LaunchedEffect(Unit) { installed = AndroidAppSource.listInstalled(context) }

    val includedPkgs = remember(state) {
        state.androidRowByGameId.values.map { it.pkg }.toSet()
    }
    val includedCount = includedPkgs.size

    var sort by remember { mutableStateOf(AndroidAppSort.NameAsc) }
    val sortedApps = remember(installed, sort, includedPkgs) {
        when (sort) {
            AndroidAppSort.NameAsc -> installed.sortedBy { it.label.lowercase() }
            AndroidAppSort.NameDesc -> installed.sortedByDescending { it.label.lowercase() }
            AndroidAppSort.RecentlyInstalled -> installed.sortedByDescending { it.firstInstallTime }
            AndroidAppSort.IncludedFirst -> installed.sortedWith(
                compareByDescending<AndroidAppSource.InstalledApp> { it.pkg in includedPkgs }
                    .thenBy { it.label.lowercase() }
            )
        }
    }

    SectionPane(
        title = "Android Apps",
        subtitle = "Pick which installed apps show up as launchable games. $includedCount selected.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SORT",
                color = neon.inkMuted,
                fontSize = 10.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AndroidAppSort.values().forEach { mode ->
                    AndroidSortChip(label = mode.label, selected = mode == sort,
                        onClick = { sort = mode })
                }
            }
        }
        if (installed.isEmpty()) {
            Text("Reading installed apps…", color = neon.inkMuted, fontSize = 12.sp)
        } else {
            sortedApps.forEach { app ->
                AndroidAppRow(
                    app = app,
                    included = app.pkg in includedPkgs,
                    onToggle = { checked ->
                        vm.setAndroidApp(app.pkg, app.label, app.launchComponent, checked)
                    },
                )
            }
        }
    }
}

@Composable
private fun AndroidSortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        selected -> neon.accent.copy(alpha = 0.24f)
        focused -> Color(0x33FFFFFF)
        else -> Color(0x22FFFFFF)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(
                width = 1.dp,
                color = when {
                    selected -> neon.accent
                    focused -> neon.accent.copy(alpha = 0.6f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(4.dp),
            )
            .focusRing(focused, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) neon.accent else neon.ink,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun AndroidAppRow(
    app: AndroidAppSource.InstalledApp,
    included: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val neon = LocalNeon.current
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color(0x33FFFFFF)
        included -> neon.accent.copy(alpha = 0.08f)
        else -> Color(0x14000000)
    }
    // PackageManager icon → ImageBitmap, cached per pkg for the row's lifetime.
    val icon = remember(app.pkg) {
        runCatching {
            val d = context.packageManager.getApplicationIcon(app.pkg)
            val s = 96
            val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, s, s)
            d.draw(android.graphics.Canvas(bmp))
            bmp.asImageBitmap()
        }.getOrNull()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .focusRing(focused, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate { onToggle(!included) }
            .clickable { onToggle(!included) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Checkbox indicator
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (included) neon.accent else Color(0x33FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            if (included) {
                Text(
                    text = "✓",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // App icon so the picker scans faster visually.
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x22000000)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = icon,
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            } else {
                Text(
                    text = app.label.take(1).uppercase(),
                    color = neon.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                color = if (included) neon.accent else neon.ink,
                fontSize = 13.sp,
                fontWeight = if (included) FontWeight.SemiBold else FontWeight.Medium,
            )
            Text(
                text = app.pkg,
                color = neon.inkMuted,
                fontSize = 10.sp,
            )
        }
    }
}

// ---------------- ART ----------------

@Composable
private fun ArtSection(prefs: PrefsStore) {
    val neon = LocalNeon.current
    val scope = rememberCoroutineScope()
    val onArtPicker = com.maiconerick.xgamelauncher.ui.LocalOnArtPicker.current
    val vm: LauncherViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var sgdbKey by remember { mutableStateOf("") }
    var ssUser by remember { mutableStateOf("") }
    var ssPass by remember { mutableStateOf("") }
    var rawgKey by remember { mutableStateOf("") }
    var igdbId by remember { mutableStateOf("") }
    var igdbSecret by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        sgdbKey = prefs.steamGridDbKey.first()
        ssUser = prefs.screenScraperUser.first()
        ssPass = prefs.screenScraperPass.first()
        rawgKey = prefs.rawgApiKey.first()
        igdbId = prefs.igdbClientId.first()
        igdbSecret = prefs.igdbClientSecret.first()
    }
    SectionPane(title = "Art providers", subtitle = "Where box art, heroes, and logos come from.") {
        LabeledField("STEAMGRIDDB API KEY", sgdbKey, { sgdbKey = it }) {
            scope.launch { prefs.setSgdbKey(sgdbKey) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LabeledField(
                modifier = Modifier.weight(1f),
                label = "SCREENSCRAPER USER",
                value = ssUser,
                onChange = { ssUser = it },
                onCommit = { scope.launch { prefs.setScreenScraperCreds(ssUser, ssPass) } },
            )
            LabeledField(
                modifier = Modifier.weight(1f),
                label = "SCREENSCRAPER PASS",
                value = ssPass,
                onChange = { ssPass = it },
                onCommit = { scope.launch { prefs.setScreenScraperCreds(ssUser, ssPass) } },
            )
        }
        Text(
            text = "SteamGridDB is the primary lookup; ScreenScraper is a fallback for platforms SGDB doesn't cover.",
            color = neon.inkMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        // RAWG
        LabeledField("RAWG API KEY", rawgKey, { rawgKey = it }) {
            scope.launch { prefs.setRawgApiKey(rawgKey) }
        }
        // IGDB
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LabeledField(
                modifier = Modifier.weight(1f),
                label = "IGDB / TWITCH CLIENT ID",
                value = igdbId, onChange = { igdbId = it },
                onCommit = { scope.launch { prefs.setIgdbCreds(igdbId, igdbSecret) } },
            )
            LabeledField(
                modifier = Modifier.weight(1f),
                label = "IGDB / TWITCH CLIENT SECRET",
                value = igdbSecret, onChange = { igdbSecret = it },
                onCommit = { scope.launch { prefs.setIgdbCreds(igdbId, igdbSecret) } },
            )
        }
        Text(
            text = "RAWG needs one free API key. IGDB uses Twitch app credentials — create an app on dev.twitch.tv, paste client id + secret; no user login flow needed. Both feed the interactive art picker below.",
            color = neon.inkMuted, fontSize = 11.sp, lineHeight = 16.sp,
        )

        // Manual picker trigger
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 6.dp)) {
            SmallChip(
                label = "PICK ART FOR MISSING GAMES",
                accent = true,
                onClick = {
                    scope.launch {
                        val ids = vm.missingArtGameIds()
                        onArtPicker(ids)
                    }
                },
            )
        }
    }
}

// ---------------- PLAYTIME ----------------

@Composable
private fun PlaytimeSection(
    vm: LauncherViewModel,
    prefs: PrefsStore,
    onRefreshLibrary: () -> Unit,
    onEditPlaytime: () -> Unit,
) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var xpttDb by remember { mutableStateOf("") }
    var skipXptt by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        xpttDb = prefs.xpttDbPath.first()
        skipXptt = prefs.skipXpttRead.first()
    }
    val hasUsageAccess = remember(importStatus) { hasUsageAccess(ctx) }
    SectionPane(title = "Playtime",
        subtitle = "xGameLauncher records every session itself (watches the game via Usage access, closes on background / screen-off). Old xPlayTimeTracker history can be imported below.") {
        com.maiconerick.xgamelauncher.ui.components.ListRow(
            icon = androidx.compose.material.icons.Icons.Filled.Edit,
            title = "Edit playtime",
            subtitle = "Fix, add or delete sessions: any column. Changes are mirrored to the Dropbox backup.",
            onClick = onEditPlaytime,
        )
        var overlayOn by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { overlayOn = prefs.overlayEnabled.first() }
        ToggleRow(
            label = "OVERLAY BANNER",
            subtitle = "Floating neon banner on session start / lid open / lid closed / game finish — drawn OVER the running game. Needs 'Display over other apps' permission.",
            value = overlayOn,
            onChange = { on ->
                overlayOn = on
                scope.launch { prefs.setOverlayEnabled(on) }
            },
        )
        val canOverlay = remember(overlayOn) { android.provider.Settings.canDrawOverlays(ctx) }
        if (overlayOn && !canOverlay) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "⚠  'Display over other apps' isn't granted — banner won't appear.",
                    color = Color(0xFFFFB300), fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                SmallChip(label = "GRANT OVERLAY", accent = true, onClick = {
                    val i = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${ctx.packageName}")
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(i) }
                })
            }
        }

        if (!hasUsageAccess) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "⚠  Usage access is not granted — the tracker can't watch the game.",
                    color = Color(0xFFFFB300), fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                SmallChip(label = "GRANT USAGE ACCESS", accent = true,
                    onClick = { openUsageAccessSettings(ctx) })
            }
        }
        LabeledField(
            label = "XPTT PLAYTIME DATABASE",
            value = xpttDb,
            onChange = { xpttDb = it },
            onCommit = { scope.launch { prefs.setXpttDbPath(xpttDb); onRefreshLibrary() } },
        )

        // Import + skip-xPTT
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallChip(
                label = if (busy) "IMPORTING…" else "IMPORT XPTT SESSIONS",
                accent = true,
                onClick = {
                    if (busy) return@SmallChip
                    scope.launch {
                        busy = true
                        val r = vm.importXpttSessions()
                        busy = false
                        importStatus = when {
                            r.error != null -> "Error: ${r.error}"
                            r.imported == 0 && r.skipped == 0 -> "Nothing to import"
                            else -> "Imported ${r.imported} · skipped ${r.skipped} · xPTT reads now paused"
                        }
                        skipXptt = prefs.skipXpttRead.first()
                    }
                },
            )
            if (skipXptt) {
                Text("· xPTT reads PAUSED", color = neon.inkMuted, fontSize = 10.sp,
                    letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                SmallChip(label = "RESUME XPTT", accent = false, onClick = {
                    scope.launch {
                        prefs.setSkipXpttRead(false); skipXptt = false; onRefreshLibrary()
                    }
                })
            }
        }
        importStatus?.let {
            Text(it, color = neon.inkMuted, fontSize = 11.sp)
        }
        Text(
            text = "Import copies every row from your xPTT sqlite into xGameLauncher's own db, tagged `source=xptt` so history stays merged after xPTT is uninstalled. After a successful import the launcher stops reading xPTT's file (Resume above puts it back).",
            color = neon.inkMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        DropboxRow(vm = vm, prefs = prefs, onRefreshLibrary = onRefreshLibrary)
        SessionLog(vm)
    }
}

/**
 * The tracker's latest session rows with every DB column, and whether each one is already
 * in the Dropbox backup (a row is uploaded when it ended before the last successful backup).
 */
@Composable
private fun SessionLog(vm: LauncherViewModel) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<com.maiconerick.xgamelauncher.data.SessionRow>>(emptyList()) }
    var status by remember { mutableStateOf(com.maiconerick.xgamelauncher.net.Dropbox.backupStatus(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            rows = vm.latestSessions(30)
            status = com.maiconerick.xgamelauncher.net.Dropbox.backupStatus(ctx)
            kotlinx.coroutines.delay(5_000)
        }
    }
    val fmt = remember { java.text.SimpleDateFormat("MMM d HH:mm:ss", java.util.Locale.getDefault()) }
    fun t(unix: Long?) = unix?.let { fmt.format(java.util.Date(it * 1000)) } ?: "—"
    GroupLabel("LATEST SESSIONS")
    Text(
        text = if (status.lastSuccessMs > 0) "Last Dropbox backup: ${fmt.format(java.util.Date(status.lastSuccessMs))}" +
            (status.lastError?.let { " · last attempt failed: $it" } ?: "")
        else "No successful Dropbox backup yet" + (status.lastError?.let { " · $it" } ?: ""),
        color = if (status.lastError != null) Color(0xFFFFB74D) else neon.inkDim, fontSize = 12.sp,
    )
    if (rows.isEmpty()) Text("No sessions recorded yet.", color = neon.inkDim, fontSize = 12.sp)
    rows.forEach { r ->
        val endedMs = (r.endedUnix ?: 0L) * 1000
        val uploaded = !r.isOpen && r.endedUnix != null && status.lastSuccessMs >= endedMs
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0x14FFFFFF))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(6.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${r.id} ${r.gameName}", color = neon.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, modifier = Modifier.weight(1f))
                Text(
                    when { r.isOpen -> "● LIVE"; uploaded -> "✓ in Dropbox"; else -> "not uploaded yet" },
                    color = when { r.isOpen -> Color(0xFFFF5555); uploaded -> Color(0xFF4ADE80); else -> Color(0xFFFFB74D) },
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
            }
            Text("platform=${r.platform} · emulator=${r.emulator} · pkg=${r.pkg}", color = neon.inkDim, fontSize = 11.sp)
            Text("started=${t(r.startedUnix)} · ended=${t(r.endedUnix)} · played=${r.timePlayedSeconds ?: "—"}s",
                color = neon.inkDim, fontSize = 11.sp)
            Text("endReason=${r.endReason ?: "—"} · user=${r.user} · device=${r.device} · source=${r.source} · isOpen=${r.isOpen}",
                color = neon.inkDim, fontSize = 11.sp)
            r.artPath?.let { Text("artPath=$it", color = neon.inkMuted, fontSize = 10.sp, maxLines = 1) }
        }
    }
}

@Composable
private fun DropboxRow(
    vm: LauncherViewModel,
    prefs: PrefsStore,
    onRefreshLibrary: () -> Unit,
) {
    val neon = LocalNeon.current
    val scope = rememberCoroutineScope()
    var backup by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf("") }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        backup = prefs.dropboxBackup.first()
        device = prefs.deviceName.first()
    }
    GroupLabel("DROPBOX SYNC")
    ToggleRow(
        label = "BACKUP ON SESSION END",
        subtitle = "After every game session, upload xgl.db to /PlayTime/DB/xGameLauncher_<device>.db. Uses xPTT's Dropbox app credentials — no sign-in needed.",
        value = backup,
        onChange = {
            backup = it
            scope.launch { prefs.setDropboxBackup(it) }
        },
    )
    LabeledField(
        label = "DEVICE NAME (used in the uploaded filename)",
        value = device,
        onChange = { device = it },
        onCommit = { scope.launch { prefs.setDeviceName(device.ifBlank {
            com.maiconerick.xgamelauncher.ui.DeviceProfile.defaultDeviceName() }) } },
    )
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SmallChip(
            label = if (busy) "SYNCING…" else "SYNC FROM DROPBOX",
            accent = true,
            onClick = {
                if (busy) return@SmallChip
                scope.launch {
                    busy = true
                    val r = vm.syncFromDropbox()
                    busy = false
                    syncStatus = when {
                        r.errors.isNotEmpty() && r.imported == 0 ->
                            "Errors: ${r.errors.joinToString("; ")}"
                        r.files == 0 -> "No other-device DBs found"
                        else -> "${r.files} files · imported ${r.imported} · skipped ${r.skipped}" +
                            if (r.errors.isNotEmpty()) " · ${r.errors.size} error(s)" else ""
                    }
                }
            },
        )
        SmallChip(label = if (busy) "…" else "BACKUP NOW", accent = false, onClick = {
            if (busy) return@SmallChip
            scope.launch {
                busy = true
                val ok = vm.backupToDropbox()
                busy = false
                syncStatus = if (ok) "Backup uploaded" else "Backup failed"
            }
        })
    }
    syncStatus?.let {
        Text(it, color = neon.inkMuted, fontSize = 11.sp)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    subtitle: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(6.dp))
            .focusRing(focused, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate { onChange(!value) }
            .clickable { onChange(!value) }
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (value) neon.accent else Color(0x33FFFFFF)),
            contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(2.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (value) Color.Black else Color(0x99FFFFFF)),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = neon.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
            Text(subtitle, color = neon.inkMuted, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Group header inside a section page: accent divider + accent label. */
@Composable
private fun GroupLabel(text: String) {
    val neon = LocalNeon.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(neon.accent.copy(alpha = 0.35f)))
        Spacer(Modifier.height(10.dp))
        Text(text, color = neon.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    }
}

// ---------------- APPEARANCE ----------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(prefs: PrefsStore) {
    val scope = rememberCoroutineScope()
    val showHints by prefs.showButtonHints.collectAsState(initial = true)
    // Focus + bar settings belong to the active theme (each theme keeps its own).
    val theme = com.maiconerick.xgamelauncher.ui.theme.LocalAppTheme.current
    val tg = theme.group
    val focusA by remember(tg) { prefs.focusColorAOf(tg) }.collectAsState(initial = "")
    val focusB by remember(tg) { prefs.focusColorBOf(tg) }.collectAsState(initial = "")
    var editing by remember { mutableStateOf<String?>(null) } // "a" | "b"
    val neon = LocalNeon.current
    editing?.let { which ->
        com.maiconerick.xgamelauncher.ui.dialogs.ColorPickerDialog(
            initialHex = (if (which == "a") focusA else focusB).ifBlank { null },
            fallback = if (which == "a") neon.accent else Color.White,
            onApply = { hex ->
                scope.launch {
                    if (which == "a") prefs.setFocusColorsOf(tg, hex ?: "", focusB)
                    else prefs.setFocusColorsOf(tg, focusA, hex ?: "")
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    SectionPane(title = "Appearance", subtitle = "How the launcher looks.") {
        Text("Focus and bar animations below belong to the ${theme.label} theme; every theme keeps its own (Settings → Theme).",
            color = neon.inkDim, fontSize = 12.sp)
        GroupLabel("FOCUS BORDER COLOURS")
        Text("The animated border on whatever is selected: a base line plus a travelling highlight.",
            color = neon.inkDim, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ColorChoice("BASE", com.maiconerick.xgamelauncher.ui.dialogs.parseHex(focusA) ?: neon.accent,
                if (focusA.isBlank()) "accent" else focusA) { editing = "a" }
            ColorChoice("HIGHLIGHT", com.maiconerick.xgamelauncher.ui.dialogs.parseHex(focusB) ?: Color.White,
                if (focusB.isBlank()) "white" else focusB) { editing = "b" }
            if (focusA.isNotBlank() || focusB.isNotBlank()) SmallChip("RESET", accent = false,
                onClick = { scope.launch { prefs.setFocusColorsOf(tg, "", "") } })
        }
        val navOn by prefs.navSounds.collectAsState(initial = false)
        val navVol by prefs.navVolume.collectAsState(initial = 50)
        // ---- focus animation (live previews) ----
        val focusKind by remember(tg) { prefs.focusKindOf(tg, theme.defaultFocusKind) }.collectAsState(initial = theme.defaultFocusKind)
        GroupLabel("FOCUS ANIMATION")
        Text("How the selected item is highlighted. Each card below is a live preview.",
            color = neon.inkDim, fontSize = 12.sp)
        val fStyle = com.maiconerick.xgamelauncher.ui.components.FocusStyle(
            com.maiconerick.xgamelauncher.ui.dialogs.parseHex(focusA),
            com.maiconerick.xgamelauncher.ui.dialogs.parseHex(focusB),
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            com.maiconerick.xgamelauncher.ui.components.FocusStyle.KINDS.forEach { (kind, desc) ->
                FocusPreviewCard(
                    title = desc.substringBefore(" —"),
                    selected = focusKind == kind,
                    style = fStyle.copy(kind = kind),
                    onClick = { scope.launch { prefs.setFocusKindOf(tg, kind) } },
                )
            }
        }

        // ---- bar animation (live preview + sliders) ----
        val barKind by remember(tg) { prefs.barFxKindOf(tg, theme.defaultBarFx) }.collectAsState(initial = theme.defaultBarFx)
        val barSpeed by remember(tg) { prefs.barFxSpeedOf(tg) }.collectAsState(initial = 0.35f)
        val barAmount by remember(tg) { prefs.barFxAmountOf(tg) }.collectAsState(initial = 0.5f)
        GroupLabel("TOP & BOTTOM BAR ANIMATION")
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.maiconerick.xgamelauncher.ui.components.BarFxStyle.KINDS.forEach { (kind, label) ->
                SmallChip(label = if (barKind == kind) "● $label" else label, accent = barKind == kind,
                    onClick = { scope.launch { prefs.setBarFxOf(tg, kind, barSpeed, barAmount) } })
            }
        }
        // Live preview: a bar-sized strip running the current settings.
        Box(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(6.dp)).background(theme.barColor)
                .border(1.dp, neon.accent.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.CenterStart,
        ) {
            com.maiconerick.xgamelauncher.ui.components.BarFx(
                Modifier.matchParentSize(),
                com.maiconerick.xgamelauncher.ui.components.BarFxStyle(barKind, barSpeed, barAmount),
            )
            Text(if (barKind == "none") "  Preview (off)" else "  Preview", color = neon.ink, fontSize = 12.sp)
        }
        if (barKind != "none") {
            com.maiconerick.xgamelauncher.ui.components.GamepadSlider(
                label = "SPEED", value = barSpeed,
                onChange = { v -> scope.launch { prefs.setBarFxOf(tg, barKind, v, barAmount) } },
                valueLabel = { "%.1fx".format(0.25f + it * 2.75f) },
            )
            com.maiconerick.xgamelauncher.ui.components.GamepadSlider(
                label = "AMOUNT", value = barAmount,
                onChange = { v -> scope.launch { prefs.setBarFxOf(tg, barKind, barSpeed, v) } },
            )
        }
        if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.lowPower) {
            Text("Low-power device: bar animations stay off and focus uses a plain outline to keep things smooth.",
                color = Color(0xFFFFB74D), fontSize = 12.sp)
        }

        val highRefresh by prefs.highRefresh.collectAsState(initial = false)
        GroupLabel("SMOOTHNESS")
        ToggleRow(
            label = "HIGH REFRESH RATE",
            subtitle = "Use the screen's fastest mode (120Hz on the Thor) for smoother scrolling and animations. Uses a bit more battery; no effect on 60Hz screens.",
            value = highRefresh,
            onChange = { v -> scope.launch { prefs.setHighRefresh(v) } },
        )
        GroupLabel("SOUNDS")
        ToggleRow(
            label = "PIXEL NAVIGATION SOUNDS",
            subtitle = "Retro blips when moving (D-pad), selecting (A) and going back (B).",
            value = navOn,
            onChange = { v -> scope.launch { prefs.setNavSounds(v) } },
        )
        if (navOn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("VOLUME", color = neon.inkDim, fontSize = 11.sp, letterSpacing = 1.2.sp)
            listOf(25, 50, 75, 100).forEach { v ->
                SmallChip(label = if (navVol == v) "● $v%" else "$v%", accent = navVol == v,
                    onClick = { scope.launch { prefs.setNavVolume(v); com.maiconerick.xgamelauncher.ui.NavSounds.select() } })
            }
        }
        GroupLabel("HOME")
        val fullBleed by prefs.hubFullBleed.collectAsState(initial = false)
        ToggleRow(
            label = "PLATFORM BACKGROUND FILLS THE SCREEN",
            subtitle = "Show the focused platform's background edge to edge between the top and bottom bars, instead of a rounded card with a border.",
            value = fullBleed,
            onChange = { v -> scope.launch { prefs.setHubFullBleed(v) } },
        )
        if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.multiDisplay) {
            GroupLabel("BOTTOM SCREEN")
            val bgKind by prefs.toolString(com.maiconerick.xgamelauncher.bottom.BottomHomeActivity.BG_KIND, "system").collectAsState(initial = "system")
            Text("BACKGROUND", color = neon.inkDim, fontSize = 11.sp, letterSpacing = 1.2.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("animated" to "xGL animations", "system" to "Wallpaper", "black" to "Black").forEach { (k, label) ->
                    SmallChip(label = if (bgKind == k) "● $label" else label, accent = bgKind == k,
                        onClick = { scope.launch { prefs.setToolString(com.maiconerick.xgamelauncher.bottom.BottomHomeActivity.BG_KIND, k) } })
                }
                if (bgKind == "image" || bgKind == "video") SmallChip(label = "● ${if (bgKind == "image") "Image" else "Video"}", accent = true, onClick = {})
            }
            Text("Image or looping video: long-press empty space on the bottom screen → Background…",
                color = neon.inkMuted, fontSize = 11.sp)
            val drawerOpaque by prefs.toolFlag(com.maiconerick.xgamelauncher.bottom.BottomHomeActivity.DRAWER_OPAQUE, false).collectAsState(initial = false)
            ToggleRow(
                label = "TRANSPARENT APP DRAWER",
                subtitle = "Show the bottom screen's background through the app drawer. Off = the top screen's moving starfield behind it.",
                value = !drawerOpaque,
                onChange = { v -> scope.launch { prefs.setToolFlag(com.maiconerick.xgamelauncher.bottom.BottomHomeActivity.DRAWER_OPAQUE, !v) } },
            )
        }
        GroupLabel("BOTTOM BAR")
        val stripIcons by prefs.stripUseIcons.collectAsState(initial = false)
        ToggleRow(
            label = "PLATFORM ICONS IN THE BOTTOM BAR",
            subtitle = "Show each platform's bar icon (set in its Skin page; falls back to the platform icon) instead of its name.",
            value = stripIcons,
            onChange = { v -> scope.launch { prefs.setStripUseIcons(v) } },
        )
        val stripMini by prefs.stripMiniIcons.collectAsState(initial = false)
        ToggleRow(
            label = "MINI ICONS IN THE BOTTOM BAR",
            subtitle = "Show each platform's mini icon (set in its Skin page) instead of its name; platforms without one keep the name.",
            value = stripMini,
            onChange = { v -> scope.launch { prefs.setStripMiniIcons(v) } },
        )
        val stripSize by prefs.stripIconSize.collectAsState(initial = 0.2f)
        com.maiconerick.xgamelauncher.ui.components.GamepadSlider(
            label = "ICON SIZE", value = stripSize,
            onChange = { v -> scope.launch { prefs.setStripIconSize(v) } },
            valueLabel = { "${PrefsStore.stripIconDp(it).toInt()}dp" },
        )
        ToggleRow(
            label = "BUTTON HINTS IN THE BOTTOM BAR",
            subtitle = "Show which controller buttons do something on each screen. Turn off to leave more room in the bar.",
            value = showHints,
            onChange = { v -> scope.launch { prefs.setShowButtonHints(v) } },
        )
    }
}

// ---------------- THEME ----------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ThemeSection(prefs: PrefsStore) {
    val scope = rememberCoroutineScope()
    val neon = LocalNeon.current
    val active = com.maiconerick.xgamelauncher.ui.theme.LocalAppTheme.current
    SectionPane(title = "Theme", subtitle = "The whole look of the launcher. Each card is a live preview; A picks it.") {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            com.maiconerick.xgamelauncher.ui.theme.AppTheme.ALL.forEach { t ->
                ThemeCard(prefs, t, selected = t.id == active.id,
                    onClick = { scope.launch { prefs.setThemeId(t.id) } })
            }
        }
        Text("Focus and bar animations are saved per theme: change them in Appearance while a theme is active, " +
            "and switching back to Neon restores your own choices. The Halloween themes share one set.",
            color = neon.inkDim, fontSize = 12.sp, lineHeight = 17.sp)
        if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.lowPower) {
            Text("Low-power device: the Halloween sky is drawn once (no moving bats or fog) to keep things smooth.",
                color = Color(0xFFFFB74D), fontSize = 12.sp)
        }
    }
}

/** Live preview of one theme: its backdrop, bar effect, focus animation, font and colours. */
@Composable
private fun ThemeCard(prefs: PrefsStore, t: com.maiconerick.xgamelauncher.ui.theme.AppTheme, selected: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    val compact = com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact
    val animate = com.maiconerick.xgamelauncher.ui.animationsOn()
    var focused by remember { mutableStateOf(false) }
    val focusKind by remember(t.group) { prefs.focusKindOf(t.group, t.defaultFocusKind) }.collectAsState(initial = t.defaultFocusKind)
    val fa by remember(t.group) { prefs.focusColorAOf(t.group) }.collectAsState(initial = "")
    val fb by remember(t.group) { prefs.focusColorBOf(t.group) }.collectAsState(initial = "")
    val barKind by remember(t.group) { prefs.barFxKindOf(t.group, t.defaultBarFx) }.collectAsState(initial = t.defaultBarFx)
    val barSpeed by remember(t.group) { prefs.barFxSpeedOf(t.group) }.collectAsState(initial = 0.35f)
    val barAmount by remember(t.group) { prefs.barFxAmountOf(t.group) }.collectAsState(initial = 0.5f)
    val pal = t.palette ?: neon
    val w = if (compact) 180.dp else 240.dp
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(w), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().height(if (compact) 104.dp else 136.dp)
                .focusRing(focused, shape).clip(shape)
                .border(if (selected) 2.dp else 1.dp, if (selected) pal.accent else Color(0x33FFFFFF), shape)
                .onFocusChanged { focused = it.isFocused }.focusable()
                .onGamepadActivate(onClick).clickable(onClick = onClick),
        ) {
            CompositionLocalProvider(
                LocalNeon provides pal,
                com.maiconerick.xgamelauncher.ui.theme.LocalAppTheme provides t,
            ) {
                if (t.halloween) com.maiconerick.xgamelauncher.ui.theme.HalloweenBackdrop(
                    Modifier.matchParentSize(), amoled = t.amoled, animate = animate, mini = true)
                else com.maiconerick.xgamelauncher.ui.components.AnimatedDotsBackground(Modifier.matchParentSize(), animate = animate)
                Column(Modifier.matchParentSize()) {
                    // mini top bar with the theme's bar effect
                    Box(Modifier.fillMaxWidth().height(18.dp).background(t.barColor)) {
                        com.maiconerick.xgamelauncher.ui.components.BarFx(Modifier.matchParentSize(),
                            com.maiconerick.xgamelauncher.ui.components.BarFxStyle(barKind, barSpeed, barAmount))
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(10.dp)) {
                        // a "focused" tile running the theme's focus animation
                        Box(
                            Modifier.size(if (compact) 40.dp else 52.dp, if (compact) 54.dp else 70.dp)
                                .align(Alignment.CenterStart)
                                .focusEffect(com.maiconerick.xgamelauncher.ui.components.FocusStyle(
                                    com.maiconerick.xgamelauncher.ui.dialogs.parseHex(fa),
                                    com.maiconerick.xgamelauncher.ui.dialogs.parseHex(fb), focusKind),
                                    RoundedCornerShape(6.dp), animate = animate)
                                .clip(RoundedCornerShape(6.dp)).background(pal.surfaceElevated),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (t.halloween) com.maiconerick.xgamelauncher.ui.theme.PixelSprite(
                                com.maiconerick.xgamelauncher.ui.theme.Sprites.pumpkin, if (compact) 20.dp else 26.dp)
                            else Text("▶", color = pal.accent, fontSize = 18.sp)
                        }
                        Text(t.label, color = pal.ink, fontFamily = t.displayFont,
                            fontSize = if (compact) 15.sp else 19.sp,
                            fontWeight = if (t.displayFont != null) FontWeight.Normal else FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.BottomEnd))
                    }
                    Box(Modifier.fillMaxWidth().height(3.dp).background(pal.accent))
                }
            }
            if (selected) Text("✓", color = pal.accent, fontSize = 18.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 8.dp))
        }
        Text(t.label, color = if (selected) neon.accent else neon.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(t.subtitle, color = neon.inkMuted, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

/** A sample card showing one focus animation live (always "focused"); A/tap selects it. */
@Composable
private fun FocusPreviewCard(
    title: String,
    selected: Boolean,
    style: com.maiconerick.xgamelauncher.ui.components.FocusStyle,
    onClick: () -> Unit,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(width = 104.dp, height = 72.dp)
                .focusEffect(style, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(if (focused) Color(0x33FFFFFF) else Color(0x1FFFFFFF))
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onGamepadActivate(onClick)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Text("✓", color = neon.accent, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
        Text(title, color = if (selected) neon.accent else if (focused) neon.ink else neon.inkDim,
            fontSize = 12.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun ColorChoice(label: String, color: Color, value: String, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .focusRing(focused, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x1FFFFFFF))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Column {
            Text(label, color = neon.ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(value, color = neon.inkDim, fontSize = 11.sp)
        }
    }
}

// ---------------- DEVICE ----------------

@Composable
private fun DeviceSection(prefs: PrefsStore) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode by prefs.uiProfile.collectAsState(initial = "auto")
    val detected = remember { com.maiconerick.xgamelauncher.ui.DeviceProfile.detect(ctx, "auto") }
    val cfg = ctx.resources.configuration
    val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
    val ramGb = remember {
        android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }.totalMem / (1024.0 * 1024 * 1024)
    }

    var trees by remember { mutableStateOf(com.maiconerick.xgamelauncher.launch.RomUris.grantedTrees(ctx)) }
    val treePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            com.maiconerick.xgamelauncher.launch.RomUris.persist(ctx, uri)
            trees = com.maiconerick.xgamelauncher.launch.RomUris.grantedTrees(ctx)
        }
    }

    SectionPane(
        title = "Device",
        subtitle = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}",
    ) {
        GroupLabel("UI PROFILE")
        Text(
            text = "Screen ${cfg.screenWidthDp}×${cfg.screenHeightDp}dp · %.1f GB RAM · ".format(ramGb) +
                "auto-detect says " + buildList {
                    add(if (detected.compact) "compact" else "full-size")
                    if (detected.lowPower) add("low-power")
                    add(if (detected.multiDisplay) "dual-screen" else "single screen")
                }.joinToString(", "),
            color = neon.inkDim, fontSize = 11.sp, lineHeight = 16.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "AUTO", "full" to "FULL", "compact" to "COMPACT").forEach { (id, label) ->
                SmallChip(
                    label = if (mode == id) "● $label" else label,
                    accent = mode == id,
                    onClick = { scope.launch { prefs.setUiProfile(id) } },
                )
            }
        }
        Text(
            text = "Compact = tighter layout for small 4:3 screens (RG353M) and low-power " +
                "rendering: static background, no blur/crossfades, no side panels.",
            color = neon.inkMuted, fontSize = 11.sp, lineHeight = 16.sp,
        )

        GroupLabel("HOME APP")
        val askHome by prefs.toolFlag("ask_home_launcher", true).collectAsState(initial = true)
        ToggleRow(
            label = "ASK TO BE THE HOME APP",
            subtitle = "When xGameLauncher isn't your home app, ask on launch whether to make it one.",
            value = askHome,
            onChange = { v -> scope.launch { prefs.setToolFlag("ask_home_launcher", v) } },
        )

        GroupLabel("ROM FOLDER ACCESS · {file.uri}")
        Text(
            text = "Players that take {file.uri} (DuckStation, ARMSX2, EmuCoreX…) need a " +
                "content:// uri, like Daijishō passes. Grant your ROMS folder once and " +
                "xGameLauncher hands each game's uri to the emulator. Without a grant, " +
                "{file.uri} falls back to file://.",
            color = neon.inkMuted, fontSize = 11.sp, lineHeight = 16.sp,
        )
        trees.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = com.maiconerick.xgamelauncher.launch.RomUris.treePath(t) ?: t.toString(),
                    color = neon.ink, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                SmallChip(label = "REMOVE", accent = false, onClick = {
                    com.maiconerick.xgamelauncher.launch.RomUris.release(ctx, t)
                    trees = com.maiconerick.xgamelauncher.launch.RomUris.grantedTrees(ctx)
                })
            }
        }
        SmallChip(label = "+ GRANT ROM FOLDER", accent = true, onClick = {
            runCatching {
                treePicker.launch(
                    android.provider.DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", "primary:MaiconErick/ROMS",
                    )
                )
            }
        })
    }
}

// ---------------- ABOUT ----------------

@Composable
private fun UpdatesSection(prefs: PrefsStore) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by com.maiconerick.xgamelauncher.net.Updater.state.collectAsState()
    val U = com.maiconerick.xgamelauncher.net.Updater
    LaunchedEffect(Unit) { if (st is com.maiconerick.xgamelauncher.net.Updater.State.Idle) U.check(ctx) }
    SectionPane(title = "Updates", subtitle = "Installed: v${U.installedName(ctx)}") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val s = st) {
                is com.maiconerick.xgamelauncher.net.Updater.State.Idle,
                is com.maiconerick.xgamelauncher.net.Updater.State.Checking ->
                    Text("Checking Dropbox for a new version…", color = neon.inkDim, fontSize = 13.sp)
                is com.maiconerick.xgamelauncher.net.Updater.State.UpToDate ->
                    Text("✓ You're on the latest version (v${s.latest.name}).", color = neon.accent, fontSize = 13.sp)
                is com.maiconerick.xgamelauncher.net.Updater.State.Available -> {
                    Text("New version: v${s.latest.name}" + (s.latest.publishedAt?.let { " · published $it" } ?: ""),
                        color = neon.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    s.latest.notes?.let { Text(it, color = neon.inkDim, fontSize = 12.sp) }
                    Text("Size: ${U.mb(s.latest.size)}", color = neon.inkMuted, fontSize = 12.sp)
                    SmallChip(label = "DOWNLOAD & INSTALL", accent = true,
                        onClick = { scope.launch { U.downloadAndInstall(ctx, s.latest) } })
                }
                is com.maiconerick.xgamelauncher.net.Updater.State.Downloading -> {
                    val pct = if (s.total > 0) s.bytes.toFloat() / s.total else 0f
                    Text("Downloading v${s.latest.name}… ${(pct * 100).toInt()}%", color = neon.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { pct }, color = neon.accent, trackColor = neon.accent.copy(alpha = 0.18f),
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                    val left = if (s.bytesPerSec > 0) (s.total - s.bytes) / s.bytesPerSec else -1
                    Text("${U.mb(s.bytes)} of ${U.mb(s.total)} · ${"%.2f".format(s.bytesPerSec / 1e6)} MB/s" +
                        (if (left >= 0) " · ${left}s left" else ""), color = neon.inkDim, fontSize = 12.sp)
                }
                is com.maiconerick.xgamelauncher.net.Updater.State.Installing ->
                    Text("Installing v${s.latest.name}… xGameLauncher will restart by itself.", color = neon.accent, fontSize = 13.sp)
                is com.maiconerick.xgamelauncher.net.Updater.State.Failed ->
                    Text("✕ ${s.message}", color = Color(0xFFFF6B6B), fontSize = 13.sp)
            }
            val busy = st is com.maiconerick.xgamelauncher.net.Updater.State.Downloading ||
                st is com.maiconerick.xgamelauncher.net.Updater.State.Installing ||
                st is com.maiconerick.xgamelauncher.net.Updater.State.Checking
            if (!busy) SmallChip(label = "CHECK AGAIN", accent = false, onClick = { scope.launch { U.check(ctx) } })
            val auto by prefs.toolFlag("update_autocheck", true).collectAsState(initial = true)
            ToggleRow(
                label = "CHECK WHEN THE APP OPENS",
                subtitle = "Look for a new version on launch and ask before installing.",
                value = auto,
                onChange = { v -> scope.launch { prefs.setToolFlag("update_autocheck", v) } },
            )
        }
    }
}

@Composable
private fun AboutSection() {
    val neon = LocalNeon.current
    SectionPane(title = "About", subtitle = "xGameLauncher v0.1") {
        Text(
            text = "A Daijishō-replacement launcher with its own playtime tracker. Players use Daijishō-compatible am start arguments; sessions, cold-start and the overlay are all handled in-app.",
            color = neon.inkDim,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
    }
}

// ---------------- HELPERS ----------------

@Composable
private fun SectionPane(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    val neon = LocalNeon.current
    val compact = com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (compact) 12.dp else 40.dp, vertical = if (compact) 10.dp else 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
    ) {
        Text(subtitle, color = neon.inkMuted, fontSize = 13.sp)
        content()
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCommit: () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val neon = LocalNeon.current
        Text(label, color = neon.inkMuted, fontSize = 10.sp,
            letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium)
        FieldBox(value = value, onChange = onChange)
        LaunchedEffect(value) { onCommit() }
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val neon = LocalNeon.current
        Text(label, color = neon.inkMuted, fontSize = 10.sp,
            letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium)
        FieldBox(value = value, onChange = onChange, placeholder = placeholder)
    }
}

@Composable
private fun FieldBox(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color(0x99000000) else Color(0x66000000))
            .onFocusChanged { focused = it.isFocused },
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, color = neon.inkMuted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        }
        com.maiconerick.xgamelauncher.ui.components.GamepadTextField(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(
                color = neon.ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            singleLine = true,
            cursorBrush = SolidColor(neon.accent),
        )
    }
}
