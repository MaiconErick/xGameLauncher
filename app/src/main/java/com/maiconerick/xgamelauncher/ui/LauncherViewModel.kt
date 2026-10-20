package com.maiconerick.xgamelauncher.ui

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.withLock
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maiconerick.xgamelauncher.XGameLauncherApp
import com.maiconerick.xgamelauncher.android.AndroidAppSource
import com.maiconerick.xgamelauncher.windows.WindowsSource
import com.maiconerick.xgamelauncher.daijisho.DaijishoReader
import com.maiconerick.xgamelauncher.data.AndroidGameRow
import com.maiconerick.xgamelauncher.data.ArtRef
import com.maiconerick.xgamelauncher.data.DiscoveredEmulator
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.GameOverrideRow
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.data.PlatformRepo
import com.maiconerick.xgamelauncher.data.PlaytimeStats
import com.maiconerick.xgamelauncher.playtime.PlaytimeReader
import com.maiconerick.xgamelauncher.rom.RomScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryState(
    val platforms: List<Platform> = emptyList(),
    val gamesByPlatform: Map<String, List<GameEntry>> = emptyMap(),
    val artByGameId: Map<String, ArtRef> = emptyMap(),
    val androidRowByGameId: Map<String, AndroidGameRow> = emptyMap(),
    val overrideByGameId: Map<String, GameOverrideRow> = emptyMap(),
    val discoveredEmulators: List<DiscoveredEmulator> = emptyList(),
    /** Currently-open (still-running) tracked sessions, keyed by (gameName, platform). */
    val openSessionsKey: Set<String> = emptySet(),
    val warnings: List<String> = emptyList(),
    val loading: Boolean = true,
)

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = getApplication<XGameLauncherApp>()
    private val db get() = appCtx.appDb
    private val prefs get() = appCtx.prefs
    private val artRepo get() = appCtx.artRepo

    private var playtimeReader: PlaytimeReader? = null

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val _playtime = MutableStateFlow<Map<String, PlaytimeStats>>(emptyMap())
    val playtime: StateFlow<Map<String, PlaytimeStats>> = _playtime.asStateFlow()

    /** Game IDs whose art is currently being fetched (SGDB + ScreenScraper + download). */
    private val _artFetching = MutableStateFlow<Set<String>>(emptySet())
    val artFetching: StateFlow<Set<String>> = _artFetching.asStateFlow()

    /** Fires ONCE per refresh when a diff against the previous refresh finds newly-added
     *  games that don't yet have art or an override. Main activity consumes this to open
     *  the art picker automatically — restores the "prompt for new games" UX. */
    private val _newlyAddedForArt = kotlinx.coroutines.flow.MutableSharedFlow<List<String>>(
        replay = 0, extraBufferCapacity = 4,
    )
    val newlyAddedForArt: kotlinx.coroutines.flow.SharedFlow<List<String>> =
        _newlyAddedForArt.asSharedFlow()
    private var previousGameIds: Set<String>? = null

    /** Newly found ROM games whose cleaned names should be reviewed (platform added / sync). */
    private val _newlyAddedForReview = kotlinx.coroutines.flow.MutableSharedFlow<Pair<List<String>, List<String>>>(
        replay = 0, extraBufferCapacity = 4,
    )
    val newlyAddedForReview: kotlinx.coroutines.flow.SharedFlow<Pair<List<String>, List<String>>> =
        _newlyAddedForReview.asSharedFlow()

    /** Saves several display names at once (name review), then one refresh. */
    fun renameGames(names: Map<String, String>, clearArt: Boolean = true) {
        if (names.isEmpty()) return
        viewModelScope.launch {
            names.forEach { (id, n) ->
                val prev = db.gameOverrideDao().get(id)
                db.gameOverrideDao().upsert((prev ?: GameOverrideRow(gameId = id)).copy(displayNameOverride = n))
                if (clearArt) db.artDao().clear(id)
            }
            LibraryBus.bump()
        }
    }

    init {
        refresh()
        // Another screen's view model changed the library → reload here too.
        viewModelScope.launch { LibraryBus.tick.drop(1).collect { refresh() } }
        // Sessions end in TrackerService, possibly after this screen's last refresh: follow the table.
        viewModelScope.launch {
            db.sessionDao().openSessionsFlow()
                .map { rows -> rows.map { PlaytimeReader.keyFor(it.gameName, it.platform) }.toSet() }
                .distinctUntilChanged().drop(1)
                .collect { open ->
                    _state.value = _state.value.copy(openSessionsKey = open)
                    _playtime.value = computePlaytime()
                }
        }
        // Apps drawer "Games" tab → Android platform (checked on every change of the tab).
        viewModelScope.launch {
            com.maiconerick.xgamelauncher.ui.apptabs.AppTabsStore(prefs).flow
                .map { gamesTabPackages(it) }.distinctUntilChanged().drop(1)
                .collect { LibraryBus.bump() }
        }
    }

    /**
     * Playtime totals: xPTT sqlite + internal Room sessions merged. If the user has already
     * imported xPTT into Room, skipXpttRead avoids double-counting.
     */
    private suspend fun computePlaytime(): Map<String, PlaytimeStats> {
        val skipXptt = prefs.skipXpttRead.first()
        val xpttPath = prefs.xpttDbPath.first()
        val xpttStats: Map<String, PlaytimeStats> = if (skipXptt) emptyMap()
        else {
            playtimeReader = PlaytimeReader(appCtx, xpttPath).also { pr -> pr.refresh() }
            playtimeReader?.stats?.value.orEmpty()
        }
        val internalStats = withContext(Dispatchers.IO) {
            val agg = HashMap<String, LongArray>() // [totalSec, lastEnded, sessionCount]
            db.sessionDao().all().forEach { s ->
                val secs = s.timePlayedSeconds
                    ?: (if (s.endedUnix != null) s.endedUnix!! - s.startedUnix else 0L)
                        .coerceAtLeast(0)
                val ended = s.endedUnix ?: s.startedUnix
                val key = PlaytimeReader.keyFor(s.gameName, s.platform)
                val cur = agg.getOrPut(key) { longArrayOf(0L, 0L, 0L) }
                cur[0] += secs
                if (ended > cur[1]) cur[1] = ended
                cur[2] += 1
            }
            agg.mapValues { (_, v) -> PlaytimeStats(v[0], v[1], v[2].toInt()) }
        }
        return (xpttStats.keys + internalStats.keys).associateWith { k ->
            val a = xpttStats[k]; val b = internalStats[k]
            when {
                a != null && b != null -> PlaytimeStats(
                    totalSeconds = a.totalSeconds + b.totalSeconds,
                    lastPlayedUnix = maxOf(a.lastPlayedUnix, b.lastPlayedUnix),
                    sessionCount = a.sessionCount + b.sessionCount,
                )
                a != null -> a
                else -> b!!
            }
        }
    }

    /** Cheap reload of just the totals + open-session keys (sessions end while the library is loaded). */
    fun refreshPlaytime() {
        viewModelScope.launch {
            val pt = computePlaytime()
            val open = withContext(Dispatchers.IO) {
                db.sessionDao().openSessions().map { PlaytimeReader.keyFor(it.gameName, it.platform) }.toSet()
            }
            _playtime.value = pt
            _state.value = _state.value.copy(openSessionsKey = open)
        }
    }

    /** Packages in the Apps drawer's tab named "Games" (any case). */
    private fun gamesTabPackages(d: com.maiconerick.xgamelauncher.ui.apptabs.AppTabsData): Set<String> =
        d.tabs.filter { it.name.trim().equals("games", ignoreCase = true) }.flatMap { d.packagesIn(it.id) }.toSet()

    /** Adds every app in the drawer's Games tab to the Android platform (add-only). */
    private suspend fun syncGamesTab() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val pkgs = gamesTabPackages(com.maiconerick.xgamelauncher.ui.apptabs.AppTabsStore(prefs).flow.first())
        if (pkgs.isEmpty()) return@withContext
        val have = db.androidGameDao().all().map { it.pkg }.toSet()
        val installed = AndroidAppSource.listInstalled(appCtx).associateBy { it.pkg }
        (pkgs - have).forEach { pkg ->
            val app = installed[pkg] ?: return@forEach
            db.androidGameDao().upsert(AndroidGameRow(pkg, app.label, app.launchComponent, System.currentTimeMillis()))
        }
    }

    fun refresh(forceDaijisho: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, warnings = emptyList())
            val warnings = ArrayList<String>()
            runCatching { syncGamesTab() }

            // 1. Daijishō platforms (root, best-effort) — cached per process, off the main
            //    thread; only the Sync button re-snapshots the db.
            val djr = DaijishoReader.readPlatformsCached(appCtx, force = forceDaijisho)
            warnings.addAll(djr.warnings)
            val hidden = prefs.hiddenPlatformIds.first()
            val platforms = ArrayList(djr.platforms)

            // 2. Windows platform — seeded on first run with the three default forwarder folders,
            //    then always editable from Settings.
            if (WindowsSource.PLATFORM_ID !in hidden && PlatformRepo.get(db, WindowsSource.PLATFORM_ID) == null) {
                PlatformRepo.upsert(db, WindowsSource.defaultPlatform(), sortOrder = -100)
            }

            // 3. User-added custom platforms (includes the seeded Windows one)
            // Repair platforms saved with the bare ROMS root (older editor default): point
            // them at their own folder when one matches the name, else they scan nothing.
            val userPlatforms = PlatformRepo.list(db).map { p ->
                val synthetic = p.id == WindowsSource.PLATFORM_ID || p.id == AndroidAppSource.PLATFORM_ID
                val folders = com.maiconerick.xgamelauncher.rom.RomFolders
                // Windows still on the old Download/bannerhub + winlator defaults → the
                // MaiconErick/Windows root (BannerHub/, cronyx/, … subfolders are scanned).
                if (p.id == WindowsSource.PLATFORM_ID &&
                    p.allPaths().filter { it.isNotBlank() }.all { it.trimEnd('/') in WindowsSource.LEGACY_PATHS }) {
                    java.io.File(WindowsSource.DEFAULT_PRIMARY_PATH).mkdirs()
                    PlatformRepo.updatePaths(db, p.id, WindowsSource.DEFAULT_PRIMARY_PATH, WindowsSource.DEFAULT_EXTRA_PATHS)
                    p.copy(romsPath = WindowsSource.DEFAULT_PRIMARY_PATH, extraPaths = WindowsSource.DEFAULT_EXTRA_PATHS)
                } else if (p.id == WindowsSource.PLATFORM_ID && p.allPaths().none { it.trimEnd('/') == WindowsSource.FORWARDERS_ROOT }) {
                    // xCronyx forwarders moved to MaiconErick/Forwarders: scan it, drop the old Download/Winlator/Frontend folder
                    java.io.File(WindowsSource.FORWARDERS_ROOT).mkdirs()
                    val extras = (p.extraPaths.filterNot { it.trimEnd('/').equals("/storage/emulated/0/Download/Winlator/Frontend", true) } +
                        WindowsSource.FORWARDERS_ROOT).distinct()
                    PlatformRepo.updatePaths(db, p.id, p.romsPath, extras)
                    p.copy(extraPaths = extras)
                } else if (!synthetic && (p.allPaths().any { folders.migrate(it) != it })) {
                    // Folder under the old /storage/emulated/0/ROMS root → same folder under MaiconErick/ROMS.
                    val moved = p.copy(romsPath = folders.migrate(p.romsPath), extraPaths = p.extraPaths.map(folders::migrate))
                    PlatformRepo.updatePaths(db, p.id, moved.romsPath, moved.extraPaths)
                    moved
                } else if (synthetic || !folders.isRootLike(p.romsPath)) p
                else {
                    val fixed = com.maiconerick.xgamelauncher.rom.RomFolders.suggest(appCtx, p.name)
                    if (fixed == null) {
                        warnings.add("Platform '${p.name}' points at the ROMS root — pick its folder in the editor.")
                        p
                    } else p.copy(romsPath = fixed).also { PlatformRepo.upsert(db, it, 0) }
                }
            }
            val userIds = userPlatforms.map { it.id }.toSet()
            platforms.removeAll { it.id in userIds }
            platforms.addAll(userPlatforms)
            platforms.removeAll { it.id in hidden }

            // 4. Android apps synthetic platform. Only insert the synthetic default when
            //    the user hasn't already saved their own row for it in platform_meta —
            //    otherwise we'd end up with two Android platforms (user's edited copy +
            //    a fresh synthetic), which is what caused the duplication bug.
            val androidCount = db.androidGameDao().count()
            val hasCustomAndroid = platforms.any { it.id == AndroidAppSource.PLATFORM_ID }
            if (androidCount > 0 && !hasCustomAndroid && AndroidAppSource.PLATFORM_ID !in hidden) {
                platforms.add(AndroidAppSource.platform())
            }

            // 5. Load overrides (used to apply display names + intent picking)
            val overridesList = db.gameOverrideDao().let { dao ->
                // no all() method — read every row by iterating known game ids after the scan
                emptyList<GameOverrideRow>() // filled below after we know game ids
            }

            // 6. Scan each platform
            val gamesByPlatform = HashMap<String, List<GameEntry>>()
            val resolvedPlatforms = platforms.map { p ->
                val games = when (p.id) {
                    WindowsSource.PLATFORM_ID -> WindowsSource.scan(appCtx, db, p)
                    AndroidAppSource.PLATFORM_ID -> AndroidAppSource.scan(db)
                    else -> RomScanner.scan(db, p)
                }
                gamesByPlatform[p.id] = games
                p.copy(gameCount = games.size)
            }.filter { it.gameCount > 0 }

            // 7. Load overrides for every known game id
            val allGameIds = gamesByPlatform.values.flatMap { it.map(GameEntry::id) }
            val overrideByGameId = withContext(Dispatchers.IO) {
                val map = HashMap<String, GameOverrideRow>()
                allGameIds.forEach { id ->
                    db.gameOverrideDao().get(id)?.let { map[id] = it }
                }
                map
            }

            // 8. Apply displayNameOverride to GameEntry.name
            val gamesByPlatformFinal = gamesByPlatform.mapValues { (_, list) ->
                list.map { g ->
                    val displayName = overrideByGameId[g.id]?.displayNameOverride?.takeIf { it.isNotBlank() }
                    if (displayName != null) g.copy(name = displayName) else g
                }
            }

            // 9. Cached art — auto-fetched paths from ArtDao, then user overrides
            //    (heroArtOverride / boxArtOverride) win. This makes overrides "sticky":
            //    every display site reads state.artByGameId and gets the override
            //    without knowing about it.
            val artByGameId = HashMap<String, ArtRef>()
            gamesByPlatformFinal.values.flatten().forEach { g ->
                val row = db.artDao().get(g.id)
                val ov = overrideByGameId[g.id]
                val boxOverride = ov?.boxArtOverride?.takeIf { it.isNotBlank() }
                val heroOverride = ov?.heroArtOverride?.takeIf { it.isNotBlank() }
                if (row != null || boxOverride != null || heroOverride != null) {
                    artByGameId[g.id] = ArtRef(
                        gameId = g.id,
                        boxPath = boxOverride ?: row?.boxPath,
                        heroPath = heroOverride ?: row?.heroPath,
                        logoPath = row?.logoPath,
                    )
                }
            }

            // 10. Android app rows so MainActivity can resolve launch payloads
            val androidRows = db.androidGameDao().all()
            val androidRowByGameId = androidRows.associateBy { AndroidAppSource.stableId(it.pkg) }

            // 11. Playtime refresh (xPTT sqlite + internal Room sessions merged).
            _playtime.value = computePlaytime()

            // 12. Discovered emulators — auto-detected winlator packages + user platform intents.
            val discovered = ArrayList<DiscoveredEmulator>()
            resolvedPlatforms.forEach { p ->
                if (p.id == WindowsSource.PLATFORM_ID) {
                    WindowsSource.discoveredWinlatorPackages(p).forEach { pkg ->
                        val label = runCatching {
                            appCtx.packageManager.getApplicationLabel(
                                appCtx.packageManager.getApplicationInfo(pkg, 0)
                            ).toString()
                        }.getOrNull() ?: pkg
                        discovered.add(
                            DiscoveredEmulator(
                                id = "winlator:$pkg",
                                displayName = label,
                                pkg = pkg,
                                platformNames = listOf(p.name),
                                source = "auto-winlator",
                            )
                        )
                    }
                }
                p.intents.forEach { intent ->
                    discovered.add(
                        DiscoveredEmulator(
                            id = "intent:${p.id}:${intent.id}",
                            displayName = intent.name,
                            pkg = intent.pkg ?: intent.component?.substringBefore('/'),
                            platformNames = listOf(p.name),
                            source = "platform-intent",
                        )
                    )
                }
            }

            val openSessionsKey = withContext(Dispatchers.IO) {
                db.sessionDao().openSessions().map {
                    PlaytimeReader.keyFor(it.gameName, it.platform)
                }.toSet()
            }

            _state.value = LibraryState(
                platforms = resolvedPlatforms,
                gamesByPlatform = gamesByPlatformFinal,
                artByGameId = artByGameId,
                androidRowByGameId = androidRowByGameId,
                overrideByGameId = overrideByGameId,
                discoveredEmulators = discovered,
                openSessionsKey = openSessionsKey,
                warnings = warnings,
                loading = false,
            )

            // Prompt-for-art detection. First refresh just seeds the baseline; from the
            // second refresh onward, any game id not previously seen is considered "just
            // added". If it has no art and no override, we forward it to MainActivity so
            // the art picker opens. Android apps skip the prompt since their icon comes
            // from PackageManager, not the art repo.
            val currentIds = gamesByPlatformFinal.values.flatten().map { it.id }.toSet()
            val prev = previousGameIds
            previousGameIds = currentIds
            if (prev != null) {
                val newIds = (currentIds - prev).toList()
                val needArt = newIds.filter { id ->
                    val plat = gamesByPlatformFinal.entries.firstOrNull { (_, list) ->
                        list.any { it.id == id }
                    }?.key
                    plat != AndroidAppSource.PLATFORM_ID
                        && artByGameId[id]?.boxPath == null
                        && artByGameId[id]?.heroPath == null
                        && overrideByGameId[id] == null
                }
                // ROM games (not Android apps / Windows forwarders) get their cleaned names
                // reviewed first; the art picker follows from the review screen.
                val review = newIds.filter { id ->
                    val plat = gamesByPlatformFinal.entries.firstOrNull { (_, list) -> list.any { it.id == id } }?.key
                    plat != AndroidAppSource.PLATFORM_ID && plat != WindowsSource.PLATFORM_ID && overrideByGameId[id] == null
                }
                if (review.isNotEmpty()) _newlyAddedForReview.tryEmit(review to needArt)
                else if (needArt.isNotEmpty()) _newlyAddedForArt.tryEmit(needArt)
            }
        }
    }

    /** Called from GameDetail's END SESSION chip. Delegates to TrackerService which will
     *  close the row and stop the foreground service. */
    fun endSession(context: android.content.Context) {
        com.maiconerick.xgamelauncher.tracker.TrackerService.endNow(context)
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            refresh()
        }
    }

    /** Called from Settings → Playtime "Import xPTT sessions" button. */
    suspend fun importXpttSessions(): com.maiconerick.xgamelauncher.tracker.XpttImporter.Result {
        val path = prefs.xpttDbPath.first()
        val result = com.maiconerick.xgamelauncher.tracker.XpttImporter.run(appCtx, path)
        if (result.imported > 0 && result.error == null) {
            prefs.setSkipXpttRead(true)
        }
        refresh()
        return result
    }

    // ---- Art picker + preload cache ----

    private val _artCandidates =
        MutableStateFlow<Map<String, com.maiconerick.xgamelauncher.art.GameArtCandidates>>(emptyMap())
    val artCandidates:
        StateFlow<Map<String, com.maiconerick.xgamelauncher.art.GameArtCandidates>> =
        _artCandidates.asStateFlow()

    private val _artLoading = MutableStateFlow<Set<String>>(emptySet())
    val artLoading: StateFlow<Set<String>> = _artLoading.asStateFlow()

    fun preloadCandidates(gameIds: List<String>) {
        gameIds.forEach { id ->
            if (_artCandidates.value.containsKey(id) || id in _artLoading.value) return@forEach
            val game = _state.value.gamesByPlatform.values.flatten().firstOrNull { it.id == id }
                ?: return@forEach
            viewModelScope.launch {
                _artLoading.value = _artLoading.value + id
                try {
                    val cands = artRepo.candidates(game.name)
                    _artCandidates.value = _artCandidates.value + (id to cands)
                } finally {
                    _artLoading.value = _artLoading.value - id
                }
            }
        }
    }

    /** IDs of games with no cached art (or a cache miss). Used by the "Pick art for missing"
     *  action to open the ArtPicker over just those games. */
    suspend fun missingArtGameIds(): List<String> = withContext(Dispatchers.IO) {
        val all = _state.value.gamesByPlatform.values.flatten()
        all.mapNotNull { g ->
            val row = db.artDao().get(g.id)
            if (row == null || row.boxPath == null) g.id else null
        }
    }

    /** One art choice at a time: box then hero for the same game must not overwrite each other. */
    private val artChoiceLock = kotlinx.coroutines.sync.Mutex()

    fun applyArtChoice(gameId: String, coverUrl: String?, heroUrl: String?) {
        viewModelScope.launch { artChoiceLock.withLock {
            val game = _state.value.gamesByPlatform.values.flatten().firstOrNull { it.id == gameId }
                ?: return@launch
            artRepo.applyChoice(gameId, game.platformId, game.name, coverUrl, heroUrl)
            // Refresh art in state so tiles reflect the choice — but let any user override
            // still win over the auto-fetched paths.
            val row = db.artDao().get(gameId)
            val ov = _state.value.overrideByGameId[gameId]
            if (row != null) {
                _state.value = _state.value.copy(
                    artByGameId = _state.value.artByGameId + (gameId to
                        com.maiconerick.xgamelauncher.data.ArtRef(
                            gameId = gameId,
                            boxPath = ov?.boxArtOverride?.takeIf { it.isNotBlank() } ?: row.boxPath,
                            heroPath = ov?.heroArtOverride?.takeIf { it.isNotBlank() } ?: row.heroPath,
                            logoPath = row.logoPath,
                        )),
                )
            }
        } }
    }

    /** Called from Settings → Playtime "Sync from Dropbox" — pulls every device's DB,
     *  runs each through XpttImporter (which handles both xPTT and xGL schemas). */
    suspend fun syncFromDropbox(): com.maiconerick.xgamelauncher.net.DropboxSync.Result {
        val device = prefs.deviceName.first()
        val r = com.maiconerick.xgamelauncher.net.DropboxSync.pullAll(appCtx, device)
        if (r.imported > 0) refresh()
        return r
    }

    /** Manual "Backup now" for the Dropbox settings row. */
    suspend fun backupToDropbox(): Boolean {
        val device = prefs.deviceName.first()
        return withContext(Dispatchers.IO) {
            com.maiconerick.xgamelauncher.net.Dropbox.backupDb(appCtx, device)
        }
    }

    fun ensureArt(platform: Platform, game: GameEntry) {
        // Guard against duplicate concurrent fetches for the same game id.
        if (game.id in _artFetching.value) return
        // Skip if we already have a resolved (non-missed) art ref.
        if (_state.value.artByGameId[game.id]?.boxPath != null) return
        viewModelScope.launch {
            _artFetching.value = _artFetching.value + game.id
            try {
                val ref = artRepo.get(platform, game)
                _state.value = _state.value.copy(
                    artByGameId = _state.value.artByGameId + (game.id to ref)
                )
            } finally {
                _artFetching.value = _artFetching.value - game.id
            }
        }
    }

    /** Re-fetch art forcing SGDB lookup and setting URLs as overrides. Returns candidate URLs. */
    suspend fun searchArtCandidates(name: String): List<com.maiconerick.xgamelauncher.art.SgdbGameHit> =
        artRepo.searchCandidates(name)

    suspend fun candidateArtUrls(sgdbGameId: Long): Triple<String?, String?, String?> =
        artRepo.candidateUrls(sgdbGameId)

    fun playtimeFor(gameName: String, platformName: String): PlaytimeStats? {
        val key = PlaytimeReader.keyFor(gameName, platformName)
        return _playtime.value[key]
    }

    suspend fun userPlatforms(): List<Platform> = PlatformRepo.list(db)

    fun savePlatform(p: Platform) {
        viewModelScope.launch { PlatformRepo.upsert(db, p, 0); LibraryBus.bump() }
    }

    fun deletePlatform(id: String) {
        viewModelScope.launch {
            PlatformRepo.delete(db, id)
            prefs.hidePlatform(id)
            LibraryBus.bump()
        }
    }

    fun restorePlatform(id: String) {
        viewModelScope.launch { prefs.unhidePlatform(id); refresh(forceDaijisho = true) }
    }

    /** Closed sessions for one game (oldest first) — the game screen's playtime chart. */
    suspend fun sessionsFor(gameName: String, platformName: String): List<com.maiconerick.xgamelauncher.data.SessionRow> =
        withContext(Dispatchers.IO) {
            db.sessionDao().all()
                .filter { !it.isOpen && it.gameName.equals(gameName, true) && it.platform.equals(platformName, true) }
                .sortedBy { it.startedUnix }
        }

    // ---- Playtime editor ----

    /** Every session row, newest first (the playtime editor filters it in memory). */
    suspend fun allSessions(): List<com.maiconerick.xgamelauncher.data.SessionRow> =
        withContext(Dispatchers.IO) { db.sessionDao().all() }

    /** Inserts (id 0) or updates a hand-edited row, then refreshes playtime and mirrors
     *  the change to Dropbox. Returns the row id. */
    suspend fun saveSession(
        row: com.maiconerick.xgamelauncher.data.SessionRow,
        before: com.maiconerick.xgamelauncher.data.SessionRow?,
    ): Long = withContext(Dispatchers.IO) {
        val edits = com.maiconerick.xgamelauncher.data.SessionEdits
        val id = if (row.id == 0L) db.sessionDao().insert(row) else { db.sessionDao().update(row); row.id }
        // A changed (game, platform, start) key must not be re-imported under the old key.
        val oldKey = before?.let { edits.keyOf(it) }
        val newKey = edits.keyOf(row)
        edits.bury(appCtx, listOfNotNull(oldKey?.takeIf { it != newKey }), revive = listOf(newKey))
        afterSessionEdit()
        id
    }

    suspend fun deleteSessions(rows: List<com.maiconerick.xgamelauncher.data.SessionRow>): Int =
        withContext(Dispatchers.IO) {
            if (rows.isEmpty()) return@withContext 0
            val edits = com.maiconerick.xgamelauncher.data.SessionEdits
            val n = rows.map { it.id }.chunked(500).sumOf { db.sessionDao().deleteIds(it) }
            edits.bury(appCtx, rows.map { edits.keyOf(it) })
            edits.addPendingDeletes(appCtx, n)
            afterSessionEdit()
            n
        }

    private var editBackupJob: kotlinx.coroutines.Job? = null

    /** Playtime totals are rebuilt by refresh(); the Dropbox copy is rewritten from the db a
     *  few seconds after the last edit, so a burst of edits uploads once. */
    private fun afterSessionEdit() {
        refresh()
        editBackupJob?.cancel()
        editBackupJob = viewModelScope.launch {
            kotlinx.coroutines.delay(4_000)
            backupToDropbox()
        }
    }

    /** Newest session rows first — Settings → Playtime log. */
    suspend fun latestSessions(limit: Int): List<com.maiconerick.xgamelauncher.data.SessionRow> =
        withContext(Dispatchers.IO) { db.sessionDao().all().take(limit) }

    /** Hidden (deleted) platform ids, for Settings → Platforms → Restore. */
    suspend fun hiddenPlatformIds(): Set<String> = prefs.hiddenPlatformIds.first()

    fun setAndroidApp(pkg: String, label: String, launchComponent: String, included: Boolean) {
        viewModelScope.launch {
            if (included) db.androidGameDao().upsert(
                AndroidGameRow(pkg, label, launchComponent, System.currentTimeMillis())
            )
            else db.androidGameDao().delete(pkg)
            LibraryBus.bump()
        }
    }

    suspend fun overrideFor(gameId: String): GameOverrideRow? =
        withContext(Dispatchers.IO) { db.gameOverrideDao().get(gameId) }

    fun saveOverride(row: GameOverrideRow) {
        viewModelScope.launch {
            val prev = db.gameOverrideDao().get(row.gameId)
            db.gameOverrideDao().upsert(row)
            // Only a display-name change drops the fetched art (so it re-fetches by name).
            if (prev?.displayNameOverride != row.displayNameOverride) db.artDao().clear(row.gameId)
            LibraryBus.bump()
        }
    }

    fun clearOverride(gameId: String) {
        viewModelScope.launch {
            db.gameOverrideDao().delete(gameId)
            db.artDao().clear(gameId)
            LibraryBus.bump()
        }
    }

    /** Sync: full refresh + drop misses + drop art whose sourceName no longer matches. */
    fun sync() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val allArt = db.artDao().all()
                val nameById = _state.value.gamesByPlatform.values.flatten().associate { it.id to it.name }
                allArt.forEach { row ->
                    val currentName = nameById[row.gameId]
                    val nameMismatch = row.sourceName != null && currentName != null &&
                        currentName != row.sourceName
                    if (row.missed || nameMismatch) {
                        db.artDao().clear(row.gameId)
                    }
                }
            }
            refresh(forceDaijisho = true)
        }
    }

}
