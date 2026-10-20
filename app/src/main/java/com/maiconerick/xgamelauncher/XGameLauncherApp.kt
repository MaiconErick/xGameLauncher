package com.maiconerick.xgamelauncher

import android.app.Application
import com.maiconerick.xgamelauncher.art.ArtRepository
import com.maiconerick.xgamelauncher.art.GameImageProvider
import com.maiconerick.xgamelauncher.art.IgdbClient
import com.maiconerick.xgamelauncher.art.RawgClient
import com.maiconerick.xgamelauncher.art.ScreenScraperClient
import com.maiconerick.xgamelauncher.art.SteamGridDbClient
import com.maiconerick.xgamelauncher.data.AppDatabase
import com.maiconerick.xgamelauncher.prefs.PrefsStore
import com.maiconerick.xgamelauncher.tracker.SessionRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Bare-bones DI: singletons hang off the Application. Read-only from Composables.
 */
class XGameLauncherApp : Application(), coil.ImageLoaderFactory {

    lateinit var appDb: AppDatabase; private set
    lateinit var prefs: PrefsStore; private set
    lateinit var artRepo: ArtRepository; private set
    lateinit var imageProvider: GameImageProvider; private set
    lateinit var igdb: IgdbClient; private set

    /** One random neon accent per app session, shared by the top and bottom launchers. */
    /** Last chosen theme, read synchronously so a cold start doesn't flash the Neon theme
     *  while DataStore loads (mirrored by PrefsStore.setThemeId). */
    val themeIdHint: String
        get() = getSharedPreferences("xgl_ui", MODE_PRIVATE).getString("theme_id", "neon") ?: "neon"

    val sessionAccentArgb: Int by lazy {
        android.graphics.Color.HSVToColor(
            floatArrayOf(kotlin.random.Random.nextFloat() * 360f, 0.75f + kotlin.random.Random.nextFloat() * 0.25f, 1f)
        )
    }

    // Cached API creds snapshots, kept fresh by the flow observer below.
    @Volatile private var sgdbKeyCache: String = ""
    @Volatile private var ssUserCache: String = ""
    @Volatile private var ssPassCache: String = ""
    @Volatile private var rawgKeyCache: String = ""
    @Volatile private var igdbIdCache: String = ""
    @Volatile private var igdbSecretCache: String = ""

    /**
     * Coil loader sized to the device: on ≤3 GB handhelds (RG353M) the memory cache is
     * halved and opaque art decodes as RGB565, which roughly halves bitmap memory.
     */
    override fun newImageLoader(): coil.ImageLoader {
        val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val lowRam = am?.isLowRamDevice == true || mem.totalMem in 1L..(3L * 1024 * 1024 * 1024)
        return coil.ImageLoader.Builder(this)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(if (lowRam) 0.12 else 0.25)
                    .build()
            }
            .allowRgb565(lowRam)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        appDb = AppDatabase.get(this)
        prefs = PrefsStore(this)
        val sgdb = SteamGridDbClient { sgdbKeyCache }
        val ss = ScreenScraperClient { ssUserCache to ssPassCache }
        val rawg = RawgClient { rawgKeyCache }
        val igdb = IgdbClient { igdbIdCache to igdbSecretCache }
        this.igdb = igdb
        artRepo = ArtRepository(this, appDb, sgdb, ss, rawg, igdb)
        imageProvider = GameImageProvider(
            igdbCreds = { igdbIdCache to igdbSecretCache },
            rawgKey = { rawgKeyCache },
            sgdbKey = { sgdbKeyCache },
        )

        // Prime creds (blocking; cheap — reads DataStore once).
        runBlocking {
            sgdbKeyCache = prefs.steamGridDbKey.first()
            ssUserCache = prefs.screenScraperUser.first()
            ssPassCache = prefs.screenScraperPass.first()
            rawgKeyCache = prefs.rawgApiKey.first()
            igdbIdCache = prefs.igdbClientId.first()
            igdbSecretCache = prefs.igdbClientSecret.first()

            // Older installs stored device_name="THOR" (uppercase). xPTT and everything
            // downstream keys off "Thor" (title case) in the /PlayTime/DB folder, so
            // normalize on first launch of the exporter-aware build.
            if (prefs.deviceName.first() == "THOR") prefs.setDeviceName("Thor")

            // First-run seed: user's IGDB/RAWG API keys, so the multi-source art picker
            // works without a trip through the Settings screen. Only writes when blank —
            // never overwrites a key the user set themselves.
            val igdbId = prefs.igdbClientId.first()
            val igdbSecret = prefs.igdbClientSecret.first()
            if (igdbId.isBlank() || igdbSecret.isBlank()) {
                prefs.setIgdbCreds(
                    clientId = igdbId.ifBlank { "pqcrd3xy0p9zxzqaorswrhd9yqzsmy" },
                    clientSecret = igdbSecret.ifBlank { "ijr7wi9tg8j1tngcbe3shjhwydsu04" },
                )
            }
            if (prefs.rawgApiKey.first().isBlank()) {
                prefs.setRawgApiKey("c8ab7842727242e29e1ae922dc2c0711")
            }
        }
        // Keep them fresh reactively.
        val scope = CoroutineScope(SupervisorJob())
        scope.launch { prefs.steamGridDbKey.collect { sgdbKeyCache = it } }
        scope.launch { prefs.screenScraperUser.collect { ssUserCache = it } }
        scope.launch { prefs.screenScraperPass.collect { ssPassCache = it } }
        scope.launch { prefs.rawgApiKey.collect { rawgKeyCache = it } }
        scope.launch { prefs.igdbClientId.collect { igdbIdCache = it } }
        scope.launch { prefs.igdbClientSecret.collect { igdbSecretCache = it } }
        scope.launch {
            kotlinx.coroutines.flow.combine(prefs.navSounds, prefs.navVolume) { on, vol -> on to vol }
                .collect { (on, vol) -> com.maiconerick.xgamelauncher.ui.NavSounds.configure(on, vol / 100f) }
        }

        // Animations pause button state (shared by both screens), restored from prefs.
        scope.launch {
            com.maiconerick.xgamelauncher.ui.AppChrome.animationsPaused = prefs.toolFlag("animations_paused", false).first()
        }

        // Reconcile any session rows the tracker service was killed mid-write on.
        scope.launch(Dispatchers.IO) { SessionRecovery.run(this@XGameLauncherApp) }
    }
}
