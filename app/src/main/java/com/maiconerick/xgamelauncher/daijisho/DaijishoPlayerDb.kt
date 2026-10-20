package com.maiconerick.xgamelauncher.daijisho

import android.content.Context
import com.maiconerick.xgamelauncher.data.LaunchIntent
import com.maiconerick.xgamelauncher.launch.AmArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Daijishō's community platform/player database (github.com/TapiocaFox/Daijishou/platforms).
 * A trimmed snapshot ships in assets/daijisho_players.json so import works offline on the
 * handhelds; [refreshFromGitHub] pulls the live repo into filesDir, which then wins.
 */
object DaijishoPlayerDb {

    @Serializable
    data class Player(
        val name: String,
        val uniqueId: String? = null,
        val description: String? = null,
        val acceptedFilenameRegex: String? = null,
        val amStartArguments: String = "",
        val killPackageProcesses: Boolean = false,
    ) {
        /** Package named by the player's -n / -p argument. */
        val pkg: String? get() = AmArgs.parse(amStartArguments, BLANK).intent.targetPackage()
    }

    @Serializable
    data class PlatformDef(
        val file: String,
        val name: String? = null,
        val uniqueId: String? = null,
        val shortname: String? = null,
        val acceptedFilenameRegex: String? = null,
        val players: List<Player> = emptyList(),
    ) {
        val displayName: String get() = name ?: file.removeSuffix(".json")
    }

    @Serializable
    data class Snapshot(
        val source: String = "",
        val snapshot: String = "",
        val platforms: List<PlatformDef> = emptyList(),
    )

    private val BLANK = LaunchIntent(id = "", name = "")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private const val ASSET = "daijisho_players.json"
    private const val CACHE = "daijisho_players_live.json"
    private const val REPO_API = "https://api.github.com/repos/TapiocaFox/Daijishou/contents/platforms"
    private const val RAW = "https://raw.githubusercontent.com/TapiocaFox/Daijishou/main/platforms/"

    @Volatile private var memo: Snapshot? = null

    suspend fun load(ctx: Context): Snapshot = withContext(Dispatchers.IO) {
        memo?.let { return@withContext it }
        val live = File(ctx.filesDir, CACHE)
        val text = if (live.isFile) live.readText()
                   else ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
        json.decodeFromString(Snapshot.serializer(), text).also { memo = it }
    }

    /** Re-downloads every platform JSON from GitHub. Returns the new snapshot or throws. */
    suspend fun refreshFromGitHub(ctx: Context): Snapshot = withContext(Dispatchers.IO) {
        val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
        fun get(url: String): String = http.newCall(
            Request.Builder().url(url).header("User-Agent", "xGameLauncher").build()
        ).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} for $url")
            r.body?.string().orEmpty()
        }
        // platforms/index.json lists every platform file (no API rate limit); the GitHub
        // contents API is only a fallback.
        val names = runCatching {
            json.parseToJsonElement(get(RAW + "index.json")).jsonObject["platformList"]!!.jsonArray
                .mapNotNull { it.jsonObject["filename"]?.jsonPrimitive?.contentOrNull }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: json.parseToJsonElement(get(REPO_API)).jsonArray
            .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            .filter { it.endsWith(".json") && it != "index.json" }
        val platforms = names.mapNotNull { file ->
            runCatching {
                val o = json.parseToJsonElement(
                    get(RAW + URLEncoder.encode(file, "UTF-8").replace("+", "%20"))
                ).jsonObject
                val p = o["platform"]?.jsonObject
                PlatformDef(
                    file = file,
                    name = p.str("name"),
                    uniqueId = p.str("uniqueId"),
                    shortname = p.str("shortname"),
                    acceptedFilenameRegex = p.str("acceptedFilenameRegex"),
                    players = o["playerList"]?.jsonArray.orEmpty().map { e ->
                        val pl = e.jsonObject
                        Player(
                            name = pl.str("name") ?: "Player",
                            uniqueId = pl.str("uniqueId"),
                            description = pl.str("description"),
                            acceptedFilenameRegex = pl.str("acceptedFilenameRegex"),
                            amStartArguments = pl.str("amStartArguments") ?: "",
                            killPackageProcesses = pl["killPackageProcesses"]?.jsonPrimitive?.booleanOrNull ?: false,
                        )
                    },
                )
            }.getOrNull()
        }
        if (platforms.isEmpty()) error("GitHub returned no platforms")
        val snap = Snapshot(source = REPO_API, snapshot = java.time.LocalDate.now().toString(), platforms = platforms)
        File(ctx.filesDir, CACHE).writeText(json.encodeToString(Snapshot.serializer(), snap))
        memo = snap
        snap
    }

    private fun JsonObject?.str(key: String): String? =
        this?.get(key)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    /** The platform [platformName] most likely means ("GBA", "Game Boy Advance" → gba), or
     *  null when nothing is a confident match. */
    fun bestMatch(defs: List<PlatformDef>, platformName: String): PlatformDef? {
        val name = platformName.lowercase().replace(" ", "")
        if (name.isEmpty()) return null
        return defs.firstOrNull { it.shortname?.lowercase() == name || it.uniqueId?.lowercase() == name }
            ?: defs.firstOrNull { it.displayName.lowercase().replace(" ", "") == name }
            ?: defs.firstOrNull {
                // "Game Boy Advance" vs "Nintendo - Game Boy Advance"
                val n = it.displayName.lowercase().replace(" ", "").substringAfter("-")
                n == name
            }
    }

    /** Platforms ordered so the ones that look like [platformName] / [romsPath] come first. */
    fun rank(defs: List<PlatformDef>, platformName: String, romsPath: String): List<PlatformDef> {
        val folder = romsPath.trimEnd('/').substringAfterLast('/').lowercase()
            .takeUnless { com.maiconerick.xgamelauncher.rom.RomFolders.isRootLike(romsPath) }.orEmpty()
        val name = platformName.lowercase().replace(" ", "")
        fun score(d: PlatformDef): Int {
            val n = d.displayName.lowercase().replace(" ", "")
            val short = d.shortname?.lowercase().orEmpty()
            return when {
                folder.isNotEmpty() && (short == folder || d.uniqueId?.lowercase() == folder) -> 0
                name.isNotEmpty() && (short == name || n == name) -> 1
                name.isNotEmpty() && (n.contains(name) || name.contains(n)) -> 2
                folder.isNotEmpty() && n.contains(folder) -> 3
                else -> 9
            }
        }
        return defs.sortedWith(compareBy<PlatformDef> { score(it) }.thenBy { it.displayName.lowercase() })
    }

    /** Converts a Daijishō player into our [LaunchIntent]. "psx - Duckstation" → "Duckstation". */
    fun toLaunchIntent(p: Player, platformShort: String?): Pair<LaunchIntent, List<String>> {
        val prefix = platformShort?.let { "$it - " }
        val name = p.name.let { if (prefix != null && it.startsWith(prefix, true)) it.substring(prefix.length) else it }
        val base = LaunchIntent(
            id = "int_" + System.currentTimeMillis() + "_" + (0..99999).random(),
            name = name.trim().ifBlank { p.name },
            killBefore = p.killPackageProcesses,
        )
        val r = AmArgs.parse(p.amStartArguments, base)
        return r.intent.copy(killBefore = p.killPackageProcesses) to r.warnings
    }
}
