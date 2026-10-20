package com.maiconerick.xgamelauncher.rom

import android.content.Context
import com.maiconerick.xgamelauncher.daijisho.DaijishoPlayerDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where platform ROM folders live, and how to guess one from a platform name. A platform
 * pointed at the ROMS root would scan every system's files (plus images, saves, BIOS…), so
 * roots are never valid platform folders.
 */
object RomFolders {

    /** The ROMS root: every platform folder lives under it. */
    const val ROMS_ROOT = "/storage/emulated/0/MaiconErick/ROMS"
    /** The Windows games root (BannerHub/, winlator/, cronyx/ … forwarder folders). */
    const val WINDOWS_ROOT = "/storage/emulated/0/MaiconErick/Windows"

    /** Older ROMS roots, still recognised as roots and rewritten to [ROMS_ROOT]. */
    val LEGACY_ROOTS = listOf(
        "/storage/emulated/0/ROMS", "/storage/emulated/0/Roms", "/storage/emulated/0/roms",
    )
    val ROOTS = listOf(ROMS_ROOT) + LEGACY_ROOTS

    /** The ROMS root (default for pickers and downloads). */
    fun root(): String = ROMS_ROOT

    /** Rewrites a path under a legacy ROMS root to the same sub-path under [ROMS_ROOT]. */
    fun migrate(path: String): String {
        val n = path.trim().replace("/sdcard/", "/storage/emulated/0/").let { if (it == "/sdcard") "/storage/emulated/0" else it }
        for (old in LEGACY_ROOTS) {
            if (n.equals(old, ignoreCase = true)) return ROMS_ROOT
            if (n.startsWith("$old/")) return ROMS_ROOT + n.substring(old.length)
        }
        return path
    }

    /** True for blank paths, storage roots and ROMS roots — never valid for a platform. */
    fun isRootLike(path: String?): Boolean {
        val p = path?.trim()?.trimEnd('/').orEmpty()
        if (p.isEmpty()) return true
        val n = p.replace("/sdcard", "/storage/emulated/0")
        return n == "/storage/emulated/0" || n == "/storage" || n == "/" ||
            ROOTS.any { it.equals(n, ignoreCase = true) }
    }

    /** Sub-folders of the ROMS root, for the folder picker. */
    fun subfolders(): List<File> =
        File(root()).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }.orEmpty()

    /**
     * Guesses the ROM folder for a platform name: exact / space-less name, then Daijishō's
     * short name and unique id for the best-matching platform ("GBA" or "Game Boy Advance"
     * → gba). Case-insensitive. Null when nothing matches.
     */
    suspend fun suggest(ctx: Context, name: String): String? = withContext(Dispatchers.IO) {
        val n = name.trim()
        if (n.isEmpty()) return@withContext null
        val dirs = subfolders()
        if (dirs.isEmpty()) return@withContext null
        val candidates = linkedSetOf(n, n.replace(" ", ""))
        runCatching { DaijishoPlayerDb.load(ctx) }.getOrNull()?.let { snap ->
            DaijishoPlayerDb.bestMatch(snap.platforms, n)?.let { d ->
                d.shortname?.let { candidates += it }
                d.uniqueId?.let { candidates += it }
            }
        }
        candidates.firstNotNullOfOrNull { c -> dirs.firstOrNull { it.name.equals(c, ignoreCase = true) } }
            ?.absolutePath
    }

    // ---- scanner junk filter (used when the platform has no filename regex) ----

    private val JUNK_EXT = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "mp4", "mkv", "txt", "nfo", "xml", "json",
        "db", "ini", "cfg", "log", "md", "pdf", "html", "srm", "sav", "state", "auto", "rtc",
        "sha1", "md5", "crc", "dat", "lnk", "url", "ds_store",
    )
    private val JUNK_DIRS = setOf(
        "media", "images", "imgs", "boxart", "box art", "covers", "snap", "snaps", "screenshots",
        "videos", "manuals", "downloaded_images", "saves", "states", "bios", "system",
    )

    /** Files the scanner should never list as games (unless a regex explicitly allows them). */
    fun isJunk(root: File, f: File): Boolean {
        if (f.name.startsWith(".")) return true
        val ext = f.extension.lowercase()
        if (ext in JUNK_EXT || ext.startsWith("state")) return true
        var p = f.parentFile
        while (p != null && p != root) {
            if (p.name.lowercase() in JUNK_DIRS || p.name.startsWith(".")) return true
            p = p.parentFile
        }
        return false
    }
}
