package com.maiconerick.xgamelauncher.net

import android.content.Context
import com.maiconerick.xgamelauncher.tracker.XpttImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pulls every device's session DB from /PlayTime/DB (both xPTT's `PlayTime_*.db` and our
 * own `xGameLauncher_*.db`), then feeds each through [XpttImporter] which dedupes on
 * (game, platform, startedUnix) so re-running is idempotent.
 */
object DropboxSync {

    data class Result(val files: Int, val imported: Int, val skipped: Int, val errors: List<String>)

    suspend fun pullAll(ctx: Context, thisDevice: String): Result = withContext(Dispatchers.IO) {
        val files = Dropbox.listRemoteDbs()
        var importedTotal = 0
        var skippedTotal = 0
        val errors = ArrayList<String>()
        var considered = 0
        val tmpDir = File(ctx.cacheDir, "dropbox_sync").also { it.mkdirs() }

        for (f in files) {
            // Skip our own upload — it's already the source of truth for local sessions.
            if (f.name.equals("xGameLauncher_${thisDevice}.db", ignoreCase = true)) continue
            considered++
            val local = File(tmpDir, f.name)
            if (!Dropbox.download(f.path, local)) {
                errors.add("${f.name}: download failed"); continue
            }
            val r = XpttImporter.run(ctx, local.absolutePath)
            importedTotal += r.imported
            skippedTotal += r.skipped
            r.error?.let { errors.add("${f.name}: $it") }
            runCatching { local.delete() }
        }
        Result(considered, importedTotal, skippedTotal, errors)
    }
}
