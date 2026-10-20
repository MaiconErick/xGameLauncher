package com.maiconerick.xgamelauncher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Display
import com.maiconerick.xgamelauncher.bottom.BottomHomeActivity

/**
 * xGameLauncher's ONLY launcher/home entry point. Home managers like Mjolnir pick an app per
 * screen by package name, so both screens resolve to the same entry; this invisible,
 * multi-instance router forwards to the right launcher for the display it was opened on:
 * the default display gets MainActivity, any other display gets BottomHomeActivity. (Opening
 * the singleTask MainActivity directly would drag it from one screen to the other.)
 */
class HomeRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val displayId = if (Build.VERSION.SDK_INT >= 30) display?.displayId ?: Display.DEFAULT_DISPLAY
            else @Suppress("DEPRECATION") windowManager.defaultDisplay.displayId
        val target = if (displayId == Display.DEFAULT_DISPLAY) MainActivity::class.java else BottomHomeActivity::class.java
        val i = Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            startActivity(i, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
