package com.maiconerick.xgamelauncher.ui

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * What kind of device we're drawing on. Every screen reads this through [LocalDeviceProfile]
 * so the same build runs well on the Ayn Thor (big 16:9, dual screen, lots of RAM) and on
 * small/low-RAM handhelds like the Anbernic RG353M (3.5" 640x480 4:3, 2 GB, Android 11).
 *
 *  · compact    — small or near-square screen: tighter paddings, smaller type, no side panels.
 *  · lowPower   — weak GPU / little RAM: no infinite animations, no blur, no crossfades,
 *                 no full-screen backdrop decodes, smaller image cache.
 *  · multiDisplay — a second display exists (Thor bottom screen). When false, every
 *                 "top/bottom screen" control is hidden and display ids are never passed.
 *
 * Rule for new features: anything decorative must be gated on `lowPower`, and every new
 * layout must fit in ~420x300dp when `compact` is true.
 */
data class DeviceProfile(
    val compact: Boolean = false,
    val lowPower: Boolean = false,
    val multiDisplay: Boolean = false,
    val mode: String = MODE_AUTO,
) {
    /** Picks between the full-size and compact value — keeps call sites one-liners. */
    fun dp(full: Dp, compact: Dp): Dp = if (this.compact) compact else full
    fun sp(full: TextUnit, compact: TextUnit): TextUnit = if (this.compact) compact else full
    val pagePadding: Dp get() = if (compact) 12.dp else 40.dp

    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_FULL = "full"
        const val MODE_COMPACT = "compact"

        /**
         * Auto-detection:
         *  · compact when the short side is < 400dp or the aspect ratio is under 1.5
         *    (4:3 / 3:2 panels) — the Thor's 16:9 top screen is ~1.78.
         *  · lowPower when total RAM is under 3 GB or the OS flags the device as low-RAM.
         * `mode` = "full" / "compact" forces both flags off / on.
         */
        fun detect(ctx: Context, mode: String): DeviceProfile {
            val cfg = ctx.resources.configuration
            val w = cfg.screenWidthDp.coerceAtLeast(1)
            val h = cfg.screenHeightDp.coerceAtLeast(1)
            val shortSide = minOf(w, h)
            val aspect = maxOf(w, h).toFloat() / shortSide
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val totalGb = mem.totalMem / (1024.0 * 1024 * 1024)
            val autoCompact = shortSide < 400 || aspect < 1.5f
            val autoLowPower = (totalGb in 0.1..3.0) || am?.isLowRamDevice == true
            val multi = DisplayTargets.secondaryDisplayId(ctx) != null
            return when (mode) {
                MODE_FULL -> DeviceProfile(false, false, multi, mode)
                MODE_COMPACT -> DeviceProfile(true, true, multi, mode)
                else -> DeviceProfile(autoCompact, autoLowPower, multi, MODE_AUTO)
            }
        }

        /** Default device label for session rows / Dropbox filenames. */
        fun defaultDeviceName(): String {
            val model = Build.MODEL.orEmpty().trim()
            // The RG353M's Android 12 ROM spoofs a Pixel 3a (MODEL/DEVICE), but the board
            // props still say Rockchip (hardware rk30board, board rk30sdk, platform rk356x).
            val rockchip = Build.HARDWARE.startsWith("rk", ignoreCase = true) ||
                Build.BOARD.startsWith("rk", ignoreCase = true)
            return when {
                model.contains("thor", ignoreCase = true) -> "Thor"
                rockchip -> "RG353M"
                model.isNotBlank() -> model
                else -> "Android"
            }
        }
    }
}

val LocalDeviceProfile = staticCompositionLocalOf { DeviceProfile() }

/**
 * Maps the per-game "top" / "bottom" screen preference to a real display id. The Thor's
 * bottom screen is display 4, but we look it up instead of hard-coding it, and return null
 * (= let Android decide) on single-screen devices so launches never target a missing display.
 */
object DisplayTargets {
    fun secondaryDisplayId(ctx: Context): Int? {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        return dm.displays
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }
            ?.displayId
            ?: dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }?.displayId
    }

    fun displayIdFor(ctx: Context, pref: String?): Int? = when (pref) {
        "top" -> if (secondaryDisplayId(ctx) != null) Display.DEFAULT_DISPLAY else null
        "bottom" -> secondaryDisplayId(ctx)
        else -> null
    }
}
