package com.maiconerick.xgamelauncher.tracker

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import com.maiconerick.xgamelauncher.data.AppDatabase
import com.maiconerick.xgamelauncher.net.Dropbox
import com.maiconerick.xgamelauncher.prefs.PrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service — watches the launched game's package via UsageStats and closes the
 * open session row when the game leaves the foreground past the grace window. Pauses on
 * screen-off (lid closed) and resumes on screen-on if the game is still up.
 *
 * Adapted from xPlayTimeTracker's TrackerService but sized for MVP: session-based
 * (one service per launch), no midnight-split, no Dropbox, no overlay.
 */
class TrackerService : Service() {

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var lastSeen = 0L
    @Volatile private var t0 = 0L
    @Volatile private var everSeen = false
    // Set synchronously when a resume starts: lid-open and screen-on arrive together, and each used
    // to insert its own row (the loser stayed open forever: a phantom "now playing").
    @Volatile private var resuming = false
    private var worker: Thread? = null
    private val probe = RootProbe()

    private var sessionId = -1L
    private var watchPkg = ""
    private var gameName = ""
    private var emulator = ""
    private var platform = ""

    private lateinit var db: AppDatabase
    private lateinit var prefs: PrefsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var screenReceiver: BroadcastReceiver? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    // Ayn Thor: bottom (lid) screen sits on display id 4 (user-confirmed 2026-09-22).
    private val bottomDisplayId = 4
    @Volatile private var lastBottomState: Int = Display.STATE_ON

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        db = AppDatabase.get(this)
        prefs = PrefsStore(this)

        if (intent?.getBooleanExtra(EX_END, false) == true) {
            goForeground()
            scope.launch {
                if (sessionId <= 0) {
                    val open = db.sessionDao().openSessions().firstOrNull()
                    open?.let {
                        sessionId = it.id; watchPkg = it.pkg; gameName = it.gameName
                        emulator = it.emulator; platform = it.platform
                    }
                }
                if (sessionId > 0) finishSession("Ended manually") else stopSelfSafely()
            }
            return START_NOT_STICKY
        }

        if (intent != null && intent.hasExtra(EX_SID)) {
            sessionId = intent.getLongExtra(EX_SID, -1)
            watchPkg = intent.getStringExtra(EX_PKG) ?: ""
            gameName = intent.getStringExtra(EX_NAME) ?: ""
            emulator = intent.getStringExtra(EX_EMU) ?: ""
            platform = intent.getStringExtra(EX_PLAT) ?: ""
        } else {
            // Restarted by the system after being killed — adopt any still-open session.
            scope.launch {
                val open = db.sessionDao().openSessions().firstOrNull()
                if (open == null) {
                    stopSelfSafely(); return@launch
                }
                sessionId = open.id; watchPkg = open.pkg; gameName = open.gameName
                emulator = open.emulator; platform = open.platform
                startWatchIfNeeded()
            }
            goForeground()
            return START_STICKY
        }

