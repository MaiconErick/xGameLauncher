package com.maiconerick.xgamelauncher.ui.screens

import com.maiconerick.xgamelauncher.art.ArtTarget
import com.maiconerick.xgamelauncher.ui.components.focusRing
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maiconerick.xgamelauncher.art.ArtCandidate
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.ui.LauncherViewModel
import com.maiconerick.xgamelauncher.ui.LibraryState
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon

/**
 * Interactive art picker walking a queue of games. Covers on the left, heroes on the right;
 * A picks. NEXT saves the current selection and advances; SKIP moves on without saving; DONE
 * commits and closes. Preloads candidates for the next 3 games in the queue via the
 * ViewModel while the user is choosing the current one.
 */
@Composable
fun ArtPickerScreen(
    vm: LauncherViewModel,
    state: LibraryState,
    gameIds: List<String>,
    onDone: () -> Unit,
) {
    val neon = LocalNeon.current

    var index by remember { mutableStateOf(0) }
    // Per game: the search text as edited (carried from box art to background) and the
    // name after a rename, so the rename is only asked once.
    val queries = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val renamed = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    // Per game: BOX ART, then BACKGROUND, then the next game.
    var stage by remember { mutableStateOf(0) }
    val games = remember(state.gamesByPlatform) { state.gamesByPlatform.values.flatten().associateBy { it.id } }

    val currentId = gameIds.getOrNull(index)
    val game: GameEntry? = currentId?.let { games[it] }
    // Always box art first, then background, for every game (SKIP passes on one).
    val needs = listOf(ArtTarget.BOX, ArtTarget.HERO)

    fun next() {
        if (stage + 1 < needs.size) { stage += 1; return }
        stage = 0
        if (index + 1 < gameIds.size) index += 1 else onDone()
    }

    if (currentId == null || game == null) { LaunchedEffect(Unit) { onDone() }; return }
    val target = needs[stage.coerceIn(0, needs.lastIndex)]
    val upcoming = gameIds.drop(index + 1).take(3).mapNotNull { games[it]?.name }
        .map { com.maiconerick.xgamelauncher.util.NameCleaner.forArtSearch(it) }
    androidx.compose.runtime.key(currentId, stage) {
        com.maiconerick.xgamelauncher.ui.dialogs.ArtSearchDialog(
            initialQuery = queries[currentId] ?: game.name,
            renameBaseline = renamed[currentId] ?: game.name,
            onQueryChange = { q -> queries[currentId] = q },
            target = target,
            caption = "${index + 1} of ${gameIds.size} · ${game.name} · " +
                (if (target == ArtTarget.BOX) "box art" else "background") +
                (if (needs.size > 1) " (${stage + 1}/${needs.size})" else ""),
            askRename = true,
            // This game's other image + the next games' both images load in the background.
            prefetchNext = listOf(game.name) + upcoming,
            extraActions = { TopChip("SKIP", accent = false, onClick = { next() }) },
            onApply = { r ->
                r.renameTo?.takeIf { it.isNotBlank() }?.let { n ->
                    renamed[currentId] = n
                    vm.renameGames(mapOf(currentId to n), clearArt = false)
                }
                vm.applyArtChoice(currentId, r.boxUrl, r.heroUrl)
                next()
            },
            onCancel = onDone,
        )
    }
}


@Composable
private fun CandidateColumn(
    title: String,
    items: List<ArtCandidate>,
    selected: String?,
    onSelect: (String) -> Unit,
    aspect: Float,
    modifier: Modifier = Modifier,
) {
    val neon = LocalNeon.current
    Column(modifier.padding(16.dp)) {
        Text(title, color = neon.accent, fontSize = 11.sp, letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        if (items.isEmpty()) {
            Text("(none)", color = neon.inkMuted, fontSize = 12.sp)
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (aspect < 1f) 140.dp else 220.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { _, c -> c.url }) { idx, c ->
                CandidateCell(
                    candidate = c,
                    aspect = aspect,
                    selected = selected == c.url,
                    initiallyFocused = idx == 0,
                    onClick = { onSelect(c.url) },
                )
            }
        }
    }
}

@Composable
private fun CandidateCell(
    candidate: ArtCandidate,
    aspect: Float,
    selected: Boolean,
    initiallyFocused: Boolean,
    onClick: () -> Unit,
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(initiallyFocused) {
        if (initiallyFocused) runCatching { focusRequester.requestFocus() }
    }
    val borderColor = when {
        selected -> neon.accent
        focused -> neon.accent.copy(alpha = 0.7f)
        else -> Color(0x33FFFFFF)
    }
    Column(
        modifier = Modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
            .background(neon.surfaceElevated)
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .focusRequester(focusRequester)
            .focusRing(focused, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.weight(1f)) {
            AsyncImage(model = candidate.url, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(neon.accent)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text("✓", color = Color.Black, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(candidate.source.uppercase(), color = neon.inkMuted, fontSize = 8.sp,
            letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(6.dp))
    }
}

@Composable
private fun TopChip(label: String, accent: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val active = if (accent) neon.accent else neon.ink
    val bg = when {
        focused && accent -> neon.accent.copy(alpha = 0.28f)
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
