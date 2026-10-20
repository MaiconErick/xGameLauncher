package com.maiconerick.xgamelauncher.ui

sealed class Route {
    data object Boot : Route()
    /** Platforms hub (grid of platform tiles + bottom platform strip). */
    data object Home : Route()
    /** Full-device app drawer — every installed launcher app. */
    data object Apps : Route()
    /** Games grid for a specific platform. */
    data class Games(val platformId: String) : Route()
    data class GameDetail(val platformId: String, val gameId: String) : Route()
    data object Search : Route()
    /** Full-screen platform editor. `platformId = null` = new custom platform. */
    data class PlatformEdit(val platformId: String?, val initialTab: String? = null) : Route()
    /** Full-screen per-game override editor (splash, hero, title override, etc). */
    data class GameEdit(val platformId: String, val gameId: String) : Route()
    /** Interactive art picker over a queue of games. Preloads next-N candidates while the
     *  user is choosing for the current one. */
    data class ArtPicker(val gameIds: List<String>) : Route()
    /** Review / edit the cleaned-up names of newly found games, then (optionally) art. */
    data class NameReview(val gameIds: List<String>, val artIds: List<String>) : Route()
    /** Tools tab index and a single tool's settings page. */
    data object Tools : Route()
    data class Tool(val id: String) : Route()
    /** romgi catalog search (optionally scoped to one of our platforms) and one entry. */
    data class RomBrowse(val platformId: String?) : Route()
    data class RomEntry(val slug: String, val platformId: String?) : Route()
    /** Settings; optional section id for deep-linking (e.g. from Y-on-Android). */
    data class Settings(val initialSectionId: String? = null) : Route()
    /** Playtime editor; [filter] pre-fills the search (a game's name from its detail page). */
    data class PlaytimeEditor(val filter: String? = null) : Route()
}

/**
 * Sort options for the games grid. Persists nowhere — resets on app restart.
 * (Kept intentionally small; add DataStore-backed persistence later if needed.)
 */
enum class GameSortMode(val label: String) {
    NameAsc("Name ↑"),
    NameDesc("Name ↓"),
    LastPlayed("Last played"),
    PlayTime("Time played"),
    Added("Recently added"),
}

/** Order for games never played, used by the play-based sorts (Last / Time played). */
val UNPLAYED_SORTS = listOf(GameSortMode.NameAsc, GameSortMode.NameDesc, GameSortMode.Added)
