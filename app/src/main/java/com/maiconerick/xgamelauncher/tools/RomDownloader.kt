package com.maiconerick.xgamelauncher.tools

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Download queue for the ROM downloader (runs inside [ToolsService] so it survives leaving
 * the app). One job at a time:
 *  · HTTP links stream to "<file>.part" and resume with a Range request.
 *  · Torrent links (MiNERVA sets) add the catalog's embedded .torrent with every file
 *    IGNOREd except the one wanted, then move that file into the platform folder.
 * `.zip` downloads are extracted into the folder when the ROM tool's option is on.
 */
object RomDownloader {

    enum class State { QUEUED, DOWNLOADING, EXTRACTING, DONE, FAILED }

    data class Job(
        val id: Long,
        val title: String,
        val platformLabel: String,
        val destDir: String,
        val link: RomCatalog.Link,
        val state: State = State.QUEUED,
        val progress: Float = 0f,
        val bytes: Long = 0,
        val total: Long = link.size,
        val detail: String = "",
    )

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    /** Bumps whenever a download finishes, so the library can rescan. */
    private val _completed = MutableStateFlow(0)
    val completed: StateFlow<Int> = _completed.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runLock = Mutex()
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private var session: SessionManager? = null

    fun enqueue(ctx: Context, title: String, platformLabel: String, destDir: String, link: RomCatalog.Link) {
        val job = Job(System.nanoTime(), title, platformLabel, destDir, link)
        _jobs.update { it + job }
        ToolsService.ensureRunning(ctx)
        scope.launch { runQueue(ctx.applicationContext) }
    }

    fun clearFinished() = _jobs.update { l -> l.filter { it.state != State.DONE && it.state != State.FAILED } }

    fun hasActive(): Boolean = _jobs.value.any { it.state == State.QUEUED || it.state == State.DOWNLOADING || it.state == State.EXTRACTING }

    private fun set(id: Long, f: (Job) -> Job) = _jobs.update { l -> l.map { if (it.id == id) f(it) else it } }

    private suspend fun runQueue(ctx: Context) = runLock.withLock {
        while (true) {
            val next = _jobs.value.firstOrNull { it.state == State.QUEUED } ?: break
            set(next.id) { it.copy(state = State.DOWNLOADING, detail = "Starting…") }
            val result = runCatching {
                val file = if (next.link.isTorrent) torrent(ctx, next) else httpGet(next)
                val extract = runCatching { ToolsPrefsCache.extract(ctx).first() }.getOrDefault(true)
                if (extract && file.name.endsWith(".zip", ignoreCase = true)) {
                    set(next.id) { it.copy(state = State.EXTRACTING, detail = "Extracting…") }
                    unzip(file, File(next.destDir)); file.delete()
                }
            }
            result.onSuccess {
                set(next.id) { it.copy(state = State.DONE, progress = 1f, detail = "Saved to ${next.destDir}") }
                _completed.update { it + 1 }
            }.onFailure { e ->
                set(next.id) { it.copy(state = State.FAILED, detail = e.message ?: e.javaClass.simpleName) }
            }
        }
        stopSessionIfIdle()
        ToolsService.refresh(ctx)
    }

    // ---- HTTP ----

