package com.maiconerick.xgamelauncher.launch

import android.content.Context
import android.content.Intent
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.windows.WindowsSource
import com.maiconerick.xgamelauncher.windows.WindowsSource.Player

/**
 * Builds the real intent for a Windows forwarder variant (BannerHub LAUNCH_GAME or a
 * Winlator flavour's XServerDisplayActivity with shortcut_path). InternalLauncher fires it
 * and falls back to root `am start` when the activity isn't exported.
 */
object WindowsLauncher {

    /** Everything the built-in tracker needs to launch a Windows game. */
    data class DirectLaunch(
        val realIntent: Intent,
        val watchPkg: String,
        val emulatorLabel: String,
        /** Same as xPTT's configs: BannerHub cold-starts (killBefore), Winlator doesn't. */
        val killBefore: Boolean,
        /** `am start …` equivalent for the root fallback. */
        val shell: String,
    )

    /** Players for this game, in scan order. */
    fun variants(game: GameEntry): List<WindowsSource.Variant> = WindowsSource.variantsFor(game.id)

    /** Human label for a variant: "Cronyx · cronyx folder". */
    fun label(ctx: Context, v: WindowsSource.Variant): String {
        val app = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(v.player.pkg, 0)).toString()
        }.getOrDefault(v.player.pkg)
        return if (v.folderLabel.equals(app, ignoreCase = true)) app else "$app · ${v.folderLabel}"
    }

    /**
     * Winlator container a .desktop shortcut belongs to. Shortcuts exported by CronyX's
     * frontend carry no container id, and CronyX then falls back to container 0 (which may
     * not exist) and drops to its main screen. The id is in the wine prefix path
     * (`…/home/xuser-5/.wine`) or, on some builds, a `container_id` line. The bare
     * `xuser` prefix is handled by [activeContainerId].
     */
    private fun containerIdOf(file: java.io.File): Int? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        Regex("""(?m)^\s*container_?id\s*[=:]\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
            ?.let { return it.groupValues[1].toIntOrNull() }
        return Regex("""/home/xuser-(\d+)/""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** A bare `…/home/xuser/` prefix means "the active container": `xuser` is a symlink to
     *  `xuser-<id>`. Reading it needs root (another app's data dir); call off the main thread. */
    private fun activeContainerId(file: java.io.File, pkg: String): Int? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        if (!Regex("""/home/xuser/""").containsMatchIn(text)) return null
        val r = com.maiconerick.xgamelauncher.util.RootShell.run("readlink /data/data/$pkg/files/imagefs/home/xuser")
        return Regex("""xuser-(\d+)""").find(r.outText)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Builds the launch; may run `su` (Winlator container lookup), so call it off the main thread. */
    fun build(ctx: Context, v: WindowsSource.Variant): DirectLaunch = when (val p = v.player) {
        is Player.BannerHub -> {
            val i = Intent("${p.pkg}.LAUNCH_GAME").apply {
                setClassName(p.pkg, WindowsSource.BANNERHUB_ACTIVITY)
                putExtra("localGameId", p.localGameId)
                putExtra("autoStartGame", true)
            }
            val sh = "am start -a ${p.pkg}.LAUNCH_GAME -n ${p.pkg}/${WindowsSource.BANNERHUB_ACTIVITY} " +
                "--es localGameId ${p.localGameId} --ez autoStartGame true"
            DirectLaunch(i, p.pkg, label(ctx, v), killBefore = true, shell = sh)
        }
        is Player.Winlator -> {
            val container = containerIdOf(v.file) ?: activeContainerId(v.file, p.pkg)
            val i = Intent(Intent.ACTION_MAIN).apply {
                setClassName(p.pkg, p.activity)
                putExtra("shortcut_path", v.file.absolutePath)
                container?.let { putExtra("container_id", it) }
            }
            val sh = "am start -n ${p.pkg}/${p.activity} --es shortcut_path " +
                com.maiconerick.xgamelauncher.util.RootShell.q(v.file.absolutePath) +
                (container?.let { " --ei container_id $it" } ?: "")
            DirectLaunch(i, p.pkg, label(ctx, v), killBefore = false, shell = sh)
        }
    }
}
