package com.maiconerick.xgamelauncher.bottom

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.maiconerick.xgamelauncher.AppRoot
import com.maiconerick.xgamelauncher.LauncherActivityBase
import com.maiconerick.xgamelauncher.XGameLauncherApp
import com.maiconerick.xgamelauncher.data.AppDatabase
import com.maiconerick.xgamelauncher.data.SessionRow
import com.maiconerick.xgamelauncher.ui.LocalPicker
import com.maiconerick.xgamelauncher.ui.PickerOption
import com.maiconerick.xgamelauncher.ui.PickerRequest
import com.maiconerick.xgamelauncher.ui.Route
import com.maiconerick.xgamelauncher.ui.components.focusRing
import com.maiconerick.xgamelauncher.ui.dialogs.PickerHost
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import com.maiconerick.xgamelauncher.ui.theme.XGameLauncherTheme
import com.maiconerick.xgamelauncher.util.RootSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Second home screen for the Thor's bottom display (reached through HomeRouterActivity).
 * Background (system wallpaper / image / looping video), horizontally swiped widget pages,
 * long-press drag to reorder, edge-drag to resize, long-press empty space for the menu, and
 * a swipe-up drawer (swipe down to close) that embeds the full launcher UI.
 */
class BottomHomeActivity : LauncherActivityBase() {

    lateinit var widgetHost: AppWidgetHost
    private var pendingWidgetId = -1
    private var onWidgetAdded: ((Int) -> Unit)? = null
    private var onMediaPicked: ((Uri) -> Unit)? = null

