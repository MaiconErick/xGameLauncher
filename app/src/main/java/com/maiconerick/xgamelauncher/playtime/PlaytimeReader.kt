package com.maiconerick.xgamelauncher.playtime

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.maiconerick.xgamelauncher.data.PlaytimeStats
import com.maiconerick.xgamelauncher.util.NameCleaner
import com.maiconerick.xgamelauncher.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads xPTT's sqlite (the tracker records sessions; we only READ). Handles the two known
 * schemas: legacy `PlayTime(GameName, TimePlayed, ...)` and the newer `Session(gameName,
 * timePlayedSeconds, ...)`. Aggregates by (game, platform) key so the launcher can show
 * total playtime, session count, and last-played per game.
 */
class PlaytimeReader(
    private val context: Context,
    private val xpttDbPath: String,
) {
    private val _stats = MutableStateFlow<Map<String, PlaytimeStats>>(emptyMap())
    val stats: StateFlow<Map<String, PlaytimeStats>> = _stats.asStateFlow()
    private var lastMtime = 0L

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val src = File(xpttDbPath)
        // No xPTT db on this device (e.g. the RG353M) — don't even ask for root.
        if (!src.exists() && !xpttDbPath.startsWith("/data/")) return@withContext false
        val snapPath: String = if (src.canRead()) {
            src.absolutePath
        } else if (RootShell.haveRoot()) {
            val snap = File(context.cacheDir, "xptt_snapshot.db")
            val cp = RootShell.run(
                "cp ${RootShell.q(xpttDbPath)} ${RootShell.q(snap.absolutePath)}" +
                "; cp ${RootShell.q("$xpttDbPath-wal")} ${RootShell.q(snap.absolutePath + "-wal")} 2>/dev/null" +
                "; cp ${RootShell.q("$xpttDbPath-shm")} ${RootShell.q(snap.absolutePath + "-shm")} 2>/dev/null" +
                "; chmod 666 ${RootShell.q(snap.absolutePath)}*"
            )
            if (cp.code != 0 || !snap.exists()) return@withContext false
            snap.absolutePath
        } else return@withContext false

        val f = File(snapPath)
        if (f.lastModified() == lastMtime) return@withContext false
        lastMtime = f.lastModified()

        val db = runCatching {
            SQLiteDatabase.openDatabase(
                snapPath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
        }.getOrNull() ?: return@withContext false

        val agg = HashMap<String, LongArray>()
        try {
            val table = firstTable(db, listOf("Session", "Sessions", "PlayTime")) ?: return@withContext false
            val cols = columnsOf(db, table)
            val nameCol = cols.firstOrNull {
                it.equals("gameName", true) || it.equals("game_name", true) || it.equals("GameName", true)
            } ?: return@withContext false
            val platCol = cols.firstOrNull {
                it.equals("platform", true) || it.equals("Platform", true)
            }
            val secsCol = cols.firstOrNull {
                it.equals("timePlayedSeconds", true) ||
                it.equals("TimePlayed", true) ||
                it.equals("seconds", true)
            } ?: return@withContext false
            val endCol = cols.firstOrNull {
                it.equals("endedUnix", true) ||
                it.equals("TimeEnded_UNIX", true) ||
                it.equals("ended", true)
            }
            val startCol = cols.firstOrNull {
                it.equals("startedUnix", true) ||
                it.equals("TimeStarted_UNIX", true) ||
                it.equals("started", true)
            }

            val select = buildString {
                append("SELECT $nameCol, ")
                append(platCol ?: "NULL"); append(" AS platform, ")
                append(secsCol); append(" AS secs, ")
                append(endCol ?: startCol ?: "0"); append(" AS ended FROM $table")
            }
            db.rawQuery(select, null).use { c ->
                while (c.moveToNext()) {
                    val name = NameCleaner.unescape(c.getString(0) ?: continue)
                    val platform = c.getString(1) ?: ""
                    val secs = if (c.isNull(2)) 0L else c.getLong(2)
                    val ended = if (c.isNull(3)) 0L else c.getLong(3)
                    val key = keyFor(name, platform)
                    val cur = agg.getOrPut(key) { longArrayOf(0L, 0L, 0L) }
                    cur[0] += secs
                    if (ended > cur[1]) cur[1] = ended
                    cur[2] += 1
                }
            }
        } catch (_: Throwable) { return@withContext false }
        finally { runCatching { db.close() } }

        _stats.value = agg.mapValues { (_, v) -> PlaytimeStats(v[0], v[1], v[2].toInt()) }
        true
    }

    private fun firstTable(db: SQLiteDatabase, names: List<String>): String? {
        for (n in names) {
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(n)
            ).use { if (it.moveToFirst()) return n }
        }
        return null
    }

    private fun columnsOf(db: SQLiteDatabase, table: String): List<String> {
        val out = ArrayList<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val idx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) out.add(c.getString(idx))
        }
        return out
    }

    companion object {
        fun keyFor(gameName: String, platform: String): String =
            "${platform.lowercase().trim()}::${gameName.lowercase().trim()}"
    }
}
