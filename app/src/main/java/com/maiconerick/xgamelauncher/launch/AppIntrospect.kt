package com.maiconerick.xgamelauncher.launch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.maiconerick.xgamelauncher.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** PackageManager lookups behind the player editor's "pick app" / "pick activity" flow. */
object AppIntrospect {

    data class App(val pkg: String, val label: String)

    data class Activity(
        val className: String,
        val exported: Boolean,
        val isLauncher: Boolean,
        val label: String?,
    ) {
        /** "com.foo/.Main" when the class lives under the package, else the full name. */
        fun shortName(pkg: String): String =
            if (className.startsWith("$pkg.")) className.removePrefix(pkg) else className
    }

    suspend fun installedApps(ctx: Context): List<App> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName }.toSet()
        launchable
            .filter { it != ctx.packageName }
            .map { pkg ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                App(pkg, label)
            }
            .sortedBy { it.label.lowercase() }
    }

    fun isInstalled(ctx: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    fun label(ctx: Context, pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        val pm = ctx.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
    }

    /** Every activity the package declares — exported + launcher ones first. Non-exported
     *  activities still launch through the root `am start` fallback. */
    suspend fun activities(ctx: Context, pkg: String): List<Activity> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            }
        }.getOrNull() ?: return@withContext emptyList()
        val launcher = pm.getLaunchIntentForPackage(pkg)?.component?.className
        info.activities.orEmpty()
            .map { a ->
                Activity(
                    className = a.name,
                    exported = a.exported,
                    isLauncher = a.name == launcher,
                    label = runCatching { a.loadLabel(pm).toString() }.getOrNull(),
                )
            }
            .sortedWith(
                compareByDescending<Activity> { it.isLauncher }
                    .thenByDescending { it.exported }
                    .thenBy { it.className.lowercase() }
            )
    }

    fun isRetroArch(pkg: String?): Boolean = pkg?.startsWith("com.retroarch") == true

    /** Core ids ("mednafen_psx_hw") installed in RetroArch's private cores dir (needs root). */
    suspend fun retroArchCores(pkg: String): List<String> = withContext(Dispatchers.IO) {
        val r = RootShell.run("ls /data/data/$pkg/cores/")
        if (r.code != 0) return@withContext emptyList()
        r.outText.lines()
            .map { it.trim() }
            .filter { it.endsWith("_libretro_android.so") }
            .map { it.removeSuffix("_libretro_android.so") }
            .sorted()
    }

    /** Daijishō's RetroArch player shape, plus the extras RetroArch's own shortcuts pass. */
    fun retroArchExtras(pkg: String, core: String): List<com.maiconerick.xgamelauncher.data.IntentExtra> {
        val ext = "/storage/emulated/0/Android/data/$pkg/files"
        return listOf(
            com.maiconerick.xgamelauncher.data.IntentExtra("es", "ROM", "{file.path}"),
            com.maiconerick.xgamelauncher.data.IntentExtra("es", "LIBRETRO", "/data/data/$pkg/cores/${core}_libretro_android.so"),
            com.maiconerick.xgamelauncher.data.IntentExtra("es", "CONFIGFILE", "$ext/retroarch.cfg"),
            com.maiconerick.xgamelauncher.data.IntentExtra("es", "DATADIR", "/data/data/$pkg"),
            com.maiconerick.xgamelauncher.data.IntentExtra("es", "SDCARD", "/storage/emulated/0"),
            com.maiconerick.xgamelauncher.data.IntentExtra("es", "EXTERNAL", ext),
        )
    }

    const val RETROARCH_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"
}
