package com.maiconerick.xgamelauncher.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * On device boot, run session recovery so any row that was open when the device restarted
 * gets closed cleanly. We don't restart the tracker — the tracker is session-scoped and
 * only runs while a game is up.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                SessionRecovery.run(context)
                com.maiconerick.xgamelauncher.tools.ToolsService.refresh(context)
            } finally { pending.finish() }
        }
    }
}
