package com.maiconerick.xgamelauncher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maiconerick.xgamelauncher.android.AndroidAppSource
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.LaunchIntent
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.launch.WindowsLauncher
import com.maiconerick.xgamelauncher.windows.WindowsSource
import com.maiconerick.xgamelauncher.ui.GameSortMode
import com.maiconerick.xgamelauncher.ui.LauncherViewModel
import com.maiconerick.xgamelauncher.ui.LocalArtFetchingCount
import com.maiconerick.xgamelauncher.ui.LocalOnSearch
import com.maiconerick.xgamelauncher.ui.LocalOnSync
import com.maiconerick.xgamelauncher.ui.LocalPicker
import com.maiconerick.xgamelauncher.ui.PickerOption
import com.maiconerick.xgamelauncher.ui.PickerRequest
import com.maiconerick.xgamelauncher.ui.Route
import com.maiconerick.xgamelauncher.ui.components.TopTab
import com.maiconerick.xgamelauncher.ui.dialogs.PickerHost
import com.maiconerick.xgamelauncher.ui.screens.AppsScreen
import com.maiconerick.xgamelauncher.ui.screens.BootScreen
import com.maiconerick.xgamelauncher.ui.screens.PlatformEditScreen
import com.maiconerick.xgamelauncher.ui.screens.GameDetailScreen
import com.maiconerick.xgamelauncher.ui.screens.GameEditScreen
import com.maiconerick.xgamelauncher.ui.screens.HomeScreen
import com.maiconerick.xgamelauncher.ui.screens.PlatformScreen
import com.maiconerick.xgamelauncher.ui.screens.SearchScreen
import com.maiconerick.xgamelauncher.ui.screens.SettingsScreen
import com.maiconerick.xgamelauncher.ui.theme.XGameLauncherTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Controller plumbing shared by both launcher screens (main + bottom-screen home):
 * shoulder-button fallback, ZL/ZR synthesized from trigger axes, and programmatic button
 * presses from the bottom bar hints.
 */
open class LauncherActivityBase : ComponentActivity() {


    /**
     * Shoulder buttons that no focused Compose node consumed (nothing focused after a touch,
     * or a text field swallowing focus). AppRoot collects these so L/R/ZL/ZR always work.
     */
    val unhandledShoulder = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 8)

    // Trigger → key synthesis. Some pads (the RG353M's built-in "Xbox Wireless Controller")
    // report ZL/ZR as analog axes (BRAKE/GAS); Android never turns those into key events.
    private var lTrigDown = false
    private var rTrigDown = false
    private var sawRealL2 = false
    private var sawRealR2 = false
    protected var synthesizing = false

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_B || event.keyCode == android.view.KeyEvent.KEYCODE_BACK)
        ) com.maiconerick.xgamelauncher.ui.NavSounds.back()
        if (!synthesizing) when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_BUTTON_L2 -> sawRealL2 = true
            android.view.KeyEvent.KEYCODE_BUTTON_R2 -> sawRealR2 = true
        }
        if (super.dispatchKeyEvent(event)) return true
        return when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_BUTTON_L1, android.view.KeyEvent.KEYCODE_BUTTON_R1,
            android.view.KeyEvent.KEYCODE_BUTTON_L2, android.view.KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (event.action == android.view.KeyEvent.ACTION_UP) unhandledShoulder.tryEmit(event.keyCode)
                true
            }
            else -> false
        }
    }

    /** Press a controller button programmatically (tapping a hint in the bottom bar). */
    fun pressButton(name: String) {
        val code = when (name.uppercase()) {
            "A" -> android.view.KeyEvent.KEYCODE_BUTTON_A
            "B" -> android.view.KeyEvent.KEYCODE_BUTTON_B
            "X" -> android.view.KeyEvent.KEYCODE_BUTTON_X
            "Y" -> android.view.KeyEvent.KEYCODE_BUTTON_Y
            "L", "L1" -> android.view.KeyEvent.KEYCODE_BUTTON_L1
            "R", "R1" -> android.view.KeyEvent.KEYCODE_BUTTON_R1
            "ZL", "L2" -> android.view.KeyEvent.KEYCODE_BUTTON_L2
            "ZR", "R2" -> android.view.KeyEvent.KEYCODE_BUTTON_R2
            else -> return
        }
        synthesizing = true
        val handled = try {
            dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        } finally { synthesizing = false }
        // Injected keys get no framework BACK fallback, so do it by hand for B.
        if (!handled && code == android.view.KeyEvent.KEYCODE_BUTTON_B) onBackPressedDispatcher.onBackPressed()
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_MOVE &&
            (ev.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK) ||
                ev.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD))
        ) {
            val l = maxOf(ev.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER),
                ev.getAxisValue(android.view.MotionEvent.AXIS_BRAKE))
            val r = maxOf(ev.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER),
                ev.getAxisValue(android.view.MotionEvent.AXIS_GAS))
            if (!sawRealL2) lTrigDown = synthTrigger(l, lTrigDown, android.view.KeyEvent.KEYCODE_BUTTON_L2)
            if (!sawRealR2) rTrigDown = synthTrigger(r, rTrigDown, android.view.KeyEvent.KEYCODE_BUTTON_R2)
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    /** Hysteresis: press above 0.6, release below 0.3. Returns the new pressed state. */
    private fun synthTrigger(v: Float, wasDown: Boolean, keyCode: Int): Boolean {
        val down = if (wasDown) v > 0.3f else v > 0.6f
        if (down != wasDown) {
            synthesizing = true
            try {
                dispatchKeyEvent(
                    android.view.KeyEvent(
                        if (down) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP,
                        keyCode,
                    )
                )
            } finally { synthesizing = false }
        }
        return down
    }

}

