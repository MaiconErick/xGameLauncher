package com.maiconerick.xgamelauncher.bottom

import android.content.Context
import android.os.BatteryManager
import com.maiconerick.xgamelauncher.util.RootSession
import java.io.File

/** One reading of the handheld's live vitals (ported from xPTT). Null = unavailable. */
data class SysSnapshot(
    val cpuPct: Int? = null,
    val gpuPct: Int? = null,
    val fps: Int? = null,
    val drops: Int = 0,
    val ramUsedMb: Long = 0,
    val ramTotalMb: Long = 0,
    val tempC: Int? = null,
    val batteryPct: Int? = null,
    val minutesLeft: Int? = null,   // discharge estimate; null while charging/unknown
    val charging: Boolean = false,
    val amps: Float? = null,
)

/**
 * Frame rate of whatever is rendering (xPTT's FpsMeter): SurfaceFlinger TimeStats, switched
 * on while watched. `measured_fps` only reports the panel rate and `--latency` returns no
 * rows on Android 13, so TimeStats is the source that gives real per-app FPS here.
 */
object FpsMeter {
    private const val SF = "dumpsys SurfaceFlinger --timestats"
    private val IGNORE = listOf(
        "com.maiconerick.xgamelauncher", "StatusBar", "NavigationBar", "Wallpaper",
        "com.android.systemui", "Dim layer", "ScreenDecor", "InputMethod", "Splash",
    )
    @Volatile private var enabled = false

    data class Reading(val fps: Int, val dropped: Int)

    fun start() {
        if (enabled) return
        enabled = RootSession.exec("$SF -enable; $SF -clear") != null
    }

    fun stop() {
        if (!enabled) return
        RootSession.exec("$SF -disable; $SF -clear")
        enabled = false
    }

    /** FPS since the previous call; prefers [preferPkg]'s layer (the tracked game). */
    fun sample(preferPkg: String? = null): Reading? {
        if (!enabled) { start(); if (!enabled) return null }
        val dump = RootSession.exec("$SF -dump -maxlayers 8; $SF -clear") ?: return null
        if (!dump.contains("layerName")) { enabled = false; return null }
        var name: String? = null
        var frames = 0
        var pending: Double? = null
        var dropped = 0
        var best: Reading? = null; var bestFrames = 0
        var game: Reading? = null; var gameFrames = 0
        fun consider() {
            val n = name ?: return
            val fps = pending ?: return
            val r = Reading(Math.round(fps).toInt(), dropped)
            if (!preferPkg.isNullOrBlank() && n.contains(preferPkg) && frames > gameFrames) { gameFrames = frames; game = r }
            if (frames > bestFrames && IGNORE.none { n.contains(it, ignoreCase = true) }) { bestFrames = frames; best = r }
        }
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("layerName") -> { consider(); name = line.substringAfter("=").trim(); frames = 0; pending = null; dropped = 0 }
                line.startsWith("totalFrames") -> frames = line.substringAfter("=").trim().toIntOrNull() ?: 0
                line.startsWith("droppedFrames") -> dropped = line.substringAfter("=").trim().toIntOrNull() ?: 0
                line.startsWith("averageFPS") -> pending = line.substringAfter("=").trim().toDoubleOrNull()
            }
        }
        consider()
        game?.takeIf { gameFrames >= 5 && it.fps > 0 }?.let { return it }
        return best?.takeIf { bestFrames >= 5 && it.fps > 0 }
    }
}

/**
 * CPU (busiest core), GPU, RAM, SoC temperature and battery — all readable without root
 * (xPTT verified this on the Thor); only FPS goes through the root shell.
 */
class SysStats(ctx: Context) {
    private val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val lastCore = HashMap<String, Pair<Long, Long>>()
    private var currentEma = 0.0
    private var lastFps: Int? = null
    private var lastDrops = 0