    private val bindLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = pendingWidgetId
        if (r.resultCode == RESULT_OK && id >= 0) configureOrFinish(id) else cancelPending()
    }
    private val configureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = pendingWidgetId
        if (r.resultCode == RESULT_OK && id >= 0) { onWidgetAdded?.invoke(id); pendingWidgetId = -1 } else cancelPending()
    }
    private val mediaLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onMediaPicked?.invoke(uri)
        }
    }

    fun pickMedia(mime: String, cb: (Uri) -> Unit) { onMediaPicked = cb; mediaLauncher.launch(arrayOf(mime)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A process started by a system backup runs with a plain Application (our app class
        // isn't created): don't try to show the launcher from it — exit so the next launch
        // starts a normal process.
        if (application !is com.maiconerick.xgamelauncher.XGameLauncherApp) {
            finish(); android.os.Process.killProcess(android.os.Process.myPid()); return
        }
        widgetHost = AppWidgetHost(this, HOST_ID)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent { XGameLauncherTheme { BottomHome(this) } }
    }

    override fun onStart() { super.onStart(); runCatching { widgetHost.startListening() } }
    override fun onStop() { super.onStop(); runCatching { widgetHost.stopListening() } }

    /** Allocates an id and binds [provider]; root grantbind avoids the system consent prompt. */
    fun addAndroidWidget(provider: AppWidgetProviderInfo, onAdded: (Int) -> Unit) {
        val mgr = AppWidgetManager.getInstance(this)
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        onWidgetAdded = onAdded
        if (mgr.bindAppWidgetIdIfAllowed(id, provider.provider)) configureOrFinish(id)
        else bindLauncher.launch(
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
        )
    }

    private fun configureOrFinish(id: Int) {
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if (info?.configure != null) {
            runCatching {
                configureLauncher.launch(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(info.configure)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                )
            }.onFailure { onWidgetAdded?.invoke(id); pendingWidgetId = -1 }
        } else { onWidgetAdded?.invoke(id); pendingWidgetId = -1 }
    }

    private fun cancelPending() {
        if (pendingWidgetId >= 0) widgetHost.deleteAppWidgetId(pendingWidgetId)
        pendingWidgetId = -1
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        const val HOST_ID = 4242
        private const val LAYOUT_KEY = "bottom_layout"
        const val BG_KIND = "bottom_bg_kind"      // system | animated | image | video | black
        const val DRAWER_OPAQUE = "bottom_drawer_opaque"
        const val BG_URI = "bottom_bg_uri"
        suspend fun loadLayout(app: XGameLauncherApp) = BottomLayoutCodec.decode(app.prefs.toolString(LAYOUT_KEY, "").first())
        suspend fun saveLayout(app: XGameLauncherApp, pages: List<List<WidgetSpec>>) =
            app.prefs.setToolString(LAYOUT_KEY, BottomLayoutCodec.encode(pages))
    }
}

@Composable
private fun BottomHome(act: BottomHomeActivity) {
    val app = act.application as XGameLauncherApp
    val scope = rememberCoroutineScope()
    var pages by remember { mutableStateOf<List<List<WidgetSpec>>?>(null) }
    LaunchedEffect(Unit) { pages = BottomHomeActivity.loadLayout(app) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { RootSession.exec("cmd appwidget grantbind --package ${act.packageName}") }
    }
    var drawerOpen by remember { mutableStateOf(false) }
    val drawerOpaque by app.prefs.toolFlag(BottomHomeActivity.DRAWER_OPAQUE, false).collectAsState(initial = false)
    var autoLaunch by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editing by remember { mutableStateOf(false) }
    var gallery by remember { mutableStateOf(false) }
    val pickerState = remember { mutableStateOf<PickerRequest?>(null) }
    fun update(p: List<List<WidgetSpec>>) { pages = p; scope.launch { BottomHomeActivity.saveLayout(app, p) } }

    val vm: com.maiconerick.xgamelauncher.ui.LauncherViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val lib by vm.state.collectAsState()
    fun playAgain(s: SessionRow) {
        val p = lib.platforms.firstOrNull { it.name.equals(s.platform, true) } ?: return
        val g = lib.gamesByPlatform[p.id]?.firstOrNull { gm ->
            gm.name.equals(s.gameName, true) || lib.overrideByGameId[gm.id]?.displayNameOverride.equals(s.gameName, true)
        } ?: return
        autoLaunch = p.id to g.id
        drawerOpen = true
    }

    // Same accent as the top-screen launcher (one random accent per app session).
    val uiMode by app.prefs.uiProfile.collectAsState(initial = "auto")
    val profile = remember(uiMode) { com.maiconerick.xgamelauncher.ui.DeviceProfile.detect(act, uiMode) }
    val themeId by app.prefs.themeId.collectAsState(initial = app.themeIdHint)
    val appTheme = remember(themeId) { com.maiconerick.xgamelauncher.ui.theme.AppTheme.byId(themeId) }
    val palette = remember(appTheme) {
        appTheme.palette ?: com.maiconerick.xgamelauncher.paletteFromAccent(Color(app.sessionAccentArgb))
    }

    // One shared sampler for every stat card (only while this screen is resumed).
    val needStats = pages.orEmpty().flatten().any { it.type in BuiltinWidgets.SMALL || it.type == BuiltinWidgets.SYSTEM }
    var snap by remember { mutableStateOf<SysSnapshot?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(needStats) {
        if (!needStats) return@LaunchedEffect
        val stats = SysStats(act.applicationContext)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                var tick = 0
                while (true) {
                    val pkg = withContext(Dispatchers.IO) { AppDatabase.get(act).sessionDao().openSessions().firstOrNull()?.pkg }
                    snap = withContext(Dispatchers.IO) { runCatching { stats.sample(includeFps = tick % 2 == 0, fpsPkg = pkg) }.getOrNull() }
                    tick++
                    delay(1000)
                }
            } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { FpsMeter.stop() } }
        }
    }

    CompositionLocalProvider(
        LocalPicker provides { pickerState.value = it },
        com.maiconerick.xgamelauncher.ui.LocalDeviceProfile provides profile,
        LocalNeon provides palette,
        com.maiconerick.xgamelauncher.ui.theme.LocalAppTheme provides appTheme,
        LocalSysSnap provides snap,
    ) {
        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when {
                    drawerOpen -> false
                    e.key == Key.ButtonY -> { drawerOpen = true; true }
                    // Start / Menu = the long-press menu for gamepads.
                    e.key == Key.ButtonStart || e.key == Key.Menu -> {
                        pickerState.value = homeMenu(act, app, pages.orEmpty(), editing,
                            onEdit = { editing = !editing }, onGallery = { gallery = true },
                            onUpdate = { update(it) }, setPicker = { pickerState.value = it })
                        true
                    }
                    editing && e.key == Key.ButtonB -> { editing = false; true }
                    else -> false
                }
            },
        ) {
            BottomBackground(app)
            val current = pages
            if (current != null) HomePages(
                act = act, pages = current, editing = editing,
                onEditing = { editing = it },
                onChange = { update(it) },
                onPlayAgain = { playAgain(it) },
                onOpenDrawer = { drawerOpen = true },
                onMenu = {
                    pickerState.value = homeMenu(act, app, current, editing,
                        onEdit = { editing = !editing }, onGallery = { gallery = true },
                        onUpdate = { update(it) }, setPicker = { pickerState.value = it })
                },
            )

            AnimatedVisibility(
                visible = drawerOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                // Swipe down to close: overscroll at the top of any list, or a drag on a
                // non-scrolling area.
                var acc by remember { mutableStateOf(0f) }
                val closeConn = remember {
                    object : NestedScrollConnection {
                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                            if (source == NestedScrollSource.UserInput && available.y > 0) {
                                acc += available.y
                                if (acc > 220f) { acc = 0f; drawerOpen = false; autoLaunch = null }
                            }
                            return Offset.Zero
                        }
                        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                            if (available.y > 2200f) { drawerOpen = false; autoLaunch = null }
                            acc = 0f
                            return Velocity.Zero
                        }
                    }
                }
                Box(
                    Modifier.fillMaxSize().background(if (drawerOpaque) Color.Transparent else Color(0xD9000000))
                        .nestedScroll(closeConn)
                        .pointerInput(Unit) {
                            var dy = 0f
                            detectVerticalDragGestures(
                                onDragStart = { dy = 0f },
                                onVerticalDrag = { _, d -> dy += d },
                                onDragEnd = { if (dy > 160f) { drawerOpen = false; autoLaunch = null } },
                            )
                        },
                ) {
                    // Not transparent: the top screen's moving starfield behind the drawer.
                    if (drawerOpaque) com.maiconerick.xgamelauncher.ui.theme.ThemedBackdrop(
                        modifier = Modifier.fillMaxSize(), animate = com.maiconerick.xgamelauncher.ui.animationsOn(),
                    )
                    androidx.compose.runtime.key(autoLaunch) {
                        AppRoot(
                            unhandledShoulder = act.unhandledShoulder,
                            startRoute = if (autoLaunch != null) Route.Home else Route.Apps,
                            autoLaunch = autoLaunch,
                            embedded = true,
                            onExitRoot = { drawerOpen = false; autoLaunch = null },
                        )
                    }
                }
            }

            if (gallery) WidgetGallery(
                act = act,
                onBuiltin = { type ->
                    gallery = false
                    update(addToCurrentPage(pages.orEmpty(), WidgetSpec(type, option = if (type == BuiltinWidgets.PLAYTIME) "week" else "")))
                },
                onAndroid = { p ->
                    gallery = false
                    act.addAndroidWidget(p) { wid ->
                        val h = (p.minHeight / act.resources.displayMetrics.density).toInt().coerceAtLeast(80)
                        update(addToCurrentPage(pages.orEmpty(), WidgetSpec(BuiltinWidgets.ANDROID, androidId = wid, heightDp = h)))
                    }
                },
                onClose = { gallery = false },
            )
            pickerState.value?.let { PickerHost(it) }
        }
    }
}

