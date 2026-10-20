package com.maiconerick.xgamelauncher.art

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-source cache + prefetcher — a xGameLauncher fork of xPTT's PickerPrefetch. Each
 * source (IGDB / RAWG / SGDB) is stored independently so a slow tab doesn't block a fast
 * one, and the queue picker can warm up next-in-queue names in the background.
 *
 * Cache key is `"<name>|<target>|<source>"` — HERO and BOX results are distinct because
 * the provider issues different endpoints for each target.
 */
object PickerPrefetch {
    enum class Source { IGDB, RAWG, SGDB }

    class Entry {
        @Volatile var igdb: List<GameImageCandidate>? = null
        @Volatile var rawg: List<GameImageCandidate>? = null
        @Volatile var sgdb: List<GameImageCandidate>? = null
    }

    private val cache = ConcurrentHashMap<String, Entry>()
    /** App-lifetime scope: prefetches survive the dialog moving on to the next game. */
    private val bg = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var appCtx: android.content.Context? = null

    /** Enables image warm-up (candidate thumbnails downloaded into Coil's cache). */
    fun attach(ctx: android.content.Context) { appCtx = ctx.applicationContext }

    private fun warm(list: List<GameImageCandidate>) {
        val ctx = appCtx ?: return
        val loader = coil.Coil.imageLoader(ctx)
        list.take(12).forEach { c -> loader.enqueue(coil.request.ImageRequest.Builder(ctx).data(c.url).build()) }
    }
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    private fun cacheKey(name: String, target: ArtTarget) = "${name.trim()}|${target.name}"

    fun get(name: String, target: ArtTarget): Entry =
        cache.getOrPut(cacheKey(name, target)) { Entry() }

    fun invalidate(name: String) {
        val n = name.trim()
        cache.keys.removeAll { it.startsWith("$n|") }
    }

    private fun store(name: String, target: ArtTarget, source: Source, list: List<GameImageCandidate>) {
        val e = cache.getOrPut(cacheKey(name, target)) { Entry() }
        when (source) {
            Source.IGDB -> e.igdb = list
            Source.RAWG -> e.rawg = list
            Source.SGDB -> e.sgdb = list
        }
    }

    fun prefetch(
        scope: CoroutineScope,
        provider: GameImageProvider,
        names: List<String>,
        target: ArtTarget,
        sources: Set<Source> = Source.entries.toSet(),
    ) {
        for (raw in names) {
            val name = raw.trim()
            if (name.isBlank()) continue
            val entry = cache.getOrPut(cacheKey(name, target)) { Entry() }
            for (source in sources) {
                val already = when (source) {
                    Source.IGDB -> entry.igdb
                    Source.RAWG -> entry.rawg
                    Source.SGDB -> entry.sgdb
                }
                if (already != null) continue
                val key = "${cacheKey(name, target)}|${source.name}"
                if (inFlight.putIfAbsent(key, true) != null) continue
                bg.launch {
                    try {
                        val list = runCatching {
                            when (source) {
                                Source.IGDB -> provider.searchIgdb(name, target)
                                Source.RAWG -> provider.searchRawg(name, target)
                                Source.SGDB -> provider.searchSteamGridDb(name, target)
                            }
                        }.getOrDefault(emptyList())
                        store(name, target, source, list)
                        warm(list)
                    } finally {
                        inFlight.remove(key)
                    }
                }
            }
        }
    }
}
