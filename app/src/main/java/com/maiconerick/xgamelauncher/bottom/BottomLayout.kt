package com.maiconerick.xgamelauncher.bottom

import org.json.JSONArray
import org.json.JSONObject

/**
 * The bottom launcher's home: pages (swiped horizontally), each a vertical stack of widgets.
 * Stored as JSON in prefs. `type` is a built-in widget id or "android" (hosted AppWidget).
 */
data class WidgetSpec(
    val type: String,
    /** AppWidget id for type "android". */
    val androidId: Int = -1,
    /** Height in dp (android widgets and resizable built-ins). */
    val heightDp: Int = 0,
    /** Built-in option, e.g. playtime period "week" | "month" | "year". */
    val option: String = "",
    val key: Long = System.nanoTime(),
    /** Width in columns of [BuiltinWidgets.COLUMNS]; 0 = the type's default (legacy flow layout). */
    val span: Int = 0,
    /** Free placement on the page, in dp. [w] 0 = not placed yet (auto-placed on first show). */
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 0,
) {
    val placed: Boolean get() = w > 0 && heightDp > 0
    val columns: Int get() = (if (span > 0) span else if (type in BuiltinWidgets.SMALL) 4 else BuiltinWidgets.COLUMNS)
        .coerceIn(BuiltinWidgets.MIN_SPAN, BuiltinWidgets.COLUMNS)
}

object BuiltinWidgets {
    const val PLAYTIME = "playtime"
    const val LAST_PLAYED = "last_played"
    const val LIVE = "live_session"
    const val SYSTEM = "system"
    const val SEPARATOR = "separator"
    const val INVADERS = "invaders"
    const val CLOCK = "clock"
    const val CLUSTERTUNE = "clustertune"
    const val ANDROID = "android"

    /** Page grid width: widgets span 2..12 columns and wrap into rows. */
    const val COLUMNS = 12
    const val MIN_SPAN = 2

    // Small stat cards (three per row).
    const val STAT_FPS = "stat_fps"
    const val STAT_CPU = "stat_cpu"
    const val STAT_GPU = "stat_gpu"
    const val STAT_RAM = "stat_ram"
    const val STAT_TEMP = "stat_temp"
    const val STAT_BATTERY = "stat_battery"
    const val STAT_POWER = "stat_power"
    val SMALL = setOf(STAT_FPS, STAT_CPU, STAT_GPU, STAT_RAM, STAT_TEMP, STAT_BATTERY, STAT_POWER)

    val ALL = listOf(
        CLOCK to "Clock & date",
        LIVE to "Live session (what's playing, ticking every second)",
        LAST_PLAYED to "Last played (with Play again)",
        PLAYTIME to "Playtime · this week / month / year",
        STAT_FPS to "FPS (small card)",
        STAT_CPU to "CPU (small card)",
        STAT_GPU to "GPU (small card)",
        STAT_RAM to "RAM (small card)",
        STAT_TEMP to "Temperature (small card)",
        STAT_BATTERY to "Battery + time left (small card)",
        STAT_POWER to "Power draw (small card)",
        CLUSTERTUNE to "ClusterTune (CPU profile, live caps, switch)",
        SEPARATOR to "Neon separator",
        INVADERS to "Space invaders",
    )
}

/** Natural height (dp) for a widget type, used when it's first placed. */
fun defaultHeightDp(type: String): Int = when (type) {
    BuiltinWidgets.CLOCK -> 150
    BuiltinWidgets.LIVE -> 130
    BuiltinWidgets.LAST_PLAYED -> 170
    BuiltinWidgets.PLAYTIME -> 230
    BuiltinWidgets.SYSTEM -> 110
    in BuiltinWidgets.SMALL -> 100
    BuiltinWidgets.SEPARATOR -> 30
    BuiltinWidgets.INVADERS -> 170
    BuiltinWidgets.CLUSTERTUNE -> 130
    else -> 180
}

/**
 * Gives every unplaced widget on a page a position inside the visible page: first the old
 * column flow below what's already there; anything that wouldn't fit on screen is centred
 * on the page instead (overlapping is allowed), so a new widget is always visible.
 */
