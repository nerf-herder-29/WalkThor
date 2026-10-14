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
        super.onCreate(savedInstanceState)
        DisplayHelper.init(this)
        SwipeLauncher.init(this)
        PageStyle.init(this)
        AutoDim.init(this)
        PlaySession.init(this)
        if (DisplayHelper.ensureOnBottomScreen(this, savedInstanceState)) return
        AppState.activity = java.lang.ref.WeakReference(this)
        coverForBookId = savedInstanceState?.getString("coverFor")

        if (savedInstanceState == null) handleIntent(intent)
        val initialBook = intent.getStringExtra(DisplayHelper.EXTRA_BOOK)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    pollTick.tryEmit(Unit)
                    delay(3_000)
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
                            host = this@MainActivity,
                        )
                        AutoDimOverlay()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("coverFor", coverForBookId)
    }

    override fun onResume() {
        super.onResume()
        hasOtherScreen.value = DisplayHelper.otherDisplay(this) != null
        AppState.activityDisplayId = DisplayHelper.currentDisplayId(this)
        AppState.resumedDisplayId = AppState.activityDisplayId
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
private fun WalkThorApp(initialBookId: String?, host: MainActivity) {
    val library: LibraryViewModel = viewModel()
    val context = LocalContext.current
    val books by library.books.collectAsState()
    val importState by library.importState.collectAsState()
    val artFolder by library.artFolder.collectAsState()
    val artImages by library.artImages.collectAsState()
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
    LaunchedEffect(Unit) { library.opened.collect { openId = it } }
    LaunchedEffect(openId) { AppState.openBookId = openId }

    // ---- open the guide for the game Cocoon just launched ----
    // The game we last acted on, while we wait to see which guide the user opens for it.
    var swipeGame by remember { mutableStateOf<LaunchedGame?>(null) }
    var swipeGameAt by remember { mutableStateOf(0L) }
    var autoOpened by remember { mutableStateOf<String?>(null) }
    val booksNow by rememberUpdatedState(books)
    val gameGuideNow by rememberUpdatedState(gameGuideOn)
    val checkLock = remember { Mutex() }

    /**
     * Looks at Cocoon's newest launch. A swipe ([force]) always acts on it; the on-screen poll only
     * acts on a launch it hasn't handled yet, so browsing other guides isn't interrupted.
     */
    suspend fun checkGame(force: Boolean) = checkLock.withLock {
        val tree = CocoonLaunchLog.linkedFolder(context)
        if (tree == null) { PlaySession.current.value = null; return@withLock }
        val game = CocoonLaunchLog.lastLaunch(context, tree)
        // Only a game launched in the last few hours counts as "playing now".
        val recent = game != null && (game.at <= 0 || System.currentTimeMillis() - game.at <= GAME_RECENT_MS)
        PlaySession.current.value = if (recent) game else null
        if (!gameGuideNow) return@withLock
        if (game == null) {
            if (force) Toast.makeText(context, "Couldn't read Cocoon's launch log — try ⋮ → Change Cocoon folder…", Toast.LENGTH_LONG).show()
            return@withLock
        }
        if (!recent) return@withLock
        if (!force && CocoonLaunchLog.isHandled(context, game)) return@withLock
        CocoonLaunchLog.markHandled(context, game)
        swipeGame = game
        swipeGameAt = System.currentTimeMillis()
        val book = withContext(Dispatchers.Default) { GameGuides.pick(context, booksNow, game) }
        autoOpened = book?.id
        if (book != null) {
            if (book.id != openId) {
                openId = book.id
                AutoDim.wake() // a new game's guide just opened; show it
            }
        } else {
            Toast.makeText(context, "Playing ${game.title} — open its guide and WalkThor will remember it", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(swipeTick) { if (swipeTick != 0L) checkGame(force = true) }
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
        if (openId != null && openId != lastOpened) introFor = openId
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
    } else {
        LibraryScreen(
            books = books,
            artLinked = artFolder != null,
            artCount = artImages.size,
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

    ImportDialogs(
        state = importState,
        onCancel = library::cancelImport,
        onDismissError = library::dismissError,
    )
}

/** A Cocoon launch this recent counts as the game being played. */
private const val GAME_RECENT_MS = 6 * 60 * 60 * 1000L
/** After swiping in, the next guide opened within this time is linked to the game. */
private const val LEARN_WINDOW_MS = 15 * 60 * 1000L
