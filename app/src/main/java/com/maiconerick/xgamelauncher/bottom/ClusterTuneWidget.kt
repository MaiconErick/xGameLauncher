package com.maiconerick.xgamelauncher.bottom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import com.maiconerick.xgamelauncher.util.RootSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ClusterTune (com.aure.clustertune) at a glance: the selected profile, and the CPU cluster /
 * GPU caps actually in effect (read with root). ClusterTune has no public API, so switching
 * goes through its own Quick Settings profile picker; RE-APPLY runs ClusterTune's last
 * frequency script when the live caps don't match the profile (e.g. after the system reset them).
 */
object ClusterTune {
    const val PKG = "com.aure.clustertune"
    private const val DATA = "/data/data/$PKG/files"

    data class State(
        val profile: String?,
        /** policy → (live cap kHz, hardware max kHz) */
        val cpu: List<Triple<String, Long, Long>>,
        val gpuMhz: Long?,
        /** true when the profile's saved caps aren't what the CPU is running with. */
        val drift: Boolean,
        val installed: Boolean,
    )

    fun read(): State {
        val out = RootSession.exec(
            "strings $DATA/datastore/android_tuner.preferences_pb 2>/dev/null; echo '#SYS'; " +
                "for p in 0 3 7; do echo \"P\$p \$(cat /sys/devices/system/cpu/cpufreq/policy\$p/scaling_max_freq) \$(cat /sys/devices/system/cpu/cpufreq/policy\$p/cpuinfo_max_freq)\"; done; " +
                "echo \"G \$(cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null)\"",
        ) ?: return State(null, emptyList(), null, false, installed = false)
        val prefs = out.substringBefore("#SYS")
        val sys = out.substringAfter("#SYS")
        val profile = Regex("\\{\"id\":\"[^\"]*\",\"name\":\"([^\"]+)\"").find(prefs.substringAfter("effective_profile_state", prefs))?.groupValues?.get(1)
        val saved = Regex("\\{(\"\\d+\":\\d+,?)+\\}").find(prefs.substringAfter("last_values", ""))?.value
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val names = mapOf("0" to "LITTLE", "3" to "MID", "7" to "BIG")
        val cpu = sys.lines().filter { it.startsWith("P") }.mapNotNull { l ->
            val p = l.trim().split(Regex("\\s+"))
            val id = p.getOrNull(0)?.removePrefix("P") ?: return@mapNotNull null
            Triple(names[id] ?: id, p.getOrNull(1)?.toLongOrNull() ?: 0L, p.getOrNull(2)?.toLongOrNull() ?: 0L).let { id to it }
        }
        val drift = saved != null && cpu.any { (id, t) -> saved.has(id) && saved.getLong(id) != t.second }
        val gpu = sys.lines().firstOrNull { it.startsWith("G ") }?.substringAfter("G ")?.trim()?.toLongOrNull()?.div(1_000_000)
        return State(profile, cpu.map { it.second }, gpu, drift, installed = prefs.isNotBlank())
    }

    /** Re-runs ClusterTune's own "apply the last profile" script. */
    fun reapply() { RootSession.exec("sh $DATA/root-scripts/apply-frequencies.sh") }

    /** ClusterTune's Quick Settings profile picker (the same dialog as its tile's long-press). */
    fun openPicker(ctx: android.content.Context) {
        val i = android.content.Intent("android.service.quicksettings.action.QS_TILE_PREFERENCES")
            .setClassName(PKG, "$PKG.TileControlActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure {
            val display = (ctx as? android.app.Activity)?.display?.displayId ?: 0
            RootSession.exec("am start --display $display -n $PKG/.TileControlActivity")
        }
    }
}

@Composable
fun ClusterTuneWidget() {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ClusterTune.State?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        while (true) {
            state = withContext(Dispatchers.IO) { runCatching { ClusterTune.read() }.getOrNull() }
            delay(15_000)
        }
    }
    val s = state
    WidgetCard(title = "ClusterTune", onClick = { ClusterTune.openPicker(ctx) }) {
        when {
            s == null -> Text("Reading…", color = neon.inkDim, fontSize = 13.sp)
            !s.installed -> Text("ClusterTune not found (needs root)", color = neon.inkDim, fontSize = 13.sp)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.profile ?: "Unknown profile", color = neon.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Text("CHANGE ›", color = neon.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    s.cpu.forEach { (name, cap, max) ->
                        Text(
                            "$name ${"%.2f".format(cap / 1e6)}" + (if (cap < max) "" else " (stock)"),
                            color = if (cap < max) neon.accent else neon.inkDim, fontSize = 12.sp,
                        )
                    }
                    s.gpuMhz?.let { Text("GPU ${it}MHz", color = neon.inkDim, fontSize = 12.sp) }
                }
                if (s.drift) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("⚠ Profile caps aren't applied", color = Color(0xFFFFB74D), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp)).background(neon.accent)
                            .clickable { scope.launch { withContext(Dispatchers.IO) { ClusterTune.reapply() }; tick++ } }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("RE-APPLY", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black) }
                }
            }
        }
    }
}
