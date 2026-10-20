package com.maiconerick.xgamelauncher.windows

import android.content.Context
import com.maiconerick.xgamelauncher.util.RootShell

/**
 * Running Windows emulators (BannerHub/GameHub and every Winlator flavour) and what they use.
 * Wine, wineserver and box64 run under the emulator app's uid, so everything is grouped by
 * uid. CPU is sampled from /proc/<pid>/stat over [SAMPLE_MS]. One `su` call per scan; call
 * off the main thread.
 */
object EmulatorProcs {

    private const val SAMPLE_MS = 500

    data class AppUsage(
        val pkg: String,
        val label: String,
        val pids: List<Int>,
        /** Distinct process names, most memory first. */
        val names: List<String>,
        val rssKb: Long,
        /** Share of the whole CPU (all cores), 0..100. */
        val cpuPct: Float,
    ) {
        val summary: String
            get() = "${pids.size} process${if (pids.size == 1) "" else "es"} · ${mem(rssKb)} RAM · ${"%.0f".format(cpuPct)}% CPU"
    }

    fun mem(kb: Long): String = if (kb >= 1024 * 1024) "%.1f GB".format(kb / 1024f / 1024f) else "${kb / 1024} MB"

    fun packages(): List<String> = WindowsSource.BANNERHUB_PACKAGES + WindowsSource.WINLATOR_COMPONENTS.keys

    fun scan(ctx: Context): List<AppUsage> {
        val pm = ctx.packageManager
        val byUid = packages().mapNotNull { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0).uid to pkg }.getOrNull()
        }.toMap()
        if (byUid.isEmpty()) return emptyList()
        val uids = byUid.keys.joinToString(",")
        val filter = """awk -v u="$uids" 'BEGIN{n=split(u,a,",");for(i=1;i<=n;i++)m[a[i]]=1} NR>1 && ($2 in m)'"""
        // stat: strip "pid (comm) " (comm may contain spaces), then utime/stime are fields 12/13.
        val stat = """for p in ${'$'}P; do echo "${'$'}p ${'$'}(sed 's/.*) //' /proc/${'$'}p/stat 2>/dev/null | cut -d' ' -f12,13)"; done"""
        val script = """
            L=${'$'}(ps -A -o PID,UID,RSS,NAME | $filter)
            echo "${'$'}L"
            P=${'$'}(echo "${'$'}L" | awk '{print ${'$'}1}')
            echo ===
            $stat
            sleep ${SAMPLE_MS / 1000f}
            echo ===
            $stat
        """.trimIndent()
        val out = RootShell.run(script)
        if (out.code != 0) return emptyList()
        val parts = out.outText.split("===")
        if (parts.size < 3) return emptyList()

        data class Proc(val pid: Int, val uid: Int, val rss: Long, val name: String)
        val procs = parts[0].lines().mapNotNull { l ->
            val f = l.trim().split(Regex("\\s+"), limit = 4)
            if (f.size < 4) null
            else Proc(f[0].toIntOrNull() ?: return@mapNotNull null, f[1].toIntOrNull() ?: return@mapNotNull null,
                f[2].toLongOrNull() ?: 0L, f[3])
        }
        if (procs.isEmpty()) return emptyList()
        fun ticks(block: String) = block.lines().mapNotNull { l ->
            val f = l.trim().split(" ")
            if (f.size < 3) null else f[0].toIntOrNull()?.let { it to ((f[1].toLongOrNull() ?: 0L) + (f[2].toLongOrNull() ?: 0L)) }
        }.toMap()
        val t0 = ticks(parts[1]); val t1 = ticks(parts[2])
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        // Clock ticks are 1/100 s on Android.
        val budget = 100f * (SAMPLE_MS / 1000f) * cores

        return procs.groupBy { it.uid }.mapNotNull { (uid, list) ->
            val pkg = byUid[uid] ?: return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            val dTicks = list.sumOf { p -> ((t1[p.pid] ?: 0L) - (t0[p.pid] ?: 0L)).coerceAtLeast(0L) }
            AppUsage(
                pkg = pkg, label = label,
                pids = list.map { it.pid },
                names = list.sortedByDescending { it.rss }.map { it.name.substringAfterLast('/') }.distinct(),
                rssKb = list.sumOf { it.rss },
                cpuPct = (dTicks / budget * 100f).coerceIn(0f, 100f),
            )
        }.sortedByDescending { it.rssKb }
    }

    /** Force-stops the apps and kills anything of theirs that survives (wine children). */
    fun kill(apps: List<AppUsage>) {
        if (apps.isEmpty()) return
        val cmd = apps.joinToString("; ") { a ->
            "am force-stop ${a.pkg}; kill -9 ${a.pids.joinToString(" ")} 2>/dev/null"
        }
        RootShell.run(cmd)
    }
}
