package com.maiconerick.xgamelauncher.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.maiconerick.xgamelauncher.MainActivity

object Notifier {
    const val CH_TRACK = "xgl_track"
    const val NOTIF_TRACK = 42

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH_TRACK) == null) {
            val ch = NotificationChannel(
                CH_TRACK, "Playtime tracking", NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    fun tracking(ctx: Context, gameName: String, emulator: String): Notification {
        ensureChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(ctx, CH_TRACK)
            .setContentTitle("Tracking · $gameName")
            .setContentText(emulator)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