    private val cpuZones: List<File> by lazy {
        val out = ArrayList<File>()
        File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach { z ->
            val type = readText(File(z, "type"))?.trim()?.lowercase() ?: return@forEach
            if (type.startsWith("cpu") || type.contains("soc")) out.add(File(z, "temp"))
        }
        if (out.isEmpty()) listOfNotNull(File("/sys/class/thermal/thermal_zone0/temp").takeIf { it.exists() }) else out
    }

    fun sample(includeFps: Boolean, fpsPkg: String? = null): SysSnapshot {
        if (includeFps) FpsMeter.sample(fpsPkg).let { lastFps = it?.fps; lastDrops = it?.dropped ?: 0 }
        val mem = readMeminfo()
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val status = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return SysSnapshot(
            cpuPct = cpuPercent(), gpuPct = gpuPercent(), fps = lastFps, drops = lastDrops,
            ramUsedMb = mem.first, ramTotalMb = mem.second, tempC = cpuTemp(),
            batteryPct = pct, charging = charging,
            minutesLeft = if (charging) null else minutesLeft(),
            amps = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?.let { Math.abs(it) / 1_000_000f }?.takeIf { it > 0.01f },
        )
    }

    private fun cpuPercent(): Int? {
        val text = readText(File("/proc/stat")) ?: RootSession.exec("cat /proc/stat") ?: return null
        var max = -1
        for (line in text.lineSequence()) {
            if (!line.startsWith("cpu")) break
            val parts = line.trim().split(Regex("\\s+"))
            val name = parts.firstOrNull() ?: continue
            if (name == "cpu") continue
            val f = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (f.size < 5) continue
            val total = f.take(8).sum()
            val idle = f[3] + f[4]
            val prev = lastCore[name]
            lastCore[name] = total to idle
            if (prev != null) {
                val dTotal = total - prev.first
                val dIdle = idle - prev.second
                if (dTotal > 0) max = maxOf(max, (((dTotal - dIdle) * 100) / dTotal).toInt())
            }
        }
        return max.takeIf { it >= 0 }?.coerceIn(0, 100)
    }

    private fun gpuPercent(): Int? {
        readText(File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"))?.let { raw ->
            raw.trim().takeWhile { it.isDigit() }.toIntOrNull()?.let { return it.coerceIn(0, 100) }
        }
        readText(File("/sys/class/kgsl/kgsl-3d0/gpubusy"))?.let { raw ->
            val p = raw.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (p.size >= 2 && p[1] > 0) return ((p[0] * 100) / p[1]).toInt().coerceIn(0, 100)
        }
        // Mali (RK3566): devfreq load "NN@freq"
        File("/sys/class/devfreq").listFiles()?.firstOrNull { it.name.contains("gpu") }?.let { d ->
            readText(File(d, "load"))?.trim()?.substringBefore('@')?.toIntOrNull()?.let { return it.coerceIn(0, 100) }
        }
        return null
    }

    private fun readMeminfo(): Pair<Long, Long> {
        var total = 0L; var available = 0L
        readText(File("/proc/meminfo"))?.lineSequence()?.forEach { l ->
            when {
                l.startsWith("MemTotal:") -> total = kb(l)
                l.startsWith("MemAvailable:") -> available = kb(l)
            }
        }
        return ((total - available) / 1024) to (total / 1024)
    }

    private fun kb(line: String): Long = line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L

    private fun cpuTemp(): Int? {
        var max = Int.MIN_VALUE
        cpuZones.forEach { f ->
            readText(f)?.trim()?.toIntOrNull()?.let { raw ->
                val c = if (raw > 1000) raw / 1000 else raw
                if (c in 1..150 && c > max) max = c
            }
        }
        return if (max == Int.MIN_VALUE) null else max
    }

    private fun minutesLeft(): Int? {
        val chargeUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 } ?: return null
        val draw = Math.abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble())
        if (draw < 1000) return null
        currentEma = if (currentEma == 0.0) draw else currentEma * 0.7 + draw * 0.3
        return ((chargeUah / currentEma) * 60).toInt().takeIf { it in 1..3000 }
    }

    private fun readText(f: File): String? = try { if (f.canRead()) f.readText() else null } catch (_: Exception) { null }
}
