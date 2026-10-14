package com.nerfherder.walkthor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nerfherder.walkthor.ui.ImportDialogs
import com.nerfherder.walkthor.ui.LibraryScreen
import com.nerfherder.walkthor.ui.ReaderScreen
import com.nerfherder.walkthor.ui.WalkThorTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** A document handed to us by "Open with" / "Share", waiting to be imported. */
    private val incoming = MutableStateFlow<Uri?>(null)
    private val hasOtherScreen = MutableStateFlow(false)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) incoming.value = uri
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DisplayHelper.maybeRelaunchOnPreferredScreen(this, savedInstanceState)) return

        if (savedInstanceState == null) handleIntent(intent)
        val initialBook = intent.getStringExtra(DisplayHelper.EXTRA_BOOK)

        setContent {
            WalkThorTheme {
                WalkThorApp(
                    initialBookId = initialBook,
                    incoming = incoming,
                    hasOtherScreen = hasOtherScreen,
                    onPickFile = {
                        // Some file managers label .txt guides as octet-stream, so allow that too;
                        // the repository checks the actual content.
                        picker.launch(arrayOf("application/pdf", "text/plain", "text/*", "application/octet-stream"))
                    },
                    onSwapScreen = { bookId -> DisplayHelper.moveToOtherScreen(this, bookId) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasOtherScreen.value = DisplayHelper.otherDisplay(this) != null
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
private fun WalkThorApp(
    initialBookId: String?,
    incoming: MutableStateFlow<Uri?>,
    hasOtherScreen: MutableStateFlow<Boolean>,
    onPickFile: () -> Unit,
    onSwapScreen: (String?) -> Unit,
) {
    val library: LibraryViewModel = viewModel()
    val books by library.books.collectAsState()
    val importState by library.importState.collectAsState()
    val pending by incoming.collectAsState()
    val canSwap by hasOtherScreen.collectAsState()
    var openId by rememberSaveable { mutableStateOf(initialBookId) }

    LaunchedEffect(pending) {
        pending?.let {
            incoming.value = null
            library.importGuide(it)
        }
    }
    LaunchedEffect(Unit) {
        library.opened.collect { openId = it }
    }

    val current = openId
    if (current != null) {
        ReaderScreen(
            bookId = current,
            onBack = { openId = null },
            onSwapScreen = if (canSwap) ({ onSwapScreen(current) }) else null,
        )
    } else {
        LibraryScreen(
            books = books,
            onOpen = { openId = it.id },
            onImport = onPickFile,
            onDelete = library::delete,
            onRename = library::rename,
            onSwapScreen = if (canSwap) ({ onSwapScreen(null) }) else null,
        )
    }

    ImportDialogs(
        state = importState,
        onCancel = library::cancelImport,
        onDismissError = library::dismissError,
    )
}
