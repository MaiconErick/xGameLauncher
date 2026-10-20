package com.maiconerick.xgamelauncher.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Right-aligned status chips: WiFi signal (0-4 bars, accent colour), battery percent, clock.
 * Battery reads a sticky broadcast (no permission needed); WiFi asks ConnectivityManager +
 * WifiManager (needs ACCESS_WIFI_STATE + ACCESS_NETWORK_STATE, declared in the manifest).
 */
@Composable
fun SystemChips(modifier: Modifier = Modifier) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current

    // ---- battery: sticky broadcast ----
    var battery by remember { mutableStateOf(readBattery(ctx)) }
    DisposableEffect(Unit) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                battery = readBattery(context)
            }
        }
        ctx.registerReceiver(receiver, filter)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    // ---- wifi: poll every 20s. Track bars + raw RSSI (shown on click). ----
    var wifi by remember { mutableStateOf(readWifi(ctx)) }
    var showRssi by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            wifi = readWifi(ctx)
            delay(20_000)
        }
    }


    // ---- clock: tick every 15s ----
    var clock by remember { mutableStateOf(nowHHmm()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = nowHHmm()
            delay(15_000)
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact) 6.dp else 12.dp
        ),
    ) {
        // Compact top bars are too narrow for the wifi glyph — battery only.
        if (!com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact) {
            WifiIcon(
                bars = wifi.bars,
                rssi = wifi.rssi,
                showRssi = showRssi,
                onToggleRssi = { showRssi = !showRssi },
            )
        }
        BatteryChip(percent = battery, charging = false, accent = neon.accent, ink = neon.inkDim)
        Text(
            text = clock,
            color = neon.ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        )
    }
}

@Composable
private fun WifiIcon(bars: Int, rssi: Int?, showRssi: Boolean, onToggleRssi: () -> Unit) {
    val neon = LocalNeon.current
    // Single colour: lit arcs in the accent, unlit arcs as a faint accent.
    val strengthColor = neon.accent
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onToggleRssi)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(width = 18.dp, height = 14.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h * 0.95f       // arc origin near bottom-center
            val stroke = h * 0.14f
            val dimAlpha = 0.20f
            val on = strengthColor
            val off = neon.accent.copy(alpha = dimAlpha)

            fun arc(radius: Float, color: Color) {
                val topLeft = Offset(cx - radius, cy - radius)
                val sz = Size(radius * 2, radius * 2)
                drawArc(
                    color = color,
                    startAngle = 210f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = sz,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = stroke,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    ),
                )
            }
            // Three arcs: outer, middle, inner. Light up based on bars (0-4).
            arc(h * 0.85f, if (bars >= 3) on else off)
            arc(h * 0.60f, if (bars >= 2) on else off)
            arc(h * 0.35f, if (bars >= 1) on else off)
            // Dot at center
            drawCircle(
                color = if (bars >= 1) on else off,
                radius = h * 0.09f,
                center = Offset(cx, cy - h * 0.02f),
            )
        }
        if (showRssi) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (rssi != null) "$rssi dBm" else "no wifi",
                color = strengthColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
            )
        }
    }
}

@Composable
private fun BatteryChip(percent: Int, charging: Boolean, accent: Color, ink: Color) {
    // Little battery pill with fill ratio inside.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(width = 22.dp, height = 12.dp),
        ) {
            // Outer border
            Canvas(modifier = Modifier.size(width = 22.dp, height = 12.dp)) {
                val outer = size
                val strokeWidth = 1.2f
                drawRect(
                    color = ink.copy(alpha = 0.6f),
                    topLeft = Offset(0f, 0f),
                    size = Size(outer.width - 3f, outer.height),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth),
                )
                // Nub
                drawRect(
                    color = ink.copy(alpha = 0.6f),
                    topLeft = Offset(outer.width - 3f, outer.height * 0.28f),
                    size = Size(3f, outer.height * 0.44f),
                )
                // Fill
                val ratio = (percent.coerceIn(0, 100)) / 100f
                val fillWidth = (outer.width - 3f - 2 * strokeWidth) * ratio
                val fillColor = when {
                    charging -> accent
                    percent <= 15 -> Color(0xFFFF5555)
                    percent <= 30 -> Color(0xFFFFB300)
                    else -> accent
                }
                drawRect(
                    color = fillColor,
                    topLeft = Offset(strokeWidth, strokeWidth),
                    size = Size(fillWidth, outer.height - 2 * strokeWidth),
                )
            }
        }
        Text(
            text = "$percent%",
            color = ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun readBattery(ctx: Context): Int {
    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 0
    return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
}

private data class WifiInfo(val bars: Int, val rssi: Int?)

private fun readWifi(ctx: Context): WifiInfo {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return WifiInfo(0, null)
    val net = cm.activeNetwork ?: return WifiInfo(0, null)
    val caps = cm.getNetworkCapabilities(net) ?: return WifiInfo(0, null)
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return WifiInfo(0, null)
    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return WifiInfo(2, null)
    val rssi = try { wm.connectionInfo?.rssi ?: -100 } catch (_: Throwable) { -100 }
    val bars = WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4)
    return WifiInfo(bars, rssi.takeIf { it > -128 })
}

private fun nowHHmm(): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(System.currentTimeMillis()))
