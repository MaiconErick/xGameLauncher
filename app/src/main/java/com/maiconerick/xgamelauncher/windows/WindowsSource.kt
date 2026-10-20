package com.maiconerick.xgamelauncher.windows

import android.content.Context
import com.maiconerick.xgamelauncher.data.AppDatabase
import com.maiconerick.xgamelauncher.data.ArtRow
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.data.RomRow
import com.maiconerick.xgamelauncher.util.NameCleaner
import com.maiconerick.xgamelauncher.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The Windows platform: forwarder files under the folders set in its platform editor,
 * scanned recursively. Supported forwarders:
 *
 *   *.localgameid / *.iso / *.steam — BannerHub; content (or file name) = "local_<uuid>".
 *                                    Title comes from BannerHub's own registry (root).
 *   *.desktop                       — Winlator shortcut (Name=, Path=, WINEPREFIX=).
 *
 * The SUBFOLDER picks the player: `BannerHub/…` → BannerHub, `xcronyx/…` → xCronyx, `cronyx/…` → Cronyx,
 * `ref4ik/…` → Winlator ref4ik, etc. Otherwise the package named in the .desktop file is
 * used if installed, else the first installed Winlator flavour. The same game found in
 * several folders becomes ONE game with several [Variant]s (players); the game detail
 * screen offers a chooser, and the last-used variant is the default.
 */
object WindowsSource {

    const val PLATFORM_ID = "windows"
    const val PLATFORM_NAME = "Windows"

    val DEFAULT_PRIMARY_PATH = com.maiconerick.xgamelauncher.rom.RomFolders.WINDOWS_ROOT
    /** xCronyx writes its live forwarders to Forwarders/Winlator (one per game, always up to date). */
    const val FORWARDERS_ROOT = "/storage/emulated/0/MaiconErick/Forwarders"
    val DEFAULT_EXTRA_PATHS = listOf(FORWARDERS_ROOT)
    /** Where xCronyx forwarders lived before; moved files keep the game id they had there (art/overrides stay). */
    private const val OLD_XCRONYX_DIR = "/storage/emulated/0/Download/Winlator/Frontend/"

    /** Earlier default folders; a Windows platform still on only these moves to the new root. */
    val LEGACY_PATHS = setOf(
        "/storage/emulated/0/Download/bannerhub", "/storage/emulated/0/Download/winlator/Frontend",
        "/storage/emulated/0/Download/winlator/forwarders", "/storage/emulated/0/ROMS/Forwarders",
        "/storage/emulated/0/Download/winlator",
    )

    /** The seeded platform we insert on first run. Paths are edited in the platform editor. */
    fun defaultPlatform(): Platform = Platform(
        id = PLATFORM_ID,
        name = PLATFORM_NAME,
        romsPath = DEFAULT_PRIMARY_PATH,
        extraPaths = DEFAULT_EXTRA_PATHS,
        fileExtensionsRegex = null,
        intents = emptyList(), // per-file dispatch — players come from the folders
        defaultIntentId = null,
        artSearchName = PLATFORM_NAME,
    )

    // ---- players ----

    const val BANNERHUB_ACTIVITY = "com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity"
    val BANNERHUB_PACKAGES = listOf("com.xiaoji.egggame", "banner.hub", "banner.hub.lite", "gamehub.lite")

    /** Winlator flavours: package → launcher activity (class, fully qualified). */
    val WINLATOR_COMPONENTS: Map<String, String> = linkedMapOf(
        // CronyX is a Winlator cmod fork: its classes keep the com.winlator.cmod namespace.
        // Hallowine (the user's own build, ex-xCronyx) comes first so it is the default flavour.
        "com.hallowquack.hallowine" to "com.winlator.cmod.XServerDisplayActivity",
        "com.winlator.xcronyx" to "com.winlator.cmod.XServerDisplayActivity",
        "com.cronyx" to "com.winlator.cmod.XServerDisplayActivity",
        "com.winlator.ref4ik" to "com.winlator.cmod.XServerDisplayActivity",
        "com.winlator.cmod" to "com.winlator.cmod.XServerDisplayActivity",
        "com.winlator" to "com.winlator.XServerDisplayActivity",
    )

    /** Folder-name keywords → package. Checked from the file's folder upwards. */
    private val FOLDER_KEYWORDS = listOf(
        "bannerhub" to "@bannerhub", "gamehub" to "@bannerhub", "egggame" to "@bannerhub",
        // "xcronyx" must be checked before "cronyx", which it contains.
        "hallowine" to "com.hallowquack.hallowine",
        "xcronyx" to "com.hallowquack.hallowine",
        "cronyx" to "com.cronyx",
        "ref4ik" to "com.winlator.ref4ik",
        "cmod" to "com.winlator.cmod",
        "winlator" to "@winlator",
    )

