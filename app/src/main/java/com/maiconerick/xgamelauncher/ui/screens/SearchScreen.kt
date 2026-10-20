package com.maiconerick.xgamelauncher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.data.PlaytimeStats
import com.maiconerick.xgamelauncher.ui.LibraryState
import com.maiconerick.xgamelauncher.ui.components.GameTile
import com.maiconerick.xgamelauncher.ui.components.GameTileState
import com.maiconerick.xgamelauncher.ui.components.TopBar
import com.maiconerick.xgamelauncher.ui.components.TopTab
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import com.maiconerick.xgamelauncher.util.TimeFormat

@Composable
fun SearchScreen(
    state: LibraryState,
    playtime: (String, String) -> PlaytimeStats?,
    onEnsureArt: (Platform, GameEntry) -> Unit,
    onOpenGame: (Platform, GameEntry) -> Unit,
    onTab: (TopTab) -> Unit,
) {
    val neon = LocalNeon.current
    var query by remember { mutableStateOf("") }
    val inputFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { inputFocus.requestFocus() } }

    val results by remember(query, state) {
        derivedStateOf {
            if (query.isBlank()) emptyList()
            else {
                val q = query.trim().lowercase()
                state.platforms.flatMap { p ->
                    state.gamesByPlatform[p.id].orEmpty()
                        .filter { it.name.lowercase().contains(q) }
                        .map { p to it }
                }.sortedBy { it.second.name.lowercase() }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column {
            TopBar(activeTab = TopTab.Home, onTab = onTab)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp, start = 240.dp, end = 240.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = neon.accent,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search across ${state.platforms.sumOf { state.gamesByPlatform[it.id]?.size ?: 0 }} games…",
                                color = neon.inkMuted,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            textStyle = TextStyle(
                                color = neon.ink,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = (-0.4).sp,
                            ),
                            singleLine = true,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(neon.accent),
                            modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                        )
                    }
                }
            }

            if (query.isNotBlank()) {
                Text(
                    text = "${results.size} MATCHES",
                    color = neon.accent,
                    fontSize = 10.sp,
                    letterSpacing = 1.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 240.dp, top = 12.dp),
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(results, key = { _, it -> it.second.id }) { index, (platform, game) ->
                    LaunchedEffect(game.id) { onEnsureArt(platform, game) }
                    GameTile(
                        state = GameTileState(
                            id = game.id,
                            title = game.name,
                            platformShort = platform.name.take(3).uppercase(),
                            boxArtPath = state.artByGameId[game.id]?.boxPath,
                            playtimeText = playtime(game.name, platform.name)?.let {
                                TimeFormat.playtimeChip(it.totalSeconds)
                            },
                        ),
                        onClick = { onOpenGame(platform, game) },
                        initiallyFocused = false,
                    )
                }
            }
        }
    }
}
