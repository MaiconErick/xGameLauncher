package com.maiconerick.xgamelauncher.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Client for the Pi clipboard hub (/home/pi/MaiconErick/ClipboardHub, port 8768). */
object ClipboardHub {
    data class Clip(val id: Long, val text: String, val source: String, val ts: Double)

    /** Tries the configured hub, then the Tailscale fallback. */
    private fun <T> withHub(hub: String, block: (String) -> T): T? {
        for (base in listOf(hub.trimEnd('/'), ToolKeys.FALLBACK_HUB).distinct()) {
            runCatching { return block(base) }
        }
        return null
    }

    private fun request(url: String, method: String = "GET", body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 3000; c.readTimeout = 5000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    suspend fun list(hub: String, limit: Int = 50, sinceId: Long = 0): List<Clip>? = withContext(Dispatchers.IO) {
        withHub(hub) { base ->
            val arr = JSONArray(request("$base/clips?limit=$limit&since_id=$sinceId"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Clip(o.getLong("id"), o.getString("text"), o.optString("source"), o.optDouble("ts"))
            }
        }
    }

    suspend fun send(hub: String, text: String, source: String): Boolean = withContext(Dispatchers.IO) {
        withHub(hub) { base -> request("$base/clips", "POST", JSONObject().put("text", text).put("source", source).toString()); true } ?: false
    }

    suspend fun delete(hub: String, id: Long): Boolean = withContext(Dispatchers.IO) {
        withHub(hub) { base -> request("$base/clips/$id", "DELETE"); true } ?: false
    }
}