    sealed class Player {
        abstract val pkg: String
        data class BannerHub(override val pkg: String, val localGameId: String) : Player()
        data class Winlator(override val pkg: String, val activity: String) : Player()
    }

    /** One way to launch a game: the forwarder file + the player it resolved to. */
    data class Variant(val file: File, val player: Player, val folderLabel: String) {
        val key: String get() = file.absolutePath
    }

    /** gameId → variants, refreshed by every [scan]. */
    private val variantsByGame = ConcurrentHashMap<String, List<Variant>>()

    fun variantsFor(gameId: String): List<Variant> = variantsByGame[gameId].orEmpty()

    // ---- scan ----

    suspend fun scan(ctx: Context, db: AppDatabase, platform: Platform): List<GameEntry> =
        withContext(Dispatchers.IO) {
            val installed = { pkg: String ->
                runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            }
            val bhPkg = BANNERHUB_PACKAGES.firstOrNull(installed)
            val firstWinlator = WINLATOR_COMPONENTS.keys.firstOrNull(installed)
            val bhNames = BannerHubNames(ctx, bhPkg)

            data class Found(val name: String, val variant: Variant, val bhId: String?)
            val found = ArrayList<Found>()
            // BannerHub writes the same game under several frontends (Beacon .iso, ES-DE
            // .steam, .localgameid): one BannerHub variant per localGameId.
            val seenBh = HashSet<String>()
            platform.allPaths().forEach { rootPath ->
                val root = File(rootPath)
                if (!root.isDirectory) return@forEach
                root.walkTopDown().maxDepth(4).forEach walk@{ f ->
                    if (!f.isFile || f.name.startsWith(".")) return@walk
                    val folderHint = folderPlayer(root, f)
                    val lower = f.name.lowercase()
                    when {
                        lower.endsWith(".localgameid") || lower.endsWith(".iso") || lower.endsWith(".steam") -> {
                            val id = runCatching { f.readText().trim() }.getOrNull()
                                ?.takeIf { it.startsWith("local_") }
                                ?: f.nameWithoutExtension.takeIf { it.startsWith("local_") }
                                ?: return@walk
                            val pkg = bhPkg ?: return@walk
                            if (!seenBh.add(id)) return@walk
                            val title = bhNames.name(id)
                                ?: f.nameWithoutExtension.takeUnless { it.startsWith("local_") }
                                ?: id
                            found += Found(title, Variant(f, Player.BannerHub(pkg, id), folderLabel(root, f)), id)
                        }
                        lower.endsWith(".desktop") -> {
                            val text = runCatching { f.readText() }.getOrNull() ?: return@walk
                            val named = parseWinlatorPackage(text)
                            val pkg = when {
                                folderHint != null && folderHint.startsWith("@").not() && installed(folderHint) -> folderHint
                                named != null && installed(named) -> named
                                else -> firstWinlator
                            } ?: return@walk
                            val activity = WINLATOR_COMPONENTS[pkg] ?: "$pkg.XServerDisplayActivity"
                            val title = parseDesktopName(text) ?: f.nameWithoutExtension
                            found += Found(title, Variant(f, Player.Winlator(pkg, activity), folderLabel(root, f)), null)
                        }
                    }
                }
            }
            bhNames.save()

            // Group by normalized title → one game, several variants (folder order kept).
            val groups = LinkedHashMap<String, MutableList<Found>>()
            found.forEach { groups.getOrPut(norm(it.name)) { ArrayList() }.add(it) }

            val rows = ArrayList<RomRow>()
            val artRows = ArrayList<ArtRow>()
            val newVariants = HashMap<String, List<Variant>>()
            groups.values.forEach { list ->
                val first = list.first()
                // Keep the pre-merge id of the first variant so art/overrides survive.
                val id = first.bhId?.let { stableIdForBh(it) } ?: stableIdForDesktop(first.variant.file.absolutePath)
                val name = NameCleaner.prettify(first.name)
                rows += RomRow(
                    id = id, platformId = PLATFORM_ID, name = name,
                    filePath = first.variant.file.absolutePath, fileName = first.variant.file.name,
                    mtime = list.maxOf { it.variant.file.lastModified() },
                )
                newVariants[id] = list.map { it.variant }
                list.firstNotNullOfOrNull { fnd -> fnd.bhId?.let { findBundledArt(fnd.variant.file, it) } }?.let { path ->
                    artRows += ArtRow(
                        gameId = id, boxPath = path, heroPath = null, logoPath = null,
                        fetchedAt = System.currentTimeMillis(), missed = false, sourceName = name,
                    )
                }
            }
            variantsByGame.clear()
            variantsByGame.putAll(newVariants)

            db.romDao().clearPlatform(PLATFORM_ID)
            db.romDao().upsertAll(rows)
            // Only seed bundled art — never overwrite art the user picked.
            artRows.forEach { if (db.artDao().get(it.gameId) == null) db.artDao().upsert(it) }
            rows.map { GameEntry(it.id, it.platformId, it.name, it.filePath, it.fileName, it.mtime) }
        }

