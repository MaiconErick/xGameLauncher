package com.maiconerick.xgamelauncher.ui.screens

import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Timer
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.ViewSidebar
import com.maiconerick.xgamelauncher.ui.components.focusRing
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.data.PlaytimeStats
import com.maiconerick.xgamelauncher.ui.GameSortMode
import com.maiconerick.xgamelauncher.ui.LibraryState
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.LocalPicker
import com.maiconerick.xgamelauncher.ui.PickerOption
import com.maiconerick.xgamelauncher.ui.PickerRequest
import com.maiconerick.xgamelauncher.ui.components.GameTile
import com.maiconerick.xgamelauncher.ui.components.GameTileState
import com.maiconerick.xgamelauncher.ui.components.TopBar
import com.maiconerick.xgamelauncher.ui.components.TopTab
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import com.maiconerick.xgamelauncher.util.TimeFormat

/**
 * Games grid for one platform.
 *  - Sort surfaced as a single button that opens the picker dialog (previously inline chips).
 *  - Info panel toggle (Y button hint) — when on, a right-side pane shows the focused game's
 *    playtime, session history, last-played, filename.
 *  - Column count is per-platform: `columnsWithoutPanel` vs `columnsWithPanel` from Platform.
 *  - Background image (if set on Platform) fills the backdrop behind the scrim with a neon
 *    rounded border using the platform's accent.
 *  - Header shows the platform's icon image if set, else the platform name.
 */
