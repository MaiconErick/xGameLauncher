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

data class SgdbGameHit(val id: Long, val name: String)

class SteamGridDbClient(private val apiKey: () -> String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private val base = "https://www.steamgriddb.com/api/v2"

    fun hasKey(): Boolean = apiKey().isNotBlank()

    suspend fun searchGame(name: String): List<SgdbGameHit> = withContext(Dispatchers.IO) {
        if (!hasKey()) return@withContext emptyList()
        val u = "$base/search/autocomplete/${java.net.URLEncoder.encode(name, "UTF-8")}"
        val body = get(u) ?: return@withContext emptyList()
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return@withContext emptyList()
        data.mapNotNull {
            val o = it.jsonObject
            val id = o["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val nm = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            SgdbGameHit(id, nm)
        }
    }

    suspend fun boxArt(gameId: Long): String? = withContext(Dispatchers.IO) {
        firstUrl("$base/grids/game/$gameId?dimensions=600x900&types=static&nsfw=false")
    }

    suspend fun hero(gameId: Long): String? = withContext(Dispatchers.IO) {
        firstUrl("$base/heroes/game/$gameId?types=static&nsfw=false")
    }

    suspend fun logo(gameId: Long): String? = withContext(Dispatchers.IO) {
        firstUrl("$base/logos/game/$gameId?types=static&nsfw=false")
    }

    private fun firstUrl(url: String): String? {
        if (!hasKey()) return null
        val body = get(url) ?: return null
        return try {
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return null
            data.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
        } catch (_: Throwable) { null }
    }

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey()}")
            .header("User-Agent", "xGameLauncher/0.1")
            .build()
        return try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        } catch (_: Throwable) { null }
    }
}
