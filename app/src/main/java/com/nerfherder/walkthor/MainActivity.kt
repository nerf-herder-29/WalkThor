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
import com.nerfherder.walkthor.ui.ImportDialogs
import com.nerfherder.walkthor.ui.LibraryActions
import com.nerfherder.walkthor.ui.LibraryScreen
import com.nerfherder.walkthor.ui.ReaderScreen
import com.nerfherder.walkthor.ui.WalkThorTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** A document handed to us by "Open with" / "Share", waiting to be imported. */
    private val incoming = MutableStateFlow<Uri?>(null)
    private val hasOtherScreen = MutableStateFlow(false)
    /** Results from the system pickers, consumed by the UI. */
    private val pickedArtFolder = MutableStateFlow<Uri?>(null)
    private val pickedCover = MutableStateFlow<Pair<String, Uri>?>(null)
    private var coverForBookId: String? = null

    private val guidePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) incoming.value = uri
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) pickedArtFolder.value = uri
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = coverForBookId
        if (uri != null && id != null) pickedCover.value = id to uri
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DisplayHelper.init(this)
        SwipeLauncher.init(this)
        if (DisplayHelper.ensureOnBottomScreen(this, savedInstanceState)) return
        coverForBookId = savedInstanceState?.getString("coverFor")

        if (savedInstanceState == null) handleIntent(intent)
        val initialBook = intent.getStringExtra(DisplayHelper.EXTRA_BOOK)

        setContent {
            WalkThorTheme {
                WalkThorApp(
                    initialBookId = initialBook,
                    host = this,
                )
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
        // Picks up "Display over other apps" being granted after a trip to Settings.
        SwipeLauncher.sync(this)
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
    }

    // ---- used by the UI ----------------------------------------------------------------------

    internal val incomingFlow get() = incoming
    internal val hasOtherScreenFlow get() = hasOtherScreen
    internal val pickedArtFolderFlow get() = pickedArtFolder
    internal val pickedCoverFlow get() = pickedCover

    internal fun pickGuide() {
        // Some file managers label .txt guides as octet-stream; the repository checks the content.
        guidePicker.launch(arrayOf("application/pdf", "text/plain", "text/*", "application/octet-stream"))
    }

    internal fun pickArtFolder() = folderPicker.launch(null)

    internal fun pickCoverImage(bookId: String) {
        coverForBookId = bookId
        imagePicker.launch(arrayOf("image/*"))
    }

    internal fun swapScreen(bookId: String?) = DisplayHelper.moveToOtherScreen(this, bookId)

    // Shoulder buttons (L1/R1), page keys and the D-pad turn pages while a guide is open.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
    LaunchedEffect(Unit) { library.opened.collect { openId = it } }
    LaunchedEffect(Unit) {
        library.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val current = openId
    if (current != null) {
        ReaderScreen(
            bookId = current,
            onBack = { openId = null },
            onSwapScreen = if (canSwap) ({ host.swapScreen(current) }) else null,
        )
    } else {
        LibraryScreen(
            books = books,
            artLinked = artFolder != null,
            artCount = artImages.size,
            artScanning = artScanning,
            alwaysBottom = alwaysBottom,
            swipeToOpen = swipeOn,
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
                onRescanArt = library::rescanArt,
                onUnlinkArt = library::unlinkArt,
                onToggleAlwaysBottom = { DisplayHelper.setAlwaysBottom(context, it) },
                onToggleSwipeToOpen = { SwipeLauncher.setEnabled(context, it) },
                onGrantSwipePermission = { SwipeLauncher.requestPermission(context) },
                canDrawOverlays = { SwipeLauncher.canDrawOverlays(context) },
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
