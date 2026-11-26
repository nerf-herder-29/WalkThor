package com.nerfherder.walkthor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.nerfherder.walkthor.data.LaunchedGame
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.ui.GuideIntro
import com.nerfherder.walkthor.ui.BookColors
import com.nerfherder.walkthor.ui.ControlsScreen
import com.nerfherder.walkthor.ui.GameTab
import com.nerfherder.walkthor.ui.GameTabBar
import com.nerfherder.walkthor.ui.GameTabs
import com.nerfherder.walkthor.ui.MirrorScreen
import com.nerfherder.walkthor.ui.TabsState
import com.nerfherder.walkthor.ui.rememberMirrorFrame
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import com.nerfherder.walkthor.ui.BootScreen
import androidx.activity.compose.BackHandler
import com.nerfherder.walkthor.ui.ImportDialogs
import com.nerfherder.walkthor.ui.LibraryActions
import com.nerfherder.walkthor.ui.LibraryScreen
import com.nerfherder.walkthor.ui.ReaderScreen
import com.nerfherder.walkthor.ui.WalkThorTheme
import com.nerfherder.walkthor.ui.PageStyle
import com.nerfherder.walkthor.ui.AutoDim
import com.nerfherder.walkthor.ui.AutoDimOverlay
import com.nerfherder.walkthor.ui.LocalPageColors
import com.nerfherder.walkthor.ui.DarkPages
import com.nerfherder.walkthor.ui.LightPages
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), WalkThorHost {

    /** A document handed to us by "Open with" / "Share", waiting to be imported. */
    private val incoming = MutableStateFlow<Uri?>(null)
    private val hasOtherScreen = MutableStateFlow(false)
    /** Bumped each time WalkThor is opened by the swipe gesture. */
    private val swipeTick = MutableStateFlow(0L)
    /** Ticks every few seconds while WalkThor is on screen, to notice a game launched meanwhile. */
    private val pollTick = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val guidePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) incoming.value = uri
    }
    /** Your ROMs folder: read its games. */
    private val romFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onRomFolderPicked(this, lifecycleScope, uri)
    }
    /** Your art folder: covers and pictures for your games. */
    private val artFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onArtFolderPicked(this, lifecycleScope, uri)
    }
    // Mirror: Android's "start recording or casting?" prompt.
    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) Mirror.start(this, result.resultCode, data)
    }

    /** Asks Android for permission to capture the top screen (it asks every time). */
    override fun requestMirror() {
        val intent = screenCaptureIntent(this) ?: return
        runCatching { captureConsent.launch(intent) }
            .onFailure { Mirror.error.value = "Couldn't ask for screen capture (${it.javaClass.simpleName})" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        openedAt = android.os.SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        installImageLoader()
        DisplayHelper.init(this)
        SwipeLauncher.init(this)
        PageStyle.init(this)
        AutoDim.init(this)
        // A fresh open always starts bright (the idle clock may have been running in the background).
        AutoDim.wake()
        GameFocus.init(this)
        PerfCheck.init(this)
        // Opening the app puts away the drawer, so there's only ever one WalkThor on screen.
        if (Drawer.shown.value) Drawer.hide()
        if (DisplayHelper.ensureOnBottomScreen(this, savedInstanceState)) return
        // A game may be running: WalkThor never grabs focus from it while starting up.
        GameFocus.gameRunning.value = gameInFront()
        applyGameFocus(initial = true)
        lifecycleScope.launch {
            var typing = GameFocus.wantFocus.value
            kotlinx.coroutines.flow.combine(
                GameFocus.keepOnGame, GameFocus.gameRunning, GameFocus.wantFocus, EmulatorWatch.gameShowing, EmulatorWatch.frontKnown,
            ) { _, _, _, _, _ -> }
                .collect {
                    applyGameFocus(initial = false)
                    // Just finished typing a search: WalkThor took focus for the keyboard, so hand
                    // the game its priority back with the same tap as after opening.
                    val finishedTyping = typing && !GameFocus.wantFocus.value
                    typing = GameFocus.wantFocus.value
                    if (finishedTyping) openedAt = android.os.SystemClock.elapsedRealtime()
                    if (yielding == true && PerfCheck.walkThorIsTop.value == true) scheduleHandBack()
                }
        }
        AppState.activity = java.lang.ref.WeakReference(this)

        if (savedInstanceState == null) handleIntent(intent)
        val initialBook = intent.getStringExtra(DisplayHelper.EXTRA_BOOK)
        // The boot screen plays on a fresh open, but not when moving screens with a guide open
        // or when opening a file handed over from another app.
        // Nor when WalkThor reopens itself after Android closed it mid-game: get back fast.
        revived = savedInstanceState == null && intent.getBooleanExtra(EXTRA_REVIVED, false)
        val boot = savedInstanceState == null && initialBook == null && incoming.value == null && !revived

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    pollTick.tryEmit(Unit)
                    delay(3_000)
                }
            }
        }
        // Performance check: count WalkThor's drawn frames while it's switched on.
        lifecycleScope.launch {
            PerfCheck.enabled.collect { on ->
                if (on && !frameListenerOn) {
                    runCatching { window.addOnFrameMetricsAvailableListener(frameListener, android.os.Handler(mainLooper)) }
                    frameListenerOn = true
                } else if (!on && frameListenerOn) {
                    runCatching { window.removeOnFrameMetricsAvailableListener(frameListener) }
                    frameListenerOn = false
                }
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(10_000)
                PerfCheck.recentFrames.value = PerfCheck.frameCounter.getAndSet(0)
            }
        }
        // While WalkThor is on screen during a game, note that it's up, so if Android closes it
        // to free memory for the game, it can come back (see Revive).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var beats = 0
                ExitHistory.collect(this@MainActivity)
                while (true) {
                    ExitHistory.snapshot(this@MainActivity)
                    if (GameFocus.gameRunning.value) {
                        Revive.heartbeat(this@MainActivity)
                        // Every half minute, note the game's power draw (for the Stats tab's
                        // "usually lasts about …").
                        if (++beats % 6 == 0) {
                            EmulatorWatch.topGame(this@MainActivity)?.name?.let { com.nerfherder.walkthor.ui.PlayStats.record(this@MainActivity, it) }
                        }
                    }
                    delay(5_000)
                }
            }
        }

        // Opened by itself on the bottom screen (a game starting, or back after Android closed it):
        // the window comes up black at once and WalkThor zooms in out of it, as the drawer does.
        val zoom = savedInstanceState == null &&
            (intent.getStringExtra(EXTRA_FROM_EMULATOR) != null || intent.getBooleanExtra(EXTRA_REVIVED, false))
        setContent {
            if (!zoom) WalkThorRoot(initialBookId = initialBook, boot = boot, host = this@MainActivity)
            else {
                val grow = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    grow.animateTo(1f, androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)))
                }
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                ) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer {
                            alpha = grow.value
                            val s = 0.9f + 0.1f * grow.value
                            scaleX = s; scaleY = s
                        },
                    ) { WalkThorRoot(initialBookId = initialBook, boot = boot, host = this@MainActivity) }
                }
            }
        }
    }

    private var yielding: Boolean? = null
    /** Reopened by [Revive]: the first hand-back comes sooner (nothing is loading). */
    private var revived = false

    // ---- performance check: is WalkThor Android's top app, and is it drawing? ----
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        PerfCheck.setTop(isTopResumedActivity)
        if (isTopResumedActivity) {
            scheduleHandBack()
        } else {
            if (handBackJob?.isActive == true) {
                PerfCheck.handBack.value = "worked (${PerfCheck.handBack.value ?: "try 1"})"
            }
            handBackJob?.cancel()
        }
    }

    private var handBackJob: kotlinx.coroutines.Job? = null
    /** When WalkThor was last opened or brought back (elapsedRealtime). */
    private var openedAt = 0L

    /**
     * WalkThor just became Android's top app while a game is running (e.g. Cocoon opened it as
     * the companion): shortly after, hand that priority back with one tap on the game's screen
     * (through the swipe-to-open service; see [SwipeGestureService.handBack]).
     */
    private fun scheduleHandBack() {
        handBackJob?.cancel()
        handBackJob = lifecycleScope.launch {
            // Let the launch (and boot screen) settle first; after reopening itself there's no boot screen.
            delay(if (revived) REVIVED_HAND_BACK_DELAY_MS else HAND_BACK_DELAY_MS)
            revived = false
            // Only tap the game's screen just after WalkThor opens, never in the middle of play.
            if (android.os.SystemClock.elapsedRealtime() - openedAt > HAND_BACK_WINDOW_MS) {
                PerfCheck.handBack.value = "skipped (WalkThor wasn't just opened)"
                return@launch
            }
            for (attempt in 0 until 2) {
                val skip = when {
                    !GameFocus.gameRunning.value -> "skipped (no game running)"
                    GameFocus.wantFocus.value -> "paused while typing"
                    else -> null
                }
                if (skip != null) { PerfCheck.handBack.value = skip; return@launch }
                if (yielding != true || PerfCheck.walkThorIsTop.value != true) return@launch
                val gameDisplay = DisplayHelper.gameDisplay(this@MainActivity)
                if (gameDisplay == null) { PerfCheck.handBack.value = "no game screen found"; return@launch }
                // Cocoon (or another frontend) on the game screen, not a game: a tap there would
                // press something in it (turning its pages), so never tap.
                // Not known yet (WalkThor's service just restarted, as after Android closes and
                // WalkThor reopens itself mid-game): go by Cocoon's log, as before. Skipping the tap
                // then would leave the game without its performance priority.
                if (EmulatorWatch.gameInFront(this@MainActivity, gameDisplay.displayId) == false) {
                    PerfCheck.handBack.value = "skipped (no game in front on the game screen)"
                    return@launch
                }
                val service = SwipeGestureService.instance?.get()
                if (service == null) {
                    PerfCheck.handBack.value = "needs accessibility access switched on"
                    return@launch
                }
                val outcome = service.handBack(gameDisplay.displayId)
                PerfCheck.handBack.value = "try ${attempt + 1}: $outcome"
                if (outcome != "tapped the game screen") return@launch
                delay(2_000L)
            }
            if (PerfCheck.walkThorIsTop.value == true) {
                PerfCheck.handBack.value = (PerfCheck.handBack.value ?: "") + " · Android kept WalkThor on top"
            }
        }
    }

    private val frameListener = android.view.Window.OnFrameMetricsAvailableListener { _, _, _ ->
        PerfCheck.frameCounter.incrementAndGet()
    }
    private var frameListenerOn = false

    /**
     * Cover images: a small in-memory cache (the default takes a quarter of the app's memory),
     * so WalkThor leaves room for the game and other apps running alongside it.
     */
    private fun installImageLoader() = installWalkThorImageLoader(applicationContext)

    /**
     * Android is short of memory (often a heavy game loading), or WalkThor went to the background:
     * let go of what can be rebuilt, so Android has less reason to close WalkThor.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            com.nerfherder.walkthor.data.PdfSource.trimAll()
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
        }
    }

    /** Makes the window non-focusable while a game is running (see [GameFocus]). */
    private fun applyGameFocus(initial: Boolean) {
        // Only when a game is actually in front on the other screen. Cocoon's log alone isn't
        // enough: back in Cocoon after playing, a non-focusable WalkThor sends your controller to
        // Cocoon and Android reports WalkThor as not responding. (Without accessibility access
        // WalkThor can't see what's in front, so it goes by the log as before.)
        val gameInFront = !EmulatorWatch.frontKnown.value || EmulatorWatch.gameShowing.value
        val yieldToGame = GameFocus.keepOnGame.value && GameFocus.gameRunning.value && gameInFront && !GameFocus.wantFocus.value
        if (yielding == yieldToGame) return
        yielding = yieldToGame
        GameFocus.yielding.value = yieldToGame
        if (yieldToGame) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            // Asked for focus (e.g. tapped the search field): bring WalkThor forward so typing works.
            if (!initial && GameFocus.wantFocus.value) {
                runCatching {
                    getSystemService(android.app.ActivityManager::class.java)?.moveTaskToFront(taskId, 0)
                }
            }
        }
    }

    override fun onResume() {
        openedAt = android.os.SystemClock.elapsedRealtime()
        AppState.appMovedAt = openedAt
        super.onResume()
        hasOtherScreen.value = DisplayHelper.otherDisplay(this) != null
        AppState.activityDisplayId = DisplayHelper.currentDisplayId(this)
        AppState.resumedDisplayId = AppState.activityDisplayId
        // Coming back to WalkThor counts as activity, so it never greets you already dimmed.
        AutoDim.wake()
        // Picks up the swipe service being switched on after a trip to Settings.
        SwipeLauncher.refresh(this)
    }

    override fun onPause() {
        AppState.resumedDisplayId = null
        AppState.appMovedAt = android.os.SystemClock.elapsedRealtime()
        super.onPause()
    }

    override fun onStop() {
        // Closed on purpose (close, swipe away, back out): stays closed.
        if (isFinishing) Revive.closedOnPurpose(this)
        super.onStop()
    }

    override fun onDestroy() {
        if (AppState.activity?.get() === this) AppState.activity = null
        if (AppState.activityDisplayId == DisplayHelper.currentDisplayId(this)) AppState.activityDisplayId = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        openedAt = android.os.SystemClock.elapsedRealtime()
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) incoming.value = uri
        // Opened by a swipe, or because an emulator just started a game: look at what's playing now.
        if (intent?.getBooleanExtra(EXTRA_FROM_SWIPE, false) == true || intent?.getStringExtra(EXTRA_FROM_EMULATOR) != null) {
            swipeTick.value = System.currentTimeMillis()
        }
    }

    // ---- used by the UI ----------------------------------------------------------------------

    override val incomingFlow get() = incoming
    override val hasOtherScreenFlow get() = hasOtherScreen
    override val swipeTickFlow get() = swipeTick
    override val pollTickFlow get() = pollTick
    override val hostScope get() = lifecycleScope

    /** Closes WalkThor (removing it from the screen it's on). */
    override fun closeApp() = finishAndRemoveTask()

    override fun pickGuide() {
        // Some file managers label .txt guides as octet-stream; the repository checks the content.
        guidePicker.launch(GUIDE_TYPES)
    }

    override fun pickRomFolder() = romFolderPicker.launch(null)
    override fun pickArtFolder() = artFolderPicker.launch(null)

    override fun swapScreen(bookId: String?) = DisplayHelper.moveToOtherScreen(this, bookId)

    // Shoulder buttons (L1/R1), page keys and the D-pad turn pages while a guide is open.
    /** Key that only woke the dimmed screen; its key-up is swallowed too. */
    private var wakeKey = -1

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Any touch counts as activity for auto-dim (the dim layer itself swallows a waking tap).
        AutoDim.interact()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && AutoDim.interact()) {
            wakeKey = event.keyCode
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == wakeKey) {
            wakeKey = -1
            return true
        }
        if (KeyBus.readerActive) {
            val cmd = when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> KeyBus.Command.NEXT
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> KeyBus.Command.PREV
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (KeyBus.textFieldFocused) null else KeyBus.Command.NEXT
                KeyEvent.KEYCODE_DPAD_LEFT -> if (KeyBus.textFieldFocused) null else KeyBus.Command.PREV
                else -> null
            }
            if (cmd != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) KeyBus.commands.tryEmit(cmd)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

