package com.maiconerick.xgamelauncher.daijisho

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.util.RootShell
import java.io.File

data class DaijishoResult(
    val platforms: List<Platform>,
    val warnings: List<String>,
)

/**
 * Snapshots Daijishō's private sqlite (needs root), pulls platform rows, and pairs each with
 * a real ROM folder under /storage/emulated/0/ROMS. Returns whatever it can plus warnings for
 * whatever it couldn't.
 */
object DaijishoReader {

    private const val PKG = "com.magneticchen.daijishou"
    private const val DB = "/data/data/$PKG/databases/Daijishou.db"

    @Volatile private var cached: DaijishoResult? = null

    /**
     * Daijishō's platform list barely ever changes, and snapshotting it costs `su` calls
     * (a Magisk prompt/toast each) — so read it once per process. `force` = the Sync button.
     */
    suspend fun readPlatformsCached(context: Context, force: Boolean = false): DaijishoResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!force) cached?.let { return@withContext it }
            val installed = runCatching { context.packageManager.getPackageInfo(PKG, 0) }.isSuccess
            val r = if (installed) readPlatforms(context)
                    else DaijishoResult(emptyList(), emptyList())
            cached = r
            r
        }

    fun readPlatforms(context: Context): DaijishoResult {
        val warnings = ArrayList<String>()
        if (!RootShell.haveRoot()) {
            warnings.add("Root not available; cannot read Daijishō's private db.")
            return DaijishoResult(emptyList(), warnings)
        }

        val work = File(context.cacheDir, "daijisho_snapshot.db")
        val cp = RootShell.run(
            "cp ${RootShell.q(DB)} ${RootShell.q(work.absolutePath)}" +
            "; cp ${RootShell.q("$DB-wal")} ${RootShell.q(work.absolutePath + "-wal")} 2>/dev/null" +
            "; cp ${RootShell.q("$DB-shm")} ${RootShell.q(work.absolutePath + "-shm")} 2>/dev/null" +
            "; chmod 666 ${RootShell.q(work.absolutePath)}*"
        )
        if (cp.code != 0 || !work.exists()) {
            warnings.add("Failed to snapshot Daijishō db (code=${cp.code}): ${cp.err}")
            return DaijishoResult(emptyList(), warnings)
        }

        val db = try {
            SQLiteDatabase.openDatabase(
                work.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
        } catch (t: Throwable) {
            warnings.add("Could not open snapshot: ${t.message}")
            return DaijishoResult(emptyList(), warnings)
        }

        return try {
            val platTable = firstExistingTable(db, listOf("PlatformEntity", "Platform"))
                ?: run {
                    warnings.add("No PlatformEntity / Platform table in Daijishō db")
                    return DaijishoResult(emptyList(), warnings)
                }
            val cols = columnsOf(db, platTable)
            val idCol = pick(cols, "id", "platformId", "_id") ?: run {
                warnings.add("Platform table has no id column")
                return DaijishoResult(emptyList(), warnings)
            }
            val nameCol = pick(cols, "name") ?: idCol
            val uniqCol = pick(cols, "uniqueId", "unique_id")
            val extCol = pick(cols, "acceptedFilenameRegex", "accepted_filename_regex")

            val list = ArrayList<Platform>()
            db.rawQuery("SELECT * FROM $platTable", null).use { c ->
                while (c.moveToNext()) {
                    val id = cellText(c, idCol) ?: continue
                    val name = cellText(c, nameCol) ?: id
                    val unique = uniqCol?.let { cellText(c, it) } ?: id
                    val extRegex = extCol?.let { cellText(c, it) }
                    val roms = discoverRomsFolder(name)
                    if (roms == null) {
                        warnings.add(
                            "Platform '$name' → no ROMs folder found under ${com.maiconerick.xgamelauncher.rom.RomFolders.ROMS_ROOT}"
                        )
                        continue
                    }
                    list.add(
                        Platform(
                            id = unique,
                            name = name,
                            romsPath = roms,
                            fileExtensionsRegex = extRegex,
                            intents = emptyList(),
                            artSearchName = name,
                        )
                    )
                }
            }
            DaijishoResult(list.sortedBy { it.name.lowercase() }, warnings)
        } catch (t: Throwable) {
            warnings.add("Query failed: ${t.message}")
            DaijishoResult(emptyList(), warnings)
        } finally {
            runCatching { db.close() }
        }
    }

    private fun firstExistingTable(db: SQLiteDatabase, names: List<String>): String? {
        for (n in names) {
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(n)
            ).use {
                if (it.moveToFirst()) return n
            }
        }
        return null
    }

    private fun columnsOf(db: SQLiteDatabase, table: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            var i = 0
            while (c.moveToNext()) { out[c.getString(nameIdx)] = i++ }
        }
        return out
    }

    private fun pick(cols: Map<String, Int>, vararg candidates: String): String? =
        candidates.firstOrNull { cols.containsKey(it) }

    private fun cellText(c: android.database.Cursor, colName: String): String? {
        val i = c.getColumnIndex(colName)
        if (i < 0 || c.isNull(i)) return null
        return c.getString(i)
    }

    private fun discoverRomsFolder(platformName: String): String? {
        val roots = com.maiconerick.xgamelauncher.rom.RomFolders.ROOTS
        val candidates = listOf(platformName, platformName.replace(" ", ""), platformName.lowercase())
        for (root in roots) {
            for (name in candidates) {
                val p = "$root/$name"
                if (File(p).isDirectory) return p
            }
        }
        return null
    }
}
