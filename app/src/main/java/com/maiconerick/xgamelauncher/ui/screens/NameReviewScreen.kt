package com.maiconerick.xgamelauncher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.ui.LibraryState
import com.maiconerick.xgamelauncher.ui.components.GamepadTextField
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon

/**
 * Shown when a platform is added or a sync finds new games: each file name next to the
 * cleaned-up game name (region / revision / language tags and extensions removed), editable
 * before anything is saved. SAVE stores only the names that were changed here.
 */
@Composable
fun NameReviewScreen(
    state: LibraryState,
    gameIds: List<String>,
    onDone: (Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    val neon = LocalNeon.current
    val games = remember(gameIds) {
        val all = state.gamesByPlatform.values.flatten().associateBy { it.id }
        gameIds.mapNotNull { all[it] }.sortedBy { it.name.lowercase() }
    }
    val edits = remember { mutableStateMapOf<String, String>() }
    androidx.activity.compose.BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize().background(neon.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("New games · check the names", color = neon.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${games.size} found. Tags like (USA) (Rev 1) and extensions were removed — edit any you don't like.",
                    color = neon.inkDim, fontSize = 11.sp)
            }
            SmallBtn("SAVE" + if (edits.isNotEmpty()) " (${edits.size} edited)" else "", accent = true, onClick = {
                onDone(edits.filter { (id, n) -> n.isNotBlank() && n.trim() != games.firstOrNull { it.id == id }?.name }
                    .mapValues { it.value.trim() })
            })
            SmallBtn("KEEP AS IS", onClick = { onDone(emptyMap()) })
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(games, key = { it.id }) { g ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(g.fileName, color = neon.inkMuted, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(0.9f))
                    Text("→", color = neon.accent, fontSize = 14.sp)
                    Box(
                        Modifier.weight(1.1f).clip(RoundedCornerShape(6.dp))
                            .background(if (g.id in edits) neon.accent.copy(alpha = 0.10f) else Color(0x55000000))
                            .border(1.dp, if (g.id in edits) neon.accent else Color(0x22FFFFFF), RoundedCornerShape(6.dp)),
                    ) {
                        GamepadTextField(
                            value = edits[g.id] ?: g.name,
                            onValueChange = { edits[g.id] = it },
                            singleLine = true,
                            textStyle = TextStyle(color = neon.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            cursorBrush = SolidColor(neon.accent),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}
