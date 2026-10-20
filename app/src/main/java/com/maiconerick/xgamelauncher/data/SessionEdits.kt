package com.maiconerick.xgamelauncher.data

import android.content.Context

/**
 * Bookkeeping for hand-edited playtime, so edits survive the Dropbox round trip:
 *
 *  · Tombstones: the (game, platform, start) key of every deleted or re-keyed session.
 *    Dropbox sync dedupes on that key, so without them a row deleted here would come
 *    straight back from another device's backup file.
 *  · Pending deletes: how many rows were removed since the last successful backup. The
 *    exporter refuses to shrink the Dropbox history, except by this many rows.
 *
 * Plain SharedPreferences (same file as the backup status) because the tracker service
 * and the importer read it outside the UI.
 */
object SessionEdits {
    private const val PREFS = "xgl_dropbox"
    private const val KEY_TOMBSTONES = "tombstones"
    private const val KEY_PENDING = "pending_deletes"

    fun keyOf(gameName: String, platform: String, startedUnix: Long) =
        "${gameName.lowercase()}|${platform.lowercase()}|$startedUnix"

    fun keyOf(r: SessionRow) = keyOf(r.gameName, r.platform, r.startedUnix)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tombstones(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_TOMBSTONES, emptySet()).orEmpty()

    /** Remember [keys] as gone; a key that is alive again (re-added by hand) is passed in [revive]. */
    @Synchronized
    fun bury(ctx: Context, keys: Collection<String>, revive: Collection<String> = emptyList()) {
        val set = tombstones(ctx).toMutableSet()
        set.addAll(keys); set.removeAll(revive.toSet())
        prefs(ctx).edit().putStringSet(KEY_TOMBSTONES, set).apply()
    }

    fun pendingDeletes(ctx: Context): Long = prefs(ctx).getLong(KEY_PENDING, 0L)

    @Synchronized
    fun addPendingDeletes(ctx: Context, n: Int) {
        if (n <= 0) return
        prefs(ctx).edit().putLong(KEY_PENDING, pendingDeletes(ctx) + n).apply()
    }

    fun clearPendingDeletes(ctx: Context) = prefs(ctx).edit().remove(KEY_PENDING).apply()
}