@Composable
fun PlatformScreen(
    platform: Platform,
    state: LibraryState,
    sort: GameSortMode,
    onSort: (GameSortMode) -> Unit,
    playtime: (String, String) -> PlaytimeStats?,
    onEnsureArt: (Platform, GameEntry) -> Unit,
    onOpenGame: (Platform, GameEntry) -> Unit,
    onLaunchGame: (Platform, GameEntry) -> Unit = onOpenGame,
    onEditPlatform: (() -> Unit)?,
    onTab: (TopTab) -> Unit,
    unplayedSort: GameSortMode = GameSortMode.NameAsc,
    onUnplayedSort: (GameSortMode) -> Unit = {},
    /** Opens the romgi catalog scoped to this platform (null = ROM tool off). */
    onGetGames: (() -> Unit)? = null,
) {
    val neon = LocalNeon.current
    val accent = parseAccent(platform.accentColorHex) ?: neon.accent
    val rawGames = state.gamesByPlatform[platform.id].orEmpty()
    val games = remember(rawGames, sort, unplayedSort, state.artByGameId) {
        sortGames(rawGames, sort, unplayedSort, platform.name, playtime)
    }
    val prefs = (androidx.compose.ui.platform.LocalContext.current.applicationContext
        as com.maiconerick.xgamelauncher.XGameLauncherApp).prefs
    val gridCols by prefs.gridColumns.collectAsState(initial = 5)
    val panelCols by prefs.panelColumns.collectAsState(initial = 0)
    val tileDp by prefs.gridTileDp.collectAsState(initial = 0)
    val showNames by prefs.toolFlag(GridLayoutSpec.KEY_NAMES, false).collectAsState(initial = false)
    val layOffRaw by prefs.toolString(GridLayoutSpec.KEY_OFF, "").collectAsState(initial = "")
    val layOnRaw by prefs.toolString(GridLayoutSpec.KEY_ON, "").collectAsState(initial = "")
    val layOff = GridLayoutSpec.decode(layOffRaw)
    val layOn = if (layOnRaw.isBlank()) GridLayoutSpec(cols = panelCols) else GridLayoutSpec.decode(layOnRaw)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val requestPicker = LocalPicker.current

    // Coming back (B from a game) restores the game that was selected, not the first one.
    val restoreId = remember(platform.id) { lastFocusedGame[platform.id]?.takeIf { id -> games.any { it.id == id } } }
    var focusedGameId by remember(platform.id) { mutableStateOf(restoreId ?: games.firstOrNull()?.id) }
    LaunchedEffect(focusedGameId) { focusedGameId?.let { lastFocusedGame[platform.id] = it } }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
        initialFirstVisibleItemIndex = restoreId?.let { id -> games.indexOfFirst { it.id == id } }?.coerceAtLeast(0) ?: 0,
    )
    val focusedGame = games.firstOrNull { it.id == focusedGameId }
    val heroPath = focusedGame?.let {
        state.artByGameId[it.id]?.heroPath ?: state.artByGameId[it.id]?.boxPath
    }
    val profile = LocalDeviceProfile.current
    var showPanelPref by remember { mutableStateOf(false) }
    // No room for a 340dp side panel on compact screens.
    val showPanel = showPanelPref && !profile.compact
    // Compact screens size tiles adaptively; full screens use the global grid settings.
    val gridCells = GridCells.Adaptive(96.dp)

    Box(Modifier.fillMaxSize()) {

        // --- Backdrop: always the focused game's hero art (blurred). Platform's own
        // background image lives on the Home hub tile, not here.
        Box(Modifier.fillMaxSize()) {
            // Low-power: skip the backdrop entirely — a full-screen decode + crossfade on
            // every d-pad step is the single most expensive thing on this screen.
            if (!profile.lowPower) Crossfade(
                targetState = heroPath to (focusedGame?.name ?: "").hashCode(),
                animationSpec = tween(400),
                label = "hero-backdrop",
            ) { (path, _) ->
                if (path != null) {
                    // Crop instead of Fit — fill both dimensions of the backdrop area so
                    // there are no letterbox bars around the blurred hero art.
                    AsyncImage(
                        model = path, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(6.dp),
                    )
                }
            }
            // Full-cover scrim so tiles stay readable regardless of backdrop.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0xD9000000),
                        0.4f to Color(0x99000000),
                        1f to Color(0xE6000000),
                    )
                )
            )
        }

        Column(Modifier.fillMaxSize()) {
            TopBar(activeTab = TopTab.Home, onTab = onTab)

            Header(
                platform = platform,
                accent = accent,
                sort = sort,
                onSort = onSort,
                showPanel = showPanel,
                onTogglePanel = { showPanelPref = !showPanelPref },
                gridCols = gridCols,
                panelCols = panelCols,
                onGridCols = { v -> scope.launch { prefs.setGridColumns(v) } },
                onPanelCols = { v -> scope.launch { prefs.setPanelColumns(v) } },
                unplayedSort = unplayedSort,
                onUnplayedSort = onUnplayedSort,
                onGetGames = onGetGames,
                canEdit = onEditPlatform != null,
                onEdit = onEditPlatform ?: {},
            )

            if (games.isEmpty()) {
                EmptyPlatform()
            } else {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                // Tile size is fixed by the no-panel column count; opening the panel only
                // drops columns, it never resizes the tiles.
                val hPad = profile.dp(40.dp, 10.dp)
                val gap = profile.dp(16.dp, 8.dp)
                val noPanelCols = gridCols.coerceIn(1, 12)
                // Per panel state (long-press the panel icon): tile size, columns, centring.
                val lay = if (showPanel) layOn else layOff
                val derived = if (tileDp > 0) tileDp.dp
                    else (maxWidth - hPad * 2 - gap * (noPanelCols - 1)) / noPanelCols
                val offTile = if (layOff.tile > 0) layOff.tile.dp else derived
                val tileWidth = (if (lay.tile > 0) lay.tile.dp else offTile).coerceAtMost(maxWidth - hPad * 2)
                val cols = lay.cols
                val cells = when {
                    profile.compact -> gridCells
                    cols > 0 && !showPanel -> GridCells.Fixed(cols)
                    else -> GridCells.FixedSize(tileWidth)
                }
                // Fixed column count with the panel → the grid is exactly that wide and the
                // panel takes the rest; Auto → fixed-width panel and as many columns as fit.
                val fixedPanel = showPanel && cols > 0
                val gridWidth = hPad * 2 + tileWidth * cols + gap * (cols - 1).coerceAtLeast(0)
                Row(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = cells,
                        contentPadding = PaddingValues(
                            horizontal = profile.dp(40.dp, 10.dp), vertical = profile.dp(12.dp, 8.dp),
                        ),
                        horizontalArrangement = if (lay.center) Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
                            else Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(profile.dp(16.dp, 8.dp)),
                        modifier = (if (fixedPanel && !profile.compact) Modifier.width(gridWidth) else Modifier.weight(1f))
                            .fillMaxHeight(),
                    ) {
                        itemsIndexed(games, key = { _, g -> g.id }) { index, g ->
                            LaunchedEffect(g.id) { onEnsureArt(platform, g) }
                            GameTile(
                                state = GameTileState(
                                    id = g.id,
                                    title = g.name,
                                    // Platform badge intentionally omitted per user request.
                                    boxArtPath = state.artByGameId[g.id]?.boxPath,
                                    playtimeText = playtime(g.name, platform.name)?.let {
                                        TimeFormat.playtimeChip(it.totalSeconds)
                                    },
                                ),
                                onClick = { onOpenGame(platform, g) },
                                // Fixed columns: the tile keeps its GAME SIZE inside the column.
                                modifier = if (cols > 0 && !showPanel) Modifier.widthIn(max = tileWidth) else Modifier,
                                initiallyFocused = if (restoreId != null) g.id == restoreId else index == 0,
                                showTitle = showNames,
                                onFocused = { focusedGameId = g.id },
                            )
                        }
                    }
                    if (showPanel) {
                        InfoPanel(
                            platform = platform,
                            game = focusedGame,
                            stats = focusedGame?.let { playtime(it.name, platform.name) },
                            heroPath = focusedGame?.let {
                                state.artByGameId[it.id]?.heroPath
                                    ?: state.artByGameId[it.id]?.boxPath
                            },
                            isSessionOpen = focusedGame?.let {
                                com.maiconerick.xgamelauncher.playtime.PlaytimeReader
                                    .keyFor(it.name, platform.name) in state.openSessionsKey
                            } ?: false,
                            accent = accent,
                            onStart = { focusedGame?.let { onLaunchGame(platform, it) } },
                            onInfo = { focusedGame?.let { onOpenGame(platform, it) } },
                            modifier = if (fixedPanel) Modifier.weight(1f) else Modifier.width(290.dp),
                        )
                    }
                }
                }
            }
        }
    }
}