class MainActivity : LauncherActivityBase() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A process started by a system backup runs with a plain Application (our app class
        // isn't created): don't try to show the launcher from it — exit so the next launch
        // starts a normal process.
        if (application !is com.maiconerick.xgamelauncher.XGameLauncherApp) {
            finish(); android.os.Process.killProcess(android.os.Process.myPid()); return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())

        launchRequest = launchRequestFrom(intent)
        setContent {
            XGameLauncherTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AppRoot(unhandledShoulder, autoLaunch = launchRequest)
                }
            }
        }
    }

    /** `am start -a com.maiconerick.xgamelauncher.LAUNCH_GAME --es platform <id|name> --es game <id>|--es game_name <name>` */
    private var launchRequest by androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null)

    private fun launchRequestFrom(i: android.content.Intent?): Pair<String, String>? {
        if (i?.action != "com.maiconerick.xgamelauncher.LAUNCH_GAME") return null
        val platform = i.getStringExtra("platform") ?: return null
        val game = i.getStringExtra("game") ?: i.getStringExtra("game_name")?.let { "name:$it" } ?: return null
        return platform to game
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        launchRequestFrom(intent)?.let { launchRequest = it }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val insets = WindowCompat.getInsetsController(window, window.decorView)
            insets.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun AppRoot(
    unhandledShoulder: kotlinx.coroutines.flow.SharedFlow<Int>,
    /** First screen (the bottom launcher's drawer starts at Apps or Home). */
    startRoute: Route = Route.Boot,
    /** Launch this game (platformId to gameId) as soon as the library is loaded. */
    autoLaunch: Pair<String, String>? = null,
    /** Embedded in the bottom launcher's drawer: no starfield, B at the root closes it. */
    embedded: Boolean = false,
    onExitRoot: () -> Unit = {},
) {
    val vm: LauncherViewModel = viewModel()
    val state by vm.state.collectAsState()
    // Sessions end while the library stays loaded: reload the totals whenever we come back.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, vm) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refreshPlaytime()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val artFetching by vm.artFetching.collectAsState()
    val app = (LocalContext.current.applicationContext as XGameLauncherApp)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Device profile (compact / low-power / multi-display) — recomputed when the user flips
    // the Settings → Device override or the configuration (rotation, density) changes.
    val uiMode by app.prefs.uiProfile.collectAsState(initial = "auto")
    val showHints by app.prefs.showButtonHints.collectAsState(initial = true)
    val romsToolOn by app.prefs.toolFlag(com.maiconerick.xgamelauncher.tools.Tool.Roms.enabledKey, true).collectAsState(initial = true)
    val highRefresh by app.prefs.highRefresh.collectAsState(initial = false)
    // Refresh-rate preference: the panel's fastest mode (120Hz on the Thor) or the default.
    androidx.compose.runtime.LaunchedEffect(highRefresh) {
        val act = context as? android.app.Activity ?: return@LaunchedEffect
        val w = act.window
        val disp = if (android.os.Build.VERSION.SDK_INT >= 30) act.display
            else @Suppress("DEPRECATION") act.windowManager.defaultDisplay
        val modes = disp?.supportedModes.orEmpty()
        val cur = disp?.mode
        val best = modes.filter { cur == null || (it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight) }
            .maxByOrNull { it.refreshRate }
        w.attributes = w.attributes.apply {
            preferredDisplayModeId = if (highRefresh && best != null) best.modeId else 0
        }
    }
    val stripIcons by app.prefs.stripUseIcons.collectAsState(initial = false)
    val barIcons by app.prefs.barIcons.collectAsState(initial = emptyMap())
    val miniIcons by app.prefs.miniIcons.collectAsState(initial = emptyMap())
    val stripMini by app.prefs.stripMiniIcons.collectAsState(initial = false)
    val stripIconSize by app.prefs.stripIconSize.collectAsState(initial = 0.2f)
    // Theme (Settings → Theme): its focus/bar animation settings are stored per theme group.
    val themeId by app.prefs.themeId.collectAsState(initial = app.themeIdHint)
    val appTheme = remember(themeId) { com.maiconerick.xgamelauncher.ui.theme.AppTheme.byId(themeId) }
    val tg = appTheme.group
    val focusA by remember(tg) { app.prefs.focusColorAOf(tg) }.collectAsState(initial = "")
    val focusB by remember(tg) { app.prefs.focusColorBOf(tg) }.collectAsState(initial = "")
    val focusKind by remember(tg) { app.prefs.focusKindOf(tg, appTheme.defaultFocusKind) }.collectAsState(initial = appTheme.defaultFocusKind)
    val focusStyle = remember(focusA, focusB, focusKind) {
        com.maiconerick.xgamelauncher.ui.components.FocusStyle(parseAccentColor(focusA), parseAccentColor(focusB), focusKind)
    }
    val barKind by remember(tg) { app.prefs.barFxKindOf(tg, appTheme.defaultBarFx) }.collectAsState(initial = appTheme.defaultBarFx)
    val barSpeed by remember(tg) { app.prefs.barFxSpeedOf(tg) }.collectAsState(initial = 0.35f)
    val barAmount by remember(tg) { app.prefs.barFxAmountOf(tg) }.collectAsState(initial = 0.5f)
    val barFx = remember(barKind, barSpeed, barAmount) {
        com.maiconerick.xgamelauncher.ui.components.BarFxStyle(barKind, barSpeed, barAmount)
    }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val profile = remember(uiMode, configuration.screenWidthDp, configuration.screenHeightDp) {
        com.maiconerick.xgamelauncher.ui.DeviceProfile.detect(context, uiMode)
    }

    val stack = remember { mutableStateListOf<Route>(startRoute) }
    val current = stack.last()

    // Auto-open the art picker whenever refresh() reports games that were just added and
    // have no art or override yet. Replaces the old silent auto-fetch flow.
    // Only the top launcher prompts: the bottom drawer's copy of the library sees the same
    // new games and would open a second picker nobody sees.
    androidx.compose.runtime.LaunchedEffect(vm, embedded) {
        if (embedded) return@LaunchedEffect
        vm.newlyAddedForArt.collect { ids ->
            if (ids.isNotEmpty()) stack.add(Route.ArtPicker(ids))
        }
    }
    androidx.compose.runtime.LaunchedEffect(vm, embedded) {
        if (embedded) return@LaunchedEffect
        vm.newlyAddedForReview.collect { (review, art) -> stack.add(Route.NameReview(review, art)) }
    }


    // Per-platform sort mode (in-memory).
    val sortByPlatform = remember { mutableStateMapOf<String, GameSortMode>() }
    fun sortFor(id: String) = sortByPlatform[id] ?: GameSortMode.NameAsc
    var unplayedSort by remember { mutableStateOf(GameSortMode.NameAsc) }

    val pickerState = remember { mutableStateOf<PickerRequest?>(null) }
    val setPicker: (PickerRequest?) -> Unit = { pickerState.value = it }

    // Launch prompts (top launcher only, once per process): make xGL the home app, then a
    // new version from Dropbox. Both can be switched off in Settings.
    androidx.compose.runtime.LaunchedEffect(embedded) {
        if (embedded || LaunchPrompts.shown) return@LaunchedEffect
        LaunchPrompts.shown = true
        kotlinx.coroutines.delay(2500)
        val release = if (app.prefs.toolFlag("update_autocheck", true).first())
            runCatching { com.maiconerick.xgamelauncher.net.Updater.check(context) }.getOrNull() else null
        fun updatePrompt() {
            val r = release ?: return
            pickerState.value = PickerRequest(
                title = "Update available",
                message = "v${com.maiconerick.xgamelauncher.net.Updater.installedName(context)} → v${r.name}" +
                    (r.notes?.let { "\n$it" } ?: "") + "\nDownloaded from Dropbox (${com.maiconerick.xgamelauncher.net.Updater.mb(r.size)}) and installed automatically; xGL restarts. Turn this check off in Settings → Updates.",
                options = listOf(PickerOption("install", "Download & install"), PickerOption("later", "Later")),
                onPick = { id ->
                    pickerState.value = null
                    if (id == "install") {
                        stack.add(Route.Settings("updates"))
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            com.maiconerick.xgamelauncher.net.Updater.downloadAndInstall(context, r)
                        }
                    }
                },
                onCancel = { pickerState.value = null },
            )
        }
        val askHome = app.prefs.toolFlag("ask_home_launcher", true).first()
        if (askHome && !LaunchPrompts.isDefaultHome(context)) {
            pickerState.value = PickerRequest(
                title = "Make xGameLauncher your home app?",
                message = "The Home button will open xGameLauncher" +
                    (if (profile.multiDisplay) " (the launcher on the top screen, xGL Bottom on the bottom screen)" else "") +
                    ". You can change it back any time in Android's Default apps. Turn this question off in Settings → Device → Home app.",
                options = listOf(
                    PickerOption("set", "Set as home app"),
                    PickerOption("later", "Not now", "Ask again next time"),
                    PickerOption("never", "Don't ask again"),
                ),
                onPick = { id ->
                    pickerState.value = null
                    when (id) {
                        "set" -> LaunchPrompts.setDefaultHome(context)
                        "never" -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            app.prefs.setToolFlag("ask_home_launcher", false)
                        }
                    }
                    updatePrompt()
                },
                onCancel = { pickerState.value = null; updatePrompt() },
            )
        } else updatePrompt()
    }

    fun replace(r: Route) { stack.clear(); stack.add(r) }
    fun push(r: Route) { stack.add(r) }
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        else if (embedded) onExitRoot()
        else { stack.clear(); stack.add(Route.Home) }
    }

    // Simple settings-section deeplink from Y-button on Android platform.
    // Settings index row to re-focus when coming back from a section page.
    var lastSettingsSection by remember { mutableStateOf<String?>(null) }
    var lastToolId by remember { mutableStateOf<String?>(null) }
    // A finished ROM download → rescan so the new game shows up in its platform.
    val romDone by com.maiconerick.xgamelauncher.tools.RomDownloader.completed.collectAsState()
    androidx.compose.runtime.LaunchedEffect(romDone) { if (romDone > 0) vm.refresh() }
    fun openSettings(section: String? = null) {
        replace(Route.Settings())
        if (section != null) { lastSettingsSection = section; push(Route.Settings(section)) }
    }

    fun cycleTab(delta: Int) {
        val order = listOf<Route>(Route.Home, Route.Apps, Route.Tools, Route.Settings())
        val curIdx = when (current) {
            Route.Apps -> 1
            Route.Tools, is Route.Tool, is Route.RomBrowse, is Route.RomEntry -> 2
            is Route.Settings -> 3
            else -> 0
        }
        val next = order[((curIdx + delta) % order.size + order.size) % order.size]
        replace(next)
    }

    // Hub-focused platform id (drives which platform is showcased on Home).
    var homeFocusedPlatformId by remember { mutableStateOf<String?>(null) }
    if (homeFocusedPlatformId == null && state.platforms.isNotEmpty()) {
        homeFocusedPlatformId = state.platforms.first().id
    }

    // "Active" platform id — the one whose accent should tint the whole UI. Follows Home
    // focus and current route so opening a platform sets the theme, and it stays sticky
    // when the user drops into Apps/Settings.
    // Platform whose accent tints the UI — only while you're INSIDE a platform. Everywhere
    // else (Home, Apps, Settings, Search) the app uses the random session accent below.
    val activePlatformId: String? = when (val c = current) {
        is Route.Games -> c.platformId
        is Route.GameDetail -> c.platformId
        is Route.GameEdit -> c.platformId
        is Route.PlatformEdit -> c.platformId
        else -> null
    }

    fun cyclePlatform(delta: Int) {
        val platforms = state.platforms
        if (platforms.isEmpty()) return
        val currentId = when (val c = current) {
            is Route.Games -> c.platformId
            Route.Home -> homeFocusedPlatformId
            else -> null
        }
        val idx = platforms.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        val nextIdx = ((idx + delta) % platforms.size + platforms.size) % platforms.size
        val nextId = platforms[nextIdx].id
        when (current) {
            Route.Home -> homeFocusedPlatformId = nextId
            else -> replace(Route.Games(nextId))
        }
    }

    /** `forcedPlayer`: a player picked in the game screen's chooser (Windows variant path or
     *  platform player id). Otherwise: game override → last used → platform default. */
    fun launchFlow(platform: Platform, game: GameEntry, forcedPlayer: String? = null) {
        scope.launch {
            val override = vm.overrideFor(game.id)
            val androidRow = state.androidRowByGameId[game.id]
            // Session rows must carry what the user sees in the launcher — the override
            // display name if they renamed it, otherwise the source-provided game.name.
            // Never a raw rom filename, UUID, or ".desktop" path.
            val displayName = override?.displayNameOverride?.takeIf { it.isNotBlank() }
                ?: game.name
            // Box art for the session row (the overlay skips URLs itself; widgets load them).
            val gameArt = override?.boxArtOverride?.takeIf { it.isNotBlank() }
                ?: state.artByGameId[game.id]?.boxPath
                ?: override?.heroArtOverride?.takeIf { it.isNotBlank() }
                ?: state.artByGameId[game.id]?.heroPath

            // Top = default display, bottom = the Thor's second panel (looked up, not
            // hard-coded). Single-screen devices (RG353M) always get null.
            fun screenToDisplayId(pref: String?): Int? =
                com.maiconerick.xgamelauncher.ui.DisplayTargets.displayIdFor(context, pref)

            fun reportFailure(ok: Boolean, what: String) {
                if (!ok) android.widget.Toast.makeText(
                    context, "Couldn't launch $what — check the player's component/extras.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }

            // Every launch goes through the built-in tracker: session row → optional root
            // force-stop → TrackerService → the real intent (root am start as fallback).
            fun launchNow(intent: LaunchIntent?, displayId: Int?) {
                scope.launch {
                    val launcher = com.maiconerick.xgamelauncher.tracker.InternalLauncher
                    when {
                        androidRow != null -> {
                            val real = launcher.buildAndroidLaunchIntent(context, androidRow.pkg)
                                ?: android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                                    component = android.content.ComponentName.unflattenFromString(androidRow.launchComponent)
                                }
                            val ok = launcher.launch(
                                context, real, androidRow.pkg, displayName,
                                "Android", "", displayId,
                                forceStopBefore = false,
                                artPath = gameArt, // the overlay and xAynButton's game card show it
                            )
                            reportFailure(ok, displayName)
                        }
                        platform.id == WindowsSource.PLATFORM_ID -> {
                            val vs = WindowsLauncher.variants(game)
                            val last = app.prefs.lastPlayer(game.id)
                            val v = vs.firstOrNull { it.key == forcedPlayer }
                                ?: vs.firstOrNull { it.key == last }
                                ?: vs.firstOrNull()
                            if (v == null) {
                                reportFailure(false, "$displayName (no matching BannerHub/Winlator installed)")
                                return@launch
                            }
                            // Leftover Windows emulators (a stuck CronyX/BannerHub wine session) eat
                            // RAM and can block the new launch: show what's running and offer to close it.
                            val running = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.maiconerick.xgamelauncher.windows.EmulatorProcs.scan(context)
                            }
                            if (running.isNotEmpty()) {
                                val procs = com.maiconerick.xgamelauncher.windows.EmulatorProcs
                                val totalKb = running.sumOf { it.rssKb }
                                val choice = kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                                    fun done(id: String) { setPicker(null); if (cont.isActive) cont.resumeWith(Result.success(id)) }
                                    setPicker(PickerRequest(
                                        title = "Windows emulators are running",
                                        message = running.joinToString("\n") { a ->
                                            "${a.label}: ${a.summary}\n   " + a.names.take(4).joinToString(", ") +
                                                if (a.names.size > 4) " +${a.names.size - 4}" else ""
                                        },
                                        options = buildList {
                                            add(PickerOption("all", "Close ${if (running.size == 1) running[0].label else "all"} and play",
                                                "Frees ${procs.mem(totalKb)}"))
                                            if (running.size > 1) running.forEach { a ->
                                                add(PickerOption("one:${a.pkg}", "Close only ${a.label} and play", a.summary))
                                            }
                                            add(PickerOption("keep", "Play without closing"))
                                            add(PickerOption("cancel", "Cancel"))
                                        },
                                        onPick = { id -> done(id) },
                                        onCancel = { done("cancel") },
                                    ))
                                }
                                val toKill = when {
                                    choice == "all" -> running
                                    choice.startsWith("one:") -> running.filter { it.pkg == choice.removePrefix("one:") }
                                    choice == "keep" -> emptyList()
                                    else -> return@launch
                                }
                                if (toKill.isNotEmpty()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    procs.kill(toKill)
                                    kotlinx.coroutines.delay(600)   // let the processes go before starting fresh
                                }
                            }
                            val direct = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                WindowsLauncher.build(context, v)
                            }
                            val ok = launcher.launch(
                                context, direct.realIntent, direct.watchPkg, displayName,
                                platform.name, direct.emulatorLabel, displayId,
                                forceStopBefore = direct.killBefore,
                                rootFallback = direct.shell,
                                artPath = gameArt,
                            )
                            if (ok) app.prefs.setLastPlayer(game.id, v.key)
                            reportFailure(ok, displayName)
                        }
                        else -> {
                            val prepared = intent?.let { launcher.buildEmulatorIntent(context, it, platform, game) }
                            if (intent == null || prepared == null) {
                                reportFailure(false, "$displayName — player \"${intent?.name}\" has no app/activity")
                                return@launch
                            }
                            val ok = launcher.launch(
                                context, prepared.intent, prepared.watchPkg, displayName,
                                platform.name, intent.name, displayId,
                                forceStopBefore = intent.killBefore,
                                rootFallback = prepared.shell,
                                artPath = gameArt,
                            )
                            if (ok) app.prefs.setLastPlayer(game.id, intent.id)
                            reportFailure(ok, displayName)
                        }
                    }
                }
            }

            fun proceedWithIntent(intent: LaunchIntent?) {
                val pref = override?.screenPref
                if (pref == "ask" && com.maiconerick.xgamelauncher.ui.DisplayTargets
                        .secondaryDisplayId(context) != null) {
                    setPicker(
                        PickerRequest(
                            title = "Show on which screen?",
                            options = listOf(
                                PickerOption("top", "Top screen"),
                                PickerOption("bottom", "Bottom screen"),
                            ),
                            onPick = { id ->
                                setPicker(null)
                                launchNow(intent, screenToDisplayId(id))
                            },
                            onCancel = { setPicker(null) },
                        )
                    )
                } else {
                    launchNow(intent, screenToDisplayId(pref))
                }
            }

            if (androidRow != null) {
                proceedWithIntent(null)
                return@launch
            }

            val intentId = override?.launchIntentId
            val lastUsed = app.prefs.lastPlayer(game.id)
            val chosen: LaunchIntent? = when {
                forcedPlayer != null -> platform.intents.firstOrNull { it.id == forcedPlayer }
                intentId == "" -> null // ask
                intentId != null ->
                    // Foreign-player support: fall back to searching all platforms if the
                    // saved intent id doesn't belong to this platform (GameEditForm's
                    // "Other…" picker can save foreign ids).
                    platform.intents.firstOrNull { it.id == intentId }
                        ?: state.platforms.flatMap { it.intents }.firstOrNull { it.id == intentId }
                lastUsed != null && platform.intents.any { it.id == lastUsed } ->
                    platform.intents.first { it.id == lastUsed }
                platform.defaultIntentId != null ->
                    platform.intents.firstOrNull { it.id == platform.defaultIntentId }
                platform.intents.size == 1 -> platform.intents.single()
                else -> null
            }
            if (chosen == null && platform.intents.isEmpty() &&
                platform.id != WindowsSource.PLATFORM_ID) {
                setPicker(
                    PickerRequest(
                        title = "No player for ${platform.name}",
                        message = "This platform has no emulator set up yet. Add one in the " +
                            "platform's Players tab — Import from Daijishō fills it in one step.",
                        options = listOf(PickerOption("setup", "Set up players…")),
                        onPick = {
                            setPicker(null)
                            push(Route.PlatformEdit(platform.id, initialTab = "players"))
                        },
                        onCancel = { setPicker(null) },
                    )
                )
                return@launch
            }
            if (chosen == null && platform.intents.size > 1) {
                setPicker(
                    PickerRequest(
                        title = "Launch with…",
                        options = platform.intents.map {
                            PickerOption(it.id, it.name, subtitle = it.targetPackage())
                        },
                        onPick = { id ->
                            setPicker(null)
                            val picked = platform.intents.first { it.id == id }
                            proceedWithIntent(picked)
                        },
                        onCancel = { setPicker(null) },
                    )
                )
                return@launch
            }
            proceedWithIntent(chosen)
        }
    }

    // "Play again" from the bottom launcher's widget: launch once the library has the game.
    // Also used by the xgl.LAUNCH_GAME intent (Tasker / shortcuts): the game can be given by
    // id or by name ("name:<game name>"), and the platform by id or name.
    var autoLaunched by remember(autoLaunch) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(autoLaunch, state.gamesByPlatform) {
        val (pid, gid) = autoLaunch ?: return@LaunchedEffect
        if (autoLaunched) return@LaunchedEffect
        val p = state.platforms.firstOrNull { it.id == pid || it.name.equals(pid, true) } ?: return@LaunchedEffect
        val games = state.gamesByPlatform[p.id].orEmpty()
        val g = (if (gid.startsWith("name:")) games.firstOrNull { it.name.equals(gid.removePrefix("name:"), true) }
            ?: games.firstOrNull { it.name.contains(gid.removePrefix("name:"), true) }
            else games.firstOrNull { it.id == gid }) ?: return@LaunchedEffect
        autoLaunched = true
        launchFlow(p, g)
    }

    // BackHandler pops the stack everywhere. `Route.GameEdit` has its own inner BackHandler
    // for the "Save changes?" prompt — Compose's nested BackHandler ordering means the inner
    // one wins, so no extra flag is needed here (the button path below also defers to it).
    BackHandler(enabled = stack.size > 1 || embedded) { pop() }

    // B while the soft keyboard is up = "done typing", not "leave this screen".
    val imeVisible by rememberUpdatedState(WindowInsets.isImeVisible)
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // Editors keep L/R/ZL/ZR (switching platform or tab there would drop unsaved edits).
    fun shouldersLocked(): Boolean =
        current is Route.PlatformEdit || current is Route.GameEdit || current is Route.PlaytimeEditor

    fun onShoulder(keyCode: Int): Boolean = if (shouldersLocked()) false else when (keyCode) {
        android.view.KeyEvent.KEYCODE_BUTTON_L2 -> { cycleTab(-1); true }
        android.view.KeyEvent.KEYCODE_BUTTON_R2 -> { cycleTab(+1); true }
        // On Apps route, L1/R1 belong to the tab pager (AppsScreen handles them).
        android.view.KeyEvent.KEYCODE_BUTTON_L1 -> if (current is Route.Apps) false else { cyclePlatform(-1); true }
        android.view.KeyEvent.KEYCODE_BUTTON_R1 -> if (current is Route.Apps) false else { cyclePlatform(+1); true }
        else -> false
    }
    val onShoulderLatest by rememberUpdatedState(::onShoulder)
    androidx.compose.runtime.LaunchedEffect(unhandledShoulder) {
        unhandledShoulder.collect { code ->
            // Editors and dialogs keep the shoulders to themselves.
            if (pickerState.value == null && !imeVisible) onShoulderLatest(code)
        }
    }

    val rootModifier = Modifier
        .fillMaxSize()
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
            when (e.key) {
                Key.ButtonB -> when {
                    pickerState.value != null -> false          // PickerHost handles B
                    imeVisible -> {                             // close the keyboard only
                        keyboard?.hide(); focusManager.clearFocus(); true
                    }
                    current is Route.GameEdit -> false          // GameEditForm's BackHandler handles B
                    current is Route.PlatformEdit -> false      // PlatformEditScreen asks to save
                    current is Route.PlaytimeEditor -> false    // form → list → leave, via its BackHandler
                    else -> { pop(); true }
                }
                Key.ButtonL2 -> if (shouldersLocked()) false else { cycleTab(-1); true }
                Key.ButtonR2 -> if (shouldersLocked()) false else { cycleTab(+1); true }
                // On Apps route, L1/R1 belong to the tab pager (AppsScreen handles them).
                Key.ButtonL1 -> if (current is Route.Apps || shouldersLocked()) false else { cyclePlatform(-1); true }
                Key.ButtonR1 -> if (current is Route.Apps || shouldersLocked()) false else { cyclePlatform(+1); true }
                Key.ButtonY -> {
                    // Y is a no-op on Apps route (per user request — the tab dropdown replaces it).
                    if (current is Route.Apps) return@onPreviewKeyEvent false
                    val platformId = when (val c = current) {
                        is Route.Games -> c.platformId
                        Route.Home -> homeFocusedPlatformId
                        else -> null
                    }
                    val platform = platformId?.let { pid -> state.platforms.firstOrNull { it.id == pid } }
                    when {
                        platform == null -> false
                        // Every platform — including the auto-generated Android one — opens the
                        // full-screen platform editor so backgrounds/icons/columns can be edited.
                        // (Adding new Android apps still lives under Settings → Android.)
                        else -> { push(Route.PlatformEdit(platform.id)); true }
                    }
                }
                else -> false
            }
        }

    fun hintsFor(r: Route): List<com.maiconerick.xgamelauncher.ui.components.ButtonHint> {
        fun h(b: String, l: String) = com.maiconerick.xgamelauncher.ui.components.ButtonHint(b, l)
        val compact = profile.compact
        val base = listOf(h("A", "SELECT"), h("B", "BACK"))
        val extra = when (r) {
            Route.Home, is Route.Games -> listOf(h("Y", "EDIT"), h("L/R", "PLATFORM"))
            else -> emptyList()
        }
        val tabs = if (!compact) listOf(h("ZL/ZR", "TAB")) else emptyList()
        return base + extra + (if (r == Route.Home || r == Route.Apps || r == Route.Tools || r is Route.Settings) tabs else emptyList())
    }

    fun tabToRoute(t: TopTab): Route = when (t) {
        TopTab.Home -> Route.Home
        TopTab.Apps -> Route.Apps
        TopTab.Tools -> Route.Tools
        TopTab.Settings -> Route.Settings()
    }

    // Palette derived from the active platform's accent hex — every LocalNeon reader
    // downstream sees this tint (buttons, focus strokes, section labels, tab pills, etc).
    val activeAccentHex = activePlatformId
        ?.let { pid -> state.platforms.firstOrNull { it.id == pid } }
        ?.accentColorHex
    // Random neon accent for this app session (outside platforms).
    val sessionAccent = remember { Color(app.sessionAccentArgb) }
    // Recomputed on entering a platform, so accent "random" gives a new colour per visit.
    val activePalette = remember(activeAccentHex, activePlatformId, appTheme) {
        appTheme.palette?.let { return@remember it }
        val c = when {
            activeAccentHex.equals("random", ignoreCase = true) -> randomNeonAccent()
            else -> parseAccentColor(activeAccentHex)
        } ?: sessionAccent
        paletteFromAccent(c)
    }

    // These locals are static, so a new lambda instance recomposes the WHOLE tree. Keep
    // them referentially stable (remembered) — before this, every carousel move or route
    // change recomposed every screen, which is what made menus crawl on the RG353M.
    val onSyncStable = remember(vm) { { vm.sync() } }
    val onSearchStable = remember { { stack.add(Route.Search); Unit } }
    val onArtPickerStable = remember {
        { ids: List<String> -> if (ids.isNotEmpty()) stack.add(Route.ArtPicker(ids)) }
    }
    val artFetchingLatest by rememberUpdatedState(artFetching)
    val artCountStable = remember { { artFetchingLatest.size } }
    // Games with missing hero OR box art (fetched art or a manual override both count) —
    // drives the `!` needs-art chip in TopBar. Android games included: they show art too.
    val missingArtIds = remember(state.gamesByPlatform, state.artByGameId, state.overrideByGameId) {
        state.gamesByPlatform.values.flatten()
            .filter { g ->
                val a = state.artByGameId[g.id]
                val o = state.overrideByGameId[g.id]
                val box = o?.boxArtOverride?.takeIf { it.isNotBlank() } ?: a?.boxPath
                val hero = o?.heroArtOverride?.takeIf { it.isNotBlank() } ?: a?.heroPath
                box.isNullOrBlank() || hero.isNullOrBlank()
            }
            .map { it.id }
            .distinct()
    }
    val missingArtStable = remember(missingArtIds) { { missingArtIds } }
    val setPickerStable = remember { setPicker }

    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(
        LocalOnSync provides onSyncStable,
        LocalOnSearch provides onSearchStable,
        LocalPicker provides setPickerStable,
        com.maiconerick.xgamelauncher.ui.LocalOnArtPicker provides onArtPickerStable,
        LocalArtFetchingCount provides artCountStable,
        com.maiconerick.xgamelauncher.ui.LocalMissingArtGameIds provides missingArtStable,
        com.maiconerick.xgamelauncher.ui.theme.LocalNeon provides activePalette,
        com.maiconerick.xgamelauncher.ui.theme.LocalAppTheme provides appTheme,
        com.maiconerick.xgamelauncher.ui.LocalDeviceProfile provides profile,
        com.maiconerick.xgamelauncher.ui.components.LocalFocusStyle provides focusStyle,
        com.maiconerick.xgamelauncher.ui.components.LocalBarFx provides barFx,
        // App-wide text scale: ~10% tighter type on the Thor, a touch less on compact screens
        // (their sizes are already reduced per component).
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
            baseDensity.density, baseDensity.fontScale * if (profile.compact) 0.95f else 0.9f,
        ),
    ) {
        Box(rootModifier) {
            // Global drifting-starfield backdrop — same ported-from-xPTT visual on every
            // screen. Hub screens leave their outer surface transparent so it shows
            // through; detail screens (GameDetail hero, edit forms) paint over it.
            if (!embedded) com.maiconerick.xgamelauncher.ui.theme.ThemedBackdrop(
                modifier = Modifier.fillMaxSize(),
                animate = com.maiconerick.xgamelauncher.ui.animationsOn(),
            )
            Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val r = current) {
                Route.Boot -> BootScreen(onReady = { replace(Route.Home) })

                Route.Home -> HomeScreen(
                    state = state,
                    focusedPlatformId = homeFocusedPlatformId,
                    onFocusPlatform = { homeFocusedPlatformId = it },
                    onOpenPlatform = { p -> push(Route.Games(p.id)) },
                    playtime = { g, p -> vm.playtimeFor(g, p) },
                    onTab = { t -> replace(tabToRoute(t)) },
                )

                is Route.Games -> {
                    val platform = state.platforms.firstOrNull { it.id == r.platformId }
                    if (platform == null) {
                        replace(Route.Home)
                    } else {
                        PlatformScreen(
                            platform = platform,
                            state = state,
                            sort = sortFor(platform.id),
                            onSort = { sortByPlatform[platform.id] = it },
                            playtime = { g, p -> vm.playtimeFor(g, p) },
                            // Auto-fetch disabled per user request — art now comes from the
                            // manual "SEARCH" flow in the game edit screen (or the bulk art
                            // picker at Settings → Art → "Pick art for missing").
                            onEnsureArt = { _, _ -> },
                            onOpenGame = { p, g -> push(Route.GameDetail(p.id, g.id)) },
                            onLaunchGame = { p, g -> launchFlow(p, g) },
                            onEditPlatform = { push(Route.PlatformEdit(platform.id)) },
                            onTab = { t -> replace(tabToRoute(t)) },
                            onGetGames = if (romsToolOn && platform.id != WindowsSource.PLATFORM_ID &&
                                platform.id != AndroidAppSource.PLATFORM_ID) ({ push(Route.RomBrowse(platform.id)) }) else null,
                            unplayedSort = unplayedSort,
                            onUnplayedSort = { unplayedSort = it },
                        )
                    }
                }

                is Route.GameDetail -> {
                    androidx.compose.runtime.LaunchedEffect(r) { vm.refreshPlaytime() }
                    val platform = state.platforms.firstOrNull { it.id == r.platformId }
                    val game = state.gamesByPlatform[r.platformId]?.firstOrNull { it.id == r.gameId }
                    if (platform != null && game != null) {
                        val players = remember(game.id, platform, state.gamesByPlatform) {
                            if (platform.id == WindowsSource.PLATFORM_ID)
                                WindowsLauncher.variants(game).map {
                                    PickerOption(it.key, WindowsLauncher.label(context, it), it.file.name)
                                }
                            else platform.intents.map { PickerOption(it.id, it.name, it.targetPackage()) }
                        }
                        var currentPlayer by remember(game.id) { mutableStateOf<String?>(null) }
                        androidx.compose.runtime.LaunchedEffect(game.id, players) {
                            currentPlayer = app.prefs.lastPlayer(game.id)?.takeIf { id -> players.any { it.id == id } }
                                ?: vm.overrideFor(game.id)?.launchIntentId?.takeIf { id -> players.any { it.id == id } }
                                ?: platform.defaultIntentId?.takeIf { id -> players.any { it.id == id } }
                                ?: players.firstOrNull()?.id
                        }
                        GameDetailScreen(
                            platform = platform, game = game, state = state,
                            playtime = { g, p -> vm.playtimeFor(g, p) },
                            onEnsureArt = { _, _ -> },
                            onLaunch = { launchFlow(platform, game) },
                            players = players,
                            currentPlayerId = currentPlayer,
                            onLaunchWith = { id -> currentPlayer = id; launchFlow(platform, game, forcedPlayer = id) },
                            loadInfo = {
                                val o = vm.overrideFor(game.id)?.displayNameOverride?.takeIf { it.isNotBlank() }
                                com.maiconerick.xgamelauncher.art.GameInfoRepo.get(context, app.igdb, game.id, o ?: game.name)
                            },
                            loadSessions = {
                                val o = vm.overrideFor(game.id)?.displayNameOverride?.takeIf { it.isNotBlank() }
                                vm.sessionsFor(o ?: game.name, platform.name)
                            },
                            onEndSession = { vm.endSession(context) },
                            onBack = { pop() },
                            onEditGame = { push(Route.GameEdit(platform.id, game.id)) },
                            onEditSessions = {
                                scope.launch {
                                    val o = vm.overrideFor(game.id)?.displayNameOverride?.takeIf { it.isNotBlank() }
                                    push(Route.PlaytimeEditor(o ?: game.name))
                                }
                            },
                        )
                    } else replace(Route.Home)
                }

                is Route.GameEdit -> {
                    val platform = state.platforms.firstOrNull { it.id == r.platformId }
                    val game = state.gamesByPlatform[r.platformId]?.firstOrNull { it.id == r.gameId }
                    if (platform != null && game != null) {
                        GameEditScreen(
                            platform = platform, game = game, state = state,
                            onSaveOverride = { row -> vm.saveOverride(row) },
                            onClearOverride = { vm.clearOverride(game.id) },
                            onDone = { pop() },
                            requestPicker = setPicker,
                            pickerShowing = { pickerState.value != null },
                        )
                    } else pop()
                }

                Route.Search -> SearchScreen(
                    state = state,
                    playtime = { g, p -> vm.playtimeFor(g, p) },
                    onEnsureArt = { _, _ -> },
                    onOpenGame = { p, g -> push(Route.GameDetail(p.id, g.id)) },
                    onTab = { t -> replace(tabToRoute(t)) },
                )

                Route.Apps -> AppsScreen(
                    state = state,
                    onTab = { t -> replace(tabToRoute(t)) },
                    onAppRenamed = { com.maiconerick.xgamelauncher.ui.LibraryBus.bump() },
                )

                is Route.NameReview -> com.maiconerick.xgamelauncher.ui.screens.NameReviewScreen(
                    state = state,
                    gameIds = r.gameIds,
                    onDone = { edited ->
                        vm.renameGames(edited)
                        // Games whose name changed get their art picked with the new name.
                        pop(); if (r.artIds.isNotEmpty()) stack.add(Route.ArtPicker(r.artIds))
                    },
                    onCancel = { pop() },
                )

                is Route.ArtPicker -> {
                    com.maiconerick.xgamelauncher.ui.screens.ArtPickerScreen(
                        vm = vm, state = state,
                        gameIds = r.gameIds,
                        onDone = { pop() },
                    )
                }

                is Route.PlatformEdit -> {
                    val existing = r.platformId?.let { pid ->
                        state.platforms.firstOrNull { it.id == pid }
                    }
                    // The new platform's id must be stable for the whole editing session —
                    // a fresh timestamp per recomposition reset the editor (it's keyed on it).
                    val newId = remember(r) { "custom_" + System.currentTimeMillis() }
                    val initial = existing ?: com.maiconerick.xgamelauncher.data.Platform(
                        id = newId,
                        name = "", romsPath = "",
                    )
                    val isAndroidPlatform = initial.id == AndroidAppSource.PLATFORM_ID
                    PlatformEditScreen(
                        initial = initial,
                        initialTab = r.initialTab,
                        library = state,
                        prefs = app.prefs,
                        isNew = existing == null,
                        onSave = { p -> vm.savePlatform(p); pop() },
                        onDelete = if (existing != null) {
                            { vm.deletePlatform(existing.id); pop() }
                        } else null,
                        onBack = { pop() },
                        androidApps = if (isAndroidPlatform)
                            state.androidRowByGameId.values.sortedBy { it.label.lowercase() }
                        else emptyList(),
                        onManageAndroid = if (isAndroidPlatform)
                            ({ openSettings("android") }) else null,
                        playtimeSec = remember(initial.id, state.gamesByPlatform) {
                            state.gamesByPlatform[initial.id].orEmpty().sumOf { vm.playtimeFor(it.name, initial.name)?.totalSeconds ?: 0L }
                        },
                    )
                }

                Route.Tools -> com.maiconerick.xgamelauncher.ui.screens.ToolsScreen(
                    prefs = app.prefs,
                    onTab = { t -> replace(tabToRoute(t)) },
                    onOpen = { t -> lastToolId = t.id; push(Route.Tool(t.id)) },
                    focusToolId = lastToolId,
                )

                is Route.Tool -> {
                    val tool = com.maiconerick.xgamelauncher.tools.Tool.byId(r.id)
                    if (tool == null) pop() else com.maiconerick.xgamelauncher.ui.screens.ToolPage(
                        prefs = app.prefs, tool = tool,
                        onTab = { t -> replace(tabToRoute(t)) },
                        onBrowse = { push(Route.RomBrowse(null)) },
                    )
                }

                is Route.RomBrowse -> com.maiconerick.xgamelauncher.ui.screens.RomBrowseScreen(
                    platforms = state.platforms, platformId = r.platformId,
                    onTab = { t -> replace(tabToRoute(t)) },
                    onOpenEntry = { slug -> push(Route.RomEntry(slug, r.platformId)) },
                )

                is Route.RomEntry -> com.maiconerick.xgamelauncher.ui.screens.RomEntryScreen(
                    prefs = app.prefs, slug = r.slug, platforms = state.platforms, platformId = r.platformId,
                    onTab = { t -> replace(tabToRoute(t)) },
                )

                is Route.Settings -> SettingsScreen(
                    vm = vm,
                    prefs = app.prefs,
                    initialSectionId = r.initialSectionId,
                    onTab = { t -> replace(tabToRoute(t)) },
                    onRefreshLibrary = { vm.refresh() },
                    onEditPlatform = { pid -> push(Route.PlatformEdit(pid)) },
                    onOpenSection = { id -> lastSettingsSection = id; push(Route.Settings(id)) },
                    focusSectionId = lastSettingsSection,
                    onEditPlaytime = { push(Route.PlaytimeEditor()) },
                )

                is Route.PlaytimeEditor -> com.maiconerick.xgamelauncher.ui.screens.PlaytimeEditorScreen(
                    vm = vm, initialFilter = r.filter, onBack = { pop() },
                )
            }

            }
            if (current != Route.Boot && !com.maiconerick.xgamelauncher.ui.AppChrome.hideBottomBar) {
                val stripActive = when (val c = current) {
                    Route.Home -> homeFocusedPlatformId
                    else -> activePlatformId
                }
                com.maiconerick.xgamelauncher.ui.components.BottomBar(
                    platforms = remember(state.platforms, barIcons, miniIcons) {
                        state.platforms.map {
                            com.maiconerick.xgamelauncher.ui.components.StripPlatform(
                                it.id, it.name, barIcons[it.id] ?: it.iconImagePath, miniIcons[it.id],
                            )
                        }
                    },
                    activeId = stripActive,
                    useIcons = stripIcons,
                    iconDp = com.maiconerick.xgamelauncher.prefs.PrefsStore.stripIconDp(stripIconSize) *
                        (if (profile.compact) 0.75f else 1f),
                    miniIcons = stripMini,
                    focusActiveOnEnter = current == Route.Home,
                    onFocusPlatform = { id -> if (current == Route.Home) homeFocusedPlatformId = id },
                    onClickPlatform = { id ->
                        when {
                            current == Route.Home && homeFocusedPlatformId == id -> push(Route.Games(id))
                            current == Route.Home -> homeFocusedPlatformId = id
                            else -> replace(Route.Games(id))
                        }
                    },
                    hints = hintsFor(current),
                    showHints = showHints,
                    onPressButton = { b -> (context as? LauncherActivityBase)?.pressButton(b) },
                )
            }
            }

            pickerState.value?.let { req -> PickerHost(request = req) }
        }
    }
}

