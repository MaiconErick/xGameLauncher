package com.maiconerick.xgamelauncher.ui.screens

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivateOrHold
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import com.maiconerick.xgamelauncher.ui.components.focusRing
import android.app.ActivityOptions
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maiconerick.xgamelauncher.XGameLauncherApp
import com.maiconerick.xgamelauncher.android.AndroidAppSource
import com.maiconerick.xgamelauncher.ui.LibraryState
import com.maiconerick.xgamelauncher.ui.apptabs.AppTab
import com.maiconerick.xgamelauncher.ui.apptabs.AppTabsData
import com.maiconerick.xgamelauncher.ui.apptabs.AppTabsStore
import com.maiconerick.xgamelauncher.ui.components.TopBar
import com.maiconerick.xgamelauncher.ui.components.TopTab
import com.maiconerick.xgamelauncher.ui.components.neonFocusStroke
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.Image as FoundationImage

/** Sentinel id for the "General" pill — always first, never deletable/renameable. */
private const val GENERAL_TAB_ID = "__general__"

/**
 * Full-device app drawer with user-defined category tabs.
 *
 * Layout matches the Thor bottom-screen launcher reference: horizontal pill tab row along
 * the top plus a `Settings` icon on the right, then a paged grid below. "General" is
 * always the first tab and shows apps NOT assigned to any user tab. Clicking a pill opens
 * a picker with `Rename` / `Select apps` / `Create new tab` / `Delete tab`.
 *
 * Gamepad + touch: L1/R1 or a horizontal swipe cycles between tabs. There's no X/Y
 * shortcut on this screen — all actions are chip-clicks by design.
 */
