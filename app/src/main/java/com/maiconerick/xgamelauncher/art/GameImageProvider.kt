package com.maiconerick.xgamelauncher.art

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * One candidate image from a remote source. Compact enough to be listed as a grid tile
 * with a resolution badge; the [url] is what gets saved into the game override.
 */
data class GameImageCandidate(
    val source: String,
    val url: String,
    val width: Int?,
    val height: Int?,
    val gameTitle: String,
) {
    val resolutionLabel: String
        get() = if (width != null && height != null) "${width}x${height}" else "?"
}

/** Which slot in the game override the picker is filling; steers per-source query shape. */
enum class ArtTarget { HERO, BOX }

/**
 * Multi-source image search — ported from xPlayTime's `GameImageProvider`. Each source is
 * queried independently and returns a flat list of candidates. IGDB / RAWG / SteamGridDB
 * are the three sources xGameLauncher has creds for (Bing/Playnite are xPTT-only).
 *
 * Access keys are read every call through lambdas so DataStore updates propagate without
 * re-instantiating.
 */
class GameImageProvider(
    private val igdbCreds: () -> Pair<String, String>,
    private val rawgKey: () -> String,
    private val sgdbKey: () -> String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var igdbToken: String? = null
    @Volatile private var igdbExpiry: Long = 0L

    // ---------- IGDB ----------

    private suspend fun igdbAccessToken(): String? = withContext(Dispatchers.IO) {
        val (id, secret) = igdbCreds()
        if (id.isBlank() || secret.isBlank()) return@withContext null
        val now = System.currentTimeMillis()
        val cached = igdbToken
        if (cached != null && now < igdbExpiry - 60_000L) return@withContext cached

        val url = "https://id.twitch.tv/oauth2/token?client_id=$id" +
            "&client_secret=$secret&grant_type=client_credentials"
        val req = Request.Builder().url(url).post("".toRequestBody(null)).build()
        runCatching {
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) return@use null
                val obj = json.parseToJsonElement(body).jsonObject
                val tok = obj["access_token"]?.jsonPrimitive?.content ?: return@use null
                val exp = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
                igdbToken = tok
                igdbExpiry = now + exp * 1000L
                tok
            }
        }.getOrNull()
    }

    suspend fun searchIgdb(query: String, target: ArtTarget): List<GameImageCandidate> =
        withContext(Dispatchers.IO) {
            val (id, _) = igdbCreds()
            val token = igdbAccessToken() ?: return@withContext emptyList()
            val clean = query.replace("\"", "\\\"")
            // Two passes: `search` (fuzzy) + `where` on alt-name for partials like "lol".
            // For HERO we grab artworks+screenshots (landscape); for BOX we grab cover only.
            val fields = when (target) {
                ArtTarget.HERO -> "name,cover.image_id,cover.width,cover.height," +
                    "artworks.image_id,artworks.width,artworks.height," +
                    "screenshots.image_id,screenshots.width,screenshots.height"
                ArtTarget.BOX -> "name,cover.image_id,cover.width,cover.height"
            }
            val bodies = listOf(
                """search "$clean"; fields $fields; limit 50;""",
                """fields $fields,alternative_names.name;
                   where (alternative_names.name ~ *"$clean"* | name ~ *"$clean"*) & cover != null;
                   limit 50;""".trimIndent(),
            )
            val seen = HashSet<String>()
            val out = ArrayList<GameImageCandidate>()
            for (body in bodies) {
                val req = Request.Builder()
                    .url("https://api.igdb.com/v4/games")
                    .addHeader("Client-ID", id)
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toRequestBody("text/plain".toMediaType()))
                    .build()
                runCatching {
                    http.newCall(req).execute().use { r ->
                        val str = r.body?.string().orEmpty()
                        if (!r.isSuccessful) return@use
                        val arr = json.parseToJsonElement(str).jsonArray
                        for (el in arr) {
                            val o = el.jsonObject
                            val name = o["name"]?.jsonPrimitive?.content ?: continue
                            when (target) {
                                ArtTarget.BOX -> {
                                    val cov = o["cover"]?.jsonObject ?: continue
                                    val imageId = cov["image_id"]?.jsonPrimitive?.content ?: continue
                                    if (!seen.add("cover:$imageId")) continue
                                    val w = cov["width"]?.jsonPrimitive?.content?.toIntOrNull()
                                    val h = cov["height"]?.jsonPrimitive?.content?.toIntOrNull()
                                    out.add(GameImageCandidate(
                                        source = "IGDB",
                                        url = igdbUrl(imageId, "t_cover_big_2x"),
                                        width = w, height = h, gameTitle = name,
                                    ))
                                }
                                ArtTarget.HERO -> {
                                    // Cover as fallback landscape-ish
                                    val cov = o["cover"]?.jsonObject
                                    val coverId = cov?.get("image_id")?.jsonPrimitive?.content
                                    val artworks = o["artworks"]?.jsonArray.orEmpty()
                                    val screenshots = o["screenshots"]?.jsonArray.orEmpty()
                                    for (art in artworks + screenshots) {
                                        val ao = art.jsonObject
                                        val aid = ao["image_id"]?.jsonPrimitive?.content ?: continue
                                        if (!seen.add("art:$aid")) continue
                                        val w = ao["width"]?.jsonPrimitive?.content?.toIntOrNull()
                                        val h = ao["height"]?.jsonPrimitive?.content?.toIntOrNull()
                                        out.add(GameImageCandidate(
                                            source = "IGDB",
                                            url = igdbUrl(aid, "t_1080p"),
                                            width = w, height = h, gameTitle = name,
                                        ))
                                    }
                                    if (coverId != null && seen.add("cover:$coverId")) {
                                        val w = cov["width"]?.jsonPrimitive?.content?.toIntOrNull()
                                        val h = cov["height"]?.jsonPrimitive?.content?.toIntOrNull()
                                        out.add(GameImageCandidate(
                                            source = "IGDB",
                                            url = igdbUrl(coverId, "t_1080p"),
                                            width = w, height = h, gameTitle = name,
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            out
        }

    private fun igdbUrl(imageId: String, size: String): String =
        "https://images.igdb.com/igdb/image/upload/$size/$imageId.jpg"

    // ---------- RAWG ----------

    suspend fun searchRawg(query: String, target: ArtTarget): List<GameImageCandidate> =
        withContext(Dispatchers.IO) {
            val key = rawgKey()
            if (key.isBlank()) return@withContext emptyList()
            val enc = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.rawg.io/api/games?search=$enc&page_size=40&key=$key"
            val req = Request.Builder().url(url).get().build()
            runCatching {
                http.newCall(req).execute().use { r ->
                    val str = r.body?.string().orEmpty()
                    if (!r.isSuccessful) return@use emptyList<GameImageCandidate>()
                    val obj = json.parseToJsonElement(str).jsonObject
                    val results = obj["results"]?.jsonArray ?: return@use emptyList()
                    val out = ArrayList<GameImageCandidate>()
                    for (el in results) {
                        val o = el.jsonObject
                        val name = o["name"]?.jsonPrimitive?.content ?: continue
                        val bg = o["background_image"]?.jsonPrimitive?.content ?: continue
                        // RAWG's `background_image` is landscape (screenshot), fits HERO well
                        // but is the only art it offers, so include for BOX too as fallback.
                        out.add(GameImageCandidate("RAWG", bg, null, null, name))
                    }
                    out
                }
            }.getOrDefault(emptyList())
        }

    // ---------- SteamGridDB ----------

    suspend fun searchSteamGridDb(query: String, target: ArtTarget): List<GameImageCandidate> =
        withContext(Dispatchers.IO) {
            val key = sgdbKey()
            if (key.isBlank()) return@withContext emptyList()
            val ids = sgdbGameIds(query, key)
            if (ids.isEmpty()) return@withContext emptyList()
            val kind = when (target) {
                ArtTarget.HERO -> "heroes"
                ArtTarget.BOX -> "grids"
            }
            val out = ArrayList<GameImageCandidate>()
            for (gameId in ids.take(3)) {
                out.addAll(sgdbAssets(kind, gameId, key, query))
            }
            out
        }

    private fun sgdbGameIds(query: String, key: String): List<Long> {
        val enc = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val req = Request.Builder()
            .url("https://www.steamgriddb.com/api/v2/search/autocomplete/$enc")
            .addHeader("Authorization", "Bearer $key")
            .get().build()
        return runCatching {
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) return@use emptyList<Long>()
                val obj = json.parseToJsonElement(body).jsonObject
                val data = obj["data"]?.jsonArray ?: return@use emptyList()
                data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content?.toLongOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    private fun sgdbAssets(
        kind: String,
        gameId: Long,
        key: String,
        title: String,
    ): List<GameImageCandidate> {
        val req = Request.Builder()
            .url("https://www.steamgriddb.com/api/v2/$kind/game/$gameId?limit=30&nsfw=false")
            .addHeader("Authorization", "Bearer $key")
            .get().build()
        val out = ArrayList<GameImageCandidate>()
        runCatching {
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) return@use
                val obj = json.parseToJsonElement(body).jsonObject
                val data = obj["data"]?.jsonArray ?: return@use
                for (el in data) {
                    val o = el.jsonObject
                    val url = o["url"]?.jsonPrimitive?.content ?: continue
                    val w = o["width"]?.jsonPrimitive?.content?.toIntOrNull()
                    val h = o["height"]?.jsonPrimitive?.content?.toIntOrNull()
                    out.add(GameImageCandidate("SteamGridDB", url, w, h, title))
                }
            }
        }
        return out
    }
}
