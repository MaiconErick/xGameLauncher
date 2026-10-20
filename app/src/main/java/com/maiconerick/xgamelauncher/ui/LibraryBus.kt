package com.maiconerick.xgamelauncher.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "The library changed" signal shared by every LauncherViewModel in the process — the top
 * launcher and the bottom screen's drawer each have their own, so a change made on one
 * screen (rename, art, platform edit) must refresh the other too.
 */
object LibraryBus {
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()
    fun bump() { _tick.value = _tick.value + 1 }
}
