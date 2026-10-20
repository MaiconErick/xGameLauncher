package com.maiconerick.xgamelauncher.net

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Dropbox v2 access via the same refresh-token app xPTT uses — no OAuth flow, no user
 * sign-in. Shares the /PlayTime/DB folder with xPTT so old and new device backups sit
 * side by side and either app can import from the other.
 */
object Dropbox {
    // Supply your own Dropbox app credentials. Real values must never be committed:
    // create an app at https://www.dropbox.com/developers/apps and generate a refresh token.
    private const val APP_KEY = "YOUR_DROPBOX_APP_KEY"
    private const val APP_SECRET = "YOUR_DROPBOX_APP_SECRET"
    private const val REFRESH_TOKEN = "YOUR_DROPBOX_REFRESH_TOKEN"

    private const val REMOTE_FOLDER = "/PlayTime/DB"

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedUntil: Long = 0

    private fun accessToken(): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < cachedUntil) return it }
        return try {
            val body = "grant_type=refresh_token" +
                "&refresh_token=" + URLEncoder.encode(REFRESH_TOKEN, "UTF-8") +
                "&client_id=" + URLEncoder.encode(APP_KEY, "UTF-8") +
                "&client_secret=" + URLEncoder.encode(APP_SECRET, "UTF-8")
            val c = URL("https://api.dropbox.com/oauth2/token").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 12000; c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) { c.disconnect(); return null }
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            c.disconnect()
            val tok = json.getString("access_token")
            val ttl = json.optLong("expires_in", 14400)
            cachedToken = tok
            cachedUntil = now + (ttl - 120) * 1000
            tok
        } catch (_: Exception) { null }
    }

    private fun remotePathFor(device: String) = "$REMOTE_FOLDER/PlayTime_$device.db"

    /**
     * Backup xGameLauncher sessions to Dropbox as an xPTT-format `PlayTime_<device>.db`.
     * We download the current file first (if any), then hand it to [XpttFormatExporter]
     * which rewrites only the `PlayTime` table from Room and preserves xPTT's own tables
     * (`Logs`, `Configs`, `android_metadata`). Finally we upload the translated file back.
     * This keeps every reader on the network (xPTT, the Compose viewer, downstream scripts)
     * happy — they still see a table called `PlayTime` with xPTT's column names.
     */
    fun backupDb(ctx: Context, device: String): Boolean {
        val token = accessToken() ?: return false
        val roomDb = ctx.getDatabasePath("xgl.db")
        if (!roomDb.exists()) return false

        val remotePath = remotePathFor(device)
        val work = File(ctx.cacheDir, "xptt_export_$device.db")
        val upstream = File(ctx.cacheDir, "xptt_upstream_$device.db")
        return try {
            // Best-effort download of the current remote so we can preserve Logs/Configs.
            val gotUpstream = downloadInternal(token, remotePath, upstream)
            XpttFormatExporter.buildOrUpdate(
                ctx = ctx,
                upstream = if (gotUpstream) upstream else null,
                outPath = work,
            )
            upload(token, work, remotePath).also { ok ->
                if (ok) {
                    recordBackup(ctx, System.currentTimeMillis(), null)
                    com.maiconerick.xgamelauncher.data.SessionEdits.clearPendingDeletes(ctx)
                }
                else recordBackup(ctx, null, "upload failed")
            }
        } catch (e: Exception) {
            recordBackup(ctx, null, e.message ?: e.javaClass.simpleName)
            false
        } finally {
            runCatching { work.delete() }
            runCatching { upstream.delete() }
        }
    }

    /** Last backup outcome (plain SharedPreferences: written from the tracker service too). */
    data class BackupStatus(val lastSuccessMs: Long, val lastError: String?, val lastAttemptMs: Long)

    fun backupStatus(ctx: Context): BackupStatus {
        val p = ctx.getSharedPreferences("xgl_dropbox", Context.MODE_PRIVATE)
        return BackupStatus(p.getLong("last_ok", 0L), p.getString("last_err", null), p.getLong("last_try", 0L))
    }

    private fun recordBackup(ctx: Context, okAtMs: Long?, error: String?) {
        val e = ctx.getSharedPreferences("xgl_dropbox", Context.MODE_PRIVATE).edit()
        e.putLong("last_try", System.currentTimeMillis())
        if (okAtMs != null) { e.putLong("last_ok", okAtMs); e.remove("last_err") } else e.putString("last_err", error)
        e.apply()
    }

    /** Opens a streaming download of [remotePath] (caller reads + disconnects). Null on failure. */
    fun openDownload(remotePath: String): HttpURLConnection? {
        val token = accessToken() ?: return null
        val c = URL("https://content.dropboxapi.com/2/files/download").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Dropbox-API-Arg", JSONObject().put("path", remotePath).toString())
        return runCatching { if (c.responseCode in 200..299) c else { c.disconnect(); null } }.getOrNull()
    }

    /** Small text file from Dropbox (e.g. the update manifest). */
    fun readText(remotePath: String): String? = openDownload(remotePath)?.let { c ->
        try { c.inputStream.bufferedReader().readText() } catch (_: Exception) { null } finally { c.disconnect() }
    }

    /** Internal variant of [download] that reuses an already-obtained access token. */
    private fun downloadInternal(token: String, remotePath: String, out: File): Boolean {
        val arg = JSONObject().put("path", remotePath).toString()
        val c = URL("https://content.dropboxapi.com/2/files/download").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Dropbox-API-Arg", arg)
        return try {
            if (c.responseCode !in 200..299) { c.disconnect(); return false }
            out.parentFile?.mkdirs()
            c.inputStream.use { input -> out.outputStream().use { fout -> input.copyTo(fout) } }
            c.disconnect(); true
        } catch (_: Exception) { false }
    }

    private fun upload(token: String, file: File, remotePath: String): Boolean {
        val arg = JSONObject().put("path", remotePath).put("mode", "overwrite").put("mute", true).toString()
        val c = URL("https://content.dropboxapi.com/2/files/upload").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Dropbox-API-Arg", arg)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        file.inputStream().use { input -> c.outputStream.use { out -> input.copyTo(out) } }
        val ok = c.responseCode in 200..299
        c.disconnect()
        return ok
    }

    /**
     * Uploads any local file to [remotePath] (e.g. the Tools folder watcher). Files over
     * ~140 MB go through Dropbox upload sessions in 32 MB chunks. `mode=add` + autorename, so
     * an existing remote file is never overwritten.
     */
    fun uploadFile(file: File, remotePath: String): Boolean {
        val token = accessToken() ?: return false
        val chunk = 32L * 1024 * 1024
        return try {
            if (file.length() <= 140L * 1024 * 1024) {
                val arg = JSONObject().put("path", remotePath).put("mode", "add").put("autorename", true)
                    .put("mute", true).toString()
                post(token, "https://content.dropboxapi.com/2/files/upload", arg) { out ->
                    file.inputStream().use { it.copyTo(out) }
                } != null
            } else {
                val start = post(token, "https://content.dropboxapi.com/2/files/upload_session/start",
                    JSONObject().put("close", false).toString()) { } ?: return false
                val sessionId = JSONObject(start).getString("session_id")
                var offset = 0L
                java.io.RandomAccessFile(file, "r").use { raf ->
                    val buf = ByteArray(1024 * 1024)
                    while (file.length() - offset > chunk) {
                        val arg = JSONObject().put("cursor", JSONObject().put("session_id", sessionId).put("offset", offset))
                            .put("close", false).toString()
                        post(token, "https://content.dropboxapi.com/2/files/upload_session/append_v2", arg) { out ->
                            raf.seek(offset); var left = chunk
                            while (left > 0) { val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
                        } ?: return false
                        offset += chunk
                    }
                    val arg = JSONObject()
                        .put("cursor", JSONObject().put("session_id", sessionId).put("offset", offset))
                        .put("commit", JSONObject().put("path", remotePath).put("mode", "add").put("autorename", true).put("mute", true))
                        .toString()
                    post(token, "https://content.dropboxapi.com/2/files/upload_session/finish", arg) { out ->
                        raf.seek(offset)
                        while (true) { val n = raf.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    } != null
                }
            }
        } catch (_: Exception) { false }
    }

    /** POST with Dropbox-API-Arg and a streamed octet body; returns the response text or null. */
    private fun post(token: String, url: String, arg: String, body: (java.io.OutputStream) -> Unit): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 120000
        c.setChunkedStreamingMode(256 * 1024)
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Dropbox-API-Arg", arg)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.outputStream.use { body(it) }
        return try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
        } finally { c.disconnect() }
    }

    data class RemoteFile(val name: String, val path: String, val size: Long)

    /** List every .db file the folder holds — xPTT's PlayTime_*.db AND our xGameLauncher_*.db. */
    fun listRemoteDbs(): List<RemoteFile> {
        val token = accessToken() ?: return emptyList()
        val arg = JSONObject()
            .put("path", REMOTE_FOLDER)
            .put("recursive", false)
            .put("include_deleted", false)
            .toString()
        val c = URL("https://api.dropboxapi.com/2/files/list_folder").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(arg.toByteArray()) }
        return try {
            if (c.responseCode !in 200..299) { c.disconnect(); return emptyList() }
            val txt = c.inputStream.bufferedReader().readText()
            c.disconnect()
            val root = JSONObject(txt)
            val out = ArrayList<RemoteFile>()
            val entries = root.getJSONArray("entries")
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                if (e.optString(".tag") != "file") continue
                val name = e.getString("name")
                if (!name.endsWith(".db")) continue
                out.add(RemoteFile(name, e.getString("path_lower"), e.optLong("size", 0)))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    /** Download a Dropbox file to disk. */
    fun download(remotePath: String, out: File): Boolean {
        val token = accessToken() ?: return false
        val arg = JSONObject().put("path", remotePath).toString()
        val c = URL("https://content.dropboxapi.com/2/files/download").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Dropbox-API-Arg", arg)
        return try {
            if (c.responseCode !in 200..299) { c.disconnect(); return false }
            out.parentFile?.mkdirs()
            c.inputStream.use { input -> out.outputStream().use { fout -> input.copyTo(fout) } }
            c.disconnect(); true
        } catch (_: Exception) { false }
    }
}
