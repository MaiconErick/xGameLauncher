package com.maiconerick.xgamelauncher.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.maiconerick.xgamelauncher.R

/**
 * A whole-app look: palette source, backdrop, bar tint, display font and the default focus
 * / bar animations. Picked in Settings → Theme. The focus/bar animations the user picks in
 * Settings → Appearance are stored per [group], so each theme keeps its own.
 */
@Immutable
data class AppTheme(
    val id: String,
    val label: String,
    val subtitle: String,
    /** Prefs group for the per-theme focus/bar settings (both Halloween variants share one). */
    val group: String,
    val halloween: Boolean,
    /** Pure-black surfaces (AMOLED). */
    val amoled: Boolean,
    /** Fixed palette; null = derived from the platform / session accent (Neon). */
    val palette: NeonPalette?,
    /** Top and bottom bar background. */
    val barColor: Color,
    /** Page titles (null = the default sans). */
    val displayFont: FontFamily?,
    val defaultFocusKind: String,
    val defaultBarFx: String,
) {
    companion object {
        val Creepster = FontFamily(Font(R.font.creepster))

        val Neon = AppTheme(
            id = "neon", label = "Neon", subtitle = "The original look: platform accents, starfield, your own animations.",
            group = "neon", halloween = false, amoled = true, palette = null,
            barColor = Color(0xE6000000), displayFont = null,
            defaultFocusKind = "snake", defaultBarFx = "none",
        )

        private val HalloweenBase = AppTheme(
            id = "halloween", label = "Halloween", subtitle = "Pixel moon, bats, ghosts and a graveyard on a haunted purple sky.",
            group = "halloween", halloween = true, amoled = false,
            palette = Spook.palette(amoled = false),
            barColor = Color(0xE6130A1E), displayFont = Creepster,
            defaultFocusKind = "candle", defaultBarFx = "bats",
        )
        val Halloween = HalloweenBase
        val HalloweenAmoled = HalloweenBase.copy(
            id = "halloween_amoled", label = "Halloween AMOLED",
            subtitle = "The same haunt on pure black: true-black sky, no fog, battery friendly.",
            amoled = true, palette = Spook.palette(amoled = true), barColor = Color(0xF2000000),
        )

        val ALL = listOf(Neon, Halloween, HalloweenAmoled)
        fun byId(id: String?): AppTheme = ALL.firstOrNull { it.id == id } ?: Neon
    }
}

val LocalAppTheme = staticCompositionLocalOf { AppTheme.Neon }

/** Halloween colours (shared with xVideoWatcher's pixel theme). */
object Spook {
    val Night = Color(0xFF0B0612)
    val Crypt = Color(0xFF150C21)
    val Crypt2 = Color(0xFF1F1230)
    val Pumpkin = Color(0xFFFF7A18)
    val PumpkinDeep = Color(0xFFC24E00)
    val Witch = Color(0xFF8B3DFF)
    val Slime = Color(0xFF7CFF4F)
    val Blood = Color(0xFFFF2E4D)
    val Bone = Color(0xFFF2E8D5)
    val Moon = Color(0xFFFFD54A)
    val Mist = Color(0xFF9A8BB0)
    val Fog = Color(0xFF5E4F75)

    fun palette(amoled: Boolean) = NeonPalette(
        accent = Pumpkin,
        accentSoft = PumpkinDeep,
        accentGlow = Pumpkin.copy(alpha = 0.4f),
        ink = Bone,
        inkDim = Color(0xFFCDBFDD),
        inkMuted = Mist,
        surface = if (amoled) Color.Black else Night,
        surfaceElevated = if (amoled) Color(0xFF07040B) else Crypt,
        stroke = if (amoled) Color(0xFF1E1229) else Color(0xFF2B1A40),
    )
}