@Composable
fun AppsScreen(
    state: LibraryState,
    onTab: (TopTab) -> Unit,
    /** Called after an app rename so the library (Android platform, playtime) reloads. */
    onAppRenamed: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as XGameLauncherApp
    val scope = rememberCoroutineScope()
    val tabsStore = remember { AppTabsStore(app.prefs) }
    val tabsData by tabsStore.flow.collectAsState(initial = AppTabsData())
    val iconSizeDp by app.prefs.appsIconSizeDp.collectAsState(initial = 108)
    val columns by app.prefs.appsColumns.collectAsState(initial = 0)
    val defaultDisplay by app.prefs.appsDefaultDisplay.collectAsState(initial = "top")
    val centerGrid by app.prefs.appsCenterGrid.collectAsState(initial = false)
    val centerTabs by app.prefs.appsCenterTabs.collectAsState(initial = false)
    // Separator height = the gap above each separator AND above the first row of apps.
    val sepDpRaw by app.prefs.toolString(APPS_SEPARATOR_KEY, "8").collectAsState(initial = "8")
    val sepDp = sepDpRaw.toIntOrNull()?.coerceIn(0, 64) ?: 8

    var installed by remember { mutableStateOf<List<AndroidAppSource.InstalledApp>>(emptyList()) }
    // Bumped on every install/uninstall/update so the list reloads while the drawer is open.
    var packagesTick by remember { mutableStateOf(0) }
    LaunchedEffect(packagesTick) { installed = AndroidAppSource.listInstalled(context) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { packagesTick++ }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    // Safety net: broadcasts are missed while the activity is stopped, so reload on resume too.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) packagesTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Tab list: General is always first, then user tabs in their configured order.
    val tabIds = remember(tabsData) {
        listOf(GENERAL_TAB_ID) + tabsData.tabs.map { it.id }
    }
    val tabLabels = remember(tabsData) {
        listOf("General") + tabsData.tabs.map { it.name }
    }

    val pagerState = rememberPagerState(initialPage = 0) { tabIds.size }
    // If tab list shrinks (e.g. delete), snap to a valid page.
    LaunchedEffect(tabIds.size) {
        if (pagerState.currentPage >= tabIds.size) pagerState.scrollToPage(0)
    }

    var showTabActions by remember { mutableStateOf<String?>(null) }        // tabId being edited
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameForTabId by remember { mutableStateOf<String?>(null) }
    var renameApp by remember { mutableStateOf<AndroidAppSource.InstalledApp?>(null) }
    // Separators: (tabId, section) being renamed, and the tab getting a new one.
    var renameSection by remember { mutableStateOf<Pair<String, com.maiconerick.xgamelauncher.ui.apptabs.AppSection>?>(null) }
    var newSectionForTab by remember { mutableStateOf<String?>(null) }
    // Separator clicked: pick its apps (from General or from its own tab).
    var sectionApps by remember { mutableStateOf<Pair<String, com.maiconerick.xgamelauncher.ui.apptabs.AppSection>?>(null) }
    val picker = com.maiconerick.xgamelauncher.ui.LocalPicker.current
    var selectAppsForTabId by remember { mutableStateOf<String?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var reorderTabs by remember { mutableStateOf(false) }
    var confirmDeleteTabId by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.ButtonL1 -> {
                        if (tabIds.size > 1) {
                            val next = (pagerState.currentPage - 1 + tabIds.size) % tabIds.size
                            scope.launch { pagerState.animateScrollToPage(next) }
                        }
                        true
                    }
                    Key.ButtonR1 -> {
                        if (tabIds.size > 1) {
                            val next = (pagerState.currentPage + 1) % tabIds.size
                            scope.launch { pagerState.animateScrollToPage(next) }
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        TopBar(activeTab = TopTab.Apps, onTab = onTab)

        // Tab row + settings icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = if (centerTabs)
                    Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
                else Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabIds.forEachIndexed { i, id ->
                    TabPill(
                        label = tabLabels[i],
                        selected = pagerState.currentPage == i,
                        // First click switches to the tab; clicking the active tab again
                        // opens its actions (rename / pick apps / new / delete).
                        onClick = {
                            if (pagerState.currentPage == i) showTabActions = id
                            else scope.launch { pagerState.animateScrollToPage(i) }
                        },
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            SettingsIconChip(onClick = { showSettingsDialog = true })
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { pageIndex ->
            val tabId = tabIds.getOrNull(pageIndex) ?: GENERAL_TAB_ID
            val visible = remember(tabId, installed, tabsData) {
                val base = installed.sortedBy { it.label.lowercase() }
                if (tabId == GENERAL_TAB_ID) {
                    // General shows apps that DO NOT belong to any user tab.
                    val assigned = tabsData.memberships.values.flatten().toSet()
                    base.filter { it.pkg !in assigned }
                } else {
                    val members = tabsData.packagesIn(tabId).toSet()
                    base.filter { it.pkg in members }
                }
            }
            // Apps with no separator first, then each separator with its apps.
            val secs = tabsData.sectionsIn(tabId)
            val groups = remember(visible, tabsData, tabId) {
                val top = visible.filter { tabsData.sectionOf(tabId, it.pkg) == null }
                listOf<Pair<com.maiconerick.xgamelauncher.ui.apptabs.AppSection?, List<AndroidAppSource.InstalledApp>>>(null to top) +
                    secs.map { sec -> sec to visible.filter { tabsData.sectionOf(tabId, it.pkg) == sec.id } }
            }
            AppsGrid(
                groups = groups,
                // Only the visible tab takes initial focus: a neighbour page grabbing focus
                // made the pager jump past empty tabs.
                takeFocus = pageIndex == pagerState.currentPage,
                separatorDp = sepDp,
                iconSizeDp = iconSizeDp,
                columns = columns,
                defaultDisplay = defaultDisplay,
                centerGrid = centerGrid,
                onAppMenu = { app ->
                    picker(com.maiconerick.xgamelauncher.ui.PickerRequest(
                        title = app.label,
                        message = appDetails(context, app),
                        options = buildList {
                            add(com.maiconerick.xgamelauncher.ui.PickerOption("rename", "Rename"))
                            if (secs.isNotEmpty()) add(com.maiconerick.xgamelauncher.ui.PickerOption("move", "Move to separator…",
                                tabsData.sectionOf(tabId, app.pkg)?.let { id -> "now under " + secs.firstOrNull { it.id == id }?.name } ?: "now at the top"))
                            add(com.maiconerick.xgamelauncher.ui.PickerOption("copy", "Copy package name", app.pkg))
                            add(com.maiconerick.xgamelauncher.ui.PickerOption("sysinfo", "System app info", "Android settings page"))
                        },
                        onPick = { id ->
                            picker(null)
                            when (id) {
                                "rename" -> renameApp = app
                                "copy" -> {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("package", app.pkg))
                                    android.widget.Toast.makeText(context, "Copied ${app.pkg}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                "sysinfo" -> runCatching {
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.fromParts("package", app.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                                "move" -> picker(com.maiconerick.xgamelauncher.ui.PickerRequest(
                                    title = "Move ${app.label} under…",
                                    options = listOf(com.maiconerick.xgamelauncher.ui.PickerOption("", "No separator (top of the tab)")) +
                                        secs.map { com.maiconerick.xgamelauncher.ui.PickerOption(it.id, it.name) },
                                    onPick = { sid ->
                                        picker(null)
                                        scope.launch { tabsStore.save(tabsData.withAppInSection(tabId, app.pkg, sid.ifBlank { null })) }
                                    },
                                    onCancel = { picker(null) },
                                ))
                            }
                        },
                        onCancel = { picker(null) },
                    ))
                },
                onSectionMenu = { sec -> sectionApps = tabId to sec                },
            )
        }
    }

    // --- overlays ---
    sectionApps?.let { (tabId, sec) ->
        val assigned = tabsData.memberships.values.flatten().toSet()
        val sorted = installed.sortedBy { it.label.lowercase() }
        val general = sorted.filter { it.pkg !in assigned }
        val inTab = if (tabId == GENERAL_TAB_ID) emptyList()
            else tabsData.packagesIn(tabId).toSet().let { m -> sorted.filter { it.pkg in m } }
        val tabName = tabsData.tabs.firstOrNull { it.id == tabId }?.name ?: "General"
        SeparatorAppsDialog(
            sectionName = sec.name,
            tabName = tabName,
            generalApps = general,
            tabApps = inTab,
            initiallySelected = tabsData.sectionMembers[sec.id].orEmpty().toSet(),
            onSave = { picked ->
                sectionApps = null
                var next = tabsData
                val before = tabsData.sectionMembers[sec.id].orEmpty().toSet()
                picked.forEach { pkg ->
                    // Picked from General → it joins this tab first.
                    if (tabId != GENERAL_TAB_ID && pkg !in next.packagesIn(tabId)) next = next.withMembershipToggled(tabId, pkg)
                    next = next.withAppInSection(tabId, pkg, sec.id)
                }
                (before - picked).forEach { pkg -> next = next.withAppInSection(tabId, pkg, null) }
                scope.launch { tabsStore.save(next) }
            },
            onRename = { sectionApps = null; renameSection = tabId to sec },
            onMove = { d -> scope.launch { tabsStore.save(tabsData.withSectionMoved(tabId, sec.id, d)) } },
            onDelete = { sectionApps = null; scope.launch { tabsStore.save(tabsData.withSectionRemoved(tabId, sec.id)) } },
            onCancel = { sectionApps = null },
        )
    }

    newSectionForTab?.let { tabId ->
        TextInputDialog(
            title = "New separator",
            initial = "",
            onSave = { name ->
                newSectionForTab = null
                if (name.isNotBlank()) scope.launch { tabsStore.save(tabsData.withSectionAdded(tabId, name.trim())) }
            },
            onCancel = { newSectionForTab = null },
        )
    }
    renameSection?.let { (tabId, sec) ->
        TextInputDialog(
            title = "Rename separator",
            initial = sec.name,
            onSave = { name ->
                renameSection = null
                if (name.isNotBlank()) scope.launch { tabsStore.save(tabsData.withSectionRenamed(tabId, sec.id, name.trim())) }
            },
            onCancel = { renameSection = null },
        )
    }
    renameApp?.let { target ->
        TextInputDialog(
            title = "Rename ${target.label}",
            initial = target.label,
            onSave = { name ->
                renameApp = null
                scope.launch {
                    AndroidAppSource.rename(context, target.pkg, name)
                    installed = AndroidAppSource.listInstalled(context)
                    onAppRenamed()
                }
            },
            onCancel = { renameApp = null },
        )
    }

    showTabActions?.let { tabId ->
        val isGeneral = tabId == GENERAL_TAB_ID
        val tab = tabsData.tabs.firstOrNull { it.id == tabId }
        TabActionsDialog(
            title = if (isGeneral) "General" else (tab?.name ?: "Tab"),
            canRename = !isGeneral,
            canDelete = !isGeneral,
            canSelectApps = !isGeneral,
            onSwitch = {
                val idx = tabIds.indexOf(tabId)
                if (idx >= 0) scope.launch { pagerState.animateScrollToPage(idx) }
                showTabActions = null
            },
            onRename = { showTabActions = null; renameForTabId = tabId },
            onSelectApps = { showTabActions = null; selectAppsForTabId = tabId },
            onCreateNew = { showTabActions = null; showCreateDialog = true },
            onAddSeparator = { showTabActions = null; newSectionForTab = tabId },
            onDelete = { showTabActions = null; confirmDeleteTabId = tabId },
            onDismiss = { showTabActions = null },
        )
    }

    if (showCreateDialog) {
        TextInputDialog(
            title = "New tab",
            initial = "",
            onSave = { name ->
                showCreateDialog = false
                if (name.isNotBlank()) {
                    val next = tabsData.withTabAdded(name.trim())
                    scope.launch {
                        tabsStore.save(next)
                        pagerState.animateScrollToPage(tabIds.size)
                    }
                }
            },
            onCancel = { showCreateDialog = false },
        )
    }

    renameForTabId?.let { tabId ->
        val tab = tabsData.tabs.firstOrNull { it.id == tabId }
        if (tab == null) { renameForTabId = null }
        else {
            TextInputDialog(
                title = "Rename tab",
                initial = tab.name,
                onSave = { name ->
                    renameForTabId = null
                    if (name.isNotBlank()) {
                        scope.launch { tabsStore.save(tabsData.withTabRenamed(tabId, name.trim())) }
                    }
                },
                onCancel = { renameForTabId = null },
            )
        }
    }

    selectAppsForTabId?.let { tabId ->
        val tab = tabsData.tabs.firstOrNull { it.id == tabId }
        if (tab == null) { selectAppsForTabId = null }
        else {
            // Only apps that are free (General) or already in this tab — apps living in
            // other tabs stay out of the list.
            val inOtherTabs = tabsData.memberships.filterKeys { it != tabId }.values.flatten().toSet()
            val mine = tabsData.packagesIn(tabId).toSet()
            SelectAppsDialog(
                tabName = tab.name,
                installed = installed.filter { it.pkg in mine || it.pkg !in inOtherTabs }.sortedBy { it.label.lowercase() },
                initiallySelected = tabsData.packagesIn(tabId).toSet(),
                onSave = { picked ->
                    selectAppsForTabId = null
                    val next = tabsData.copy(
                        memberships = tabsData.memberships + (tabId to picked.toList())
                    )
                    scope.launch { tabsStore.save(next) }
                },
                onCancel = { selectAppsForTabId = null },
            )
        }
    }

    confirmDeleteTabId?.let { tabId ->
        val tab = tabsData.tabs.firstOrNull { it.id == tabId }
        ConfirmDialog(
            title = "Delete tab",
            message = "Remove \"${tab?.name ?: "this tab"}\"? Apps stay installed; only the grouping is removed.",
            confirmLabel = "DELETE",
            onConfirm = {
                confirmDeleteTabId = null
                scope.launch { tabsStore.save(tabsData.withTabRemoved(tabId)) }
            },
            onCancel = { confirmDeleteTabId = null },
        )
    }

    if (reorderTabs) ReorderTabsDialog(
        tabs = tabsData.tabs,
        onSave = { order -> reorderTabs = false; scope.launch { tabsStore.save(tabsData.copy(tabs = order)) } },
        onCancel = { reorderTabs = false },
    )

    if (showSettingsDialog) {
        AppsSettingsDialog(
            iconSizeDp = iconSizeDp,
            columns = columns,
            centerGrid = centerGrid,
            centerTabs = centerTabs,
            separatorDp = sepDp,
            onSeparatorDp = { v -> scope.launch { app.prefs.setToolString(APPS_SEPARATOR_KEY, v.toString()) } },
            onReorderTabs = { showSettingsDialog = false; reorderTabs = true },
            onSave = { size, cols, cGrid, cTabs ->
                showSettingsDialog = false
                scope.launch {
                    app.prefs.setAppsIconSizeDp(size)
                    app.prefs.setAppsColumns(cols)
                    app.prefs.setAppsCenterGrid(cGrid)
                    app.prefs.setAppsCenterTabs(cTabs)
                }
            },
            onCancel = { showSettingsDialog = false },
        )
    }
}

private const val APPS_SEPARATOR_KEY = "apps_separator_dp"

@Composable
private fun AppsGrid(
    groups: List<Pair<com.maiconerick.xgamelauncher.ui.apptabs.AppSection?, List<AndroidAppSource.InstalledApp>>>,
    takeFocus: Boolean = true,
    separatorDp: Int = 8,
    iconSizeDp: Int,
    columns: Int,
    defaultDisplay: String,
    centerGrid: Boolean,
    onAppMenu: (AndroidAppSource.InstalledApp) -> Unit = {},
    onSectionMenu: (com.maiconerick.xgamelauncher.ui.apptabs.AppSection) -> Unit = {},
) {
    val context = LocalContext.current
    // Touch users don't want an app pre-selected; the gamepad (key input mode) does.
    val keyMode = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode ==
        androidx.compose.ui.input.InputMode.Keyboard
    val gridCells = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(iconSizeDp.dp)
    val gap = 12.dp
    val hSpacing = if (centerGrid)
        Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    else Arrangement.spacedBy(gap)
    val sidePad = 24.dp
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Fixed column count: size the grid to exactly N icons (+ gaps) and center it, so
        // "2 columns" means two icon-sized columns in the middle with empty sides — not two
        // half-screen cells with the icons pushed to the left of each.
        val gridWidth = if (columns > 0)
            (iconSizeDp.dp * columns + gap * (columns - 1) + sidePad * 2).coerceAtMost(maxWidth)
        else maxWidth
        LazyVerticalGrid(
            columns = gridCells,
            contentPadding = PaddingValues(start = sidePad, end = sidePad, top = separatorDp.dp, bottom = 8.dp),
            horizontalArrangement = hSpacing,
            // No grid-wide row gap: app rows carry their own 10dp below, so the separator's
            // height is the only space around a separator (0 = as tight as the label).
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.width(gridWidth).fillMaxHeight(),
        ) {
            var first = true
            groups.forEach { (sec, apps) ->
                if (sec != null) item(key = "sec_" + sec.id, span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(sec.name, topGap = separatorDp, onClick = { onSectionMenu(sec) })
                }
                apps.forEach { iApp ->
                    val isFirst = first
                    first = false
                    item(key = iApp.pkg) {
                        Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.TopCenter) {
                            AppTile(
                                app = iApp,
                                iconSizeDp = iconSizeDp,
                                initiallyFocused = isFirst && takeFocus && keyMode,
                                onClick = { launchApp(context, iApp, defaultDisplay) },
                                onLongClick = { onAppMenu(iApp) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Text tab in the same style as the top bar: accent text + underline when active. */
@Composable
private fun TabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .focusRing(focused, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = when {
                selected -> neon.accent
                focused -> neon.ink
                else -> neon.inkDim
            },
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .width(18.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (selected) neon.accent else Color.Transparent),
        )
    }
}

@Composable
private fun SettingsIconChip(onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    // Same language as the tab pills: dark glass, thin accent outline, accent glyph.
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(neon.accent.copy(alpha = if (focused) 0.26f else 0.08f))
            .border(1.dp, neon.accent.copy(alpha = if (focused) 1f else 0.45f), RoundedCornerShape(8.dp))
            .focusRing(focused, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = "Apps layout settings",
            tint = neon.accent,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun AppTile(
    app: AndroidAppSource.InstalledApp,
    iconSizeDp: Int,
    initiallyFocused: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val neon = LocalNeon.current
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(initiallyFocused) {
        if (initiallyFocused) runCatching { focusRequester.requestFocus() }
    }
    val icon = remember(app.pkg) { loadIconBitmap(context, app.pkg) }
    val scale = if (focused) 1.08f else 1f

    Column(
        modifier = Modifier
            // Never wider than the cell (small screens with many columns shrink the icon).
            .widthIn(max = iconSizeDp.dp)
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .neonFocusStroke(focused = focused, accent = neon.accent, cornerDp = 14)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // A = open, hold A / long-press = rename.
            .onGamepadActivateOrHold(onClick, onLongClick)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x22000000)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                FoundationImage(
                    bitmap = icon,
                    contentDescription = app.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                )
            } else {
                Text(
                    text = app.label.take(1).uppercase(),
                    color = neon.accent,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text = app.label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ---- dialogs ----

/** Full-width separator line with its name; A / tap opens its menu. */
@Composable
private fun SectionHeader(name: String, topGap: Int = 8, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    // SEPARATOR HEIGHT = the whole row's height (the label stays centred in it); at 0 the
    // row is just the label's height.
    Row(
        Modifier.fillMaxWidth().height((16 + topGap).dp)
            .focusRing(focused, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }.focusable()
            .onGamepadActivate(onClick).clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name.uppercase(), color = neon.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.5.dp).background(neon.accent.copy(alpha = 0.85f)))
    }
}

@Composable
private fun TabActionsDialog(
    title: String,
    canRename: Boolean,
    canDelete: Boolean,
    canSelectApps: Boolean,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onSelectApps: () -> Unit,
    onCreateNew: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onAddSeparator: () -> Unit = {},
) {
    val neon = LocalNeon.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0A0F14))
                .border(1.dp, neon.accent, RoundedCornerShape(12.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = neon.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            RowOption("Switch to this tab", onSwitch)
            if (canRename) RowOption("Rename tab", onRename)
            if (canSelectApps) RowOption("Select apps for this tab", onSelectApps)
            RowOption("Add separator (e.g. \"Downloaders\")", onAddSeparator)
            RowOption("Create new tab", onCreateNew)
            if (canDelete) RowOption("Delete tab", onDelete, danger = true)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogChip(label = "CLOSE", accent = false, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun RowOption(label: String, onClick: () -> Unit, danger: Boolean = false) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val fg = when {
        danger -> Color(0xFFFF7676)
        focused -> neon.accent
        else -> Color.White
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color(0x22FFFFFF) else Color.Transparent)
            .focusRing(focused, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val neon = LocalNeon.current
    var name by remember { mutableStateOf(initial) }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0A0F14))
                .border(1.dp, neon.accent, RoundedCornerShape(12.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = neon.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            NameInputField(value = name, onChange = { name = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogChip(label = "SAVE", accent = true, onClick = { onSave(name) })
                DialogChip(label = "CANCEL", accent = false, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val neon = LocalNeon.current
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0A0F14))
                .border(1.dp, neon.accent, RoundedCornerShape(12.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = neon.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(message, color = Color.White, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogChip(label = confirmLabel, accent = true, onClick = onConfirm)
                DialogChip(label = "CANCEL", accent = false, onClick = onCancel)
            }
        }
    }
}

/**
 * Separator click: choose the apps under it, from General (they join this tab) or from the
 * tab itself; plus rename / move / delete for the separator.
 */
@Composable
private fun SeparatorAppsDialog(
    sectionName: String,
    tabName: String,
    generalApps: List<AndroidAppSource.InstalledApp>,
    tabApps: List<AndroidAppSource.InstalledApp>,
    initiallySelected: Set<String>,
    onSave: (Set<String>) -> Unit,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val neon = LocalNeon.current
    val selected = remember { mutableStateOf(initiallySelected) }
    val hasTab = tabApps.isNotEmpty() || tabName != "General"
    var fromTab by remember { mutableStateOf(hasTab) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(20.dp).clip(RoundedCornerShape(12.dp))
                .background(Color(0xF2050709)).background(neon.accent.copy(alpha = 0.06f))
                .border(1.dp, neon.accent, RoundedCornerShape(12.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sectionName.uppercase(), color = neon.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
                DialogChip(label = "RENAME", accent = false, onClick = onRename)
                DialogChip(label = "▲", accent = false, onClick = { onMove(-1) })
                DialogChip(label = "▼", accent = false, onClick = { onMove(1) })
                DialogChip(label = "DELETE", accent = false, onClick = onDelete)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasTab) SettingPill(label = "FROM ${tabName.uppercase()} (${tabApps.size})", selected = fromTab, onClick = { fromTab = true })
                SettingPill(label = "FROM GENERAL (${generalApps.size})", selected = !fromTab, onClick = { fromTab = false })
                Spacer(Modifier.weight(1f))
                Text("${selected.value.size} under this separator", color = neon.inkDim, fontSize = 11.sp)
            }
            val list = if (fromTab) tabApps else generalApps
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lazyItems(items = list, key = { it.pkg }) { iApp ->
                    val isOn = iApp.pkg in selected.value
                    SelectableAppRow(app = iApp, selected = isOn, onToggle = {
                        selected.value = if (isOn) selected.value - iApp.pkg else selected.value + iApp.pkg
                    })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogChip(label = "SAVE", accent = true, onClick = { onSave(selected.value) })
                DialogChip(label = "CANCEL", accent = false, onClick = onCancel)
            }
        }
    }
}

/**
 * Drag the ≡ handle (or use ▲ / ▼ with the controller) to order the tabs. General always
 * stays first.
 */
@Composable
private fun ReorderTabsDialog(
    tabs: List<com.maiconerick.xgamelauncher.ui.apptabs.AppTab>,
    onSave: (List<com.maiconerick.xgamelauncher.ui.apptabs.AppTab>) -> Unit,
    onCancel: () -> Unit,
) {
    val neon = LocalNeon.current
    val order = remember { androidx.compose.runtime.mutableStateListOf(*tabs.toTypedArray()) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 52.dp.toPx() }
    fun move(from: Int, to: Int) { if (from != to && to in order.indices) order.add(to, order.removeAt(from)) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.5f).clip(RoundedCornerShape(12.dp)).background(Color(0xF2050709))
                .background(neon.accent.copy(alpha = 0.06f)).border(1.dp, neon.accent, RoundedCornerShape(12.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Tab order", color = neon.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("General (always first)", color = neon.inkMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
            Column {
                order.forEachIndexed { i, tab -> androidx.compose.runtime.key(tab.id) {
                    // key(): the dragged row keeps its identity (and gesture) as it moves.
                    val dragging = dragId == tab.id
                    Row(
                        Modifier.fillMaxWidth().height(52.dp)
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { if (dragging) { translationY = dragY; scaleX = 1.02f; scaleY = 1.02f } }
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (dragging) neon.accent.copy(alpha = 0.22f) else neon.accent.copy(alpha = 0.06f))
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("≡", color = neon.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp)
                                .pointerInput(tab.id) {
                                    detectDragGestures(
                                        onDragStart = { dragId = tab.id; dragY = 0f },
                                        onDragEnd = { dragId = null; dragY = 0f },
                                        onDragCancel = { dragId = null; dragY = 0f },
                                    ) { c, d ->
                                        c.consume()
                                        dragY += d.y
                                        val idx = order.indexOfFirst { it.id == tab.id }
                                        // Crossing half a row swaps with the neighbour; the offset is rebased.
                                        if (dragY > rowPx / 2 && idx < order.lastIndex) { move(idx, idx + 1); dragY -= rowPx }
                                        else if (dragY < -rowPx / 2 && idx > 0) { move(idx, idx - 1); dragY += rowPx }
                                    }
                                })
                        Text(tab.name, color = neon.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        DialogChip(label = "▲", accent = false, onClick = { move(i, i - 1) })
                        DialogChip(label = "▼", accent = false, onClick = { move(i, i + 1) })
                    }
                } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogChip(label = "SAVE", accent = true, onClick = { onSave(order.toList()) })
                DialogChip(label = "CANCEL", accent = false, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun SelectAppsDialog(
    tabName: String,
    installed: List<AndroidAppSource.InstalledApp>,
    initiallySelected: Set<String>,
    onSave: (Set<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val neon = LocalNeon.current
    val selected = remember(tabName) { mutableStateOf(initiallySelected) }
    val prefs = (LocalContext.current.applicationContext as XGameLauncherApp).prefs
    val scope = rememberCoroutineScope()
    val checkedLast by prefs.toolFlag("apps_select_checked_last", false).collectAsState(initial = false)
    // Sorted by the selection when the dialog opened, so rows don't jump while ticking.
    val shown = remember(installed, checkedLast) {
        if (checkedLast) installed.sortedBy { it.pkg in initiallySelected } else installed
    }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0A0F14))
                .border(1.dp, neon.accent, RoundedCornerShape(12.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Apps in \"$tabName\"", color = neon.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.value.size} selected · ${installed.size} available (General + this tab)",
                    color = neon.accent.copy(alpha = 0.8f), fontSize = 11.sp, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                SettingPill(label = if (checkedLast) "✓ TAB APPS LAST" else "TAB APPS LAST", selected = checkedLast,
                    onClick = { scope.launch { prefs.setToolFlag("apps_select_checked_last", !checkedLast) } })
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Aliased — `itemsIndexed` is imported from the grid package for the
                // grid tiles above, so we import the column-lazy `items` under a different
                // name to keep them from colliding at the call site.
                lazyItems(
                    items = shown,
                    key = { it.pkg },
                ) { iApp ->
                    val isOn = iApp.pkg in selected.value
                    SelectableAppRow(
                        app = iApp,
                        selected = isOn,
                        onToggle = {
                            selected.value = if (isOn) selected.value - iApp.pkg
                                              else selected.value + iApp.pkg
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogChip(label = "SAVE", accent = true, onClick = { onSave(selected.value) })
                DialogChip(label = "CANCEL", accent = false, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun SelectableAppRow(
    app: AndroidAppSource.InstalledApp,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val icon = remember(app.pkg) { loadIconBitmap(ctx, app.pkg) }
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color(0x22FFFFFF) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) neon.accent else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            .focusRing(focused, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onToggle)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x22000000)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                FoundationImage(bitmap = icon, contentDescription = app.label,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(app.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f))
        Text(
            text = if (selected) "✓" else "",
            color = neon.accent,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AppsSettingsDialog(
    iconSizeDp: Int,
    columns: Int,
    centerGrid: Boolean,
    centerTabs: Boolean,
    separatorDp: Int = 8,
    onSeparatorDp: (Int) -> Unit = {},
    onReorderTabs: () -> Unit = {},
    onSave: (icon: Int, cols: Int, centerGrid: Boolean, centerTabs: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val neon = LocalNeon.current
    // Icon size uses a slider — min 36dp lets the grid go "really really tiny" for a
    // desktop-launcher-style dense layout the user asked for; max 200dp fits huge tiles.
    var size by remember { mutableStateOf(iconSizeDp.coerceIn(36, 200).toFloat()) }
    var cols by remember { mutableStateOf(columns) }
    var cGrid by remember { mutableStateOf(centerGrid) }
    var cTabs by remember { mutableStateOf(centerTabs) }
    val colOptions = listOf(0 to "Auto", 3 to "3", 4 to "4", 5 to "5", 6 to "6", 7 to "7", 8 to "8", 10 to "10", 12 to "12")
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF2050709))
                .background(neon.accent.copy(alpha = 0.06f))
                .border(1.dp, neon.accent, RoundedCornerShape(12.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Apps layout", color = neon.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ICON SIZE", color = neon.accent.copy(alpha = 0.8f), fontSize = 11.sp,
                    letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                Text("${size.toInt()}dp", color = neon.accent, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold)
            }
            androidx.compose.material3.Slider(
                value = size,
                onValueChange = { size = it },
                valueRange = 36f..200f,
                steps = 0,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = neon.accent,
                    activeTrackColor = neon.accent,
                    inactiveTrackColor = neon.accent.copy(alpha = 0.2f),
                ),
            )

            Text("COLUMNS", color = neon.accent.copy(alpha = 0.8f), fontSize = 11.sp, letterSpacing = 1.2.sp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                colOptions.forEach { (v, label) ->
                    SettingPill(label = label, selected = cols == v, onClick = { cols = v })
                }
            }

            // Applied live: the gap above each separator and above the first row of apps.
            var sep by remember { mutableStateOf(separatorDp) }
            com.maiconerick.xgamelauncher.ui.components.GamepadSlider(
                label = "SEPARATOR HEIGHT", value = sep / 64f, step = 1f / 32f,
                onChange = { v -> sep = Math.round(v * 64); onSeparatorDp(sep) },
                valueLabel = { v -> "${Math.round(v * 64)}dp" },
            )

            ToggleRow(label = "Center apps in grid", value = cGrid, onToggle = { cGrid = it })
            DialogChip(label = "REORDER TABS…", accent = false, onClick = onReorderTabs)
            ToggleRow(label = "Center tab pills", value = cTabs, onToggle = { cTabs = it })

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogChip(label = "SAVE", accent = true,
                    onClick = { onSave(size.toInt(), cols, cGrid, cTabs) })
                DialogChip(label = "CANCEL", accent = false, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onToggle: (Boolean) -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) neon.accent.copy(alpha = 0.14f) else Color.Transparent)
            .focusRing(focused, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate { onToggle(!value) }
            .clickable { onToggle(!value) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f))
        Box(
            Modifier
                .width(38.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (value) neon.accent else neon.accent.copy(alpha = 0.18f)),
            contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(2.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun SettingPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        selected -> neon.accent
        focused -> neon.accent.copy(alpha = 0.26f)
        else -> neon.accent.copy(alpha = 0.08f)
    }
    val fg = if (selected) Color.Black else neon.accent
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(
                width = if (focused && !selected) 1.dp else 0.dp,
                color = if (focused && !selected) neon.accent else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .focusRing(focused, RoundedCornerShape(20.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
}

@Composable
private fun NameInputField(value: String, onChange: (String) -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color(0xFF12191E) else Color(0xFF0B1014))
            .border(1.dp, if (focused) neon.accent else neon.stroke, RoundedCornerShape(6.dp))
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused },
    ) {
        if (value.isEmpty()) Text("Name…", color = neon.inkMuted, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp))
        // The field fills the whole box (padding inside it): tap anywhere to edit.
        com.maiconerick.xgamelauncher.ui.components.GamepadTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium),
            singleLine = true,
            cursorBrush = SolidColor(neon.accent),
        )
    }
}

@Composable
private fun DialogChip(label: String, accent: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        accent && focused -> neon.accent.copy(alpha = 0.36f)
        accent -> neon.accent.copy(alpha = 0.20f)
        focused -> Color(0x44FFFFFF)
        else -> Color(0x22FFFFFF)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(
                width = 1.dp,
                color = when {
                    accent -> neon.accent
                    focused -> neon.accent.copy(alpha = 0.5f)
                    else -> Color(0x33FFFFFF)
                },
                shape = RoundedCornerShape(4.dp),
            )
            .focusRing(focused, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = if (accent) neon.accent else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}

// ---- helpers ----

private fun launchApp(context: Context, app: AndroidAppSource.InstalledApp, defaultDisplay: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(app.pkg) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Single-screen devices get no display override at all.
    val displayId = com.maiconerick.xgamelauncher.ui.DisplayTargets.displayIdFor(context, defaultDisplay)
    val options = displayId?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
    runCatching { context.startActivity(intent, options) }
}

/** Package, version, SDK levels, install dates and APK size for the long-press menu. */
private fun appDetails(context: Context, app: AndroidAppSource.InstalledApp): String {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val pi = runCatching { pm.getPackageInfo(app.pkg, 0) }.getOrNull() ?: return app.pkg
    val ai = pi.applicationInfo
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
    val apkMb = ai?.sourceDir?.let { java.io.File(it).length() / 1048576.0 }
    val sdk = buildList {
        ai?.let { if (android.os.Build.VERSION.SDK_INT >= 24) add("min ${it.minSdkVersion}"); add("target ${it.targetSdkVersion}") }
    }.joinToString(" · ")
    val system = ai != null && (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    @Suppress("DEPRECATION")
    val installer = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(app.pkg).installingPackageName
        else pm.getInstallerPackageName(app.pkg)
    }.getOrNull()
    return buildList {
        add(app.pkg)
        add("Version ${pi.versionName ?: "?"} ($code)")
        if (sdk.isNotEmpty()) add("SDK $sdk")
        add("Installed ${fmt.format(java.util.Date(pi.firstInstallTime))}")
        add("Updated ${fmt.format(java.util.Date(pi.lastUpdateTime))}")
        apkMb?.let { add("APK %.1f MB".format(java.util.Locale.US, it) + if (system) " · system app" else "") }
        installer?.let { add("Installer $it") }
        add("Activity ${app.launchComponent.substringAfter('/')}")
    }.joinToString("\n")
}

private fun loadIconBitmap(context: Context, pkg: String): ImageBitmap? {
    return runCatching {
        val d = context.packageManager.getApplicationIcon(pkg)
        val size = 144
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        bmp.asImageBitmap()
    }.getOrNull()
}

internal fun hasUsageAccess(context: Context): Boolean {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return false
    val end = System.currentTimeMillis()
    val start = end - TimeUnit.DAYS.toMillis(1)
    val stats = runCatching { usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) }
        .getOrNull()
    return !stats.isNullOrEmpty()
}

internal fun openUsageAccessSettings(context: Context) {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
