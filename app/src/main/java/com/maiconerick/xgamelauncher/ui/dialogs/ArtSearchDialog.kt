package com.maiconerick.xgamelauncher.ui.dialogs

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Settings
import com.maiconerick.xgamelauncher.ui.components.focusRing
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import coil.compose.AsyncImage
import com.maiconerick.xgamelauncher.XGameLauncherApp
import com.maiconerick.xgamelauncher.art.ArtTarget
import com.maiconerick.xgamelauncher.art.GameImageCandidate
import com.maiconerick.xgamelauncher.art.PickerPrefetch
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** URL returned by the picker — the caller drops it straight into a game override field.
 *  `renameTo` carries a proposed new display name when the user searched for the art
 *  using a different query than the original and confirmed the rename. */
data class ArtSearchResult(
    val heroUrl: String? = null,
    val boxUrl: String? = null,
    val renameTo: String? = null,
)

/**
 * Multi-source art picker — ported from xPlayTime's `ImagePickerDialog`. Each source
 * (IGDB / RAWG / SGDB) is queried in parallel and its results shown in its own tab;
 * candidates render in a 3-column grid with a resolution badge. The user picks one
 * and its URL is returned as either the HERO or BOX override depending on [target].
 *
 * `prefetchNext` warms the cache for upcoming queue entries (used by the bulk art picker
 * flow) so tabbing to the next game is instant.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun ArtSearchDialog(
    initialQuery: String,
    target: ArtTarget,
    onApply: (ArtSearchResult) -> Unit,
    onCancel: () -> Unit,
    prefetchNext: List<String> = emptyList(),
    /** Small caption on the tabs row (e.g. "3 of 12 · Game name" in the bulk picker). */
    caption: String? = null,
    /** Extra chips before CLOSE (e.g. SKIP in the bulk picker). */
    extraActions: @Composable () -> Unit = {},
    /** Offer to rename the game when the query was edited. */
    askRename: Boolean = true,
    /** The game's current name, to compare the edited query against (defaults to [initialQuery]). */
    renameBaseline: String = initialQuery,
    /** Reports every query edit (the bulk picker carries it to the next image). */
    onQueryChange: (String) -> Unit = {},
) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = ctx.applicationContext as XGameLauncherApp
    val provider = app.imageProvider

    // Disc numbers never help an art search ("Xenosaga II (Disc 1)" → "Xenosaga II").
    var query by remember(initialQuery) { mutableStateOf(com.maiconerick.xgamelauncher.util.NameCleaner.forArtSearch(initialQuery)) }
    LaunchedEffect(query) { onQueryChange(query) }
    // "Similar names": titles the sources know that look like this one.
    var similar by remember(initialQuery) { mutableStateOf<List<String>>(emptyList()) }
    var similarLoading by remember { mutableStateOf(false) }
    var similarFor by remember { mutableStateOf<String?>(null) }
    // Target became a switchable pair of tabs inside the dialog so the user can pick a
    // hero AND a box art in one visit. Initial value is what the caller opened us with.
    var activeTarget by remember(initialQuery) { mutableStateOf(target) }

    // Per-target result caches so switching tabs is instant when data is already fetched.
    var igdbResults by remember(initialQuery, activeTarget) { mutableStateOf<List<GameImageCandidate>>(emptyList()) }
    var rawgResults by remember(initialQuery, activeTarget) { mutableStateOf<List<GameImageCandidate>>(emptyList()) }
    var sgdbResults by remember(initialQuery, activeTarget) { mutableStateOf<List<GameImageCandidate>>(emptyList()) }
    var igdbLoading by remember(initialQuery, activeTarget) { mutableStateOf(false) }
    var rawgLoading by remember(initialQuery, activeTarget) { mutableStateOf(false) }
    var sgdbLoading by remember(initialQuery, activeTarget) { mutableStateOf(false) }

    var selectedTab by remember { mutableStateOf(0) }

    // OpenDocument gives a stable content:// URI (with persistable permission below) that
    // survives app restarts, matching how the "PICK FILE" chip in the edit form works.
    // When the user searched with a different name than the one the game currently has,
    // we hold the pick here and pop a confirm asking whether to rename the game too.
    var pendingApply by remember { mutableStateOf<ArtSearchResult?>(null) }

    fun dispatchOrPrompt(result: ArtSearchResult) {
        val q = query.trim()
        val orig = com.maiconerick.xgamelauncher.util.NameCleaner.forArtSearch(renameBaseline).trim()
        if (askRename && q.isNotBlank() && q != orig) {
            pendingApply = result  // ask before applying
        } else {
            onApply(result)
        }
    }

    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            dispatchOrPrompt(activeTarget.toResult(uri.toString()))
        }
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isBlank()) return
        val t = activeTarget
        val cached = PickerPrefetch.get(q, t)

        // IGDB
        cached.igdb?.let { igdbResults = it; igdbLoading = false } ?: run {
            igdbResults = emptyList(); igdbLoading = true
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching { provider.searchIgdb(q, t) }.getOrDefault(emptyList())
                }
                cached.igdb = r; igdbResults = r; igdbLoading = false
            }
        }
        // RAWG
        cached.rawg?.let { rawgResults = it; rawgLoading = false } ?: run {
            rawgResults = emptyList(); rawgLoading = true
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching { provider.searchRawg(q, t) }.getOrDefault(emptyList())
                }
                cached.rawg = r; rawgResults = r; rawgLoading = false
            }
        }
        // SteamGridDB
        cached.sgdb?.let { sgdbResults = it; sgdbLoading = false } ?: run {
            sgdbResults = emptyList(); sgdbLoading = true
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching { provider.searchSteamGridDb(q, t) }.getOrDefault(emptyList())
                }
                cached.sgdb = r; sgdbResults = r; sgdbLoading = false
            }
        }
    }

    // Refetch whenever the query OR the active target changes (each target keys its own
    // PickerPrefetch cache, so a HERO cache hit doesn't leak into BOX and vice-versa).
    // Search as you type (debounced) — no SEARCH button to press.
    LaunchedEffect(query, activeTarget) {
        if (query != initialQuery) kotlinx.coroutines.delay(550)
        runSearch()
    }
    LaunchedEffect(initialQuery, prefetchNext, activeTarget) {
        PickerPrefetch.attach(ctx)
        if (prefetchNext.isNotEmpty()) {
            // Both kinds of art for the upcoming games, so each next step opens instantly.
            PickerPrefetch.prefetch(scope, provider, prefetchNext, com.maiconerick.xgamelauncher.art.ArtTarget.BOX)
            PickerPrefetch.prefetch(scope, provider, prefetchNext, com.maiconerick.xgamelauncher.art.ArtTarget.HERO)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
            .clickable(onClick = onCancel)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp &&
                    (e.key == Key.ButtonB || e.key == Key.Back || e.key == Key.Escape)
                ) {
                    onCancel(); true
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        fun findSimilar() {
            val q = query.trim(); if (q.isBlank()) return
            similarFor = q; similarLoading = true
            scope.launch {
                // The query plus looser variants: before " - " / ":", and the first words.
                val words = q.split(Regex("\\s+"))
                val variants = linkedSetOf(q, q.substringBefore(" - ").trim(), q.substringBefore(":").trim(),
                    words.take(2).joinToString(" "), words.first()).filter { it.length >= 2 }
                val titles = LinkedHashSet<String>()
                variants.forEach { v ->
                    val found = withContext(Dispatchers.IO) {
                        runCatching { provider.searchIgdb(v, ArtTarget.BOX) }.getOrDefault(emptyList()) +
                            runCatching { provider.searchSteamGridDb(v, ArtTarget.BOX) }.getOrDefault(emptyList())
                    }
                    found.map { it.gameTitle.trim() }.filter { it.isNotBlank() && !it.equals(q, true) }.forEach { titles += it }
                }
                similar = titles.take(16); similarLoading = false
            }
        }
        // Nothing found anywhere → look for similar names automatically (once per query).
        LaunchedEffect(igdbLoading, rawgLoading, sgdbLoading, igdbResults, rawgResults, sgdbResults) {
            val done = !igdbLoading && !rawgLoading && !sgdbLoading
            if (done && igdbResults.isEmpty() && rawgResults.isEmpty() && sgdbResults.isEmpty() &&
                query.isNotBlank() && similarFor != query.trim()) findSimilar()
        }

        // Controls in a panel on the left, results on the right (more of each image visible).
        val compact = com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact
        val colsKey = if (activeTarget == ArtTarget.HERO) "art_cols_hero" else "art_cols_box"
        val colsDefault = if (activeTarget == ArtTarget.HERO) (if (compact) 2 else 3) else (if (compact) 3 else 5)
        val colsRaw by app.prefs.toolString(colsKey, colsDefault.toString()).collectAsState(initial = colsDefault.toString())
        val cols = colsRaw.toIntOrNull()?.coerceIn(1, 10) ?: colsDefault
        var showCols by remember { mutableStateOf(false) }
        fun setCols(n: Int) { scope.launch { app.prefs.setToolString(colsKey, n.coerceIn(1, 10).toString()) } }
        val tabs = listOf(
            Triple("IGDB", igdbResults, igdbLoading),
            Triple("RAWG", rawgResults, rawgLoading),
            Triple("SGDB", sgdbResults, sgdbLoading),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF00A0F14))
                .border(1.dp, neon.accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .clickable(enabled = true, onClick = {})
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Everything but the action chips scrolls, so SKIP / CLOSE stay pinned and visible
            // even when the similar-names list is long or the screen is short (Thor top, 353M).
            Column(
                Modifier.width(if (compact) 190.dp else 270.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
                ) {
                    Box {
                        SearchInput(value = query, onChange = { query = it }, onSubmit = { runSearch() })
                        if (igdbLoading || rawgLoading || sgdbLoading) CircularProgressIndicator(
                            color = neon.accent, strokeWidth = 2.dp,
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(18.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1f)) { TargetPill(label = "BOX ART", selected = activeTarget == ArtTarget.BOX,
                            onClick = { activeTarget = ArtTarget.BOX }) }
                        Box(Modifier.weight(1f)) { TargetPill(label = "BACKGROUND", selected = activeTarget == ArtTarget.HERO,
                            onClick = { activeTarget = ArtTarget.HERO }) }
                    }
                    Text("SOURCE", color = neon.accent.copy(alpha = 0.8f), fontSize = 10.sp, letterSpacing = 1.4.sp)
                    tabs.forEachIndexed { i, (label, list, loading) ->
                        SourceRow(label = label + if (loading) " …" else " (${list.size})", selected = selectedTab == i,
                            onClick = { selectedTab = i })
                    }
                    if (caption != null) Text(caption, color = neon.inkDim, fontSize = 12.sp, maxLines = 3)
                    // Similar-names section, set off from the sources by a divider.
                    Box(Modifier.fillMaxWidth().height(1.dp).background(neon.accent.copy(alpha = 0.3f)))
                    Text("SIMILAR NAMES" + if (similar.isNotEmpty()) " · tap to search" else "",
                        color = neon.accent.copy(alpha = 0.8f), fontSize = 10.sp, letterSpacing = 1.2.sp)
                    SourceRow(label = if (similarLoading) "Searching …" else "Find similar names", selected = false,
                        onClick = { findSimilar() })
                    if (!similarLoading && similarFor != null && similar.isEmpty())
                        Text("Nothing similar found.", color = neon.inkMuted, fontSize = 11.sp)
                    similar.forEach { t -> SourceRow(label = t, selected = false, onClick = { query = t }) }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(neon.accent.copy(alpha = 0.3f)))
                    // Gear: columns per row for this kind of art (saved separately for box / background).
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DlgIconChip(icon = androidx.compose.material.icons.Icons.Filled.Settings, selected = showCols,
                            onClick = { showCols = !showCols })
                        if (showCols) {
                            DlgChip(label = "−", accent = false, onClick = { setCols(cols - 1) })
                            Text("$cols per row", color = neon.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            DlgChip(label = "+", accent = false, onClick = { setCols(cols + 1) })
                        } else Text("$cols per row", color = neon.inkMuted, fontSize = 11.sp)
                    }
                }
                // Wraps onto a second line on narrower panels, so no label gets cut off.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DlgChip(label = "LOCAL", accent = false, onClick = { localPicker.launch(arrayOf("image/*")) })
                    extraActions()
                    DlgChip(label = "CLOSE", accent = false, onClick = onCancel)
                }
            }

            val (_, current, currentLoading) = tabs[selectedTab]
            val ratio = if (activeTarget == ArtTarget.HERO) 16f / 9f else 0.72f
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    currentLoading && current.isEmpty() ->
                        CircularProgressIndicator(color = neon.accent, modifier = Modifier.align(Alignment.Center))
                    current.isEmpty() -> Text(
                        // Empty results almost always mean the name didn't match (not a key problem).
                        "No results for “${query.trim()}”. Try a shorter or corrected name.",
                        color = neon.inkDim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(cols),
                        contentPadding = PaddingValues(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(current) { c ->
                            CandidateTile(
                                candidate = c,
                                aspectRatio = ratio,
                                accent = neon.accent,
                                onPick = { dispatchOrPrompt(activeTarget.toResult(c.url)) },
                            )
                        }
                    }
                }
            }
        }
    }

    // Rename-confirm overlay: shown on top of the picker when the user picked something
    // after typing a new search query. Choices: rename + apply / apply only / cancel.
    pendingApply?.let { result ->
        RenameConfirmDialog(
            proposedName = query.trim() + com.maiconerick.xgamelauncher.util.NameCleaner.discSuffix(renameBaseline),
            onRenameAndApply = {
                pendingApply = null
                onApply(result.copy(renameTo = query.trim() + com.maiconerick.xgamelauncher.util.NameCleaner.discSuffix(renameBaseline)))
            },
            onApplyOnly = {
                pendingApply = null
                onApply(result)
            },
            onCancel = { pendingApply = null },
        )
    }
}

