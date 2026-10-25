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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.nerfherder.walkthor.data.CocoonLaunchLog
import com.nerfherder.walkthor.data.GameGuides
import com.nerfherder.walkthor.data.LaunchedGame
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.ui.gameTitleOf
import com.nerfherder.walkthor.ui.GuideIntro
import com.nerfherder.walkthor.ui.BookColors
import com.nerfherder.walkthor.ui.ControlsScreen
import com.nerfherder.walkthor.ui.GameTab
import com.nerfherder.walkthor.ui.GameTabBar
import com.nerfherder.walkthor.ui.GameTabs
import com.nerfherder.walkthor.ui.displayTitle
import com.nerfherder.walkthor.ui.MirrorScreen
import com.nerfherder.walkthor.ui.NoGuidePanel
import com.nerfherder.walkthor.ui.TabsState
import com.nerfherder.walkthor.ui.rememberMirrorFrame
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import com.nerfherder.walkthor.ui.BootScreen
import com.nerfherder.walkthor.ui.NowPlayingScreen
import com.nerfherder.walkthor.data.ArtworkRepository
import com.nerfherder.walkthor.data.GameArt
import androidx.activity.compose.BackHandler
import com.nerfherder.walkthor.ui.BootStatus
import com.nerfherder.walkthor.ui.ImportDialogs
import com.nerfherder.walkthor.ui.LibraryActions
import com.nerfherder.walkthor.ui.LibraryScreen
import com.nerfherder.walkthor.ui.ReaderScreen
import com.nerfherder.walkthor.ui.WalkThorTheme
import com.nerfherder.walkthor.ui.PageStyle
import com.nerfherder.walkthor.ui.AutoDim
import com.nerfherder.walkthor.ui.AutoDimOverlay
import com.nerfherder.walkthor.ui.PlaySession
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

class MainActivity : ComponentActivity() {

    /** A document handed to us by "Open with" / "Share", waiting to be imported. */
    private val incoming = MutableStateFlow<Uri?>(null)
    private val hasOtherScreen = MutableStateFlow(false)
    /** Results from the system pickers, consumed by the UI. */
    private val pickedArtFolder = MutableStateFlow<Uri?>(null)
    private val pickedCocoonFolder = MutableStateFlow<Uri?>(null)
    /** Bumped each time WalkThor is opened by the swipe gesture. */
    private val swipeTick = MutableStateFlow(0L)
    /** Ticks every few seconds while WalkThor is on screen, to notice a game launched meanwhile. */
    private val pollTick = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val pickedCover = MutableStateFlow<Pair<String, Uri>?>(null)
    private var coverForBookId: String? = null

