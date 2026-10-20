package com.maiconerick.xgamelauncher.tracker

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.StrictMode
import com.maiconerick.xgamelauncher.data.AppDatabase
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.LaunchIntent
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.data.SessionRow
import com.maiconerick.xgamelauncher.launch.AmArgs
import com.maiconerick.xgamelauncher.launch.LaunchVars
import com.maiconerick.xgamelauncher.prefs.PrefsStore
import com.maiconerick.xgamelauncher.ui.DeviceProfile
import com.maiconerick.xgamelauncher.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The only launch path: session-record → force-stop (root, best effort) → start
 * TrackerService → fire the real emulator intent (root `am start` as fallback).
 */
object InternalLauncher {

    /** Fires an already-built emulator intent + records a session. Returns true on success. */
    suspend fun launch(
        ctx: Context,
        realIntent: Intent,
        watchPkg: String,
        gameName: String,
        platformName: String,
        emulatorName: String,
        displayId: Int? = null,
        forceStopBefore: Boolean = true,
        /** `am start …` line run under su when startActivity is refused (non-exported
         *  activity, missing permission). Both handhelds are rooted. */
        rootFallback: String? = null,
        /** Box art shown on the session overlay. */
        artPath: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(ctx)
        // Close any previously-open row so restart doesn't leave orphans.
        db.sessionDao().openSessions().forEach {
            db.sessionDao().update(
                it.copy(
                    endedUnix = System.currentTimeMillis() / 1000,
                    timePlayedSeconds = (System.currentTimeMillis() / 1000 - it.startedUnix)
                        .coerceAtLeast(0),
                    endReason = "Recovered",
                    isOpen = false,
                )
            )
        }
        val sid = db.sessionDao().insert(
            SessionRow(
                gameName = gameName, platform = platformName, emulator = emulatorName,
                pkg = watchPkg,
                startedUnix = System.currentTimeMillis() / 1000,
                artPath = artPath,
                user = "MaiconErick",
                device = runCatching { PrefsStore(ctx).deviceName.first() }
                    .getOrDefault(DeviceProfile.defaultDeviceName()),
                isOpen = true,
            )
        )
        if (forceStopBefore) {
            runCatching {
                RootShell.run("am force-stop $watchPkg")
            }
        }
        TrackerService.start(ctx, sid, watchPkg, gameName, emulatorName, platformName)

        val options = displayId?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        realIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Some players (FPse, legacy Daijishō entries) want a file:// uri in -d. Android
        // throws FileUriExposedException for that unless the VM policy check is off.
        if (realIntent.data?.scheme == "file") StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        try {
            try {
                withContext(Dispatchers.Main) { ctx.startActivity(realIntent, options) }
            } catch (t: Throwable) {
                val cmd = rootFallback ?: throw t
                val r = RootShell.run(cmd)
                if (r.code != 0 || r.outText.contains("Error", ignoreCase = true) ||
                    r.err.contains("Error", ignoreCase = true)) {
                    throw IllegalStateException("${t.message} · root am start: ${r.err.ifBlank { r.outText }.trim()}")
                }
            }
            true
        } catch (t: Throwable) {
            // Roll back the session row we just created.
            val row = db.sessionDao().byId(sid) ?: return@withContext false
            db.sessionDao().update(
                row.copy(
                    endedUnix = row.startedUnix, timePlayedSeconds = 0,
                    endReason = "Launch failed: ${t.message}", isOpen = false,
                )
            )
            false
        }
    }

    // ---- Intent builders ----

    /** Built emulator intent plus the equivalent `am start` line (for the root fallback). */
    data class Prepared(val intent: Intent, val shell: String, val watchPkg: String)

    fun buildEmulatorIntent(
        ctx: Context, intent: LaunchIntent, platform: Platform, game: GameEntry,
    ): Prepared? {
        val vars = LaunchVars.forGame(ctx, platform, game)
        val built = AmArgs.toIntent(intent, vars) ?: return null
        val watch = intent.targetPackage() ?: return null
        // Grant the ROM uri(s) to the whole emulator package, not just the first activity:
        // PCSX2-based emulators hand the file to another activity/thread before opening it.
        built.contentUris.forEach { u ->
            runCatching {
                ctx.grantUriPermission(watch, u,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.recoverCatching { ctx.grantUriPermission(watch, u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        return Prepared(built.intent, AmArgs.toShell(intent, vars), watch)
    }

    fun buildAndroidLaunchIntent(ctx: Context, pkg: String): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(pkg)
}
