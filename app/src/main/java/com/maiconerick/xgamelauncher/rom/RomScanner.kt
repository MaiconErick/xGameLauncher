package com.maiconerick.xgamelauncher.rom

import com.maiconerick.xgamelauncher.data.AppDatabase
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.data.RomRow
import com.maiconerick.xgamelauncher.util.NameCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object RomScanner {

    /** Walks the ROM folder, applies the platform's extension regex, and caches rows in Room. */
    suspend fun scan(db: AppDatabase, platform: Platform): List<GameEntry> = withContext(Dispatchers.IO) {
        val regex = platform.fileExtensionsRegex?.let {
            runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull()
        }
        val dir = File(platform.romsPath)
        if (!dir.isDirectory) return@withContext emptyList()
        // Never scan a ROMS/storage root: that would pull in every system's files.
        if (RomFolders.isRootLike(platform.romsPath)) return@withContext emptyList()

        val files = dir.walkTopDown().maxDepth(3).filter { f ->
            f.isFile && !RomFolders.isJunk(dir, f) && (regex == null || regex.containsMatchIn(f.name))
        }.toList()
        val skip = switchAddOns(files)

        val rows = ArrayList<RomRow>()
        files.forEach { f ->
            if (f in skip) return@forEach
            val stableId = hashId(platform.id, f.absolutePath)
            val display = NameCleaner.sanitize(f.name)
            rows.add(
                RomRow(
                    id = stableId,
                    platformId = platform.id,
                    name = display,
                    filePath = f.absolutePath,
                    fileName = f.name,
                    mtime = f.lastModified(),
                )
            )
        }
        db.romDao().clearPlatform(platform.id)
        db.romDao().upsertAll(rows)
        rows.map { GameEntry(it.id, it.platformId, it.name, it.filePath, it.fileName, it.mtime) }
    }

    private val TITLE_ID = Regex("""\[([0-9A-Fa-f]{16})]""")

    /**
     * Switch dumps name files "Game [TitleID][vN].nsp". Updates (id ends 800) and DLC (13th digit
     * odd) share the base game's id prefix, so they are listed as separate games. Returns the
     * update/DLC files whose base game is also present; orphans (no base in the folder) stay.
     */
    private fun switchAddOns(files: List<File>): Set<File> {
        val ids = files.associateWith { f -> TITLE_ID.find(f.name)?.groupValues?.get(1)?.toULongOrNull(16) }
        fun baseOf(id: ULong): ULong? = when {
            id and 0x1000uL != 0uL -> (id and 0xFFFuL.inv()) - 0x1000uL      // DLC
            id and 0xFFFuL == 0x800uL -> id and 0xFFFuL.inv()                // update
            else -> null
        }
        val bases = ids.values.filterNotNull().filter { baseOf(it) == null }.toSet()
        return ids.filter { (_, id) -> id != null && baseOf(id)?.let { it in bases } == true }.keys
    }

    private fun hashId(platformId: String, path: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest("$platformId::$path".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }
}