    private val guidePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) incoming.value = uri
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) pickedArtFolder.value = uri
    }
    private val cocoonFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) pickedCocoonFolder.value = uri
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = coverForBookId
        if (uri != null && id != null) pickedCover.value = id to uri
    }

    // Mirror: Android's "start recording or casting?" prompt.
    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) Mirror.start(this, result.resultCode, data)
    }

    /** Asks Android for permission to capture the top screen (it asks every time). */
    internal fun requestMirror() {
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java) ?: return
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // The whole top screen, never "a single app".
            mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
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
        PlaySession.init(this)
        GameFocus.init(this)
        PerfCheck.init(this)
        if (DisplayHelper.ensureOnBottomScreen(this, savedInstanceState)) return
        // Until the launch log has been checked, assume a game may be running (when Cocoon is
        // linked) so WalkThor never grabs focus from it while starting up as its companion.
        GameFocus.gameRunning.value = CocoonLaunchLog.linkedFolder(this) != null || EmulatorWatch.session.value != null
        applyGameFocus(initial = true)
        lifecycleScope.launch {
            var typing = GameFocus.wantFocus.value
            kotlinx.coroutines.flow.combine(GameFocus.keepOnGame, GameFocus.gameRunning, GameFocus.wantFocus) { _, _, _ -> }
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
        coverForBookId = savedInstanceState?.getString("coverFor")

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
                while (true) {
                    if (GameFocus.gameRunning.value) {
                        Revive.heartbeat(this@MainActivity)
                        // Every half minute, note the game's power draw (for the Stats tab's
                        // "usually lasts about …").
                        if (++beats % 6 == 0) {
                            PlaySession.current.value?.takeIf { !it.unknown }?.title?.let { com.nerfherder.walkthor.ui.PlayStats.record(this@MainActivity, it) }
                        }
                    }
                    delay(5_000)
                }
            }
        }

        setContent {
            WalkThorTheme {
                val darkPages by PageStyle.dark.collectAsState()
                CompositionLocalProvider(LocalPageColors provides if (darkPages) DarkPages else LightPages) {
                    Box(Modifier.fillMaxSize()) {
                        WalkThorApp(
                            initialBookId = initialBook,
                            boot = boot,
                            host = this@MainActivity,
                        )
                        AutoDimOverlay()
                    }
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
                    !GameFocus.gameRunning.value -> "skipped (no game running per Cocoon's launch log)"
                    GameFocus.wantFocus.value -> "paused while typing"
                    else -> null
                }
                if (skip != null) { PerfCheck.handBack.value = skip; return@launch }
                if (yielding != true || PerfCheck.walkThorIsTop.value != true) return@launch
                val gameDisplay = DisplayHelper.gameDisplay(this@MainActivity)
                if (gameDisplay == null) { PerfCheck.handBack.value = "no game screen found"; return@launch }
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
    private fun installImageLoader() {
        if (imageLoaderSet) return
        imageLoaderSet = true
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(applicationContext)
                .memoryCache { coil.memory.MemoryCache.Builder(applicationContext).maxSizePercent(0.08).build() }
                .build()
        )
    }

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
        val yieldToGame = GameFocus.keepOnGame.value && GameFocus.gameRunning.value && !GameFocus.wantFocus.value
        if (yielding == yieldToGame) return
        yielding = yieldToGame
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("coverFor", coverForBookId)
    }

    override fun onResume() {
        openedAt = android.os.SystemClock.elapsedRealtime()
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
        if (intent?.getBooleanExtra(EXTRA_FROM_SWIPE, false) == true) swipeTick.value = System.currentTimeMillis()
    }

    // ---- used by the UI ----------------------------------------------------------------------

    internal val incomingFlow get() = incoming
    internal val hasOtherScreenFlow get() = hasOtherScreen
    internal val pickedArtFolderFlow get() = pickedArtFolder
    internal val pickedCocoonFolderFlow get() = pickedCocoonFolder
    internal val swipeTickFlow get() = swipeTick
    internal val pollTickFlow get() = pollTick
    internal val pickedCoverFlow get() = pickedCover

    /** Closes WalkThor (removing it from the screen it's on). */
    internal fun closeApp() = finishAndRemoveTask()

    internal fun pickGuide() {
        // Some file managers label .txt guides as octet-stream; the repository checks the content.
        guidePicker.launch(arrayOf("application/pdf", "text/plain", "text/*", "application/octet-stream"))
    }

    internal fun pickArtFolder() = folderPicker.launch(null)
    internal fun pickCocoonFolder() = cocoonFolderPicker.launch(null)

    internal fun pickCoverImage(bookId: String) {
        coverForBookId = bookId
        imagePicker.launch(arrayOf("image/*"))
    }

    internal fun swapScreen(bookId: String?) = DisplayHelper.moveToOtherScreen(this, bookId)

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