// ---------------- HEADER ----------------

@Composable
private fun Header(
    platform: Platform,
    accent: Color,
    sort: GameSortMode,
    onSort: (GameSortMode) -> Unit,
    showPanel: Boolean,
    onTogglePanel: () -> Unit,
    canEdit: Boolean,
    onEdit: () -> Unit,
    gridCols: Int = 5,
    panelCols: Int = 0,
    onGridCols: (Int) -> Unit = {},
    onPanelCols: (Int) -> Unit = {},
    unplayedSort: GameSortMode = GameSortMode.NameAsc,
    onUnplayedSort: (GameSortMode) -> Unit = {},
    onGetGames: (() -> Unit)? = null,
) {
    val neon = LocalNeon.current
    val profile = LocalDeviceProfile.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = profile.dp(40.dp, 10.dp), vertical = profile.dp(14.dp, 6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon or platform name + count, in one flexible slot so the buttons sit flush right.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        if (!platform.iconImagePath.isNullOrBlank()) {
            AsyncImage(
                model = platform.iconImagePath,
                contentDescription = platform.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(profile.dp(52.dp, 30.dp)).width(profile.dp(180.dp, 100.dp)),
            )
        } else {
            Text(
                text = platform.name,
                color = neon.ink,
                fontSize = profile.sp(34.sp, 20.sp),
                fontWeight = FontWeight.Bold,
                letterSpacing = if (profile.compact) (-0.4).sp else (-1.2).sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.width(profile.dp(14.dp, 8.dp)))
        Text(
            text = "${platform.gameCount} GAMES",
            color = neon.inkMuted,
            fontSize = 10.sp,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        }
        SortDropdown(current = sort, onSort = onSort, accent = accent,
            unplayed = unplayedSort, onUnplayed = onUnplayedSort)
        if (!profile.compact) {
            Spacer(Modifier.width(8.dp))
            // Tap = toggle the panel; long-press (or hold A) = global grid options.
            var gridMenu by remember { mutableStateOf(false) }
            Box {
                HeaderIconChip(
                    icon = androidx.compose.material.icons.Icons.Filled.ViewSidebar,
                    description = if (showPanel) "Hide panel" else "Show panel",
                    selected = showPanel,
                    accent = accent,
                    onClick = onTogglePanel,
                    onLongClick = { gridMenu = true },
                )
                GridOptionsMenu(
                    expanded = gridMenu,
                    onDismiss = { gridMenu = false },
                    accent = accent,
                    gridCols = gridCols, panelCols = panelCols,
                    onGridCols = onGridCols, onPanelCols = onPanelCols,
                    startOnPanel = showPanel,
                    // Switching tabs shows / hides the panel so the changes are visible live.
                    onPanelTab = { on -> if (on != showPanel) onTogglePanel() },
                )
            }
        }
        if (onGetGames != null) {
            Spacer(Modifier.width(8.dp))
            HeaderIconChip(
                icon = androidx.compose.material.icons.Icons.Filled.Download,
                description = "Get games", selected = false, accent = accent, onClick = onGetGames,
            )
        }
        if (canEdit) {
            Spacer(Modifier.width(8.dp))
            HeaderChip(label = "EDIT", selected = false, accent = accent, onClick = onEdit)
        }
    }
}

@Composable
private fun SortDropdown(
    current: GameSortMode,
    onSort: (GameSortMode) -> Unit,
    accent: Color,
    unplayed: GameSortMode = GameSortMode.NameAsc,
    onUnplayed: (GameSortMode) -> Unit = {},
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    // Presses that land while the menu is still animating open/closed used to re-toggle it
    // (flicker loop). Ignore open requests for a moment after any open/close.
    var lastToggle by remember { mutableStateOf(0L) }
    fun open() {
        val now = android.os.SystemClock.uptimeMillis()
        if (expanded || now - lastToggle < 400) return
        lastToggle = now; expanded = true
    }
    fun close() { lastToggle = android.os.SystemClock.uptimeMillis(); expanded = false }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (focused) accent.copy(alpha = 0.24f) else Color(0x22FFFFFF))
                .border(1.dp, if (focused) accent else Color(0x33FFFFFF), RoundedCornerShape(4.dp))
                .focusRing(focused, RoundedCornerShape(4.dp))
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onGamepadActivate { open() }
                .clickable { open() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(
                imageVector = current.icon(),
                contentDescription = null,
                tint = if (focused) accent else neon.ink,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(current.label.uppercase(), color = if (focused) accent else neon.ink,
                fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text("▾", color = neon.inkMuted, fontSize = 10.sp)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { close() },
            modifier = Modifier.background(Color(0xFF0A0F14)),
        ) {
            GameSortMode.values().forEach { mode ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            mode.label,
                            color = if (mode == current) accent else Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (mode == current) FontWeight.Bold else FontWeight.Medium,
                        )
                    },
                    leadingIcon = {
                        androidx.compose.material3.Icon(
                            imageVector = mode.icon(),
                            contentDescription = null,
                            tint = if (mode == current) accent else Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    trailingIcon = if (mode == current) ({
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.Check,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }) else null,
                    onClick = { onSort(mode); close() },
                )
            }
            // Secondary order for never-played games (only matters for play-based sorts).
            androidx.compose.material3.HorizontalDivider(color = Color(0x33FFFFFF))
            Text(
                "UNPLAYED GAMES", color = Color(0x99FFFFFF), fontSize = 10.sp, letterSpacing = 1.4.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            com.maiconerick.xgamelauncher.ui.UNPLAYED_SORTS.forEach { mode ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(mode.label, color = if (mode == unplayed) accent else Color.White, fontSize = 13.sp,
                            fontWeight = if (mode == unplayed) FontWeight.Bold else FontWeight.Medium)
                    },
                    trailingIcon = if (mode == unplayed) ({
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, null,
                            tint = accent, modifier = Modifier.size(16.dp))
                    }) else null,
                    onClick = { onUnplayed(mode); close() },
                )
            }
        }
    }
}

