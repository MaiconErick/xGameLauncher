package com.maiconerick.xgamelauncher.android

import kotlinx.coroutines.flow.first
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.maiconerick.xgamelauncher.data.AndroidGameRow
import com.maiconerick.xgamelauncher.data.AppDatabase
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.data.RomRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * "Android" is a synthetic platform whose games are the installed apps the user has hand-picked
 * in Settings → Android Apps. Launched by InternalLauncher via the app's launch intent.
 */
object AndroidAppSource {

    const val PLATFORM_ID = "android"
    const val PLATFORM_NAME = "Android"

    /** One installed app with its label + launcher component. */
    data class InstalledApp(
        val pkg: String,
        val label: String,
        val launchComponent: String,
        /** Unix ms when the user first installed the app. 0 if unavailable. */
        val firstInstallTime: Long = 0L,
    )

    fun platform(): Platform = Platform(
        id = PLATFORM_ID,
        name = PLATFORM_NAME,
        romsPath = "android:",
        fileExtensionsRegex = null,
        intents = emptyList(),
        artSearchName = PLATFORM_NAME,
    )

    /**
     * Renames an app everywhere: the Apps tab (prefs map), its Android-platform game row,
     * and its past sessions so playtime follows the new name. Blank = back to the real label.
     */
    suspend fun rename(context: Context, pkg: String, newName: String?) = withContext(Dispatchers.IO) {
        val name = newName?.trim()?.takeIf { it.isNotEmpty() }
        com.maiconerick.xgamelauncher.prefs.PrefsStore(context).setAppName(pkg, name)
        val db = AppDatabase.get(context)
        val finalName = name ?: runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        db.androidGameDao().all().firstOrNull { it.pkg == pkg }?.let { db.androidGameDao().upsert(it.copy(label = finalName)) }
        db.sessionDao().renameAndroidApp(pkg, finalName)
        // A name set earlier in the game editor would win over the new label: update it too.
        val gid = stableId(pkg)
        db.gameOverrideDao().get(gid)?.takeIf { it.displayNameOverride != null }?.let {
            db.gameOverrideDao().upsert(it.copy(displayNameOverride = name))
        }
    }

    /** Every app on the device that has a launcher intent, sorted by label. */
    suspend fun listInstalled(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val out = ArrayList<InstalledApp>()
        val renames = runCatching {
            kotlinx.coroutines.runBlocking {
                com.maiconerick.xgamelauncher.prefs.PrefsStore(context).appNames.first()
            }
        }.getOrDefault(emptyMap())
        pm.getInstalledApplications(flags).forEach { info: ApplicationInfo ->
            val launch = pm.getLaunchIntentForPackage(info.packageName) ?: return@forEach
            val comp = launch.component ?: return@forEach
            val label = renames[info.packageName]
                ?: runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()
                ?: info.packageName
            // Skip our own app.
            if (info.packageName == context.packageName) return@forEach
            val installTime = runCatching {
                pm.getPackageInfo(info.packageName, 0).firstInstallTime
            }.getOrDefault(0L)
            out.add(
                InstalledApp(
                    pkg = info.packageName,
                    label = label,
                    launchComponent = "${comp.packageName}/${comp.className}",
                    firstInstallTime = installTime,
                )
            )
        }
        out.sortedBy { it.label.lowercase() }
    }

    /** Loads the user-added apps as launchable games; also writes rows into the `rom` table so
     *  the rest of the launcher (Home strips, Library grid) picks them up like any other games. */
    suspend fun scan(db: AppDatabase): List<GameEntry> = withContext(Dispatchers.IO) {
        val apps = db.androidGameDao().all()
        val rows = apps.map { row ->
            val id = stableId(row.pkg)
            RomRow(
                id = id,
                platformId = PLATFORM_ID,
                name = row.label,
                filePath = row.pkg,
                fileName = row.pkg,
                mtime = row.addedAt,
            )
        }
        db.romDao().clearPlatform(PLATFORM_ID)
        db.romDao().upsertAll(rows)
        rows.map { GameEntry(it.id, it.platformId, it.name, it.filePath, it.fileName, it.mtime) }
    }

    fun stableId(pkg: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest("$PLATFORM_ID::$pkg".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }
}