@Composable
private fun RenameConfirmDialog(
    proposedName: String,
    onRenameAndApply: () -> Unit,
    onApplyOnly: () -> Unit,
    onCancel: () -> Unit,
) {
    val neon = LocalNeon.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
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
            Text("Rename this game too?", color = neon.accent, fontSize = 18.sp,
                fontWeight = FontWeight.Bold)
            Text(
                text = "You searched art for \"$proposedName\". Use that as the game's display name?",
                color = Color.White, fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DlgChip(label = "RENAME + APPLY", accent = true, onClick = onRenameAndApply)
                DlgChip(label = "APPLY ONLY", accent = false, onClick = onApplyOnly)
                DlgChip(label = "CANCEL", accent = false, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun TargetPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        selected -> neon.accent
        focused -> Color(0xFF3A3A3A)
        else -> Color(0xFF2A2A2A)
    }
    val fg = if (selected) Color.Black else Color.White
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(
                width = if (focused && !selected) 1.5.dp else 0.dp,
                color = if (focused && !selected) neon.accent else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .focusRing(focused, RoundedCornerShape(20.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            text = label, color = fg, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
    }
}

private fun ArtTarget.toResult(url: String): ArtSearchResult = when (this) {
    ArtTarget.HERO -> ArtSearchResult(heroUrl = url)
    ArtTarget.BOX -> ArtSearchResult(boxUrl = url)
}

@Composable
private fun CandidateTile(
    candidate: GameImageCandidate,
    aspectRatio: Float,
    accent: Color,
    onPick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) accent else Color(0x33FFFFFF),
                shape = RoundedCornerShape(8.dp),
            )
            .focusRing(focused, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onPick)
            .clickable(onClick = onPick),
    ) {
        AsyncImage(
            model = candidate.url,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        // Resolution + source badge along the bottom.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xCC000000))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = candidate.resolutionLabel,
                color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = candidate.source,
                color = Color(0xFFCCCCCC), fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
        }
    }
}