/** Page the user is looking at, remembered for "add widget". */
private var currentPageIndex = 0

private fun addToCurrentPage(pages: List<List<WidgetSpec>>, spec: WidgetSpec): List<List<WidgetSpec>> =
    if (pages.isEmpty()) listOf(listOf(spec))
    else pages.mapIndexed { i, p -> if (i == currentPageIndex.coerceIn(0, pages.lastIndex)) p + spec else p }

/** System wallpaper (transparent), a picked image, a looping muted video, or black. */
@Composable
private fun BottomBackground(app: XGameLauncherApp) {
    val kind by app.prefs.toolString(BottomHomeActivity.BG_KIND, "system").collectAsState(initial = "system")
    val uri by app.prefs.toolString(BottomHomeActivity.BG_URI, "").collectAsState(initial = "")
    when (kind) {
        "black" -> Box(Modifier.fillMaxSize().background(Color.Black))
        // The top launcher's animated starfield (frozen by the pause button).
        "animated" -> com.maiconerick.xgamelauncher.ui.theme.ThemedBackdrop(
            modifier = Modifier.fillMaxSize(), animate = com.maiconerick.xgamelauncher.ui.animationsOn(),
        )
        "image" -> if (uri.isNotBlank()) AsyncImage(uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        "video" -> if (uri.isNotBlank()) VideoBackground(Uri.parse(uri))
    }
}

@Composable
private fun VideoBackground(uri: Uri) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(uri) { android.media.MediaPlayer() }
    DisposableEffect(uri) { onDispose { runCatching { player.release() } } }
    // The top bar's pause button stops the video too (this screen stays resumed during games).
    val paused = com.maiconerick.xgamelauncher.ui.AppChrome.animationsPaused
    LaunchedEffect(paused) { runCatching { if (paused) player.pause() else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) player.start() } }
    // Pause while this screen is in the background (saves battery during games).
    DisposableEffect(lifecycle, player) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            runCatching {
                when (e) {
                    Lifecycle.Event.ON_RESUME -> if (!com.maiconerick.xgamelauncher.ui.AppChrome.animationsPaused) player.start()
                    Lifecycle.Event.ON_PAUSE -> player.pause()
                    else -> Unit
                }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    AndroidView(
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        runCatching {
                            player.setDataSource(ctx, uri)
                            player.setSurface(android.view.Surface(st))
                            player.isLooping = true
                            player.setVolume(0f, 0f)
                            player.setOnPreparedListener { mp ->
                                // Center-crop: scale the texture to cover the view.
                                val vw = mp.videoWidth.toFloat(); val vh = mp.videoHeight.toFloat()
                                if (vw > 0 && vh > 0) {
                                    val s = maxOf(w / vw, h / vh)
                                    val m = android.graphics.Matrix()
                                    m.setScale(vw * s / w, vh * s / h, w / 2f, h / 2f)
                                    setTransform(m)
                                }
                                mp.start()
                            }
                            player.prepareAsync()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun HomePages(
    act: BottomHomeActivity,
    pages: List<List<WidgetSpec>>,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onChange: (List<List<WidgetSpec>>) -> Unit,
    onPlayAgain: (SessionRow) -> Unit,
    onOpenDrawer: () -> Unit,
    onMenu: () -> Unit,
) {
    val neon = LocalNeon.current
    val density = LocalDensity.current
    val pager = rememberPagerState { pages.size.coerceAtLeast(1) }
    LaunchedEffect(pager.currentPage) { currentPageIndex = pager.currentPage }
    val scope = rememberCoroutineScope()
    val bounds = remember { mutableStateMapOf<Long, Rect>() }
    var pageSizeDp by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var selected by remember { mutableStateOf<Long?>(null) }
    // Live geometry while moving / resizing (dp): key → (x, y, w, h). Committed on release.
    val live = remember { mutableStateMapOf<Long, androidx.compose.ui.geometry.Rect>() }
    LaunchedEffect(editing) { if (!editing) selected = null }

    // Place anything that has no position yet (older layouts, newly added widgets).
    LaunchedEffect(pages, pageSizeDp) {
        if (pageSizeDp.width <= 0) return@LaunchedEffect
        val placed = pages.map { clampToPage(autoPlace(it, pageSizeDp.width, pageSizeDp.height), pageSizeDp.width, pageSizeDp.height) }
        if (placed != pages) onChange(placed)
    }

    // Gesture handlers outlive recompositions: they must read the *current* layout,
    // never the WidgetSpec captured when the handler was created (that made the second
    // drag start from the old position).
    val pagesNow by rememberUpdatedState(pages)
    val onChangeNow by rememberUpdatedState(onChange)

    fun rectOf(w: WidgetSpec) = live[w.key]
        ?: androidx.compose.ui.geometry.Rect(w.x.toFloat(), w.y.toFloat(), (w.x + w.w).toFloat(), (w.y + w.heightDp).toFloat())

    /** Current geometry of a widget by key (live drag state, else the latest saved layout). */
    fun rectOfKey(key: Long) = live[key] ?: pagesNow.flatten().firstOrNull { it.key == key }?.let { rectOf(it) }

    /** Clamps into the page and writes the geometry back. */
    fun commit(pageIdx: Int, key: Long) {
        val r = live.remove(key) ?: return
        val pw = pageSizeDp.width.toFloat().coerceAtLeast(1f)
        val ph = pageSizeDp.height.toFloat().coerceAtLeast(1f)
        val w = r.width.coerceIn(48f, pw)
        val h = r.height.coerceIn(30f, ph)
        val x = r.left.coerceIn(0f, pw - w)
        val y = r.top.coerceIn(0f, ph - h)
        onChangeNow(pagesNow.mapIndexed { pi, pg ->
            if (pi != pageIdx) pg else pg.map { s -> if (s.key == key) s.copy(x = x.toInt(), y = y.toInt(), w = w.toInt(), heightDp = h.toInt()) else s }
        })
    }

    fun toDp(px: Float) = with(density) { px.toDp().value }

    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.ButtonL1 -> { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }; true }
                    Key.ButtonR1 -> { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(pages.size - 1)) }; true }
                    else -> false
                }
            },
    ) {
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager, modifier = Modifier.weight(1f),
            // Editing: horizontal drags move widgets, so paging is L1/R1 / the dots.
            userScrollEnabled = !editing && live.isEmpty(),
        ) { pageIdx ->
            val page = pages.getOrElse(pageIdx) { emptyList() }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(pageIdx, editing) {
                        detectTapGestures(
                            onTap = { if (editing) selected = null },
                            // Long-press on empty space (not on a widget) opens the menu.
                            onLongPress = { p ->
                                val root = Offset(p.x, p.y) + (pageOrigin[pageIdx] ?: Offset.Zero)
                                if (bounds.values.none { it.contains(root) }) onMenu()
                            },
                        )
                    }
                    // Pages don't scroll: a swipe up anywhere opens the drawer instead.
                    .pointerInput(pageIdx, editing) {
                        if (editing) return@pointerInput
                        var dragged = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragEnd = { if (live.isEmpty() && dragged < -160f) onOpenDrawer() },
                        ) { _, dy -> dragged += dy }
                    }
                    .clipToBounds()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .onGloballyPositioned {
                        pageOrigin[pageIdx] = it.boundsInRoot().topLeft
                        pageSizeDp = with(density) { androidx.compose.ui.unit.IntSize(it.size.width.toDp().value.toInt(), it.size.height.toDp().value.toInt()) }
                    },
            ) {
                if (page.isEmpty()) Text("Empty page — long-press to add widgets", color = neon.inkDim, fontSize = 13.sp)
                page.forEachIndexed { z, w ->
                    if (!w.placed && live[w.key] == null) return@forEachIndexed
                    val r = rectOf(w)
                    val isSel = editing && selected == w.key
                    Box(
                        Modifier
                            .offset(r.left.dp, r.top.dp)
                            .size(r.width.dp, r.height.dp)
                            .zIndex(if (live.containsKey(w.key)) 1000f else z.toFloat())
                            .onGloballyPositioned { bounds[w.key] = it.boundsInRoot() }
                            .let { m ->
                                // Outside edit mode: long-press starts editing and moves the widget.
                                if (editing) m else m.longPressDrag(
                                    onStart = { onEditing(true); selected = w.key; rectOfKey(w.key)?.let { live[w.key] = it } },
                                    onDrag = { d -> live[w.key]?.let { live[w.key] = it.translate(toDp(d.x), toDp(d.y)) } },
                                    onEnd = { commit(pageIdx, w.key) },
                                )
                            },
                    ) {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))) {
                            WidgetView(act, w, onPlayAgain, onOption = { opt ->
                                onChange(pages.mapIndexed { pi, pg -> if (pi == pageIdx) pg.map { x -> if (x.key == w.key) x.copy(option = opt) else x } else pg })
                            })
                        }
                        // Editing: a cover that selects on tap and moves on drag (widget taps are blocked).
                        if (editing) Box(
                            Modifier.fillMaxSize()
                                .background(if (isSel) neon.accent.copy(alpha = 0.08f) else Color.Transparent)
                                .pointerInput(w.key) { detectTapGestures { selected = w.key } }
                                .pointerInput(w.key) {
                                    detectDragGestures(
                                        onDragStart = { selected = w.key; rectOfKey(w.key)?.let { live[w.key] = it } },
                                        onDragEnd = { commit(pageIdx, w.key) },
                                        onDragCancel = { commit(pageIdx, w.key) },
                                    ) { c, d -> c.consume(); live[w.key]?.let { live[w.key] = it.translate(toDp(d.x), toDp(d.y)) } }
                                },
                        )
                    }
                }
                // Selection frame with four edge dots (Nova-style), drawn above every widget.
                val selW = page.firstOrNull { editing && it.key == selected && it.placed }
                if (selW != null) {
                    val r = rectOf(selW)
                    val dot = 22.dp
                    val half = 11f
                    Box(
                        Modifier.offset((r.left - half).dp, (r.top - half).dp)
                            .size((r.width + half * 2).dp, (r.height + half * 2).dp)
                            .zIndex(2000f),
                    ) {
                        Box(Modifier.fillMaxSize().padding(half.dp).border(2.dp, neon.accent, RoundedCornerShape(14.dp)))
                        fun edge(dx: Float, dy: Float, left: Boolean, top: Boolean, horizontal: Boolean) {
                            val cur = rectOfKey(selW.key) ?: return
                            live[selW.key] = if (horizontal) {
                                if (left) cur.copy(left = (cur.left + dx).coerceAtMost(cur.right - 48f))
                                else cur.copy(right = (cur.right + dx).coerceAtLeast(cur.left + 48f))
                            } else {
                                if (top) cur.copy(top = (cur.top + dy).coerceAtMost(cur.bottom - 30f))
                                else cur.copy(bottom = (cur.bottom + dy).coerceAtLeast(cur.top + 30f))
                            }
                        }
                        listOf(
                            Triple(Alignment.CenterStart, "l", true),
                            Triple(Alignment.CenterEnd, "r", true),
                            Triple(Alignment.TopCenter, "t", false),
                            Triple(Alignment.BottomCenter, "b", false),
                        ).forEach { (align, id, horizontal) ->
                            Box(
                                Modifier.align(align).size(dot).clip(CircleShape).background(neon.accent)
                                    .border(2.dp, Color.Black.copy(alpha = 0.5f), CircleShape)
                                    .pointerInput(selW.key, id) {
                                        detectDragGestures(
                                            onDragEnd = { commit(pageIdx, selW.key) },
                                            onDragCancel = { commit(pageIdx, selW.key) },
                                        ) { c, d ->
                                            c.consume()
                                            edge(toDp(d.x), toDp(d.y), left = id == "l", top = id == "t", horizontal = horizontal)
                                        }
                                    },
                            )
                        }
                    }
                }
            }
        }
        if (pages.size > 1) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) neon.accent else Color(0x66FFFFFF))
                    .clickable { scope.launch { pager.animateScrollToPage(i) } })
            }
        }
    }
    // Edit bar floats over the page so widgets don't shift when editing starts.
    if (editing) Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().zIndex(3000f).background(Color(0xCC000000))
        .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (selected == null) "Tap a widget · drag to move · drag the dots to resize" else "Drag to move · drag the dots to resize",
                color = neon.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val sel = selected
            if (sel != null) {
                val pageIdx = pager.currentPage
                Chip("TO FRONT") {
                    onChange(pages.mapIndexed { pi, pg ->
                        if (pi != pageIdx) pg else pg.filterNot { it.key == sel } + pg.filter { it.key == sel }
                    })
                }
                Chip("✕ REMOVE") {
                    pages.getOrNull(pageIdx)?.firstOrNull { it.key == sel }?.let { w ->
                        if (w.type == BuiltinWidgets.ANDROID) act.widgetHost.deleteAppWidgetId(w.androidId)
                    }
                    onChange(pages.mapIndexed { pi, pg -> if (pi == pageIdx) pg.filterNot { it.key == sel } else pg })
                    selected = null
                }
            }
            Chip("DONE", accent = true) { onEditing(false) }
        }
    }
}