/** Last selected game per platform (process-wide), restored when returning to the platform. */
private val lastFocusedGame = HashMap<String, String>()

/** Grid layout for one panel state: tile width (dp, 0 = default), columns (0 = as many as fit), centred. */
internal data class GridLayoutSpec(val tile: Int = 0, val cols: Int = 0, val center: Boolean = false) {
    fun encode() = "$tile|$cols|${if (center) 1 else 0}"
    companion object {
        const val KEY_OFF = "grid_layout_panel_off"
        const val KEY_ON = "grid_layout_panel_on"
        const val KEY_NAMES = "grid_show_names"
        fun decode(s: String) = s.split('|').let { p ->
            GridLayoutSpec(p.getOrNull(0)?.toIntOrNull() ?: 0, p.getOrNull(1)?.toIntOrNull() ?: 0, p.getOrNull(2) == "1")
        }
    }
}

/**
 * Grid options (long-press on the panel icon), laid out like the Apps gear dialog: game
 * size, columns and centring — separately for PANEL OFF and PANEL ON.
 */
@Composable
private fun GridOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    accent: Color,
    @Suppress("UNUSED_PARAMETER") gridCols: Int = 0,
    panelCols: Int = 0,
    @Suppress("UNUSED_PARAMETER") onGridCols: (Int) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onPanelCols: (Int) -> Unit = {},
    startOnPanel: Boolean = false,
    onPanelTab: (Boolean) -> Unit = {},
) {
    if (!expanded) return
    val neon = LocalNeon.current
    val prefs = (androidx.compose.ui.platform.LocalContext.current.applicationContext
        as com.maiconerick.xgamelauncher.XGameLauncherApp).prefs
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var onTab by remember { mutableStateOf(startOnPanel) }
    val key = if (onTab) GridLayoutSpec.KEY_ON else GridLayoutSpec.KEY_OFF
    val default = if (onTab) GridLayoutSpec(cols = panelCols).encode() else GridLayoutSpec().encode()
    val raw by prefs.toolString(key, default).collectAsState(initial = default)
    val lay = GridLayoutSpec.decode(raw)
    fun save(l: GridLayoutSpec) { scope.launch { prefs.setToolString(key, l.encode()) } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.7f).clip(RoundedCornerShape(12.dp)).background(Color(0xF2050709))
                .background(accent.copy(alpha = 0.06f))
                .border(1.dp, accent, RoundedCornerShape(12.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Games layout", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                GridPill("PANEL OFF", !onTab, accent) { onTab = false; onPanelTab(false) }
                GridPill("PANEL ON", onTab, accent) { onTab = true; onPanelTab(true) }
            }
            val shown = if (lay.tile > 0) lay.tile else 170
            com.maiconerick.xgamelauncher.ui.components.GamepadSlider(
                label = "GAME SIZE", value = (shown - 80) / 280f, step = 0.04f,
                onChange = { v -> save(lay.copy(tile = (80 + v * 280).toInt())) },
                valueLabel = { v -> if (lay.tile == 0) "default" else "${(80 + v * 280).toInt()}dp" },
            )
            Text("COLUMNS", color = accent.copy(alpha = 0.8f), fontSize = 11.sp, letterSpacing = 1.2.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf(0 to "Auto", 1 to "1", 2 to "2", 3 to "3", 4 to "4", 5 to "5", 6 to "6", 7 to "7", 8 to "8", 10 to "10", 12 to "12")
                    .forEach { (v, label) -> GridPill(label, lay.cols == v, accent) { save(lay.copy(cols = v)) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Center games in grid", color = neon.ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                GridPill(if (lay.center) "ON" else "OFF", lay.center, accent) { save(lay.copy(center = !lay.center)) }
            }
            val names by prefs.toolFlag(GridLayoutSpec.KEY_NAMES, false).collectAsState(initial = false)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Show game names", color = neon.ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                GridPill(if (names) "ON" else "OFF", names, accent) { scope.launch { prefs.setToolFlag(GridLayoutSpec.KEY_NAMES, !names) } }
            }
            Row(Modifier.align(Alignment.End)) { GridPill("DONE", true, accent, onDismiss) }
        }
    }
}

