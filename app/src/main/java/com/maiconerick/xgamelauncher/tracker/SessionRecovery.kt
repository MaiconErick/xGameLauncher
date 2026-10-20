package com.maiconerick.xgamelauncher.tracker

import android.content.Context
import com.maiconerick.xgamelauncher.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Closes any session rows that were left `isOpen = true` — happens when the app or the
 * tracker service is killed mid-session (game OOM, forced stop, reboot). We close them at
 * their last known moment (row's startedUnix + a small pad) with reason "Recovered". Called
 * on app start and from [BootReceiver] after device boot.
 */
object SessionRecovery {

    /**
     * Open rows that started within [DUP_WINDOW] s of another row of the same game come from a
     * double lid/screen split (fixed 2026-10-08): no play happened in them, so close them empty
     * as "Duplicate" instead of "recovering" hours of phantom playtime.
     */
    suspend fun closeDuplicates(context: Context, keepId: Long = -1): Int = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val open = db.sessionDao().openSessions().filter { it.id != keepId }
        if (open.isEmpty()) return@withContext 0
        val all = db.sessionDao().all()
        var n = 0
        open.forEach { row ->
            val twin = all.any { it.id != row.id && it.pkg == row.pkg && kotlin.math.abs(it.startedUnix - row.startedUnix) <= DUP_WINDOW }
            if (twin) {
                db.sessionDao().update(row.copy(endedUnix = row.startedUnix, timePlayedSeconds = 0, endReason = "Duplicate", isOpen = false))
                n++
            }
        }
        n
    }

    private const val DUP_WINDOW = 2L // the racing inserts land in the same second

    /** Returns how many rows were reconciled. */
    suspend fun run(context: Context): Int = withContext(Dispatchers.IO) {
        val dups = closeDuplicates(context)
        val db = AppDatabase.get(context)
        val open = db.sessionDao().openSessions()
        if (open.isEmpty()) return@withContext dups
        val now = System.currentTimeMillis() / 1000
        open.forEach { row ->
            // Best-effort end time: don't count more than 6h — long-tail is almost always a
            // forgotten-open, not real play.
            val cappedEnd = minOf(now, row.startedUnix + 6L * 3600)
            val played = (cappedEnd - row.startedUnix).coerceAtLeast(0)
            db.sessionDao().update(
                row.copy(
                    endedUnix = cappedEnd,
                    timePlayedSeconds = played,
                    endReason = "Recovered",
                    isOpen = false,
                )
            )
        }
        open.size + dups
    }
}