        goForeground()
        registerScreenReceiver()
        registerDisplayListener()
        startWatchIfNeeded()
        notifySessionChanged()
        maybePlay { SoundFx.playStart() }               // "▶ tracking has begun"
        maybeOverlay("Now tracking", "$emulator · $platform")
        return START_STICKY
    }

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        // Prime the last-known state so onDisplayChanged has something to diff against.
        dm.getDisplay(bottomDisplayId)?.let { lastBottomState = it.state }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {
                if (id == bottomDisplayId) onLidClosed()
            }
            override fun onDisplayChanged(id: Int) {
                if (id != bottomDisplayId) return
                val newState = dm.getDisplay(id)?.state ?: return
                if (newState == lastBottomState) return
                val was = lastBottomState
                lastBottomState = newState
                if (newState == Display.STATE_OFF && was != Display.STATE_OFF) onLidClosed()
                else if (newState == Display.STATE_ON && was == Display.STATE_OFF) onLidOpened()
            }
        }
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        displayListener = listener
    }

    private fun onLidClosed() {
        if (!pause("Lid closed")) return
        maybePlay { SoundFx.playFinish() }
        maybeOverlay("Lid closed", "Paused · session saved")
    }

    private fun onLidOpened() {
        if (!resume()) return
        maybePlay { SoundFx.playStart() }
        maybeOverlay("Lid opened", "Tracking again")
    }

    /** Closes the current row; false when already paused (lid + screen-off both fire). Main thread. */
    private fun pause(reason: String): Boolean {
        if (!running || paused) return false
        paused = true
        val id = sessionId
        scope.launch { closeSessionRow(id, System.currentTimeMillis() / 1000, reason) }
        return true
    }

    /** Opens exactly one fresh row after a pause, however many wake-up signals arrive. Main thread. */
    private fun resume(): Boolean {
        if (!running || !paused || resuming) return false
        resuming = true
        scope.launch {
            try {
                val newId = db.sessionDao().insert(openSessionRow())
                sessionId = newId
                val now = System.currentTimeMillis()
                lastSeen = now; t0 = now; everSeen = false; paused = false
                notifySessionChanged()
            } finally { resuming = false }
        }
        return true
    }

    private fun maybePlay(fx: () -> Unit) {
        scope.launch {
            val on = runCatching { prefs.soundFxEnabled.first() }.getOrDefault(true)
            if (on) {
                SoundFx.init(applicationContext)
                fx()
            }
        }
    }

    /** Banner accent: neon cyan, or pumpkin orange with a Halloween theme active. */
    private fun accentArgb(): Int {
        val theme = getSharedPreferences("xgl_ui", MODE_PRIVATE).getString("theme_id", "neon") ?: "neon"
        return if (theme.startsWith("halloween")) 0xFFFF7A18.toInt() else 0xFF00E5FF.toInt()
    }

    /** Session banner: kicker ("Now tracking"…), the game, and a detail line; art comes from
     *  the session row (set at launch) or falls back to the emulator's icon. */
    private fun maybeOverlay(kicker: String, detail: String) {
        scope.launch {
            val on = runCatching { prefs.overlayEnabled.first() }.getOrDefault(true)
            if (!on) return@launch
            val art = runCatching { db.sessionDao().byId(sessionId)?.artPath }.getOrNull()
            Overlay.show(applicationContext, kicker, gameName, detail, accentArgb(), art, watchPkg)
        }
    }

    private fun sessionLength(): String {
        val secs = ((lastSeen - t0) / 1000).coerceAtLeast(0)
        return if (secs >= 3600) "%dh %02dm".format(secs / 3600, (secs % 3600) / 60) else "%dm %02ds".format(secs / 60, secs % 60)
    }

    private fun startWatchIfNeeded() {
        if (running) return
        running = true
        worker = Thread { watchLoop() }.also { it.start() }
    }

    private fun goForeground() {
        Notifier.ensureChannel(this)
        val notif = Notifier.tracking(this, gameName, emulator)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.NOTIF_TRACK, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.NOTIF_TRACK, notif)
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    private fun onScreenOff() {
        pause("Screen off")
    }

    private fun onScreenOn() {
        // Split: open a fresh session row so lid-open time counts separately.
        resume()
    }

    private suspend fun openSessionRow() = com.maiconerick.xgamelauncher.data.SessionRow(
        gameName = gameName, platform = platform, emulator = emulator, pkg = watchPkg,
        startedUnix = System.currentTimeMillis() / 1000,
        // Lid-split rows keep the game art of the row they continue.
        artPath = runCatching { db.sessionDao().byId(sessionId)?.artPath }.getOrNull(),
        user = "MaiconErick",
        device = runCatching { prefs.deviceName.first() }
            .getOrDefault(com.maiconerick.xgamelauncher.ui.DeviceProfile.defaultDeviceName()),
        isOpen = true,
    )

    private fun watchLoop() {
        val grace = 15_000L        // 15s before we call it "backgrounded"
        val poll = 2_000L
        val launchGrace = 60_000L  // let the emulator boot + reach the game first
        // Boot phase: frontends like BannerHub hand the game to another package (CronyX,
        // Winlator…) and Android may flash the launcher in between. During this window a
        // newly resumed non-frontend app is adopted as part of the game, and a launcher
        // flash gets the full grace instead of the 2s "game closed" fast path.
        val bootPhase = 180_000L

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val frontends = frontendPackages()
        val launchedAt = System.currentTimeMillis()
        t0 = launchedAt; lastSeen = t0; everSeen = false
        var lastQuery = t0 - 3000
        val watched = hashSetOf(watchPkg)
        // pkg → classes currently started (RESUMED…STOPPED). Several can be visible at once
        // on the Thor (one per display).
        val visible = HashMap<String, MutableSet<String>>()
        var topPkg: String? = null
        var topCls: String? = null
        var probeTick = 0
        val gone = HashSet<String>()  // watched packages whose process died
        // Emulators like Eden return to their own game list (the launcher activity) when a game
        // exits, which would keep the package "visible". Once a watched package has shown a
        // non-launcher activity (the game), being back on only its launcher activity means closed.
        val launcherCls = HashMap<String, String?>()
        fun launcherOf(pkg: String) = launcherCls.getOrPut(pkg) {
            runCatching { packageManager.getLaunchIntentForPackage(pkg)?.component?.className }.getOrNull()
        }
        val gameSeen = HashSet<String>()
        var loggedClosed = false

        while (running) {
            try { Thread.sleep(poll) } catch (e: InterruptedException) { break }
            if (paused) { lastQuery = System.currentTimeMillis(); continue }
            val now = System.currentTimeMillis()
            val booting = now - launchedAt < bootPhase

            usm?.queryEvents(lastQuery, now + 1000)?.let { events ->
                val e = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    val pkg = e.packageName ?: continue
                    val cls = e.className ?: ""
                    if (pkg in watched || pkg in frontends || pkg == topPkg) {
                        android.util.Log.i(TAG, "event type=${e.eventType} $pkg/$cls " +
                            "eventAt=${e.timeStamp} seenLag=${now - e.timeStamp}ms")
                    }
                    when (e.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> {
                            visible.getOrPut(pkg) { HashSet() }.add(cls)
                            if (cls in TRANSIENT_CLASSES) continue
                            topPkg = pkg; topCls = cls
                            if (booting && pkg !in watched && pkg !in frontends && pkg !in NEVER_ADOPT) {
                                watched.add(pkg)
                                android.util.Log.i(TAG, "adopted $pkg ($cls) as part of $gameName")
                            }
                            if (pkg in watched && cls != launcherOf(pkg)) gameSeen.add(pkg)
                        }
                        UsageEvents.Event.ACTIVITY_STOPPED, EVENT_ACTIVITY_DESTROYED -> visible[pkg]?.remove(cls)
                    }
                }
            }
            lastQuery = now

            // Ground truth every few seconds: a killed game never logs ACTIVITY_STOPPED, so the
            // map above would call it visible forever. Drop packages whose process is gone.
            if (!booting && everSeen && ++probeTick % PROBE_EVERY == 0) {
                val claimed = watched.filter { !visible[it].isNullOrEmpty() || it == topPkg }
                val alive = probe.alive(claimed)
                if (alive != null) for (pkg in claimed) if (pkg !in alive) {
                    android.util.Log.i(TAG, "$pkg has no process any more: treating it as closed")
                    visible.remove(pkg)
                    if (topPkg == pkg) { topPkg = null; topCls = null }
                    gone.add(pkg)
                }
            }

            val watchedVisible = watched.any { !visible[it].isNullOrEmpty() }
            val backAtEmuHome = topPkg in watched && watched.filter { !visible[it].isNullOrEmpty() }.let { vis ->
                vis.isNotEmpty() && vis.all { p -> p in gameSeen && visible[p].orEmpty().all { it == launcherOf(p) } }
            }
            if ((watchedVisible || topPkg in watched) && !backAtEmuHome) { lastSeen = now; everSeen = true }

            val withinLaunchGrace = !everSeen && (now - t0) < launchGrace
            // The bottom-screen launcher never means "back at the frontend".
            val atFrontend = !watchedVisible && topPkg != null && topPkg in frontends &&
                topCls !in BOTTOM_CLASSES
            val absent = now - lastSeen
            // Every watched process is dead: the game was closed, no need to wait out the grace.
            val allGone = gone.isNotEmpty() && !watchedVisible && watched.all { it in gone || visible[it].isNullOrEmpty() } &&
                topPkg !in watched
            val closed = atFrontend || allGone || backAtEmuHome
            if (closed != loggedClosed) {
                loggedClosed = closed
                android.util.Log.i(TAG, "closeState=$closed atFrontend=$atFrontend allGone=$allGone " +
                    "backAtEmuHome=$backAtEmuHome booting=$booting top=$topPkg/$topCls " +
                    "absent=${absent}ms lastSeenAgo=${now - lastSeen}ms")
            }
            val effGrace = if (closed && (!booting || backAtEmuHome)) 2_000L else grace
            if (!withinLaunchGrace && absent > effGrace) {
                android.util.Log.i(TAG, "ending session: closed=$closed absent=${absent}ms grace=${effGrace}ms")
                scope.launch {
                    finishSession(if (closed) "Game closed" else "Backgrounded")
                }
                return
            }
        }
    }

    private suspend fun finishSession(reason: String) {
        running = false
        val t = System.currentTimeMillis()
        fun step(what: String) = android.util.Log.i(TAG, "finish[$reason] $what +${System.currentTimeMillis() - t}ms")
        step("start")
        maybePlay { SoundFx.playFinish() }
        step("sound")
        maybeOverlay("Session ended", "Played ${sessionLength()} · $reason")
        step("overlay")
        closeSessionRow(sessionId, (lastSeen / 1000).coerceAtLeast(1), reason)
        step("row closed")
        probe.close()
        // Rows of this game left open by an earlier double split are not real play: close them empty.
        runCatching { SessionRecovery.closeDuplicates(applicationContext, keepId = sessionId) }
        step("duplicates closed")
        // Best-effort Dropbox backup on session end (fire-and-forget; failure is silent).
        val backupOn = runCatching { prefs.dropboxBackup.first() }.getOrDefault(false)
        if (backupOn) {
            val device = runCatching { prefs.deviceName.first() }
                .getOrDefault(com.maiconerick.xgamelauncher.ui.DeviceProfile.defaultDeviceName())
            runCatching { Dropbox.backupDb(applicationContext, device) }
            step("dropbox backup")
        }
        stopSelfSafely()
        step("stopped")
    }

    private suspend fun closeSessionRow(id: Long, endedUnix: Long, reason: String) {
        if (id <= 0) return
        val existing = db.sessionDao().byId(id) ?: return
        if (!existing.isOpen) return
        val played = (endedUnix - existing.startedUnix).coerceAtLeast(0)
        db.sessionDao().update(
            existing.copy(
                endedUnix = endedUnix,
                timePlayedSeconds = played,
                endReason = reason,
                isOpen = false,
            )
        )
        notifySessionChanged()
    }

    /** Tells xAynButton (the Thor's AYN panel) to refresh its "now playing" card right away. */
    private fun notifySessionChanged() {
        runCatching {
            sendBroadcast(Intent(ACTION_SESSION_CHANGED).setPackage("com.maiconerick.xaynbutton"))
        }
    }

    private fun stopSelfSafely() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun frontendPackages(): Set<String> {
        val set = HashSet<String>()
        set.add(packageName)                        // xGameLauncher itself
        set.add("com.magneticchen.daijishou")
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, 0)
                .forEach { set.add(it.activityInfo.packageName) }
        } catch (_: Exception) {}
        return set
    }

    override fun onDestroy() {
        running = false
        probe.close()
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        displayListener?.let { l ->
            runCatching {
                (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(l)
            }
        }
        displayListener = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "xGL.Tracker"

        const val ACTION_SESSION_CHANGED = "com.maiconerick.xgamelauncher.SESSION_CHANGED"

        /** UsageEvents.Event.ACTIVITY_DESTROYED (hidden constant, API 29+). */
        private const val EVENT_ACTIVITY_DESTROYED = 24

        /** Process check every N polls (2 s each). */
        private const val PROBE_EVERY = 3

        /** Router activities that flash for a frame and hand off to the real screen. */
        private val TRANSIENT_CLASSES = setOf("com.maiconerick.xgamelauncher.HomeRouterActivity")

        /** Activities that live on the Thor's bottom screen, next to a running game. */
        private val BOTTOM_CLASSES = setOf("com.maiconerick.xgamelauncher.bottom.BottomHomeActivity")

        /** System UI that may pop up while a game boots and must never become "the game". */
        private val NEVER_ADOPT = setOf(
            "android", "com.android.systemui", "com.android.settings",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.topjohnwu.magisk", "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )

        private const val EX_SID = "sid"
        private const val EX_PKG = "pkg"
        private const val EX_NAME = "name"
        private const val EX_EMU = "emu"
        private const val EX_PLAT = "plat"
        private const val EX_END = "end"

        fun start(
            ctx: Context, sid: Long, watchPkg: String,
            name: String, emu: String, plat: String,
        ) {
            val i = Intent(ctx, TrackerService::class.java)
                .putExtra(EX_SID, sid).putExtra(EX_PKG, watchPkg)
                .putExtra(EX_NAME, name).putExtra(EX_EMU, emu).putExtra(EX_PLAT, plat)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /** End the live session (from the "END SESSION" chip in Game Detail). */
        fun endNow(ctx: Context) {
            val i = Intent(ctx, TrackerService::class.java).putExtra(EX_END, true)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