@Composable
private fun GridPill(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.focusRing(focused, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
            .background(if (selected) accent else accent.copy(alpha = if (focused) 0.26f else 0.08f))
            .onFocusChanged { focused = it.isFocused }.focusable()
            .onGamepadActivate(onClick).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) Color.Black else accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
}

private fun GameSortMode.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    GameSortMode.NameAsc -> androidx.compose.material.icons.Icons.Filled.ArrowUpward
    GameSortMode.NameDesc -> androidx.compose.material.icons.Icons.Filled.ArrowDownward
    GameSortMode.LastPlayed -> androidx.compose.material.icons.Icons.Filled.Schedule
    GameSortMode.PlayTime -> androidx.compose.material.icons.Icons.Filled.Timer
    GameSortMode.Added -> androidx.compose.material.icons.Icons.Filled.NewReleases
}

@Composable
private fun SortButtonLegacy(currentLabel: String, onClick: () -> Unit, accent: Color) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) accent.copy(alpha = 0.24f) else Color(0x22FFFFFF))
            .border(1.dp, if (focused) accent else Color(0x33FFFFFF), RoundedCornerShape(4.dp))
            .focusRing(focused, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("SORT · ", color = neon.inkMuted, fontSize = 10.sp,
            letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium)
        Text(currentLabel.uppercase(), color = if (focused) accent else neon.ink,
            fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text("▾", color = neon.inkMuted, fontSize = 10.sp)
    }
}

