package com.maiconerick.xgamelauncher.art

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * IGDB game info cached per game in filesDir/game_info/<gameId>.json. Fetched once (when
 * the game screen opens and there's no cache); "not found" is cached too so we don't keep
 * hitting the API for games IGDB doesn't know.
 */
object GameInfoRepo {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(ctx: Context, gameId: String) = File(File(ctx.filesDir, "game_info").apply { mkdirs() }, "$gameId.json")

    suspend fun cached(ctx: Context, gameId: String): GameInfo? = withContext(Dispatchers.IO) {
        val f = file(ctx, gameId)
        if (!f.isFile) return@withContext null
        runCatching { json.decodeFromString(GameInfo.serializer(), f.readText()) }.getOrNull()
    }

    suspend fun isCached(ctx: Context, gameId: String): Boolean = withContext(Dispatchers.IO) { file(ctx, gameId).exists() }

    /** Cached info, else fetch from IGDB by [searchName] and cache the result (or the miss). */
    suspend fun get(ctx: Context, igdb: IgdbClient, gameId: String, searchName: String, refresh: Boolean = false): GameInfo? =
        withContext(Dispatchers.IO) {
            val f = file(ctx, gameId)
            if (!refresh && f.exists()) return@withContext cached(ctx, gameId)
            if (!igdb.hasCreds()) return@withContext null
            val info = igdb.info(searchName)
            runCatching { f.writeText(if (info == null) "" else json.encodeToString(GameInfo.serializer(), info)) }
            info
        }
}