/** A saturated, bright random hue — reads as "neon" on the black UI. */
private fun randomNeonAccent(): Color {
    val hue = kotlin.random.Random.nextFloat() * 360f
    val sat = 0.75f + kotlin.random.Random.nextFloat() * 0.25f
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f)))
}

/** Parses a `#RRGGBB` / `#AARRGGBB` accent hex — returns null on empty or invalid input. */
private fun parseAccentColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val h = hex.trim().removePrefix("#")
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> null
    }
}

/** Builds a full NeonPalette from an accent color — keeps the base neutrals and swaps
 *  the accent + soft (30% darker via HSV V multiplier) + glow (40% alpha) shades. */
internal fun paletteFromAccent(
    accent: Color,
    base: com.maiconerick.xgamelauncher.ui.theme.NeonPalette =
        com.maiconerick.xgamelauncher.ui.theme.NeonPalette.Cyan,
): com.maiconerick.xgamelauncher.ui.theme.NeonPalette {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.rgb(
            (accent.red * 255).toInt(),
            (accent.green * 255).toInt(),
            (accent.blue * 255).toInt(),
        ),
        hsv,
    )
    val soft = FloatArray(3).apply {
        this[0] = hsv[0]; this[1] = hsv[1]
        this[2] = (hsv[2] * 0.70f).coerceIn(0.10f, 1f)
    }
    val softColor = Color(android.graphics.Color.HSVToColor(soft))
    return base.copy(
        accent = accent,
        accentSoft = softColor,
        accentGlow = accent.copy(alpha = 0.4f),
    )
}

/** Once-per-process launch prompts (home app, update) + home-app helpers. */
internal object LaunchPrompts {
    var shown = false

    fun isDefaultHome(ctx: android.content.Context): Boolean = runCatching {
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        ctx.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == ctx.packageName
    }.getOrDefault(true)

    /** Root: set it directly; otherwise open Android's default-home picker. */
    fun setDefaultHome(ctx: android.content.Context) {
        val out = com.maiconerick.xgamelauncher.util.RootSession.exec(
            "cmd package set-home-activity ${ctx.packageName}/.HomeRouterActivity",
        )
        if (out?.contains("Success", ignoreCase = true) != true) runCatching {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
