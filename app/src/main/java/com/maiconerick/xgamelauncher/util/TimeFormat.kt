package com.maiconerick.xgamelauncher.util

import java.util.concurrent.TimeUnit

object TimeFormat {

    /** "42h 15m", "8m", "12s" */
    fun playtime(totalSeconds: Long): String {
        if (totalSeconds <= 0L) return "0m"
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            m > 0 -> "${m}m"
            else -> "${totalSeconds}s"
        }
    }

    /** Short chip form used on tiles: "42h", "18m", "-" if never played. */
    fun playtimeChip(totalSeconds: Long): String {
        if (totalSeconds <= 0L) return "-"
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        return if (h > 0) "${h}h" else "${m}m"
    }

    /** "2h ago", "yesterday", "Mar 3", "-" */
    fun lastPlayed(unixSeconds: Long): String {
        if (unixSeconds <= 0L) return "never"
        val now = System.currentTimeMillis() / 1000
        val diff = now - unixSeconds
        if (diff < 60) return "just now"
        if (diff < 3600) return "${diff / 60}m ago"
        if (diff < 86_400) return "${diff / 3600}h ago"
        if (diff < 2 * 86_400) return "yesterday"
        val days = TimeUnit.SECONDS.toDays(diff)
        if (days < 30) return "${days}d ago"
        return java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
            .format(java.util.Date(unixSeconds * 1000))
    }
}