@Composable
private fun HeaderIconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    var aDownAt by remember { mutableStateOf(0L) }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) accent.copy(alpha = 0.24f) else Color(0x22FFFFFF))
            .border(1.dp, if (selected) accent else Color(0x33FFFFFF), RoundedCornerShape(4.dp))
            .focusRing(focused, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // Gamepad: tap A = click, hold A (≥500ms) = long-press.
            .onPreviewKeyEvent { e ->
                val isA = e.key == androidx.compose.ui.input.key.Key.ButtonA ||
                    e.key == androidx.compose.ui.input.key.Key.Enter ||
                    e.key == androidx.compose.ui.input.key.Key.DirectionCenter
                if (!isA) return@onPreviewKeyEvent false
                when (e.type) {
                    androidx.compose.ui.input.key.KeyEventType.KeyDown -> {
                        if (aDownAt == 0L) aDownAt = android.os.SystemClock.uptimeMillis(); true
                    }
                    androidx.compose.ui.input.key.KeyEventType.KeyUp -> {
                        val held = android.os.SystemClock.uptimeMillis() - aDownAt
                        aDownAt = 0L
                        if (held >= 500 && onLongClick != null) onLongClick() else onClick()
                        true
                    }
                    else -> false
                }
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            icon, contentDescription = description,
            tint = if (selected) accent else neon.ink, modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun HeaderChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        selected -> accent.copy(alpha = 0.24f)
        focused -> accent.copy(alpha = 0.14f)
        else -> Color(0x22FFFFFF)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(1.dp, if (focused || selected) accent else Color(0x33FFFFFF), RoundedCornerShape(4.dp))
            .focusRing(focused, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected || focused) accent else neon.ink,
            fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- INFO PANEL ----------------

/**
 * Side panel for the focused game. Page 1 is clean (art, title, START / INFO); swipe
 * horizontally for playtime stats and file details.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InfoPanel(
    platform: Platform,
    game: GameEntry?,
    stats: PlaytimeStats?,
    heroPath: String?,
    isSessionOpen: Boolean,
    accent: Color,
    onStart: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier.width(290.dp),
) {
    val neon = LocalNeon.current
    val pager = androidx.compose.foundation.pager.rememberPagerState { 3 }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(end = 16.dp, top = 10.dp, bottom = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xCC000000))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
    ) {
        if (game == null) {
            Text("No game focused", color = neon.inkMuted, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
            return@Column
        }
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager, modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            Column(
                Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (page) {
                    0 -> {
                        if (heroPath != null) Box(
                            Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(8.dp))
                                .background(neon.surfaceElevated),
                        ) {
                            AsyncImage(model = heroPath, contentDescription = null,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Text(game.name, color = neon.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp, lineHeight = 20.sp)
                        Text(platform.name, color = accent, fontSize = 10.sp, letterSpacing = 1.4.sp,
                            fontWeight = FontWeight.SemiBold)
                        if (isSessionOpen) Text("● LIVE SESSION", color = Color(0xFFFF5555), fontSize = 11.sp,
                            letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PanelButton("▶ START", accent, filled = true, onClick = onStart, modifier = Modifier.weight(1f))
                            PanelButton("INFO", accent, filled = false, onClick = onInfo, modifier = Modifier.weight(1f))
                        }
                    }
                    1 -> {
                        Text("PLAYTIME", color = accent, fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
                        InfoStat(label = "TOTAL PLAYTIME",
                            value = TimeFormat.playtime(stats?.totalSeconds ?: 0L), accent = accent)
                        InfoStat(label = "SESSIONS", value = "${stats?.sessionCount ?: 0}", accent = accent)
                        InfoStat(label = "LAST PLAYED",
                            value = TimeFormat.lastPlayed(stats?.lastPlayedUnix ?: 0L), accent = accent)
                    }
                    else -> {
                        Text("FILE", color = accent, fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
                        Text(game.fileName, color = neon.inkDim, fontSize = 11.sp)
                        Text(game.filePath, color = neon.inkMuted, fontSize = 10.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(3) { i ->
                Box(Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 7.dp else 5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (i == pager.currentPage) accent else Color(0x55FFFFFF)))
            }
        }
    }
}

@Composable
private fun PanelButton(label: String, accent: Color, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp))
            .background(if (filled) accent else accent.copy(alpha = 0.12f))
            .border(1.dp, accent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) Color.Black else accent, fontSize = 12.sp,
            fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable
private fun InfoStat(label: String, value: String, accent: Color) {
    val neon = LocalNeon.current
    Column {
        Text(label, color = neon.inkMuted, fontSize = 9.sp, letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Medium)
        Text(value, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp)
    }
}

// ---------------- HELPERS ----------------

@Composable
private fun EmptyPlatform() {
    val neon = LocalNeon.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No games found in this platform's folders.",
            color = neon.inkDim, fontSize = 13.sp)
    }
}

private fun sortGames(
    games: List<GameEntry>,
    mode: GameSortMode,
    unplayed: GameSortMode,
    platformName: String,
    playtime: (String, String) -> PlaytimeStats?,
): List<GameEntry> {
    fun simple(list: List<GameEntry>, m: GameSortMode) = when (m) {
        GameSortMode.NameDesc -> list.sortedByDescending { it.name.lowercase() }
        GameSortMode.Added -> list.sortedByDescending { it.addedAt }
        else -> list.sortedBy { it.name.lowercase() }
    }
    return when (mode) {
        GameSortMode.NameAsc, GameSortMode.NameDesc, GameSortMode.Added -> simple(games, mode)
        GameSortMode.LastPlayed, GameSortMode.PlayTime -> {
            val stats = games.associateWith { playtime(it.name, platformName) }
            val (played, never) = games.partition { (stats[it]?.totalSeconds ?: 0L) > 0L }
            val playedSorted = if (mode == GameSortMode.LastPlayed)
                played.sortedByDescending { stats[it]?.lastPlayedUnix ?: 0L }
            else played.sortedByDescending { stats[it]?.totalSeconds ?: 0L }
            playedSorted + simple(never, unplayed)
        }
    }
}

private fun parseAccent(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val h = hex.trim().removePrefix("#")
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> null
    }
}