@Composable
private fun WalkThorApp(initialBookId: String?, boot: Boolean, host: MainActivity) {
    val library: LibraryViewModel = viewModel()
    val context = LocalContext.current
    val books by library.books.collectAsState()
    val importState by library.importState.collectAsState()
    val artFolder by library.artFolder.collectAsState()
    val artCount by library.artCount.collectAsState()
    val artScanning by library.artScanning.collectAsState()
    val alwaysBottom by DisplayHelper.alwaysBottom.collectAsState()
    val swipeOn by SwipeLauncher.enabled.collectAsState()
    val swipeNeedsPermission by SwipeLauncher.needsPermission.collectAsState()
    val pending by host.incomingFlow.collectAsState()
    val pickedFolder by host.pickedArtFolderFlow.collectAsState()
    val pickedCocoon by host.pickedCocoonFolderFlow.collectAsState()
    val swipeTick by host.swipeTickFlow.collectAsState()
    val gameGuideOn by SwipeLauncher.gameGuide.collectAsState()
    val cocoonLinked by SwipeLauncher.cocoonLinked.collectAsState()
    val pickedCover by host.pickedCoverFlow.collectAsState()
    val canSwap by host.hasOtherScreenFlow.collectAsState()
    var openId by rememberSaveable { mutableStateOf(initialBookId) }

    LaunchedEffect(pending) {
        pending?.let {
            host.incomingFlow.value = null
            library.importGuide(it)
        }
    }
    LaunchedEffect(pickedFolder) {
        pickedFolder?.let {
            host.pickedArtFolderFlow.value = null
            library.linkArtFolder(it)
        }
    }
    LaunchedEffect(pickedCover) {
        pickedCover?.let { (bookId, uri) ->
            host.pickedCoverFlow.value = null
            books.firstOrNull { it.id == bookId }?.let { library.setCover(it, uri) }
        }
    }
    LaunchedEffect(pickedCocoon) {
        // Hand off to the view model: clearing the picked folder below changes this effect's key,
        // which cancels anything still running in here.
        pickedCocoon?.let { tree ->
            host.pickedCocoonFolderFlow.value = null
            library.linkCocoon(tree)
        }
    }
    // ---- open the guide for the game Cocoon just launched ----
    // The game we last acted on, while we wait to see which guide the user opens for it.
    var swipeGame by remember { mutableStateOf<LaunchedGame?>(null) }
    var swipeGameAt by remember { mutableStateOf(0L) }
    var autoOpened by remember { mutableStateOf<String?>(null) }
    /** A launched game with no guide, shown with its artwork until a guide is added or picked. */
    var noGuide by remember { mutableStateOf<Showing?>(null) }
    /**
     * The game's open tab other than a guide being read (that's [openId]): Mirror, Face Buttons,
     * or Guide when the game has no guide yet. Null with no guide open = Now playing.
     */
    var gameTab by remember { mutableStateOf<GameTab?>(null) }
    /** The open tab was reached from All, so Back goes back there. */
    var fromAll by remember { mutableStateOf(false) }
    /** The guide whose opening flourish is playing, and the last guide opened (no flourish again). */
    var introFor by rememberSaveable { mutableStateOf<String?>(null) }
    var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
    /** "Add guide" on the no-guide screen: the next imported guide is linked to this game. */
    var linkImportTo by remember { mutableStateOf<LaunchedGame?>(null) }

    fun linkGuide(game: LaunchedGame, id: String) {
        GameGuides.remember(context, game, id)
        autoOpened = id // already linked; the learning effect below needn't repeat it
        // The Now playing screen (waiting under the guide) now shows this guide too.
        noGuide = noGuide?.let { s -> if (s.game == game) s.copy(guide = books.firstOrNull { b -> b.id == id } ?: s.guide) else s }
        Toast.makeText(context, "WalkThor will open this guide for ${game.title} from now on", Toast.LENGTH_SHORT).show()
    }

    // The Now playing screen stays underneath an opened guide, so Back from the guide returns
    // to it. A guide just imported for it may arrive on the shelf a moment after it's linked.
    LaunchedEffect(books) {
        val s = noGuide ?: return@LaunchedEffect
        if (s.guide == null) {
            GameGuides.linkedTo(context, s.game)?.let { id ->
                books.firstOrNull { it.id == id }?.let { noGuide = s.copy(guide = it) }
            }
        }
    }
    // Leaving the no-guide screen without adding a guide: don't link some later import to that game.
    LaunchedEffect(noGuide) { if (noGuide == null) linkImportTo = null }
    LaunchedEffect(openId) { AppState.openBookId = openId }

    val booksNow by rememberUpdatedState(books)
    val gameGuideNow by rememberUpdatedState(gameGuideOn)
    val checkLock = remember { Mutex() }

    // ---- without Cocoon: the game an emulator is running ("Detect games from any emulator") ----
    /** The emulator session WalkThor last acted on, and whether you picked its guide yourself. */
    var emuActedAt by remember { mutableStateOf(0L) }
    var emuPicked by remember { mutableStateOf(false) }

    /**
     * Which game the running emulator has: a guide whose title matches text the emulator showed,
     * else the guide you last used with this emulator; null if neither (WalkThor asks).
     */
    fun identifyFromEmulator(s: EmulatorWatch.Session): Book? {
        val books = booksNow
        if (books.isEmpty()) return null
        EmulatorWatch.seenText().asReversed().forEach { text ->
            GameGuides.pick(context, books, LaunchedGame(text, null, s.name, s.at))?.let { return it }
        }
        return EmulatorWatch.recentGuides(context, s.pkg).firstNotNullOfOrNull { id -> books.firstOrNull { it.id == id } }
    }

    fun emulatorGame(s: EmulatorWatch.Session, book: Book?) =
        if (book != null) LaunchedGame(gameTitleOf(book.title), null, s.name, s.at, emulatorPkg = s.pkg)
        else LaunchedGame(s.name, null, s.name, s.at, emulatorPkg = s.pkg, unknown = true)

    suspend fun checkEmulator(force: Boolean): Acted? {
        val s = EmulatorWatch.session.value
        if (s == null) { PlaySession.current.value = null; GameFocus.gameRunning.value = false; return null }
        GameFocus.gameRunning.value = true
        val showing = noGuide?.game?.takeIf { it.emulatorPkg == s.pkg && it.at == s.at }
        // Same session: only revisit it while the game is still unknown (new text may name it).
        if (!force && s.at == emuActedAt && (showing == null || !showing.unknown || emuPicked)) {
            PlaySession.current.value = showing ?: PlaySession.current.value
            return null
        }
        val book = withContext(Dispatchers.Default) { identifyFromEmulator(s) }
        if (s.at == emuActedAt && book == null && !force) return null
        val newSession = s.at != emuActedAt
        emuActedAt = s.at
        if (newSession) emuPicked = false
        val game = emulatorGame(s, book)
        PlaySession.current.value = game
        swipeGame = game
        swipeGameAt = System.currentTimeMillis()
        autoOpened = book?.id
        if (newSession) {
            com.nerfherder.walkthor.data.PdfSource.trimAll()
            runCatching { coil.Coil.imageLoader(context).memoryCache?.clear() }
        }
        val art = if (game.unknown) GameArt(null) else ArtworkRepository.get(context).gameArt(game.title, null)
        if (newSession) {
            openId = null
            gameTab = null
            fromAll = false
        }
        noGuide = Showing(game, art, book)
        if (newSession && !game.unknown) {
            when (val first = GameTabs.openOn(context, game.title)) {
                null -> Unit
                GameTab.GUIDE -> if (book != null) { lastOpened = book.id; openId = book.id } else gameTab = GameTab.GUIDE
                else -> gameTab = first
            }
        }
        AutoDim.wake()
        return Acted(game, book, art)
    }

    /** You picked [book] for the emulator's game: that's the game now, and it's suggested first next time. */
    suspend fun pickForEmulator(game: LaunchedGame, book: Book) {
        val pkg = game.emulatorPkg ?: return
        EmulatorWatch.noteGuide(context, pkg, book.id)
        emuPicked = true
        val named = game.copy(title = gameTitleOf(book.title), unknown = false)
        PlaySession.current.value = named
        swipeGame = named
        autoOpened = book.id
        val art = ArtworkRepository.get(context).gameArt(named.title, null)
        noGuide = Showing(named, art, book)
    }

    /**
     * Looks at Cocoon's newest launch. A swipe ([force]) always acts on it; the on-screen poll only
     * acts on a launch it hasn't handled yet, so browsing other guides isn't interrupted.
     */
    suspend fun checkGame(force: Boolean): Acted? = checkLock.withLock {
        val tree = CocoonLaunchLog.linkedFolder(context)
        if (tree == null) return@withLock checkEmulator(force)
        val game = CocoonLaunchLog.lastLaunch(context, tree)
        // Only a game launched in the last few hours counts as "playing now".
        val recent = game != null && (game.at <= 0 || System.currentTimeMillis() - game.at <= GAME_RECENT_MS)
        PlaySession.current.value = if (recent) game else null
        GameFocus.gameRunning.value = recent
        if (!gameGuideNow) return@withLock null
        if (game == null) {
            if (force) Toast.makeText(context, "Couldn't read Cocoon's launch log — try ⋮ → Change Cocoon folder…", Toast.LENGTH_LONG).show()
            return@withLock null
        }
        if (!recent) return@withLock null
        if (!force && CocoonLaunchLog.isHandled(context, game)) return@withLock null
        CocoonLaunchLog.markHandled(context, game)
        swipeGame = game
        swipeGameAt = System.currentTimeMillis()
        val book = withContext(Dispatchers.Default) { GameGuides.pick(context, booksNow, game) }
        autoOpened = book?.id
        if (book != null && book.id == openId) {
            // Already reading this game's guide (e.g. swiped back in): stay on the page, with the
            // game's tabs.
            if (noGuide?.game?.title != game.title) {
                noGuide = Showing(game, ArtworkRepository.get(context).gameArt(game.title, game.file), book)
            }
            gameTab = null
            fromAll = false
            Acted(game, book, noGuide?.art)
        } else {
            // Every launch lands on the Now playing screen, which dims after a few seconds;
            // with a guide it offers Open guide, without one the game's artwork and ways to add one.
            // A new game is starting and may need a lot of memory: let go of cached pages and
            // covers first, so Android has less reason to close WalkThor while it loads.
            com.nerfherder.walkthor.data.PdfSource.trimAll()
            runCatching { coil.Coil.imageLoader(context).memoryCache?.clear() }
            val art = ArtworkRepository.get(context).gameArt(game.title, game.file)
            openId = null
            gameTab = null
            fromAll = false
            noGuide = Showing(game, art, book)
            // Opens straight on the tab you chose for this game (long-press a tab), if any.
            when (val first = GameTabs.openOn(context, game.title)) {
                null -> Unit
                GameTab.GUIDE -> if (book != null) { lastOpened = book.id; openId = book.id } else gameTab = GameTab.GUIDE
                else -> gameTab = first
            }
            AutoDim.wake()
            Acted(game, book, art)
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        library.opened.collect { id ->
            linkImportTo?.let { g ->
                if (g.emulatorPkg != null) {
                    // A guide just added for the emulator's game: it may reach the shelf a moment later.
                    val book = booksNow.firstOrNull { it.id == id } ?: kotlinx.coroutines.withTimeoutOrNull(3_000) {
                        library.books.first { list -> list.any { it.id == id } }.first { it.id == id }
                    }
                    if (book != null) pickForEmulator(g, book)
                } else linkGuide(g, id)
            }
            linkImportTo = null
            openId = id
        }
    }
    // ---- boot screen: plays while Cocoon's launch log is read on a fresh open ----
    var booting by rememberSaveable { mutableStateOf(boot) }
    var bootFinished by remember { mutableStateOf(false) }
    var bootStatus by remember { mutableStateOf<BootStatus>(BootStatus.Idle) }
    // Cocoon linked with auto-open on: WalkThor is most likely opening alongside a game, so
    // keep the boot animation light while the game loads.
    val bootLight = remember { (CocoonLaunchLog.linkedFolder(context) != null || EmulatorWatch.session.value != null) && gameGuideOn }
    // A swipe that opened WalkThor is handled by the boot check, not the effect below.
    val bootSwipeTick = remember { if (boot) swipeTick else 0L }
    LaunchedEffect(Unit) {
        if (!booting) return@LaunchedEffect
        val start = System.currentTimeMillis()
        val tree = CocoonLaunchLog.linkedFolder(context)
        if ((tree != null || EmulatorWatch.session.value != null) && gameGuideNow) {
            bootStatus = BootStatus.Checking
            // Cocoon may start WalkThor (as a companion app) a moment before it finishes writing
            // the launch to its log, so keep looking briefly for a launch we haven't seen yet.
            var force = bootSwipeTick != 0L
            // What the check found, straight from it (a guide it opened, or the game's art).
            var found: Acted? = null
            while (true) {
                found = checkGame(force)
                force = false
                // The poll (or a swipe) may have acted on the launch first.
                if (found != null || swipeGameAt >= start || openId != null) break
                if (System.currentTimeMillis() - start >= BOOT_WAIT_MS) break
                delay(250)
            }
            if (found == null && swipeGameAt >= start) {
                // Acted on elsewhere: rebuild what it found from the current state.
                val game = swipeGame
                val guide = noGuide?.guide ?: openId?.let { id -> booksNow.firstOrNull { it.id == id } }
                if (game != null) found = Acted(game, guide, noGuide?.art)
            }
            found?.let { (game, guide, art) ->
                // The game's title card: its wide art (or box art) fades in behind its name.
                val cover = art?.cover
                bootStatus = BootStatus.Playing(
                    game.title, guide, guide?.let(library::coverFile), cover,
                    if (game.unknown) null else game.player, hero = art?.hero, unknown = game.unknown,
                )
                delay(if (art?.hero != null || cover != null) 1_300 else 900)
            }
        } else {
            checkGame(force = false) // just refreshes the play-time readout
        }
        val left = BOOT_MIN_MS - (System.currentTimeMillis() - start)
        if (left > 0) delay(left)
        bootFinished = true
    }

    LaunchedEffect(swipeTick) { if (swipeTick != 0L && swipeTick != bootSwipeTick) checkGame(force = true) }
    LaunchedEffect(Unit) { host.pollTickFlow.collect { checkGame(force = false) } }
    // Without Cocoon: a new emulator session, or new text from the running emulator.
    val emuSession by EmulatorWatch.session.collectAsState()
    val emuText by EmulatorWatch.textTick.collectAsState()
    LaunchedEffect(emuSession, emuText) {
        if (!booting && emuSession != null && CocoonLaunchLog.linkedFolder(context) == null) checkGame(force = false)
    }

    // Opening a different guide soon after swiping in teaches WalkThor which guide goes with the game.
    LaunchedEffect(openId) {
        val game = swipeGame ?: return@LaunchedEffect
        val id = openId ?: return@LaunchedEffect
        if (System.currentTimeMillis() - swipeGameAt > LEARN_WINDOW_MS) { swipeGame = null; return@LaunchedEffect }
        if (id != autoOpened) {
            if (game.emulatorPkg != null) {
                // Without Cocoon: the guide you open is the game you're playing.
                books.firstOrNull { it.id == id }?.let { pickForEmulator(game, it) }
                autoOpened = id
                return@LaunchedEffect
            }
            GameGuides.remember(context, game, id)
            autoOpened = id
            Toast.makeText(context, "Linked this guide to ${game.title}", Toast.LENGTH_SHORT).show()
        }
    }
    // Each time a guide is opened, play its intro (see introFor / lastOpened above).
    LaunchedEffect(openId) {
        // A guide opened by the boot screen already got its flourish there.
        if (openId != null && openId != lastOpened && !booting) introFor = openId
        lastOpened = openId
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

    val current = openId
    val showing = noGuide
    if (showing != null) {
        // ---- a game is playing: Now playing, and its Guide, Mirror and Face Buttons tabs ----
        val (game, art, guide) = showing
        val selectedTab = if (current != null) GameTab.GUIDE else gameTab
        fun selectTab(tab: GameTab) {
            AutoDim.wake()
            if (tab == selectedTab) return
            // Opened from All (or moving between tabs after that): Back returns to All.
            fromAll = when {
                tab == GameTab.ALL -> false
                selectedTab == GameTab.ALL -> true
                selectedTab == null -> false
                else -> fromAll
            }
            if (tab == GameTab.GUIDE && guide != null) {
                lastOpened = guide.id // switching tabs, not opening anew: skip the opening flourish
                gameTab = null
                openId = guide.id
            } else {
                openId = null
                gameTab = tab
            }
        }
        var pinned by remember(game.title) { mutableStateOf(GameTabs.openOn(context, game.title)) }
        val tabs = TabsState(
            selected = selectedTab,
            pinned = pinned,
            onPin = { tab ->
                // Long-press a tab: this game opens on it from now on (again to go back to Now playing).
                val next = if (pinned == tab) null else tab
                GameTabs.setOpenOn(context, game.title, next)
                pinned = next
                Toast.makeText(
                    context,
                    "${displayTitle(game.title)} will open on ${next?.label ?: "Now playing"}",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onSelect = ::selectTab,
        )
        SideEffect { GameTabs.state.value = tabs }
        DisposableEffect(Unit) { onDispose { GameTabs.state.value = null } }
        // The mirror's capture keeps running (paused) while you're on other tabs.
        // Multi only needs the capture when it shows the Mirror or Stats panel.
        val multiPanels by com.nerfherder.walkthor.ui.AllLayout.panels(context).collectAsState()
        val mirrorFrame = rememberMirrorFrame(
            active = current == null && (gameTab == GameTab.MIRROR || (gameTab == GameTab.ALL && GameTab.MIRROR in multiPanels)),
            counting = current == null && (gameTab == GameTab.STATS || (gameTab == GameTab.ALL && GameTab.STATS in multiPanels)),
        )
        val toNowPlaying = { openId = null; gameTab = null; fromAll = false }
        // Back from a tab: to All if that's where you came from, else to Now playing.
        val back = { if (fromAll) { openId = null; gameTab = GameTab.ALL; fromAll = false } else toNowPlaying() }
        val bar: @Composable () -> Unit = {
            GameTabBar(selectedTab, ::selectTab, onBack = back, onClose = host::closeApp)
        }
        // The game's tabs sit under a strip of its art; Now playing draws its own art.
        val onTab = current != null || gameTab != null
        Box(Modifier.fillMaxSize()) {
            if (onTab) com.nerfherder.walkthor.ui.GameBackdrop(art.hero)
            androidx.compose.runtime.CompositionLocalProvider(com.nerfherder.walkthor.ui.LocalBackdrop provides onTab) {
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
                    shownBook != null -> Reader(shownBook, onBack = back, tabs = tabs)
                    shownTab == GameTab.ALL -> {
                        BackHandler { toNowPlaying() }
                        com.nerfherder.walkthor.ui.AllScreen(
                            game = game,
                            guide = guide?.let { g -> books.firstOrNull { it.id == g.id } ?: g },
                            frame = mirrorFrame,
                            onOpen = ::selectTab,
                            onStartCapture = host::requestMirror,
                            topBar = {
                                GameTabBar(
                                    selectedTab, ::selectTab, onBack = toNowPlaying, onClose = host::closeApp,
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
                            NoGuidePanel(
                                game = game,
                                books = books,
                                coverFile = library::coverFile,
                                onAddGuide = { linkImportTo = game; host.pickGuide() },
                                onPickGuide = { b ->
                                    if (game.emulatorPkg != null) scope.launch { pickForEmulator(game, b) } else linkGuide(game, b.id)
                                    autoOpened = b.id
                                    gameTab = null
                                    openId = b.id
                                },
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
                    shownTab == GameTab.STATS -> {
                        BackHandler { back() }
                        com.nerfherder.walkthor.ui.StatsScreen(game = game, onStartCapture = host::requestMirror, topBar = bar)
                    }
                    shownTab == GameTab.FACE -> {
                        BackHandler { back() }
                        ControlsScreen(game = game, onBack = back, topBar = bar)
                    }
                    else -> {
                        BackHandler { noGuide = null }
                        NowPlayingScreen(
                            game = game,
                            art = art,
                            books = books,
                            coverFile = library::coverFile,
                            onAddGuide = { linkImportTo = game; host.pickGuide() },
                            onPickGuide = { b ->
                                if (game.emulatorPkg != null) scope.launch { pickForEmulator(game, b) } else linkGuide(game, b.id)
                                autoOpened = b.id
                                openId = b.id
                            },
                            onLibrary = { noGuide = null },
                            guide = guide,
                            onClose = host::closeApp,
                            onTab = ::selectTab,
                        )
                    }
                }
              }
            }
        }
    } else if (current != null) {
        Reader(current, onBack = { openId = null }, tabs = null)
    } else {
        LibraryScreen(
            books = books,
            artLinked = artFolder != null,
            artCount = artCount,
            artScanning = artScanning,
            alwaysBottom = alwaysBottom,
            swipeToOpen = swipeOn,
            gameGuide = gameGuideOn && cocoonLinked,
            cocoonLinked = cocoonLinked,
            swipeNeedsPermission = swipeNeedsPermission,
            actions = LibraryActions(
                onOpen = { openId = it.id },
                onImport = host::pickGuide,
                onDelete = library::delete,
                onRename = library::rename,
                coverFile = library::coverFile,
                onPickCoverFile = { host.pickCoverImage(it.id) },
                onSetCover = library::setCover,
                onClearCover = library::clearCover,
                suggestions = { library.suggestions(it) },
                onLinkArtFolder = host::pickArtFolder,
                onLinkCocoon = host::pickCocoonFolder,
                onToggleGameGuide = { on ->
                    if (on && CocoonLaunchLog.linkedFolder(context) == null) host.pickCocoonFolder()
                    else SwipeLauncher.setGameGuide(context, on)
                },
                onUnlinkCocoon = { CocoonLaunchLog.unlink(context); SwipeLauncher.refresh(context) },
                onRescanArt = library::rescanArt,
                onUnlinkArt = library::unlinkArt,
                onToggleAlwaysBottom = { DisplayHelper.setAlwaysBottom(context, it) },
                onToggleSwipeToOpen = { SwipeLauncher.setEnabled(context, it) },
                onClose = host::closeApp,
                onGrantSwipePermission = { SwipeLauncher.requestPermission(context) },
                hasSwipeConsent = { SwipeLauncher.hasConsent(context) },
                onSwipeConsent = { SwipeLauncher.setConsent(context, it) },
                isSwipeServiceOn = { SwipeLauncher.isServiceOn(context) },
                onOpenAppInfo = { SwipeLauncher.openAppInfo(context) },
                onSwapScreen = if (canSwap) ({ host.swapScreen(null) }) else null,
            ),
        )
    }

    if (booting) {
        BootScreen(
            light = bootLight,
            status = bootStatus,
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

/** The Now playing screen's subject: the launched game, its art, and its guide if it has one. */
private data class Showing(val game: LaunchedGame, val art: GameArt, val guide: com.nerfherder.walkthor.data.Book?)

/** What a check of Cocoon's launch log acted on: the game, and its guide or (without one) its art. */
private data class Acted(val game: LaunchedGame, val guide: com.nerfherder.walkthor.data.Book?, val art: GameArt?)

/** How long after WalkThor becomes the top app before it hands priority back to the game. */
private const val HAND_BACK_DELAY_MS = 2_500L
/** The same, when WalkThor has just reopened itself (no boot screen to wait for). */
private const val REVIVED_HAND_BACK_DELAY_MS = 1_000L
/** The hand-back tap only happens this soon after WalkThor opens. */
private const val HAND_BACK_WINDOW_MS = 20_000L

/** Longest the boot screen waits for Cocoon to log a new launch. */
private const val BOOT_WAIT_MS = 1_500L
/** Shortest the boot screen shows, so it reads as an animation rather than a flicker. */
private const val BOOT_MIN_MS = 1_100L
/** Set once per process. */
private var imageLoaderSet = false

/** A Cocoon launch this recent counts as the game being played. */
private const val GAME_RECENT_MS = 6 * 60 * 60 * 1000L
/** After swiping in, the next guide opened within this time is linked to the game. */
private const val LEARN_WINDOW_MS = 15 * 60 * 1000L
