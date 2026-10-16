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
import com.nerfherder.walkthor.ui.GuideIntro
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
        GameFocus.gameRunning.value = CocoonLaunchLog.linkedFolder(this) != null
        applyGameFocus(initial = true)
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(GameFocus.keepOnGame, GameFocus.gameRunning, GameFocus.wantFocus) { _, _, _ -> }
                .collect {
                    applyGameFocus(initial = false)
                    // Back to yielding (e.g. finished a search) while still top: hand priority back again.
                    if (yielding == true && PerfCheck.walkThorIsTop.value == true) scheduleHandBack()
                }
        }
        AppState.activity = java.lang.ref.WeakReference(this)
        coverForBookId = savedInstanceState?.getString("coverFor")

        if (savedInstanceState == null) handleIntent(intent)
        val initialBook = intent.getStringExtra(DisplayHelper.EXTRA_BOOK)
        // The boot screen plays on a fresh open, but not when moving screens with a guide open
        // or when opening a file handed over from another app.
        val boot = savedInstanceState == null && initialBook == null && incoming.value == null

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
            delay(HAND_BACK_DELAY_MS) // let the launch (and boot screen) settle first
            // Only tap the game's screen just after WalkThor opens, never in the middle of play.
            if (android.os.SystemClock.elapsedRealtime() - openedAt > HAND_BACK_WINDOW_MS) {
                PerfCheck.handBack.value = "skipped (WalkThor wasn't just opened)"
                return@launch
            }
            for (attempt in 0 until 2) {
                val skip = when {
                    !GameFocus.keepOnGame.value -> "off (Keep focus on the game is off)"
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
                    PerfCheck.handBack.value = "needs swipe-to-open switched on"
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

    /** Android is short of memory, or WalkThor went to the background: let go of what can be rebuilt. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
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
    /** "Add guide" on the no-guide screen: the next imported guide is linked to this game. */
    var linkImportTo by remember { mutableStateOf<LaunchedGame?>(null) }

    fun linkGuide(game: LaunchedGame, id: String) {
        GameGuides.remember(context, game, id)
        autoOpened = id // already linked; the learning effect below needn't repeat it
        // The Now playing screen (waiting under the guide) now shows this guide too.
        noGuide = noGuide?.let { s -> if (s.game == game) s.copy(guide = books.firstOrNull { b -> b.id == id } ?: s.guide) else s }
        Toast.makeText(context, "WalkThor will open this guide for ${game.title} from now on", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        library.opened.collect { id ->
            linkImportTo?.let { linkGuide(it, id) }
            linkImportTo = null
            openId = id
        }
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

    /**
     * Looks at Cocoon's newest launch. A swipe ([force]) always acts on it; the on-screen poll only
     * acts on a launch it hasn't handled yet, so browsing other guides isn't interrupted.
     */
    suspend fun checkGame(force: Boolean): Acted? = checkLock.withLock {
        val tree = CocoonLaunchLog.linkedFolder(context)
        if (tree == null) { PlaySession.current.value = null; GameFocus.gameRunning.value = false; return@withLock null }
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
            // Already reading this game's guide (e.g. swiped back in): stay on the page.
            noGuide = null
            Acted(game, book, null)
        } else {
            // Every launch lands on the Now playing screen, which dims after a few seconds;
            // with a guide it offers Open guide, without one the game's artwork and ways to add one.
            val art = ArtworkRepository.get(context).gameArt(game.title, game.file)
            openId = null
            noGuide = Showing(game, art, book)
            AutoDim.wake()
            Acted(game, book, art)
        }
    }
    // ---- boot screen: plays while Cocoon's launch log is read on a fresh open ----
    var booting by rememberSaveable { mutableStateOf(boot) }
    var bootFinished by remember { mutableStateOf(false) }
    var bootStatus by remember { mutableStateOf<BootStatus>(BootStatus.Idle) }
    // Cocoon linked with auto-open on: WalkThor is most likely opening alongside a game, so
    // keep the boot animation light while the game loads.
    val bootLight = remember { CocoonLaunchLog.linkedFolder(context) != null && gameGuideOn }
    // A swipe that opened WalkThor is handled by the boot check, not the effect below.
    val bootSwipeTick = remember { if (boot) swipeTick else 0L }
    LaunchedEffect(Unit) {
        if (!booting) return@LaunchedEffect
        val start = System.currentTimeMillis()
        val tree = CocoonLaunchLog.linkedFolder(context)
        if (tree != null && gameGuideNow) {
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
                // A guide: flip into its tile (no separate intro). No guide: flip into the game's art.
                val cover = art?.cover
                bootStatus = BootStatus.Playing(game.title, guide, guide?.let(library::coverFile), cover, game.player)
                delay(if (guide != null || cover != null) 1_150 else 800)
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

    // Opening a different guide soon after swiping in teaches WalkThor which guide goes with the game.
    LaunchedEffect(openId) {
        val game = swipeGame ?: return@LaunchedEffect
        val id = openId ?: return@LaunchedEffect
        if (System.currentTimeMillis() - swipeGameAt > LEARN_WINDOW_MS) { swipeGame = null; return@LaunchedEffect }
        if (id != autoOpened) {
            GameGuides.remember(context, game, id)
            autoOpened = id
            Toast.makeText(context, "Linked this guide to ${game.title}", Toast.LENGTH_SHORT).show()
        }
    }
    // Each time a guide is opened, play its intro.
    var introFor by rememberSaveable { mutableStateOf<String?>(null) }
    var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openId) {
        // A guide opened by the boot screen already got its flourish there.
        if (openId != null && openId != lastOpened && !booting) introFor = openId
        lastOpened = openId
    }
    val current = openId
    if (current != null) {
        Box(Modifier.fillMaxSize()) {
            ReaderScreen(
                bookId = current,
                onBack = { openId = null },
                onClose = host::closeApp,
                onSwapScreen = if (canSwap) ({ host.swapScreen(current) }) else null,
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
    } else if (noGuide != null) {
        val (game, art, guide) = noGuide!!
        BackHandler { noGuide = null }
        NowPlayingScreen(
            game = game,
            art = art,
            books = books,
            coverFile = library::coverFile,
            onAddGuide = { linkImportTo = game; host.pickGuide() },
            onPickGuide = { linkGuide(game, it.id); openId = it.id },
            onLibrary = { noGuide = null },
            guide = guide,
            onOpenGuide = {
                if (guide != null) {
                    AutoDim.wake()
                    lastOpened = guide.id // the tile was just on screen; skip the opening flourish
                    openId = guide.id
                }
            },
            onClose = host::closeApp,
        )
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
