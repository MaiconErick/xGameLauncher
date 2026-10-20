package com.maiconerick.xgamelauncher.tools

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.maiconerick.xgamelauncher.data.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * romgi's ROM catalog (github.com/caprado/romgi, db/romdb.db.gz — schema v4), downloaded
 * once into filesDir and queried read-only. Links are direct HTTP (Internet Archive,
 * NoPayStation, MarioCube) or single files inside MiNERVA torrents (cartridge systems);
 * the torrent metadata itself ships in the `torrents` table.
 */
object RomCatalog {
    private const val BASE = "https://raw.githubusercontent.com/caprado/romgi/main/db"
    private const val SUPPORTED_SCHEMA = 4

    data class Status(val installed: Boolean, val version: String?, val entries: Int, val sizeBytes: Long)
    data class Remote(val version: String, val schema: Int, val gzSize: Long, val rawSize: Long, val entries: Int)
    data class Entry(val slug: String, val title: String, val platform: String, val boxart: String?, val regions: String?)
    data class Link(
        val name: String, val type: String, val format: String, val url: String, val filename: String,
        val host: String, val size: Long, val sizeStr: String, val requiresAuth: Boolean,
        val torrentHash: String?, val torrentFileIndex: Int?, val torrentFilePath: String?,
    ) {
        val isTorrent: Boolean get() = torrentHash != null
        val downloadable: Boolean get() = !requiresAuth && (isTorrent || url.startsWith("http"))
    }
    data class RPlatform(val id: String, val brand: String, val name: String)

    private fun dbFile(ctx: Context) = File(File(ctx.filesDir, "romgi").apply { mkdirs() }, "romdb.db")
    private fun versionFile(ctx: Context) = File(File(ctx.filesDir, "romgi"), "version.json")

    @Volatile private var db: SQLiteDatabase? = null