fun autoPlace(page: List<WidgetSpec>, pageWidthDp: Int, pageHeightDp: Int, gapDp: Int = 10): List<WidgetSpec> {
    if (page.all { it.placed } || pageWidthDp <= 0 || pageHeightDp <= 0) return page
    var top = page.filter { it.placed }.maxOfOrNull { it.y + it.heightDp + gapDp } ?: 0
    val col = (pageWidthDp + gapDp) / BuiltinWidgets.COLUMNS.toFloat()
    var cursor = 0          // columns used in the current row
    var rowH = 0
    var cascade = 0         // offsets stacked fallbacks so they don't sit exactly on top of each other
    return page.map { w ->
        if (w.placed) return@map w
        val cols = w.columns
        if (cursor + cols > BuiltinWidgets.COLUMNS) { top += rowH + gapDp; cursor = 0; rowH = 0 }
        val h = (if (w.heightDp > 0) w.heightDp else defaultHeightDp(w.type)).coerceAtMost(pageHeightDp)
        val width = (cols * col - gapDp).toInt().coerceIn(48, pageWidthDp)
        if (top + h <= pageHeightDp) {
            val placed = w.copy(x = (cursor * col).toInt(), y = top, w = width, heightDp = h)
            cursor += cols; rowH = maxOf(rowH, h)
            placed
        } else {
            val off = 24 * cascade++
            w.copy(
                x = ((pageWidthDp - width) / 2 + off).coerceIn(0, pageWidthDp - width),
                y = ((pageHeightDp - h) / 2 + off).coerceIn(0, pageHeightDp - h),
                w = width, heightDp = h,
            )
        }
    }
}

/** Pulls widgets that ended up off-screen (older placement) back inside the page. */
fun clampToPage(page: List<WidgetSpec>, pageWidthDp: Int, pageHeightDp: Int): List<WidgetSpec> =
    if (pageWidthDp <= 0 || pageHeightDp <= 0) page else page.map { w ->
        if (!w.placed) return@map w
        val ww = w.w.coerceAtMost(pageWidthDp)
        val hh = w.heightDp.coerceAtMost(pageHeightDp)
        val nx = w.x.coerceIn(0, pageWidthDp - ww)
        val ny = w.y.coerceIn(0, pageHeightDp - hh)
        if (ww == w.w && hh == w.heightDp && nx == w.x && ny == w.y) w else w.copy(x = nx, y = ny, w = ww, heightDp = hh)
    }

object BottomLayoutCodec {
    val DEFAULT: List<List<WidgetSpec>> = listOf(
        listOf(
            WidgetSpec(BuiltinWidgets.CLOCK),
            WidgetSpec(BuiltinWidgets.LIVE),
            WidgetSpec(BuiltinWidgets.LAST_PLAYED),
            WidgetSpec(BuiltinWidgets.SEPARATOR),
            WidgetSpec(BuiltinWidgets.PLAYTIME, option = "week"),
        ),
        listOf(
            WidgetSpec(BuiltinWidgets.STAT_FPS), WidgetSpec(BuiltinWidgets.STAT_CPU), WidgetSpec(BuiltinWidgets.STAT_GPU),
            WidgetSpec(BuiltinWidgets.STAT_TEMP), WidgetSpec(BuiltinWidgets.STAT_RAM), WidgetSpec(BuiltinWidgets.STAT_BATTERY),
            WidgetSpec(BuiltinWidgets.INVADERS),
        ),
    )

    fun decode(json: String): List<List<WidgetSpec>> = runCatching {
        if (json.isBlank()) return DEFAULT
        val pages = JSONArray(json)
        (0 until pages.length()).map { p ->
            val arr = pages.getJSONArray(p)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WidgetSpec(
                    type = o.getString("t"), androidId = o.optInt("id", -1), heightDp = o.optInt("h", 0),
                    option = o.optString("o", ""), key = o.optLong("k", System.nanoTime() + i),
                    span = o.optInt("s", 0),
                    x = o.optInt("x", 0), y = o.optInt("y", 0), w = o.optInt("w", 0),
                )
            }
        }.ifEmpty { DEFAULT }
    }.getOrDefault(DEFAULT)

    fun encode(pages: List<List<WidgetSpec>>): String = JSONArray().apply {
        pages.forEach { page ->
            put(JSONArray().apply {
                page.forEach { w ->
                    put(JSONObject().put("t", w.type).put("id", w.androidId).put("h", w.heightDp).put("o", w.option).put("k", w.key).put("s", w.span)
                        .put("x", w.x).put("y", w.y).put("w", w.w))
                }
            })
        }
    }.toString()
}
