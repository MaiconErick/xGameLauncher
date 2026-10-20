package com.maiconerick.xgamelauncher.net

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Translates xGameLauncher's Room `session` table into xPTT's canonical `PlayTime` schema,
 * writing to a standalone sqlite file that any app reading `PlayTime_<device>.db` (xPTT,
 * the Compose viewer, etc.) can consume unchanged.
 *
 * When [upstream] is non-null and exists, its `Logs`, `Configs`, and `android_metadata`
 * rows are preserved verbatim — only `PlayTime` is dropped and rebuilt from Room. That
 * keeps xPTT's launcher-profile configs alive across our backups. When [upstream] is null,
 * a fresh xPTT-schema file is created with empty Logs/Configs.
 */
object XpttFormatExporter {

    /** xPTT stores TimeStarted/TimeEnded as `YYYY-MM-DD HH:mm:ss` in device local time. */
    private fun tsFormatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }

    /**
     * Builds the export file at [outPath]. If [upstream] exists it becomes the starting
     * point (copied byte-for-byte); we then wipe & repopulate PlayTime. Room's `xgl.db`
     * is opened by absolute path via ATTACH DATABASE so we don't need to touch Room's
     * own connection.
     */
    fun buildOrUpdate(ctx: Context, upstream: File?, outPath: File) {
        outPath.parentFile?.mkdirs()
        if (outPath.exists()) outPath.delete()
        if (upstream != null && upstream.exists() && upstream.length() > 0) {
            upstream.copyTo(outPath, overwrite = true)
        }
        val roomPath = ctx.getDatabasePath("xgl.db").absolutePath
        val db = SQLiteDatabase.openDatabase(
            outPath.absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
        )
        try {
            // Never shrink the Dropbox history: if it holds more sessions than this device's
            // own database (e.g. history not imported yet), refuse instead of wiping it.
            // Counted before any schema change, so old-layout files are protected too.
            val cols = db.rawQuery("PRAGMA table_info(PlayTime)", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(1)) }
            }
            val remoteRows = if (cols.isEmpty()) 0L else
                db.rawQuery("SELECT COUNT(*) FROM PlayTime", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            val localRows = SQLiteDatabase.openDatabase(roomPath, null, SQLiteDatabase.OPEN_READONLY).use { r ->
                r.rawQuery("SELECT COUNT(*) FROM session", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            }
            // Rows deleted by hand in the playtime editor are allowed to disappear.
            val allowance = com.maiconerick.xgamelauncher.data.SessionEdits.pendingDeletes(ctx)
            if (localRows + allowance < remoteRows) error("Dropbox has $remoteRows sessions but this device only $localRows — not overwriting")
            // Old xPTT files use UNIX_TimeStarted/UNIX_TimeEnded and lack Pkg/User/Device/…:
            // PlayTime is rebuilt from Room anyway, so recreate it with the current layout.
            if (cols.isNotEmpty() && "TimeStarted_UNIX" !in cols) db.execSQL("DROP TABLE PlayTime")
            ensureSchema(db)
            db.execSQL("DELETE FROM PlayTime")
            db.execSQL("DELETE FROM sqlite_sequence WHERE name='PlayTime'")
            db.execSQL("ATTACH DATABASE ? AS src", arrayOf(roomPath))
            db.beginTransaction()
            try {
                val fmt = tsFormatter()
                val cur = db.rawQuery(
                    """SELECT gameName, startedUnix, endedUnix, timePlayedSeconds,
                              platform, emulator, pkg, user, device, isOpen, endReason, artPath
                       FROM src.session""",
                    null,
                )
                val insert = db.compileStatement(
                    """INSERT INTO PlayTime(
                        GameName, TimeStarted, TimeEnded, TimeStarted_UNIX, TimeEnded_UNIX,
                        TimePlayed, Platform, Emulator, Pkg, User, Device, IsOpen, EndReason, ArtPath
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
                )
                while (cur.moveToNext()) {
                    val gameName = cur.getString(0) ?: ""
                    val startedUnix = cur.getLong(1)
                    val endedUnix = if (cur.isNull(2)) null else cur.getLong(2)
                    val played = if (cur.isNull(3)) null else cur.getLong(3)
                    val platform = cur.getString(4) ?: ""
                    val emulator = cur.getString(5) ?: ""
                    val pkg = cur.getString(6) ?: ""
                    val user = cur.getString(7) ?: ""
                    val device = cur.getString(8) ?: ""
                    val isOpen = cur.getInt(9)
                    val endReason = cur.getString(10)
                    val artPath = if (cur.isNull(11)) null else cur.getString(11)
                    val timeStarted = fmt.format(Date(startedUnix * 1000L))
                    val timeEnded = endedUnix?.let { fmt.format(Date(it * 1000L)) }

                    insert.clearBindings()
                    insert.bindString(1, gameName)
                    insert.bindString(2, timeStarted)
                    if (timeEnded != null) insert.bindString(3, timeEnded) else insert.bindNull(3)
                    insert.bindLong(4, startedUnix)
                    if (endedUnix != null) insert.bindLong(5, endedUnix) else insert.bindNull(5)
                    if (played != null) insert.bindLong(6, played) else insert.bindNull(6)
                    insert.bindString(7, platform)
                    insert.bindString(8, emulator)
                    insert.bindString(9, pkg)
                    insert.bindString(10, user)
                    insert.bindString(11, device)
                    insert.bindLong(12, isOpen.toLong())
                    if (endReason != null) insert.bindString(13, endReason) else insert.bindNull(13)
                    if (artPath != null) insert.bindString(14, artPath) else insert.bindNull(14)
                    insert.executeInsert()
                }
                cur.close()
                insert.close()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.execSQL("DETACH DATABASE src")
        } finally {
            db.close()
        }
    }

    /** Creates the xPTT tables (matching the exact schema xPTT itself uses on Thor). */
    private fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS PlayTime(
                ID INTEGER PRIMARY KEY AUTOINCREMENT,
                GameName TEXT, TimeStarted TEXT, TimeEnded TEXT,
                TimeStarted_UNIX INTEGER, TimeEnded_UNIX INTEGER, TimePlayed INTEGER,
                Platform TEXT, Emulator TEXT, Pkg TEXT, User TEXT, Device TEXT,
                IsOpen INTEGER DEFAULT 1, EndReason TEXT, ArtPath TEXT)"""
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS android_metadata (locale TEXT)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS Logs(
                ID INTEGER PRIMARY KEY AUTOINCREMENT,
                Ts INTEGER, Level TEXT, Tag TEXT, Msg TEXT)"""
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS Configs(ID TEXT PRIMARY KEY, Json TEXT)")
        val c = db.rawQuery("SELECT COUNT(*) FROM android_metadata", null)
        val n = if (c.moveToFirst()) c.getInt(0) else 0
        c.close()
        if (n == 0) db.execSQL("INSERT INTO android_metadata VALUES ('en_US')")
    }
}