    private fun open(ctx: Context): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        val f = dbFile(ctx)
        if (!f.isFile) return null
        return runCatching {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        }.getOrNull()?.also { db = it }
    }

    fun status(ctx: Context): Status {
        val f = dbFile(ctx)
        if (!f.isFile) return Status(false, null, 0, 0)
        val v = runCatching { JSONObject(versionFile(ctx).readText()) }.getOrNull()
        return Status(true, v?.optString("version"), v?.optInt("entries") ?: 0, f.length())
    }

    suspend fun remote(): Remote? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$BASE/version.json").build()).execute().use { r ->
                val o = JSONObject(r.body!!.string())
                Remote(o.getString("version"), o.optInt("schema_version"), o.optLong("size"),
                    o.optLong("uncompressed_size"), o.optInt("entries"))
            }
        }.getOrNull()
    }

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /**
     * Downloads + gunzips the catalog (≈55 MB → ≈345 MB) into a temp file, then swaps it in.
     * [progress] gets 0..1 (compressed bytes read / total).
     */
    suspend fun download(ctx: Context, progress: (Float) -> Unit): Result<Remote> = withContext(Dispatchers.IO) {
        runCatching {
            val remote = remote() ?: error("Can't reach GitHub")
            if (remote.schema > SUPPORTED_SCHEMA) error("Catalog schema ${remote.schema} is newer than supported ($SUPPORTED_SCHEMA)")
            val free = ctx.filesDir.usableSpace
            if (free < remote.rawSize + 20_000_000) error("Not enough space: need ${remote.rawSize / 1_000_000} MB, have ${free / 1_000_000} MB")
            val tmp = File(dbFile(ctx).parentFile, "romdb.db.part")
            http.newCall(Request.Builder().url("$BASE/romdb.db.gz").build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val body = r.body ?: error("empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: remote.gzSize
                val counting = object : java.io.FilterInputStream(body.byteStream()) {
                    var read = 0L
                    var lastReport = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            read += n
                            if (read - lastReport > 256 * 1024) { lastReport = read; progress((read.toFloat() / total).coerceIn(0f, 1f)) }
                        }
                        return n
                    }
                }
                GZIPInputStream(counting, 64 * 1024).use { gz -> tmp.outputStream().use { gz.copyTo(it, 256 * 1024) } }
            }
            db?.close(); db = null
            val dest = dbFile(ctx)
            dest.delete()
            if (!tmp.renameTo(dest)) error("Couldn't move the catalog into place")
            versionFile(ctx).writeText(
                JSONObject().put("version", remote.version).put("entries", remote.entries).toString()
            )
            progress(1f)
            remote
        }
    }

    fun delete(ctx: Context) {
        db?.close(); db = null
        dbFile(ctx).delete(); versionFile(ctx).delete()
    }

    fun platforms(ctx: Context): List<RPlatform> {
        val d = open(ctx) ?: return emptyList()
        return d.rawQuery("SELECT id, brand, name FROM platforms ORDER BY brand, name", null).use { c ->
            buildList { while (c.moveToNext()) add(RPlatform(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: c.getString(0))) }
        }
    }

    /** Title search (every word must match), optional platform filter; 60 per page. */
    suspend fun search(ctx: Context, query: String, platform: String?, page: Int = 0): List<Entry> = withContext(Dispatchers.IO) {
        val d = open(ctx) ?: return@withContext emptyList()
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        query.lowercase().split(Regex("\\s+")).map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }.filter { it.isNotEmpty() }.forEach {
            where += "LOWER(e.title) LIKE ?"; args += "%$it%"
        }
        if (!platform.isNullOrBlank()) { where += "e.platform = ?"; args += platform }
        val sql = """
            SELECT e.slug, e.title, e.platform, e.boxart_url, GROUP_CONCAT(DISTINCT r.name)
            FROM entries e
            LEFT JOIN regions_entries re ON re.entry = e.slug
            LEFT JOIN regions r ON r.id = re.region
            ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")}
            GROUP BY e.slug ORDER BY e.title LIMIT 60 OFFSET ${page * 60}
        """.trimIndent()
        d.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))) }
        }
    }

    suspend fun entry(ctx: Context, slug: String): Entry? = withContext(Dispatchers.IO) {
        val d = open(ctx) ?: return@withContext null
        d.rawQuery(
            """SELECT e.slug, e.title, e.platform, e.boxart_url, GROUP_CONCAT(DISTINCT r.name)
               FROM entries e LEFT JOIN regions_entries re ON re.entry = e.slug
               LEFT JOIN regions r ON r.id = re.region WHERE e.slug = ? GROUP BY e.slug""",
            arrayOf(slug),
        ).use { c -> if (c.moveToFirst()) Entry(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)) else null }
    }

    suspend fun links(ctx: Context, slug: String): List<Link> = withContext(Dispatchers.IO) {
        val d = open(ctx) ?: return@withContext emptyList()
        d.rawQuery(
            "SELECT name, type, format, url, filename, host, size, size_str, requires_auth, " +
                "torrent_infohash, torrent_file_index, torrent_file_path FROM links WHERE entry = ?",
            arrayOf(slug),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Link(
                        name = c.getString(0) ?: "", type = c.getString(1) ?: "Game", format = c.getString(2) ?: "",
                        url = c.getString(3) ?: "", filename = c.getString(4) ?: "", host = c.getString(5) ?: "",
                        size = c.getLong(6), sizeStr = c.getString(7) ?: "", requiresAuth = c.getInt(8) != 0,
                        torrentHash = c.getString(9), torrentFileIndex = if (c.isNull(10)) null else c.getInt(10),
                        torrentFilePath = c.getString(11),
                    )
                )
            }
        }
    }

    /** Public trackers romgi bundles into magnets (HTTPS first: some networks block UDP). */
    private val PUBLIC_TRACKERS = listOf(
        "https://tracker.gbitt.info:443/announce", "https://tracker.nanoha.org:443/announce",
        "https://opentracker.i2p.rocks:443/announce", "https://1337.abcvg.info:443/announce",
        "http://tracker.openbittorrent.com:80/announce", "http://tracker.opentrackr.org:1337/announce",
        "udp://tracker.opentrackr.org:1337/announce", "udp://open.demonii.com:1337/announce",
        "udp://exodus.desync.com:6969/announce", "udp://explodie.org:6969/announce",
        "udp://opentracker.io:6969/announce", "udp://tracker.torrent.eu.org:451/announce",
        "udp://bt1.archive.org:6969/announce", "udp://open.stealth.si:80/announce",
    )

    /**
     * Magnet for an info-hash. The catalog's `torrents` table holds the magnet / trackers
     * (not the .torrent itself), so the metadata is fetched from peers like romgi does.
     */
    fun magnetFor(ctx: Context, infohash: String): String {
        var stored: String? = null
        var trackers: List<String> = emptyList()
        open(ctx)?.let { d ->
            runCatching {
                d.rawQuery("SELECT * FROM torrents WHERE infohash = ?", arrayOf(infohash)).use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex("magnet").takeIf { it >= 0 && !c.isNull(it) }?.let { stored = c.getString(it) }
                        c.getColumnIndex("trackers_json").takeIf { it >= 0 && !c.isNull(it) }?.let { i ->
                            trackers = runCatching {
                                val a = org.json.JSONArray(c.getString(i)); (0 until a.length()).map { a.getString(it) }
                            }.getOrDefault(emptyList())
                        }
                    }
                }
            }
        }
        stored?.takeIf { it.startsWith("magnet:") }?.let { return it }
        val tr = (trackers.ifEmpty { PUBLIC_TRACKERS }).joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
        return "magnet:?xt=urn:btih:$infohash$tr"
    }

    /** Where fetched .torrent metadata is cached (so a retry doesn't ask peers again). */
    fun torrentCache(ctx: Context, infohash: String) =
        File(File(ctx.filesDir, "romgi/torrents").apply { mkdirs() }, "$infohash.torrent")

    /** Our platform → romgi platform id, by folder name / Daijishō short name / display name. */
    private val ALIASES = mapOf(
        "psx" to "ps1", "playstation" to "ps1", "genesis" to "smd", "megadrive" to "smd", "md" to "smd",
        "gamecube" to "gc", "ngc" to "gc", "saturn" to "sat", "dreamcast" to "dc", "segacd" to "scd",
        "mastersystem" to "sms", "gamegear" to "gg", "pcengine" to "tg16", "tg16" to "tg16", "pce" to "tg16",
        "arcade" to "fbneo", "neogeocd" to "ngcd", "atari2600" to "a26", "atari7800" to "a78", "atari5200" to "a52",
        "lynx" to "lynx", "virtualboy" to "vb", "psvita" to "psv", "vita" to "psv", "xbox360" to "x360",
    )

    fun romgiPlatformFor(ctx: Context, p: Platform): String? {
        val ids = platforms(ctx).map { it.id }.toSet()
        if (ids.isEmpty()) return null
        val folder = p.romsPath.trimEnd('/').substringAfterLast('/').lowercase()
        val name = p.name.lowercase().replace(Regex("[^a-z0-9]"), "")
        listOf(folder, name).forEach { k ->
            if (k in ids) return k
            ALIASES[k]?.let { if (it in ids) return it }
        }
        return platforms(ctx).firstOrNull { it.name.lowercase().replace(Regex("[^a-z0-9]"), "") == name }?.id
    }
}