/** WalkThor's screens with its theme and the auto-dim layer, in the app or the drawer. */
@Composable
internal fun WalkThorRoot(initialBookId: String?, boot: Boolean, host: WalkThorHost) {
    WalkThorTheme {
        val darkPages by PageStyle.dark.collectAsState()
        CompositionLocalProvider(LocalPageColors provides if (darkPages) DarkPages else LightPages) {
            Box(Modifier.fillMaxSize()) {
                WalkThorApp(initialBookId = initialBookId, boot = boot, host = host)
                AutoDimOverlay()
            }
        }
    }
}

@Composable
private fun WalkThorApp(initialBookId: String?, boot: Boolean, host: WalkThorHost) {
    val library: LibraryViewModel = viewModel()
    val context = LocalContext.current
    val books by library.books.collectAsState()
    val importState by library.importState.collectAsState()
    val alwaysBottom by DisplayHelper.alwaysBottom.collectAsState()
    val swipeOn by SwipeLauncher.enabled.collectAsState()
    val swipeNeedsPermission by SwipeLauncher.needsPermission.collectAsState()
    val pending by host.incomingFlow.collectAsState()
    val canSwap by host.hasOtherScreenFlow.collectAsState()
    // Built again mid-game (the drawer's let go while put away, to give the game its memory):
    // back where you were in this game, the same tab and guide.
    val resumeGame = remember { EmulatorWatch.topGame(context)?.pkg?.takeIf { it == DrawerPlace.gamePkg } }
    var openId by rememberSaveable { mutableStateOf(initialBookId ?: resumeGame?.let { DrawerPlace.bookId }) }

    LaunchedEffect(pending) {
        pending?.let {
            host.incomingFlow.value = null
            library.importGuide(it)
        }
    }
    LaunchedEffect(openId) { AppState.openBookId = openId }

    // ---- is a game in front? (one of your emulators, on the game screen) ----
    val frontKnown by EmulatorWatch.frontKnown.collectAsState()
    val emulatorShowing by EmulatorWatch.emulatorShowing.collectAsState()
    val session by EmulatorWatch.session.collectAsState()
    val gameOn = if (frontKnown) emulatorShowing else session != null
    LaunchedEffect(gameOn) { GameFocus.gameRunning.value = gameOn }

    /** The tab open while you play (Home unless you've pinned another); a guide being read is [openId]. */
    var gameTab by remember { mutableStateOf(resumeGame?.let { DrawerPlace.tab } ?: GameTab.HOME) }
    /** The library, reached from Home's WalkThor button while a game is in front. */
    var libraryOpen by remember { mutableStateOf(false) }
    /** The open tab was reached from Multi, so Back goes back there. */
    var fromAll by remember { mutableStateOf(false) }
    /** The guide whose opening flourish is playing, and the last guide opened (no flourish again). */
    var introFor by rememberSaveable { mutableStateOf<String?>(null) }
    var lastOpened by rememberSaveable { mutableStateOf<String?>(resumeGame?.let { DrawerPlace.lastOpened }) }
    /** "Add a guide" beside a game in the library: the next imported guide goes with that game. */
    var linkImportTo by remember { mutableStateOf<com.nerfherder.walkthor.data.LaunchedGame?>(null) }
    var pinned by remember { mutableStateOf(GameTabs.openOn(context, PIN_KEY)?.takeIf { it != GameTab.HOME }) }

    // Where you are, for the game in front, so a rebuilt drawer comes back there.
    LaunchedEffect(gameOn, gameTab, openId, lastOpened) {
        if (!gameOn) return@LaunchedEffect
        EmulatorWatch.topGame(context)?.pkg?.let { pkg ->
            DrawerPlace.gamePkg = pkg; DrawerPlace.tab = gameTab; DrawerPlace.bookId = openId; DrawerPlace.lastOpened = lastOpened
        }
    }
    var resuming by remember { mutableStateOf(resumeGame != null) }

    // A game came to the front: start on Home (or the tab you pinned). Not when this is the drawer
    // built again for the same game: then it's where you were.
    LaunchedEffect(gameOn) {
        if (!gameOn) return@LaunchedEffect
        if (resuming) { resuming = false; return@LaunchedEffect }
        libraryOpen = false
        fromAll = false
        val first = pinned ?: GameTab.HOME
        if (first == GameTab.GUIDE && lastOpened != null && books.any { it.id == lastOpened }) {
            openId = lastOpened
        } else {
            openId = null
            gameTab = first
        }
        // A game is starting and may need a lot of memory: let go of cached pages first.
        com.nerfherder.walkthor.data.PdfSource.trimAll()
        runCatching { coil.Coil.imageLoader(context).memoryCache?.clear() }
        AutoDim.wake()
    }

    LaunchedEffect(Unit) {
        library.opened.collect { id ->
            linkImportTo?.let { g -> com.nerfherder.walkthor.data.GameGuides.remember(context, g, id) }
            linkImportTo = null
            openId = id
        }
    }

    // ---- boot screen: a fresh open of the app on its own (not alongside a game) ----
    var booting by rememberSaveable { mutableStateOf(boot && !gameInFront()) }
    var bootFinished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!booting) return@LaunchedEffect
        delay(BOOT_MIN_MS)
        bootFinished = true
    }

    // Each time a guide is opened, play its intro (see introFor / lastOpened above).
    LaunchedEffect(openId) {
        if (openId != null && openId != lastOpened && !booting) introFor = openId
        if (openId != null) lastOpened = openId
    }
    /** A guide, with its opening flourish playing over it the first time it opens. */
    @Composable
    fun Reader(id: String, onBack: () -> Unit, tabs: TabsState?) {
        Box(Modifier.fillMaxSize()) {
            ReaderScreen(
                bookId = id,
                onBack = onBack,
                onClose = host::closeApp,
                onSwapScreen = if (canSwap) ({ host.swapScreen(id) }) else null,
                tabs = tabs,
            )
            // The opening flourish; the guide loads underneath while it plays.
            val introBook = books.firstOrNull { it.id == introFor }
            if (introBook != null) {
                GuideIntro(
                    book = introBook,
                    cover = remember(introBook.id, introBook.cover) { library.coverFile(introBook) },
                    onDone = { introFor = null },
                )
            }
        }
    }

    // ---- your games (the frontend), with no game running ----
    val frontOn by com.nerfherder.walkthor.ui.Frontend.enabled(context).collectAsState()
    val romLibrary = remember { com.nerfherder.walkthor.data.RomLibrary.get(context) }
    val allRoms by romLibrary.roms.collectAsState()
    LaunchedEffect(Unit) { romLibrary.load() }
    /** The library (guides, settings), reached from the games' WalkThor button. */
    var libraryFromGames by rememberSaveable { mutableStateOf(false) }
    // A game ended: back on your games.
    LaunchedEffect(gameOn) { if (!gameOn) libraryFromGames = false }

    val current = openId
    if (gameOn && !libraryOpen) {
        // ---- a game is in front: Home and the other tabs, along the top ----
        // (Only Home dims while these are up.)
        DisposableEffect(Unit) {
            val holder = Any()
            AutoDim.showingGameScreens(holder, true)
            onDispose { AutoDim.showingGameScreens(holder, false) }
        }
        // What's playing, for the tabs that keep things per game (Mirror's areas, Shots): the app in front.
        val front = remember(session, emulatorShowing) { EmulatorWatch.topGame(context) ?: session }
        val game = remember(front?.pkg, front?.at) {
            front?.let { com.nerfherder.walkthor.data.LaunchedGame(it.name, null, it.name, it.at, emulatorPkg = it.pkg) }
                ?: com.nerfherder.walkthor.data.LaunchedGame("Game", null, null, 0L)
        }
        val lastGuide = books.firstOrNull { it.id == lastOpened }
        val selectedTab = if (current != null) GameTab.GUIDE else gameTab
        fun selectTab(tab: GameTab) {
            AutoDim.wake()
            if (tab == selectedTab && tab != GameTab.GUIDE) return
            // Opened from Multi (or moving between tabs after that): Back returns to Multi.
            fromAll = when {
                tab == GameTab.ALL || tab == GameTab.HOME -> false
                selectedTab == GameTab.ALL -> true
                else -> fromAll
            }
            // The Guide tab always asks which guide.
            openId = null
            gameTab = tab
        }
        val tabs = TabsState(
            selected = selectedTab,
            pinned = pinned,
            onPin = { tab ->
                // Long-press a tab: the drawer opens on it from now on (again, or Home, to go back to Home).
                val next = if (pinned == tab || tab == GameTab.HOME) null else tab
                GameTabs.setOpenOn(context, PIN_KEY, next)
                pinned = next
                Toast.makeText(context, "WalkThor will open on ${next?.label ?: "Home"} while you play", Toast.LENGTH_SHORT).show()
            },
            onSelect = ::selectTab,
        )
        SideEffect { GameTabs.state.value = tabs }
        DisposableEffect(Unit) { onDispose { GameTabs.state.value = null } }
        // The mirror's capture keeps running (paused) while you're on other tabs.
        // Multi only needs the capture when it shows the Mirror or Stats panel.
        val multiPanels by com.nerfherder.walkthor.ui.AllLayout.panels(context).collectAsState()
        // Where the display driver gives the frame rate, Stats doesn't need to count captured frames.
        val driverFps by com.nerfherder.walkthor.ui.DriverFps.available.collectAsState()
        val mirrorFrame = rememberMirrorFrame(
            active = current == null && (gameTab == GameTab.MIRROR || gameTab == GameTab.SHOTS ||
                (gameTab == GameTab.ALL && (GameTab.MIRROR in multiPanels || GameTab.SHOTS in multiPanels))),
            counting = driverFps != true && current == null && (gameTab == GameTab.STATS || (gameTab == GameTab.ALL && GameTab.STATS in multiPanels)),
        )
        val toHome = { openId = null; gameTab = GameTab.HOME; fromAll = false }
        // Back from a tab: to Multi if that's where you came from, else to Home.
        val back = { if (fromAll) { openId = null; gameTab = GameTab.ALL; fromAll = false } else toHome() }
        val bar: @Composable () -> Unit = {
            GameTabBar(selectedTab, ::selectTab, onBack = back, onClose = host::closeApp)
        }
        Box(Modifier.fillMaxSize().background(BookColors.Ink)) {
          // Switching tabs: a quick crossfade (the tab line glides across at the same time).
          androidx.compose.animation.AnimatedContent(
            targetState = current to gameTab,
            transitionSpec = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
            },
            label = "gameTab",
          ) { (shownBook, shownTab) ->
            when {
                shownBook != null -> Reader(shownBook, onBack = { openId = null; gameTab = GameTab.GUIDE }, tabs = tabs)
                shownTab == GameTab.ALL -> {
                    BackHandler { toHome() }
                    com.nerfherder.walkthor.ui.AllScreen(
                        game = game,
                        guide = lastGuide,
                        frame = mirrorFrame,
                        onOpen = { t ->
                            if (t == GameTab.GUIDE && lastGuide != null) {
                                fromAll = true
                                lastOpened = lastGuide.id // switching tabs, not opening anew: skip the opening flourish
                                openId = lastGuide.id
                            } else selectTab(t)
                        },
                        onStartCapture = host::requestMirror,
                        topBar = {
                            GameTabBar(
                                selectedTab, ::selectTab, onBack = toHome, onClose = host::closeApp,
                                trailing = { com.nerfherder.walkthor.ui.MultiMenu(onClose = host::closeApp) },
                            )
                        },
                    )
                }
                shownTab == GameTab.APPS -> {
                    BackHandler { back() }
                    com.nerfherder.walkthor.ui.AppsScreen(topBar = bar)
                }
                shownTab == GameTab.GUIDE -> {
                    BackHandler { back() }
                    Column(Modifier.fillMaxSize()) {
                        bar()
                        com.nerfherder.walkthor.ui.GuidePickPanel(
                            books = books,
                            coverFile = library::coverFile,
                            onAddGuide = host::pickGuide,
                            onPick = { b -> openId = b.id },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                shownTab == GameTab.MIRROR -> {
                    BackHandler { back() }
                    MirrorScreen(
                        game = game.title,
                        onBack = back,
                        onStartCapture = host::requestMirror,
                        frame = mirrorFrame,
                        topBar = bar,
                    )
                }
                shownTab == GameTab.SHOTS -> {
                    BackHandler { back() }
                    com.nerfherder.walkthor.ui.ShotsScreen(
                        game = game.title,
                        frame = mirrorFrame,
                        onStartCapture = host::requestMirror,
                        topBar = bar,
                    )
                }
                shownTab == GameTab.CAPTIONS -> {
                    BackHandler { back() }
                    com.nerfherder.walkthor.ui.CaptionsScreen(onStartCapture = host::requestMirror, topBar = bar)
                }
                shownTab == GameTab.LEVELS -> {
                    BackHandler { back() }
                    com.nerfherder.walkthor.ui.LevelsScreen(topBar = bar)
                }
                shownTab == GameTab.STATS -> {
                    BackHandler { back() }
                    com.nerfherder.walkthor.ui.StatsScreen(game = game, onStartCapture = host::requestMirror, topBar = bar)
                }
                shownTab == GameTab.FACE -> {
                    BackHandler { back() }
                    ControlsScreen(game = game, onBack = back, topBar = bar)
                }
                else -> {
                    // Home: the same as when no game is running, under the tabs. It's the one that dims.
                    BackHandler { libraryOpen = true }
                    DisposableEffect(Unit) {
                        val holder = Any()
                        AutoDim.showingNowPlaying(holder, true)
                        onDispose { AutoDim.showingNowPlaying(holder, false) }
                    }
                    Column(Modifier.fillMaxSize()) {
                        bar()
                        // Kept at Home's own shape (the tab bar takes some height), so it looks
                        // exactly as it does without a game, just a little smaller.
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                          Box(Modifier.fillMaxHeight().aspectRatio(620f / 540f, matchHeightConstraintsFirst = true)) {
                            com.nerfherder.walkthor.ui.HomeScreen(
                                onOpenWalkThor = { libraryOpen = true },
                                onOpenApp = { pkg ->
                                    com.nerfherder.walkthor.ui.openFromHome(context, pkg, AppState.drawerDisplayId ?: AppState.activityDisplayId)
                                },
                            )
                          }
                        }
                    }
                }
            }
          }
          // On the tabs (not Home, which has its own dock, nor Apps or a guide): swipe up from the
          // bottom edge for your apps.
          if (gameTab != GameTab.HOME && gameTab != GameTab.APPS && current == null) {
              com.nerfherder.walkthor.ui.ShelfPullUp(
                  onOpenApp = { pkg -> com.nerfherder.walkthor.ui.AppCatalog.open(context, pkg, here = true) },
                  onAllApps = { selectTab(GameTab.APPS) },
              )
          }
        }
    } else if (current != null) {
        Reader(current, onBack = { openId = null }, tabs = null)
    } else if (frontOn != false && allRoms.isNotEmpty() && !gameOn && !libraryFromGames) {
        // ---- no game running: your games, to start one (the chosen one big on the top screen) ----
        DisposableEffect(Unit) { onDispose { com.nerfherder.walkthor.ui.Frontend.hideTop() } }
        com.nerfherder.walkthor.ui.FrontendScreen(
            roms = allRoms,
            books = books,
            coverFile = library::coverFile,
            onOpenGuide = { openId = it.id },
            onAddGuideFor = { game -> linkImportTo = game; host.pickGuide() },
            onOpenLibrary = { libraryFromGames = true },
        )
    } else {
        val backToGames = frontOn != false && allRoms.isNotEmpty() && !gameOn
        if (backToGames) BackHandler { libraryFromGames = false }
        LibraryScreen(
            books = books,
            alwaysBottom = alwaysBottom,
            swipeToOpen = swipeOn,
            swipeNeedsPermission = swipeNeedsPermission,
            actions = LibraryActions(
                onOpen = { openId = it.id },
                onImport = host::pickGuide,
                onDelete = library::delete,
                onRename = library::rename,
                coverFile = library::coverFile,
                onToggleAlwaysBottom = { DisplayHelper.setAlwaysBottom(context, it) },
                onToggleSwipeToOpen = { SwipeLauncher.setEnabled(context, it) },
                onClose = host::closeApp,
                onGrantSwipePermission = { SwipeLauncher.requestPermission(context) },
                hasSwipeConsent = { SwipeLauncher.hasConsent(context) },
                onSwipeConsent = { SwipeLauncher.setConsent(context, it) },
                isSwipeServiceOn = { SwipeLauncher.isServiceOn(context) },
                onOpenAppInfo = { SwipeLauncher.openAppInfo(context) },
                onSwapScreen = if (canSwap) ({ host.swapScreen(null) }) else null,
                onLinkRoms = host::pickRomFolder,
                onAddGuideFor = { game -> linkImportTo = game; host.pickGuide() },
                onBackToGame = if (gameOn) ({ libraryOpen = false }) else null,
                onLinkArt = host::pickArtFolder,
                onBackToGames = if (backToGames) ({ libraryFromGames = false }) else null,
            ),
        )
        if (gameOn) BackHandler { libraryOpen = false }
    }

    if (booting) {
        BootScreen(
            light = false,
            status = com.nerfherder.walkthor.ui.BootStatus.Idle,
            finished = bootFinished,
            onSkip = { bootFinished = true },
            onGone = { booting = false },
        )
    }

    ImportDialogs(
        state = importState,
        onCancel = library::cancelImport,
        onDismissError = library::dismissError,
    )
}