/** Page top-left in root coordinates (for the empty-space long-press hit test). */
private val pageOrigin = HashMap<Int, Offset>()

/**
 * Long-press then drag, detected in the Initial pass so it also works on top of hosted
 * Android widgets (which swallow touches). Taps and scrolls pass through untouched.
 */
private fun Modifier.longPressDrag(onStart: () -> Unit, onDrag: (Offset) -> Unit, onEnd: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var result = false
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { result = true; break }
                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                result
            }
            if (released != null) return@awaitEachGesture   // tap or scroll: not ours
            onStart()
            var last = down.position
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                c.consume()
                if (!c.pressed) break
                onDrag(c.position - last)
                last = c.position
            }
            onEnd()
        }
    }



@Composable
private fun Chip(label: String, accent: Boolean = false, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.focusRing(focused, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
            .background(if (accent) neon.accent.copy(alpha = 0.3f) else Color(0xCC000000))
            .border(1.dp, neon.accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }.focusable()
            .onGamepadActivate(onClick).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = if (accent) neon.accent else neon.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
internal fun WidgetView(act: BottomHomeActivity?, w: WidgetSpec, onPlayAgain: (SessionRow) -> Unit, onOption: (String) -> Unit) {
    when (w.type) {
        BuiltinWidgets.CLOCK -> ClockWidget()
        BuiltinWidgets.LIVE -> LiveSessionWidget()
        BuiltinWidgets.LAST_PLAYED -> LastPlayedWidget(onPlayAgain)
        BuiltinWidgets.PLAYTIME -> PlaytimeWidget(w.option.ifBlank { "week" }) {
            onOption(when (w.option) { "month" -> "year"; "year" -> "week"; else -> "month" })
        }
        BuiltinWidgets.SYSTEM -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatWidget(BuiltinWidgets.STAT_FPS, Modifier.weight(1f))
            StatWidget(BuiltinWidgets.STAT_CPU, Modifier.weight(1f))
            StatWidget(BuiltinWidgets.STAT_BATTERY, Modifier.weight(1f))
        }
        in BuiltinWidgets.SMALL -> StatWidget(w.type, Modifier.fillMaxWidth())
        BuiltinWidgets.SEPARATOR -> SeparatorWidget()
        BuiltinWidgets.INVADERS -> InvadersWidget()
        BuiltinWidgets.CLUSTERTUNE -> ClusterTuneWidget()
        BuiltinWidgets.ANDROID -> {
            if (act == null) return
            val info = remember(w.androidId) { AppWidgetManager.getInstance(act).getAppWidgetInfo(w.androidId) }
            if (info == null) Text("Widget unavailable (app removed?)", color = LocalNeon.current.inkDim, fontSize = 12.sp)
            else AndroidView(
                factory = { ctx -> act.widgetHost.createView(ctx, w.androidId, info).apply { setAppWidget(w.androidId, info) } },
                modifier = Modifier.fillMaxWidth().let { if (w.heightDp > 0) it.fillMaxSize() else it.height(180.dp) }.clip(RoundedCornerShape(14.dp)),
            )
        }
    }
}

/** Long-press menu: add widgets, edit layout, pages, background. */
private fun homeMenu(
    act: BottomHomeActivity,
    app: XGameLauncherApp,
    pages: List<List<WidgetSpec>>,
    editing: Boolean,
    onEdit: () -> Unit,
    onGallery: () -> Unit,
    onUpdate: (List<List<WidgetSpec>>) -> Unit,
    setPicker: (PickerRequest?) -> Unit,
): PickerRequest = PickerRequest(
    title = "Home",
    options = listOf(
        PickerOption("add", "Add widget", "xGL and Android widgets, with previews"),
        PickerOption("edit", if (editing) "Done editing" else "Edit layout", "Drag widgets anywhere (overlap is fine), resize with the edge dots, remove"),
        PickerOption("addpage", "Add page"),
        PickerOption("delpage", "Remove this page"),
        PickerOption("bg", "Background…", "xGL animations, system wallpaper, image, looping video or black"),
    ),
    onPick = { id ->
        setPicker(null)
        when (id) {
            "add" -> onGallery()
            "edit" -> onEdit()
            "addpage" -> onUpdate(pages + listOf(emptyList()))
            "delpage" -> if (pages.size > 1) {
                val p = currentPageIndex.coerceIn(0, pages.lastIndex)
                pages[p].filter { it.type == BuiltinWidgets.ANDROID }.forEach { act.widgetHost.deleteAppWidgetId(it.androidId) }
                onUpdate(pages.filterIndexed { i, _ -> i != p })
            }
            "bg" -> setPicker(PickerRequest(
                title = "Background",
                message = "Live wallpapers only run on this screen if the wallpaper app supports a second display. For Wallpaper Engine, export the wallpaper as a video and pick it here.",
                options = listOf(
                    PickerOption("animated", "xGL animations", "The top screen's starfield; the pause button freezes it"),
                    PickerOption("system", "System wallpaper"),
                    PickerOption("image", "Image…"),
                    PickerOption("video", "Looping video…", "Muted, paused while a game is running"),
                    PickerOption("black", "Black"),
                ),
                onPick = { kind ->
                    setPicker(null)
                    val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
                    when (kind) {
                        "image", "video" -> act.pickMedia(if (kind == "image") "image/*" else "video/*") { uri ->
                            scope.launch {
                                app.prefs.setToolString(BottomHomeActivity.BG_URI, uri.toString())
                                app.prefs.setToolString(BottomHomeActivity.BG_KIND, kind)
                            }
                        }
                        else -> scope.launch { app.prefs.setToolString(BottomHomeActivity.BG_KIND, kind) }
                    }
                },
                onCancel = { setPicker(null) },
            ))
        }
    },
    onCancel = { setPicker(null) },
)
