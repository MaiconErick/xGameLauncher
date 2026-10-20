package com.maiconerick.xgamelauncher.art

import android.content.Context
import com.maiconerick.xgamelauncher.data.AppDatabase
import com.maiconerick.xgamelauncher.data.ArtRef
import com.maiconerick.xgamelauncher.data.ArtRow
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class ArtRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val sgdb: SteamGridDbClient,
    private val ss: ScreenScraperClient,
    private val rawg: RawgClient? = null,
    private val igdb: IgdbClient? = null,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun artDir(platformId: String): File =
        File(context.filesDir, "art/$platformId").also { it.mkdirs() }

    /**
     * Returns cached art if we have it AND the game's name hasn't drifted since we searched.
     * Otherwise re-fetches (best-effort) and caches under the current name.
     */
    suspend fun get(platform: Platform, game: GameEntry): ArtRef =
        withContext(Dispatchers.IO) {
            val cached = db.artDao().get(game.id)
            val nameChanged = cached != null && cached.sourceName != null &&
                cached.sourceName != game.name
            if (cached != null && !nameChanged && (cached.boxPath != null || cached.missed)) {
                return@withContext ArtRef(game.id, cached.boxPath, cached.heroPath, cached.logoPath)
            }
            fetch(platform, game)
        }

    /** Public: pass-through to SGDB search so a UI picker can present multiple matches. */
    suspend fun searchCandidates(name: String): List<SgdbGameHit> = sgdb.searchGame(name)

    /** Public: fetch (hero, box, logo) URLs for a specific SGDB game id. */
    suspend fun candidateUrls(sgdbGameId: Long): Triple<String?, String?, String?> =
        withContext(Dispatchers.IO) {
            Triple(sgdb.hero(sgdbGameId), sgdb.boxArt(sgdbGameId), sgdb.logo(sgdbGameId))
        }

    private suspend fun fetch(platform: Platform, game: GameEntry): ArtRef {
        var box: String? = null; var hero: String? = null; var logo: String? = null

        if (sgdb.hasKey()) {
            val hit = sgdb.searchGame(game.name).firstOrNull()
            if (hit != null) {
                sgdb.boxArt(hit.id)?.let { box = download(it, artDir(platform.id), "${game.id}.box") }
                sgdb.hero(hit.id)?.let { hero = download(it, artDir(platform.id), "${game.id}.hero") }
                sgdb.logo(hit.id)?.let { logo = download(it, artDir(platform.id), "${game.id}.logo") }
            }
        }

        if (box == null && ss.hasCreds()) {
            val ssArt = ss.lookup(platform.artSearchName, game.fileName)
            if (ssArt != null) {
                if (box == null) box = ssArt.boxUrl?.let {
                    download(it, artDir(platform.id), "${game.id}.box")
                }
                if (hero == null) hero = ssArt.heroUrl?.let {
                    download(it, artDir(platform.id), "${game.id}.hero")
                }
                if (logo == null) logo = ssArt.logoUrl?.let {
                    download(it, artDir(platform.id), "${game.id}.logo")
                }
            }
        }

        db.artDao().upsert(
            ArtRow(
                gameId = game.id,
                boxPath = box, heroPath = hero, logoPath = logo,
                fetchedAt = System.currentTimeMillis(),
                missed = (box == null && hero == null && logo == null),
                sourceName = game.name,
            )
        )
        return ArtRef(game.id, box, hero, logo)
    }

    /** Gather candidate images across every configured source. Used by the ArtPicker. */
    suspend fun candidates(name: String): GameArtCandidates = withContext(Dispatchers.IO) {
        val covers = mutableListOf<ArtCandidate>()
        val heroes = mutableListOf<ArtCandidate>()

        // SGDB
        if (sgdb.hasKey()) {
            runCatching {
                sgdb.searchGame(name).firstOrNull()?.let { hit ->
                    sgdb.boxArt(hit.id)?.let { covers.add(ArtCandidate(it, "sgdb", "cover")) }
                    sgdb.hero(hit.id)?.let { heroes.add(ArtCandidate(it, "sgdb", "hero")) }
                }
            }
        }
        // RAWG
        if (rawg?.hasKey() == true) {
            runCatching {
                rawg.search(name).take(4).forEach { h ->
                    h.cover?.let { covers.add(ArtCandidate(it, "rawg", "cover")) }
                    h.background?.let { heroes.add(ArtCandidate(it, "rawg", "hero")) }
                }
            }
        }
        // IGDB
        if (igdb?.hasCreds() == true) {
            runCatching {
                igdb.search(name).take(4).forEach { h ->
                    h.cover?.let { covers.add(ArtCandidate(it, "igdb", "cover")) }
                    h.artworks.forEach { heroes.add(ArtCandidate(it, "igdb", "hero")) }
                    h.screenshots.take(2).forEach { heroes.add(ArtCandidate(it, "igdb", "hero")) }
                }
            }
        }
        // ScreenScraper — skip in the picker (per-platform system ids, less consistent for freeform search).

        GameArtCandidates(covers.distinctBy { it.url }, heroes.distinctBy { it.url })
    }

    /** Download a chosen URL and persist it as the game's art. */
    suspend fun applyChoice(gameId: String, platformId: String, name: String,
                            cover: String?, hero: String?): Unit = withContext(Dispatchers.IO) {
        val dir = artDir(platformId)
        // An explicit choice replaces the stored image (download() reuses existing files).
        fun fresh(base: String) { dir.listFiles()?.filter { it.name.startsWith("$base.") }?.forEach { it.delete() } }
        val boxPath = cover?.let { fresh("$gameId.box"); download(it, dir, "$gameId.box") }
        val heroPath = hero?.let { fresh("$gameId.hero"); download(it, dir, "$gameId.hero") }
        db.artDao().upsert(
            ArtRow(
                gameId = gameId,
                boxPath = boxPath ?: db.artDao().get(gameId)?.boxPath,
                heroPath = heroPath ?: db.artDao().get(gameId)?.heroPath,
                logoPath = db.artDao().get(gameId)?.logoPath,
                fetchedAt = System.currentTimeMillis(),
                missed = boxPath == null && heroPath == null,
                sourceName = name,
            )
        )
    }

    private fun download(url: String, dir: File, baseName: String): String? {
        val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase().let {
            if (it in setOf("png", "jpg", "jpeg", "webp")) it else "jpg"
        }
        val out = File(dir, "$baseName.$ext")
        if (out.exists() && out.length() > 0L) return out.absolutePath
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "xGameLauncher/0.1").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = r.body ?: return null
                out.outputStream().use { fout -> body.byteStream().copyTo(fout) }
            }
            out.absolutePath
        } catch (_: Throwable) { null }
    }
}
