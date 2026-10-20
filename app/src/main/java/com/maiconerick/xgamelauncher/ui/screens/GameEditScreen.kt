package com.maiconerick.xgamelauncher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.GameOverrideRow
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.ui.LibraryState
import com.maiconerick.xgamelauncher.ui.PickerRequest
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon

/**
 * Full-screen wrapper around [GameEditForm], reached via `Route.GameEdit`. The inline
 * bottom-panel version used to be crammed into 40% of the detail screen — this route
 * gives the form the whole viewport so buttons don't overlap and long fields don't
 * force the user to scroll a tiny sub-region.
 */
@Composable
fun GameEditScreen(
    platform: Platform,
    game: GameEntry,
    state: LibraryState,
    onSaveOverride: (GameOverrideRow) -> Unit,
    onClearOverride: () -> Unit,
    onDone: () -> Unit,
    requestPicker: (PickerRequest?) -> Unit,
    pickerShowing: () -> Boolean,
) {
    val neon = LocalNeon.current
    GameEditForm(
        platform = platform,
        game = game,
        initial = state.overrideByGameId[game.id],
        androidComponent = state.androidRowByGameId[game.id]?.launchComponent,
        allPlatforms = state.platforms,
        onSave = { row -> onSaveOverride(row); onDone() },
        onReset = { onClearOverride(); onDone() },
        onCancel = onDone,
        requestPicker = requestPicker,
        pickerShowing = pickerShowing,
        modifier = Modifier.fillMaxSize().background(neon.surface),
    )
}
