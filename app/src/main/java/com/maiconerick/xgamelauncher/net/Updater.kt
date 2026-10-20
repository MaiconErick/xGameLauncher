package com.maiconerick.xgamelauncher.net

import android.content.Context
import com.maiconerick.xgamelauncher.util.RootSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Self-updates from Dropbox. Every release build is published by the Pi to
 * `/xGameLauncher/xGameLauncher.apk` with a `version.json` manifest next to it
 * ({versionCode, versionName, size, sha256, publishedAt, notes}). The APK is downloaded
 * with live progress and installed with root (`pm install -r`), which restarts the app.
 */
object Updater {
    private const val FOLDER = "/xGameLauncher"

    data class Release(val code: Long, val name: String, val size: Long, val sha256: String?, val notes: String?, val publishedAt: String?)

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class UpToDate(val latest: Release) : State()
        data class Available(val latest: Release) : State()
        data class Downloading(val latest: Release, val bytes: Long, val total: Long, val bytesPerSec: Long) : State()
        data class Installing(val latest: Release) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun installedCode(ctx: Context): Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    fun installedName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Fetches the manifest; returns the newer release or null. */
    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        val text = Dropbox.readText("$FOLDER/version.json")
        if (text == null) { _state.value = State.Failed("Couldn't reach Dropbox (no internet or no release published yet)"); return@withContext null }
        val r = runCatching {
            val o = JSONObject(text)
            Release(o.getLong("versionCode"), o.getString("versionName"), o.optLong("size"),
                o.optString("sha256").ifBlank { null }, o.optString("notes").ifBlank { null }, o.optString("publishedAt").ifBlank { null })
        }.getOrNull()
        if (r == null) { _state.value = State.Failed("Bad version.json on Dropbox"); return@withContext null }
        if (r.code > installedCode(ctx)) { _state.value = State.Available(r); r } else { _state.value = State.UpToDate(r); null }
    }

    /** Downloads [r] with progress, verifies it, then installs it with root. */
    suspend fun downloadAndInstall(ctx: Context, r: Release) = withContext(Dispatchers.IO) {
        val out = File(ctx.cacheDir, "update.apk")
        try {
            val c = Dropbox.openDownload("$FOLDER/xGameLauncher.apk") ?: error("Download failed to start")
            val total = c.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: r.size
            var done = 0L
            val t0 = System.currentTimeMillis()
            var lastUi = 0L
            c.inputStream.use { ins ->
                out.outputStream().use { fos ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        fos.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 250) {
                            lastUi = now
                            val secs = ((now - t0) / 1000.0).coerceAtLeast(0.001)
                            _state.value = State.Downloading(r, done, total, (done / secs).toLong())
                        }
                    }
                }
            }
            c.disconnect()
            if (r.size > 0 && out.length() != r.size) error("Download incomplete (${out.length()} of ${r.size} bytes)")
            r.sha256?.let { want ->
                val md = java.security.MessageDigest.getInstance("SHA-256")
                out.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(want, true)) error("Checksum mismatch — download corrupted, try again")
            }
            _state.value = State.Installing(r)
            // Detached so the install survives this process being replaced.
            val tmp = "/data/local/tmp/xgl-update.apk"
            val res = RootSession.exec(
                "cp '${out.absolutePath}' $tmp && chmod 644 $tmp && (setsid sh -c 'pm install -r -d $tmp > /data/local/tmp/xgl-update.log 2>&1; rm -f $tmp' &) && echo started",
            )
            if (res?.contains("started") != true) error("Install needs root (su was denied)")
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun mb(b: Long) = "%.1f MB".format(b / 1e6)
}
