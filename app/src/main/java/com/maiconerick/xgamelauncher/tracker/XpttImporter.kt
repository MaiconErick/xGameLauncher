package com.maiconerick.xgamelauncher.tracker

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.maiconerick.xgamelauncher.data.AppDatabase
import com.maiconerick.xgamelauncher.data.SessionRow
import com.maiconerick.xgamelauncher.util.NameCleaner
import com.maiconerick.xgamelauncher.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One-shot import of xPTT's `PlayTime` sqlite into our Room `session` table. Rows are
 * tagged `source = "xptt"` so a subsequent import won't double-write, and the running
 * PlaytimeReader won't double-count once [com.maiconerick.xgamelauncher.prefs.PrefsStore.setSkipXpttRead]
 * is set.
 *
 * Handles both known xPTT column shapes (`Session(gameName, timePlayedSeconds, …)` and legacy
 * `PlayTime(GameName, TimePlayed, …)`).
 */
object XpttImporter {

    data class Result(val imported: Int, val skipped: Int, val error: String?)

    suspend fun run(context: Context, xpttDbPath: String): Result = withContext(Dispatchers.IO) {
        val src = File(xpttDbPath)
        if (!src.exists() && !xpttDbPath.startsWith("/data/"))
            return@withContext Result(0, 0, "No xPTT db at $xpttDbPath")
        val snapPath = if (src.canRead()) src.absolutePath
        else if (RootShell.haveRoot()) {
            val snap = File(context.cacheDir, "xptt_import.db")
            val cp = RootShell.run(
                "cp ${RootShell.q(xpttDbPath)} ${RootShell.q(snap.absolutePath)}" +
                "; cp ${RootShell.q("$xpttDbPath-wal")} ${RootShell.q(snap.absolutePath + "-wal")} 2>/dev/null" +
                "; cp ${RootShell.q("$xpttDbPath-shm")} ${RootShell.q(snap.absolutePath + "-shm")} 2>/dev/null" +
                "; chmod 666 ${RootShell.q(snap.absolutePath)}*"
            )
            if (cp.code != 0 || !snap.exists()) return@withContext Result(0, 0, "root snapshot failed: ${cp.err}")
            snap.absolutePath
        } else return@withContext Result(0, 0, "xPTT db not readable and no root")

        val src_db = runCatching {
            SQLiteDatabase.openDatabase(snapPath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        }.getOrElse { return@withContext Result(0, 0, "open failed: ${it.message}") }

        val dst = AppDatabase.get(context)
        var imported = 0
        var skipped = 0
        try {
            val table = firstTable(src_db, listOf("Session", "Sessions", "PlayTime"))
                ?: return@withContext Result(0, 0, "no Session/PlayTime table")
            val cols = columnsOf(src_db, table)

            fun col(vararg names: String) = names.firstOrNull { it in cols }
            val nameCol = col("gameName", "game_name", "GameName") ?: return@withContext Result(0, 0, "no gameName column")
            val platCol = col("platform", "Platform")
            val emuCol = col("emulator", "Emulator")
            val pkgCol = col("pkg", "Pkg")
            val secsCol = col("timePlayedSeconds", "TimePlayed", "seconds") ?: return@withContext Result(0, 0, "no seconds column")
            val startCol = col("startedUnix", "TimeStarted_UNIX", "started")
            val endCol = col("endedUnix", "TimeEnded_UNIX", "ended")
            val userCol = col("user", "User")
            val deviceCol = col("device", "Device")
            val artCol = col("artPath", "ArtPath")
            val reasonCol = col("endReason", "EndReason")

            val existingIds = HashSet<Triple<String, String, Long>>()
            dst.sessionDao().all().forEach { r ->
                existingIds.add(Triple(r.gameName.lowercase(), r.platform.lowercase(), r.startedUnix))
            }
            // Sessions deleted or re-keyed in the playtime editor must not come back.
            val tombstones = com.maiconerick.xgamelauncher.data.SessionEdits.tombstones(context)

            val sql = buildString {
                append("SELECT ")
                append(nameCol); append(", ")
                append(platCol ?: "NULL"); append(", ")
                append(emuCol ?: "NULL"); append(", ")
                append(pkgCol ?: "NULL"); append(", ")
                append(secsCol); append(", ")
                append(startCol ?: "0"); append(", ")
                append(endCol ?: "NULL"); append(", ")
                append(userCol ?: "NULL"); append(", ")
                append(deviceCol ?: "NULL"); append(", ")
                append(artCol ?: "NULL"); append(", ")
                append(reasonCol ?: "NULL")
                append(" FROM ").append(table)
            }
            src_db.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    val name = NameCleaner.unescape(c.getString(0) ?: continue)
                    val platform = c.getString(1) ?: ""
                    val emulator = c.getString(2) ?: ""
                    val pkg = c.getString(3) ?: ""
                    val secs = if (c.isNull(4)) 0L else c.getLong(4)
                    val started = if (c.isNull(5)) 0L else c.getLong(5)
                    val ended = if (c.isNull(6)) null else c.getLong(6)
                    val user = c.getString(7) ?: "MaiconErick"
                    val device = c.getString(8) ?: "THOR"
                    val art = c.getString(9)
                    val reason = c.getString(10)
                    if (started <= 0) { skipped++; continue }
                    val key = Triple(name.lowercase(), platform.lowercase(), started)
                    if (key in existingIds) { skipped++; continue }
                    if (com.maiconerick.xgamelauncher.data.SessionEdits.keyOf(name, platform, started) in tombstones) {
                        skipped++; continue
                    }
                    dst.sessionDao().insert(
                        SessionRow(
                            gameName = name, platform = platform, emulator = emulator,
                            pkg = pkg, startedUnix = started, endedUnix = ended,
                            timePlayedSeconds = secs, endReason = reason ?: "imported",
                            user = user, device = device, artPath = art,
                            isOpen = false,
                            source = "xptt",
                        )
                    )
                    imported++
                }
            }
            Result(imported, skipped, null)
        } catch (t: Throwable) {
            Result(imported, skipped, "read failed: ${t.message}")
        } finally {
            runCatching { src_db.close() }
        }
    }

    private fun firstTable(db: SQLiteDatabase, names: List<String>): String? {
        for (n in names) db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(n)
        ).use { if (it.moveToFirst()) return n }
        return null
    }

    private fun columnsOf(db: SQLiteDatabase, table: String): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val idx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) out.add(c.getString(idx))
        }
        return out
    }
}
