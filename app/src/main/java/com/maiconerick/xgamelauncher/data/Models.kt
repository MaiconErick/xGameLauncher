package com.maiconerick.xgamelauncher.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One way to launch a game on this platform ("player" in Daijishō terms) — e.g.
 * "RetroArch · gambatte", "DuckStation", "ARMSX2". A platform can have multiple players; the
 * launcher picks by (in order): game override → platform default → single-player shortcut →
 * picker dialog.
 *
 * The fields mirror `am start` options one-to-one so Daijishō's `amStartArguments` can be
 * imported losslessly (see [com.maiconerick.xgamelauncher.launch.AmArgs]):
 *   -n component · -a action · -d dataTemplate · -t mimeType · -c categories ·
 *   -e/--es/--ez/--ei/--el/--ef/--eu/--esa/--eia/--esn extras · -f flagsHex · --activity-* flags.
 * Every string value may contain template tokens like {file.path} / {file.uri}.
 */
@Serializable
data class LaunchIntent(
    val id: String,
    val name: String,
    val pkg: String? = null,
    val component: String? = null,         // "pkg/class", "pkg/.Class" or just class
    val action: String? = null,
    val dataTemplate: String? = null,      // "{file.uri}" | "{file.path}" | custom
    val mimeType: String? = null,
    val categories: List<String> = emptyList(),
    val extras: List<IntentExtra> = emptyList(),
    /** am-style flag switches, e.g. "--activity-clear-task", "--grant-read-uri-permission". */
    val flags: List<String> = emptyList(),
    /** Raw `-f` value (hex or decimal), OR-ed into the intent flags. */
    val flagsHex: String? = null,
    /** Daijishō's killPackageProcesses: force-stop the emulator (root) so it cold-starts. */
    val killBefore: Boolean = true,
) {
    /** Package this player launches — explicit pkg, else the component's package part. */
    fun targetPackage(): String? =
        pkg?.takeIf { it.isNotBlank() }
            ?: component?.substringBefore('/', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
}

/** One `am start` extra. `type` is the am switch without dashes: es (string, same as -e),
 *  ez (bool), ei (int), el (long), ef (float), eu (uri), esa (string[]), eia (int[]),
 *  esn (null string). Array values are comma-separated, like am. */
@Serializable
data class IntentExtra(
    val type: String = "es",
    val key: String = "",
    val value: String = "",
)

/** A platform group (NDS, PSX, Windows, …). Games are scanned from `romsPath` plus any
 *  `extraPaths` — Windows in particular pulls from multiple forwarder folders
 *  (bannerhub/Beacon, bannerhub/ES-DE, winlator/frontend). */
data class Platform(
    val id: String,
    val name: String,
    val romsPath: String,
    val extraPaths: List<String> = emptyList(),
    val fileExtensionsRegex: String? = null,
    val intents: List<LaunchIntent> = emptyList(),
    val defaultIntentId: String? = null,
    val artSearchName: String = name,
    val gameCount: Int = 0,
    val totalPlaytimeSec: Long = 0L,
    /** Per-platform layout + skinning — surfaced in the platform edit form. */
    val columnsWithoutPanel: Int = 5,
    val columnsWithPanel: Int = 4,
    val accentColorHex: String? = null,          // "#00E5FF" — null = fall back to neon palette
    val backgroundImagePath: String? = null,     // local file or bundled asset:// uri
    val iconImagePath: String? = null,           // shown in place of the platform name
) {
    /** All folders to scan for this platform, primary first. */
    fun allPaths(): List<String> = listOf(romsPath) + extraPaths
}

data class GameEntry(
    val id: String,
    val platformId: String,
    val name: String,
    val filePath: String,
    val fileName: String,
    /** When this game first appeared in the library (file mtime, or added-at for Android apps). */
    val addedAt: Long = 0L,
)

data class PlaytimeStats(
    val totalSeconds: Long,
    val lastPlayedUnix: Long,
    val sessionCount: Int,
)

/** An emulator surfaced in Settings → Players. Sourced from platform intents + detected
 *  winlator packages. Read-only for now; edit lives per-platform under Settings → Platforms. */
data class DiscoveredEmulator(
    val id: String,               // "winlator:<pkg>" or "intent:<platformId>:<intentId>"
    val displayName: String,
    val pkg: String?,
    val platformNames: List<String>,
    val source: String,           // "auto-winlator" | "platform-intent"
)

data class ArtRef(
    val gameId: String,
    val boxPath: String?,
    val heroPath: String?,
    val logoPath: String?,
)

// ---- Room entities -----------------------------------------------------------

@Entity(tableName = "rom")
data class RomRow(
    @PrimaryKey val id: String,
    val platformId: String,
    val name: String,
    val filePath: String,
    val fileName: String,
    val mtime: Long,
)

@Entity(tableName = "art")
data class ArtRow(
    @PrimaryKey val gameId: String,
    val boxPath: String?,
    val heroPath: String?,
    val logoPath: String?,
    val fetchedAt: Long,
    val missed: Boolean,
    val sourceName: String? = null,      // name used to search — invalidate art when it changes
)

/** Playtime session — written by TrackerService when the built-in tracker is on. Mirrors
 *  xPTT's Session schema (PlayTime table) closely so a future sync/import stays cheap. */
@Entity(tableName = "session")
data class SessionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameName: String,
    val platform: String,
    val emulator: String,
    val pkg: String,
    val startedUnix: Long,
    val endedUnix: Long? = null,
    val timePlayedSeconds: Long? = null,
    val endReason: String? = null,
    val user: String,
    val device: String,
    val artPath: String? = null,
    val isOpen: Boolean = true,
    /** Where the row came from: "internal" (this app's tracker) or "xptt" (imported from
     *  xPlayTimeTracker's sqlite). Used to skip re-reading xPTT after a one-shot import. */
    val source: String = "internal",
)

@Entity(tableName = "android_game")
data class AndroidGameRow(
    @PrimaryKey val pkg: String,
    val label: String,
    val launchComponent: String,
    val addedAt: Long,
)

/**
 * Per-game override. Populated by the "Edit" panel on GameDetail.
 *  · launchIntentId: "" = ask on every launch; null = fall back to platform default.
 *  · screenPref: "top" | "bottom" | "ask" (top=display 0, bottom=display 1 on the Thor); null = default screen.
 *  · displayNameOverride: user-typed name; wins over the scanner-produced one.
 */
@Entity(tableName = "game_override")
data class GameOverrideRow(
    @PrimaryKey val gameId: String,
    val launchIntentId: String? = null,
    val screenPref: String? = null,
    val displayNameOverride: String? = null,
    val boxArtOverride: String? = null,
    val heroArtOverride: String? = null,
)

@Entity(tableName = "platform_meta")
data class PlatformMetaRow(
    @PrimaryKey val id: String,
    val name: String,
    val romsPath: String,
    val extraPathsJson: String?,
    val fileExtensionsRegex: String?,
    val intentsJson: String,
    val defaultIntentId: String?,
    val artSearchName: String,
    val sortOrder: Int,
    val columnsWithoutPanel: Int = 5,
    val columnsWithPanel: Int = 4,
    val accentColorHex: String? = null,
    val backgroundImagePath: String? = null,
    val iconImagePath: String? = null,
)