/** How long after WalkThor becomes the top app before it hands priority back to the game. */
private const val HAND_BACK_DELAY_MS = 2_500L
/** The same, when WalkThor has just reopened itself (no boot screen to wait for). */
private const val REVIVED_HAND_BACK_DELAY_MS = 1_000L
/** The hand-back tap only happens this soon after WalkThor opens. */
private const val HAND_BACK_WINDOW_MS = 20_000L

/** Shortest the boot screen shows, so it reads as an animation rather than a flicker. */
private const val BOOT_MIN_MS = 1_100L
/** Set once per process. */
private var imageLoaderSet = false

/**
 * WalkThor's image loader, with a small memory cache (covers, icons). Set up by whichever starts
 * first, the app or the service: before, only the app set it, so when the drawer was all that
 * had run, images used the library's default cache, several times the size.
 */
@Synchronized
fun installWalkThorImageLoader(context: android.content.Context) {
    if (imageLoaderSet) return
    imageLoaderSet = true
    val app = context.applicationContext
    coil.Coil.setImageLoader(
        coil.ImageLoader.Builder(app)
            .memoryCache { coil.memory.MemoryCache.Builder(app).maxSizePercent(0.06).build() }
            // Covers and other images without see-through parts at half the memory per pixel.
            .allowRgb565(true)
            .build()
    )
}

/** The pinned tab is the same for every game, kept under this one key. */
private const val PIN_KEY = "any game"

/** Where you were in the drawer during the current game (kept while the drawer itself is let go). */
internal object DrawerPlace {
    @Volatile var gamePkg: String? = null
    @Volatile var tab: GameTab? = null
    @Volatile var bookId: String? = null
    @Volatile var lastOpened: String? = null
}

/**
 * One of your emulators (Settings → Emulators) is in front on the game screen; before anything's
 * been seen there, an emulator session from the service counts.
 */
internal fun gameInFront(): Boolean =
    if (EmulatorWatch.frontKnown.value) EmulatorWatch.emulatorShowing.value else EmulatorWatch.session.value != null
