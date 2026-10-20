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

data class SsArt(val boxUrl: String?, val heroUrl: String?, val logoUrl: String?)

/**
 * Fallback art scraper. ScreenScraper's API needs `devid`/`devpassword` (from a registered dev
 * account) plus a per-user `ssid`/`sspassword`. For a personal launcher, keeping this optional
 * is enough — SteamGridDB covers most PC/console games.
 */
class ScreenScraperClient(private val userPass: () -> Pair<String, String>) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    fun hasCreds(): Boolean {
        val (u, p) = userPass()
        return u.isNotBlank() && p.isNotBlank()
    }

    suspend fun lookup(platform: String, fileName: String): SsArt? =
        withContext(Dispatchers.IO) {
            if (!hasCreds()) return@withContext null
            val (u, p) = userPass()
            val url = buildString {
                append("https://www.screenscraper.fr/api2/jeuInfos.php?output=json")
                append("&devid=xgl&devpassword=xgl")
                append("&ssid=").append(java.net.URLEncoder.encode(u, "UTF-8"))
                append("&sspassword=").append(java.net.URLEncoder.encode(p, "UTF-8"))
                append("&romnom=").append(java.net.URLEncoder.encode(fileName, "UTF-8"))
                append("&systemeid=").append(guessSystemId(platform))
            }
            val body = get(url) ?: return@withContext null
            parseMedias(body)
        }

    private fun parseMedias(body: String): SsArt? {
        return try {
            val jeu = json.parseToJsonElement(body).jsonObject["response"]
                ?.jsonObject?.get("jeu")?.jsonObject ?: return null
            val medias = jeu["medias"]?.jsonArray ?: return null
            var box: String? = null; var hero: String? = null; var logo: String? = null
            medias.forEach { m ->
                val o = m.jsonObject
                val type = o["type"]?.jsonPrimitive?.content ?: return@forEach
                val u = o["url"]?.jsonPrimitive?.content ?: return@forEach
                when (type) {
                    "box-2D", "box-3D" -> if (box == null) box = u
                    "fanart", "ss" -> if (hero == null) hero = u
                    "wheel", "wheel-hd" -> if (logo == null) logo = u
                }
            }
            SsArt(box, hero, logo)
        } catch (_: Throwable) { null }
    }

    private fun guessSystemId(platform: String): Int = when (platform.uppercase()) {
        "NDS" -> 15
        "GBA" -> 12
        "GB" -> 9
        "GBC" -> 10
        "GC", "GAMECUBE" -> 13
        "N64" -> 14
        "SNES" -> 4
        "NES" -> 3
        "PS2" -> 58
        "PSX", "PS1" -> 57
        "PSP" -> 61
        "WII" -> 16
        "3DS" -> 17
        else -> 1 // Arcade catch-all; ScreenScraper still often resolves by rom hash
    }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", "xGameLauncher/0.1")
            .build()
        return try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        } catch (_: Throwable) { null }
    }
}
