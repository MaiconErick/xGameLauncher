package com.maiconerick.xgamelauncher.art

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class RawgHit(val id: Long, val name: String, val cover: String?, val background: String?)

/**
 * RAWG.io — free tier gives cover + `background_image_additional`. One key needed.
 * Backgrounds are usually beefy screenshots — good hero-art candidates.
 */
class RawgClient(private val apiKey: () -> String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun hasKey(): Boolean = apiKey().isNotBlank()

    suspend fun search(name: String): List<RawgHit> = withContext(Dispatchers.IO) {
        if (!hasKey()) return@withContext emptyList()
        val url = "https://api.rawg.io/api/games?" +
            "search=" + java.net.URLEncoder.encode(name, "UTF-8") +
            "&key=" + apiKey() +
            "&page_size=6"
        val body = get(url) ?: return@withContext emptyList()
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonArray ?: return@withContext emptyList()
        results.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val nm = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val cover = o["background_image"]?.jsonPrimitive?.contentOrNullSafe()
            val bg = o["background_image_additional"]?.jsonPrimitive?.contentOrNullSafe() ?: cover
            RawgHit(id, nm, cover, bg)
        }
    }

    /** Second call — the details endpoint sometimes carries a bigger bg image than search. */
    suspend fun screenshots(gameId: Long): List<String> = withContext(Dispatchers.IO) {
        if (!hasKey()) return@withContext emptyList()
        val url = "https://api.rawg.io/api/games/$gameId/screenshots?key=" + apiKey()
        val body = get(url) ?: return@withContext emptyList()
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonArray ?: return@withContext emptyList()
        results.mapNotNull { it.jsonObject["image"]?.jsonPrimitive?.contentOrNullSafe() }
    }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", "xGameLauncher/0.1").build()
        return try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        } catch (_: Throwable) { null }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { if (this.toString() == "null") null else content }.getOrNull()
