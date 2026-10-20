package com.maiconerick.xgamelauncher.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import com.maiconerick.xgamelauncher.net.Dropbox
import com.maiconerick.xgamelauncher.prefs.PrefsStore
import com.maiconerick.xgamelauncher.ui.DeviceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Background host for the Tools tab: the folder → Dropbox watcher, clipboard auto-copy from
 * the Pi hub, and ROM downloads. Runs as a low-priority foreground service ONLY while one of
 * those needs it, and stops itself otherwise.
 */
class ToolsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: PrefsStore
    private var observer: FileObserver? = null
    private var observedPath: String? = null
    private val sweepNow = MutableStateFlow(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsStore(this)
        goForeground("Tools running")
        scope.launch { mainLoop() }
        scope.launch { clipboardLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        observer?.stopWatching()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun mainLoop() {
        while (true) {
            val watchOn = prefs.toolFlag(Tool.Watcher.enabledKey, false).first()
            val folder = prefs.toolString(ToolKeys.WATCH_FOLDER, "").first()
            val clipOn = prefs.toolFlag(Tool.Clipboard.enabledKey, false).first() &&
                prefs.toolFlag(ToolKeys.CLIP_AUTOCOPY, false).first()
            val downloading = RomDownloader.hasActive()
            if (!(watchOn && folder.isNotBlank()) && !clipOn && !downloading) { stopSelf(); return }

            val parts = buildList {
                if (downloading) add("downloading ROMs")
                if (watchOn && folder.isNotBlank()) add("watching ${File(folder).name}")
                if (clipOn) add("clipboard sync")
            }
            goForeground(parts.joinToString(" · ").replaceFirstChar { it.uppercase() })

            if (watchOn && folder.isNotBlank()) {
                ensureObserver(folder)
                sweep(folder)
            } else { observer?.stopWatching(); observer = null; observedPath = null }

            // Wake early when the FileObserver saw something; otherwise sweep every 30 s.
            val seen = sweepNow.value
            repeat(30) { if (sweepNow.value != seen) return@repeat; delay(1000) }
        }
    }

    // ---- folder → Dropbox ----

    private fun ensureObserver(path: String) {
        if (observedPath == path && observer != null) return
        observer?.stopWatching()
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        @Suppress("DEPRECATION")
        observer = object : FileObserver(path, mask) {
            override fun onEvent(event: Int, p: String?) { sweepNow.update { it + 1 } }
        }.also { it.startWatching() }
        observedPath = path
    }

    private suspend fun sweep(path: String) {
        val root = File(path)
        if (!root.isDirectory) return
        val dest = prefs.toolString(ToolKeys.WATCH_DEST, ToolKeys.DEFAULT_WATCH_DEST).first().trimEnd('/')
        val delete = prefs.toolFlag(ToolKeys.WATCH_DELETE, true).first()
        root.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") && !it.name.endsWith(".tmp") }
            .toList()
            .forEach { f ->
                // Only upload files that stopped changing (still being written otherwise).
                if (System.currentTimeMillis() - f.lastModified() < 5_000) return@forEach
                val size = f.length(); delay(1500); if (!f.exists() || f.length() != size) return@forEach
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                val ok = Dropbox.uploadFile(f, "$dest/$rel")
                log(if (ok) "✓ ${f.name} → $dest/$rel" + (if (delete) " (deleted locally)" else "") else "✗ ${f.name} upload failed")
                if (ok && delete) f.delete()
            }
    }

    // ---- clipboard auto-copy ----

    private suspend fun clipboardLoop() {
        var lastId = -1L
        val me = DeviceProfile.defaultDeviceName()
        while (true) {
            val on = prefs.toolFlag(Tool.Clipboard.enabledKey, false).first() &&
                prefs.toolFlag(ToolKeys.CLIP_AUTOCOPY, false).first()
            if (on) {
                val hub = prefs.toolString(ToolKeys.CLIP_HUB, ToolKeys.DEFAULT_HUB).first()
                val clips = ClipboardHub.list(hub, limit = 1)
                if (clips != null) {
                    val newest = clips.firstOrNull()
                    // First look just records the current newest clip (don't paste stale history);
                    // an empty hub counts as seen, so the first clip after it still gets copied.
                    if (newest != null && lastId >= 0 && newest.id > lastId && newest.source != me) {
                        withContext(Dispatchers.Main) {
                            val cm = getSystemService(ClipboardManager::class.java)
                            cm?.setPrimaryClip(ClipData.newPlainText("From ${newest.source}", newest.text))
                        }
                    }
                    lastId = newest?.id ?: maxOf(lastId, 0L)
                }
            }
            delay(4_000)
        }
    }

    // ---- notification ----

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Tools", NotificationManager.IMPORTANCE_MIN))
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("xGameLauncher tools")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF, n)
    }

    companion object {
        private const val CHANNEL = "xgl_tools"
        private const val NOTIF = 77

        private val _log = MutableStateFlow<List<String>>(emptyList())
        /** Latest folder-watcher events (newest first) for the tool's settings page. */
        val log: StateFlow<List<String>> = _log.asStateFlow()
        private fun log(line: String) = _log.update { (listOf(line) + it).take(30) }

        fun ensureRunning(ctx: Context) {
            val i = Intent(ctx, ToolsService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
        }

        /** Re-evaluate after a settings change: starts the service if a tool needs it. */
        fun refresh(ctx: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                val p = PrefsStore(ctx)
                val need = (p.toolFlag(Tool.Watcher.enabledKey, false).first() &&
                    p.toolString(ToolKeys.WATCH_FOLDER, "").first().isNotBlank()) ||
                    (p.toolFlag(Tool.Clipboard.enabledKey, false).first() && p.toolFlag(ToolKeys.CLIP_AUTOCOPY, false).first()) ||
                    RomDownloader.hasActive()
                if (need) ensureRunning(ctx)
            }
        }
    }
}
