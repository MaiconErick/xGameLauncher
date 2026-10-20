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

@kotlinx.serialization.Serializable
data class GameInfo(
    val name: String,
    val summary: String? = null,
    val releaseUnix: Long? = null,
    val genres: List<String> = emptyList(),
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val rating: Double? = null,
    val url: String? = null,
)

data class IgdbHit(
    val id: Long,
    val name: String,
    val cover: String?,           // t_cover_big
    val artworks: List<String>,   // t_1080p
    val screenshots: List<String>,
)

/**
 * IGDB via Twitch client-credentials OAuth. User provides a Twitch Developer Console app's
 * client id + secret (no user login flow needed — server-side app auth). Returns cover +
 * artwork + screenshot URLs, giving a rich "hero" pool that Playnite users are used to.
 */
class IgdbClient(private val creds: () -> Pair<String, String>) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var accessToken: String? = null
    @Volatile private var accessUntil: Long = 0

    fun hasCreds(): Boolean {
        val (id, secret) = creds()
        return id.isNotBlank() && secret.isNotBlank()
    }

    private suspend fun token(): String? = withContext(Dispatchers.IO) {
        val (id, secret) = creds()
        if (id.isBlank() || secret.isBlank()) return@withContext null
        val now = System.currentTimeMillis()
        accessToken?.let { if (now < accessUntil) return@withContext it }
        val url = "https://id.twitch.tv/oauth2/token?" +
            "client_id=$id&client_secret=$secret&grant_type=client_credentials"
        val req = Request.Builder().url(url)
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val body = r.body?.string() ?: return@withContext null
                val o = json.parseToJsonElement(body).jsonObject
                val tok = o["access_token"]?.jsonPrimitive?.content ?: return@withContext null
                val ttl = o["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600
                accessToken = tok
                accessUntil = now + (ttl - 120) * 1000
                tok
            }
        } catch (_: Throwable) { null }
    }

    suspend fun search(name: String): List<IgdbHit> = withContext(Dispatchers.IO) {
        val tok = token() ?: return@withContext emptyList()
        val (id, _) = creds()
        val body =
            """search "${name.replace("\"", "\\\"")}"; fields name, cover.image_id, artworks.image_id, screenshots.image_id; limit 6;"""
        val req = Request.Builder()
            .url("https://api.igdb.com/v4/games")
            .header("Client-ID", id)
            .header("Authorization", "Bearer $tok")
            .header("Accept", "application/json")
            .post(body.toRequestBody("text/plain".toMediaType()))
            .build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext emptyList()
                val txt = r.body?.string() ?: return@withContext emptyList()
                val arr = json.parseToJsonElement(txt).jsonArray
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val gid = o["id"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: return@mapNotNull null
                    val nm = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val coverId = o["cover"]?.jsonObject?.get("image_id")?.jsonPrimitive?.content
                    val artworkIds = o["artworks"]?.jsonArray.orEmpty().mapNotNull {
                        it.jsonObject["image_id"]?.jsonPrimitive?.content
                    }
                    val screenshotIds = o["screenshots"]?.jsonArray.orEmpty().mapNotNull {
                        it.jsonObject["image_id"]?.jsonPrimitive?.content
                    }
                    IgdbHit(
                        id = gid, name = nm,
                        cover = coverId?.let { imageUrl(it, "t_cover_big") },
                        artworks = artworkIds.map { imageUrl(it, "t_1080p") },
                        screenshots = screenshotIds.map { imageUrl(it, "t_1080p") },
                    )
                }
            }
        } catch (_: Throwable) { emptyList() }
    }

    /** Descriptive data for the game screen (best match for [name]); null if not found. */
    suspend fun info(name: String): GameInfo? = withContext(Dispatchers.IO) {
        val tok = token() ?: return@withContext null
        val (id, _) = creds()
        val body = """search "${name.replace("\"", "\\\"")}"; """ +
            "fields name, summary, first_release_date, genres.name, total_rating, url, " +
            "involved_companies.company.name, involved_companies.developer, involved_companies.publisher; limit 1;"
        val req = Request.Builder()
            .url("https://api.igdb.com/v4/games")
            .header("Client-ID", id)
            .header("Authorization", "Bearer $tok")
            .header("Accept", "application/json")
            .post(body.toRequestBody("text/plain".toMediaType()))
            .build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val txt = r.body?.string() ?: return@withContext null
                val o = json.parseToJsonElement(txt).jsonArray.firstOrNull()?.jsonObject ?: return@withContext null
                val companies = o["involved_companies"]?.jsonArray.orEmpty().map { it.jsonObject }
                fun names(flag: String) = companies.filter { it[flag]?.jsonPrimitive?.content == "true" }
                    .mapNotNull { it["company"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
                GameInfo(
                    name = o["name"]?.jsonPrimitive?.content ?: name,
                    summary = o["summary"]?.jsonPrimitive?.content,
                    releaseUnix = o["first_release_date"]?.jsonPrimitive?.content?.toLongOrNull(),
                    genres = o["genres"]?.jsonArray.orEmpty().mapNotNull {
                        it.jsonObject["name"]?.jsonPrimitive?.content
                    },
                    developers = names("developer"),
                    publishers = names("publisher"),
                    rating = o["total_rating"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                    url = o["url"]?.jsonPrimitive?.content,
                )
            }
        } catch (_: Throwable) { null }
    }

    private fun imageUrl(imageId: String, size: String): String =
        "https://images.igdb.com/igdb/image/upload/$size/$imageId.jpg"
}