    /** Player keyword from the folders between [root] and [f] (nearest folder wins). */
    private fun folderPlayer(root: File, f: File): String? {
        var p = f.parentFile
        while (p != null && p != root.parentFile) {
            val n = p.name.lowercase()
            FOLDER_KEYWORDS.firstOrNull { (k, _) -> n.contains(k) }?.let { return it.second }
            if (p == root) break
            p = p.parentFile
        }
        return null
    }

    private fun folderLabel(root: File, f: File): String =
        f.parentFile?.takeIf { it != root }?.name ?: root.name

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private val PATH_PKG_REGEX = Regex("""(?m)^Path=/data/(?:user/0|data)/([^/\s]+)/""")
    private val WINEPREFIX_PKG_REGEX = Regex("""WINEPREFIX="?/data/(?:user/0|data)/([^/"\s]+)/""")
    private val NAME_REGEX = Regex("""(?m)^Name=(.+)$""")

    fun parseWinlatorPackage(desktop: String): String? =
        PATH_PKG_REGEX.find(desktop)?.groupValues?.getOrNull(1)
            ?: WINEPREFIX_PKG_REGEX.find(desktop)?.groupValues?.getOrNull(1)

    private fun parseDesktopName(desktop: String): String? =
        NAME_REGEX.find(desktop)?.groupValues?.getOrNull(1)?.trim()

    private fun stableIdForBh(localGameId: String): String = sha1("windows::bh::$localGameId")
    private fun stableIdForDesktop(path: String): String {
        val moved = path.indexOf("/MaiconErick/Forwarders/Winlator/").takeIf { it >= 0 }
            ?.let { OLD_XCRONYX_DIR + path.substringAfterLast('/') }
        return sha1("windows::desktop::${moved ?: path}")
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    /** `<folder>/boxart/<id>.jpg|png` next to the forwarder (xDaijishoHelper drops art there). */
    private fun findBundledArt(forwarderFile: File, localGameId: String): String? {
        var probe: File? = forwarderFile.parentFile
        repeat(3) {
            val p = probe ?: return null
            listOf("jpg", "png", "webp").forEach { ext ->
                val c = File(p, "boxart/$localGameId.$ext")
                if (c.isFile) return c.absolutePath
            }
            probe = p.parentFile
        }
        return null
    }

    /** Every Winlator package the scan resolved — feeds Settings → Players. */
    fun discoveredWinlatorPackages(@Suppress("UNUSED_PARAMETER") platform: Platform): Set<String> =
        variantsByGame.values.flatten().map { it.player }
            .filterIsInstance<Player.Winlator>().map { it.pkg }.toSet()

    /**
     * BannerHub titles: localGameId → name, read from BannerHub's MMKV "WineFile" registry
     * with ONE root read per scan, and only when an id isn't cached yet (cache file in
     * filesDir), so there's no root prompt per game.
     */
    private class BannerHubNames(ctx: Context, private val pkg: String?) {
        private val file = File(ctx.filesDir, "bannerhub_names.json")
        private val map: MutableMap<String, String> = runCatching {
            val o = JSONObject(file.readText())
            o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
        }.getOrDefault(HashMap())
        private var registry: String? = null
        private var readRegistry = false
        private var dirty = false

        fun name(id: String): String? {
            map[id]?.let { return it }
            if (pkg == null) return null
            if (!readRegistry) {
                readRegistry = true
                val r = RootShell.run("cat ${RootShell.q("/data/data/$pkg/files/mmkv/WineFile")}")
                registry = if (r.code == 0) String(r.out, Charsets.ISO_8859_1) else null
            }
            val raw = registry ?: return null
            // Records look like {"a":"<id>", … "h":"<name>", …}
            val re = Regex("\\{\"a\":\"" + Regex.escape(id) + "\"[^{}]*?\"h\":\"((?:\\\\.|[^\"\\\\])*)\"")
            val n = re.find(raw)?.groupValues?.get(1)?.replace("\\/", "/")?.takeIf { it.isNotBlank() } ?: return null
            map[id] = n; dirty = true
            return n
        }

        fun save() {
            if (!dirty) return
            runCatching { file.writeText(JSONObject(map as Map<*, *>).toString()) }
        }
    }
}
