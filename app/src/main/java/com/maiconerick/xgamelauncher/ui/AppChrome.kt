package com.maiconerick.xgamelauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App-wide chrome switches that full-screen tools (e.g. the background maker) can flip. */
object AppChrome {
    /** True hides the bottom platform strip / hint bar. */
    var hideBottomBar by mutableStateOf(false)

    /** Top-bar pause button: freezes every decorative animation (starfield, bar effects,
     *  focus effects, widget animations, video background) so a running game keeps the GPU.
     *  Shared by both screens (same process); persisted in prefs. */
    var animationsPaused by mutableStateOf(false)
}

/** True when decorative animations should run (not low-power, not paused). */
@androidx.compose.runtime.Composable
fun animationsOn(): Boolean = !LocalDeviceProfile.current.lowPower && !AppChrome.animationsPaused