@Composable
private fun SearchInput(
    value: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color(0xFF12191E) else Color(0xFF0B1014))
            .border(1.dp, if (focused) neon.accent else neon.stroke, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused },
    ) {
        if (value.isEmpty()) {
            Text("Search…", color = neon.inkMuted, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        }
        com.maiconerick.xgamelauncher.ui.components.GamepadTextField(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 40.dp),
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = neon.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            singleLine = true,
            cursorBrush = SolidColor(neon.accent),
        )
    }
}

@Composable
private fun DlgChip(label: String, accent: Boolean, onClick: () -> Unit) {
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (accent) neon.accent else neon.ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}

@Composable
private fun SourceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().focusRing(focused, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
            .background(if (selected) neon.accent.copy(alpha = 0.2f) else if (focused) Color(0x22FFFFFF) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }.focusable()
            .onGamepadActivate(onClick).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) neon.accent else neon.ink, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun DlgIconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.size(34.dp).focusRing(focused, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
            .background(neon.accent.copy(alpha = if (selected || focused) 0.26f else 0.08f))
            .border(1.dp, neon.accent.copy(alpha = if (selected) 1f else 0.45f), RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }.focusable()
            .onGamepadActivate(onClick).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = "Columns", tint = neon.accent, modifier = Modifier.size(18.dp))
    }
}
