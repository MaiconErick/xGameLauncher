package com.maiconerick.xgamelauncher.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Global "sync now" action reachable from any TopBar. Wired at the AppRoot level. */
val LocalOnSync = staticCompositionLocalOf<() -> Unit> { {} }

/** Global "open search" action reachable from any TopBar. Wired at the AppRoot level. */
val LocalOnSearch = staticCompositionLocalOf<() -> Unit> { {} }

/** Global picker requester — any screen can trigger a modal choice via this. */
val LocalPicker = staticCompositionLocalOf<(PickerRequest?) -> Unit> { { } }

/** Number of art fetches currently in flight. Read from any TopBar to render a "fetching…" chip. */
val LocalArtFetchingCount = staticCompositionLocalOf<() -> Int> { { 0 } }

/** Global "open the art picker for these games". Wired at the AppRoot level. */
val LocalOnArtPicker = staticCompositionLocalOf<(List<String>) -> Unit> { { } }

/** IDs of every game in the library that's missing either hero or box art. Used by
 *  the TopBar to render the "!" needs-art chip and to feed the art picker on tap. */
val LocalMissingArtGameIds = staticCompositionLocalOf<() -> List<String>> { { emptyList() } }

data class PickerOption(
    val id: String,
    val label: String,
    val subtitle: String? = null,
)

data class PickerRequest(
    val title: String,
    val options: List<PickerOption>,
    val onPick: (String) -> Unit,
    val onCancel: () -> Unit = {},
    /** Shows a type-to-filter box above the list (long lists: apps, activities, platforms). */
    val filterable: Boolean = false,
    /** Optional line under the title. */
    val message: String? = null,
)