    private fun httpGet(job: Job): File {
        val dir = File(job.destDir).apply { mkdirs() }
        val name = job.link.filename.ifBlank { job.link.url.substringAfterLast('/').substringBefore('?') }
            .let { java.net.URLDecoder.decode(it, "UTF-8") }
        val out = File(dir, name)
        val part = File(dir, "$name.part")
        val have = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(job.link.url).header("User-Agent", "xGameLauncher")
            .apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val resumed = r.code == 206
            val body = r.body ?: error("empty body")
            val total = (if (resumed) have else 0L) + body.contentLength().coerceAtLeast(0)
            RandomAccessFile(part, "rw").use { raf ->
                if (resumed) raf.seek(have) else raf.setLength(0)
                var done = if (resumed) have else 0L
                val buf = ByteArray(128 * 1024)
                var lastUi = 0L
                body.byteStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 500) {
                            lastUi = now
                            set(job.id) { it.copy(bytes = done, total = total, progress = if (total > 0) done.toFloat() / total else 0f,
                                detail = "${mb(done)} / ${mb(total)}") }
                        }
                    }
                }
            }
        }
        out.delete()
        if (!part.renameTo(out)) error("Couldn't finalize $name")
        return out
    }

    // ---- torrent (single file out of a big set) ----

    private suspend fun torrent(ctx: Context, job: Job): File {
        val hash = job.link.torrentHash ?: error("no torrent hash")
        val idx = job.link.torrentFileIndex ?: error("no torrent file index")
        val s = session ?: SessionManager().also { it.start(); session = it }
        val cache = RomCatalog.torrentCache(ctx, hash)
        val blob = cache.takeIf { it.isFile && it.length() > 0 }?.readBytes() ?: run {
            set(job.id) { it.copy(detail = "Finding peers for the torrent's file list…") }
            val magnet = RomCatalog.magnetFor(ctx, hash)
            val tmp = File(ctx.cacheDir, "magnet").apply { mkdirs() }
            val bytes = kotlinx.coroutines.withContext(Dispatchers.IO) { s.fetchMagnet(magnet, 180, tmp) }
                ?: error("Couldn't get the torrent's file list from peers (timed out after 3 min) — try again later")
            cache.writeBytes(bytes)
            bytes
        }
        val ti = TorrentInfo(blob)
        if (idx !in 0 until ti.numFiles()) error("file index out of range")
        val dest = File(job.destDir).apply { mkdirs() }
        val work = File(dest, ".xgl-torrent").apply { mkdirs() }
        val prio = Array(ti.numFiles()) { if (it == idx) Priority.DEFAULT else Priority.IGNORE }
        s.download(ti, work, null, prio, null, org.libtorrent4j.TorrentFlags.AUTO_MANAGED)
        val size = ti.files().fileSize(idx)
        var noPeersSince = System.currentTimeMillis()
        while (true) {
            delay(1000)
            val th = s.find(ti.infoHash()) ?: error("torrent vanished")
            val got = th.fileProgress().getOrNull(idx) ?: 0L
            val st = th.status()
            if (st.numPeers() > 0) noPeersSince = System.currentTimeMillis()
            set(job.id) {
                it.copy(bytes = got, total = size, progress = if (size > 0) got.toFloat() / size else 0f,
                    detail = "${mb(got)} / ${mb(size)} · ${st.numPeers()} peers · ${st.downloadPayloadRate() / 1024} KB/s")
            }
            if (got >= size) {
                // Let libtorrent flush the piece to disk, then move the file out.
                delay(1500)
                val src = File(ti.files().filePath(idx, work.absolutePath))
                val out = File(dest, File(job.link.torrentFilePath ?: src.name).name)
                out.delete()
                if (!src.renameTo(out)) { src.copyTo(out, overwrite = true); src.delete() }
                s.remove(th, SessionHandle.DELETE_FILES)
                work.deleteRecursively()
                return out
            }
            if (System.currentTimeMillis() - noPeersSince > 5 * 60_000) {
                s.remove(th, SessionHandle.DELETE_FILES)
                error("No peers for 5 minutes — nobody is seeding this file right now")
            }
        }
    }

    private fun stopSessionIfIdle() {
        if (!hasActive()) { runCatching { session?.stop() }; session = null }
    }

    private fun unzip(zip: File, dir: File) {
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(dir, e.name)
                if (!out.canonicalPath.startsWith(dir.canonicalPath)) continue  // zip-slip guard
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
    }

    private fun mb(b: Long) = if (b >= 1_000_000_000) "%.2f GB".format(b / 1e9) else "%.1f MB".format(b / 1e6)
}

/** Tiny bridge so the downloader can read the "extract zips" option without a PrefsStore ref. */
internal object ToolsPrefsCache {
    fun extract(ctx: Context) = com.maiconerick.xgamelauncher.prefs.PrefsStore(ctx).toolFlag(ToolKeys.ROM_EXTRACT, true)
}
