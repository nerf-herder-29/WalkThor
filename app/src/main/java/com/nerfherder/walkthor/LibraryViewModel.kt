package com.nerfherder.walkthor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nerfherder.walkthor.data.ArtMatch
import com.nerfherder.walkthor.data.ArtworkRepository
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.BookRepository
import com.nerfherder.walkthor.data.ImportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ImportState {
    data class Working(val message: String) : ImportState
    data class Failed(val message: String) : ImportState
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = BookRepository.get(app)
    private val art = ArtworkRepository.get(app)

    val books: StateFlow<List<Book>> = repo.books

    val artFolder: StateFlow<Uri?> = art.folder
    /** Number of images in the linked artwork folder (the index itself stays on disk). */
    val artCount = art.count.also { art.prepare() }
    val artScanning: StateFlow<String?> = art.scanning

    private val _import = MutableStateFlow<ImportState?>(null)
    val importState: StateFlow<ImportState?> = _import.asStateFlow()

    /** Emits the id of a freshly imported guide so the UI can open it. */
    private val _opened = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val opened: SharedFlow<String> = _opened.asSharedFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short notices for a snackbar/toast. */
    val messages: SharedFlow<String> = _message.asSharedFlow()

    private var importJob: Job? = null


    fun coverFile(book: Book): File? = repo.coverFile(book)

    /** Links Cocoon's data folder (the one holding launch_debug.log) for game-aware opening. */
    fun linkCocoon(tree: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            if (com.nerfherder.walkthor.data.CocoonLaunchLog.hasLog(app, tree)) {
                com.nerfherder.walkthor.data.CocoonLaunchLog.link(app, tree)
                com.nerfherder.walkthor.SwipeLauncher.setGameGuide(app, true)
                _message.tryEmit("Cocoon linked — swipe in during a game to open its guide")
            } else {
                _message.tryEmit("No launch_debug.log in that folder — pick the Cocoon folder itself")
            }
            com.nerfherder.walkthor.SwipeLauncher.refresh(app)
        }
    }

    fun importGuide(uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _import.value = ImportState.Working("Opening…")
            try {
                val book = repo.importGuide(uri) { msg -> _import.value = ImportState.Working(msg) }
                autoCover(book)
                _import.value = null
                _opened.tryEmit(book.id)
            } catch (e: ImportException) {
                _import.value = ImportState.Failed(e.message ?: "Import failed.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _import.value = null
                throw e
            } catch (e: Exception) {
                _import.value = ImportState.Failed("Import failed (${e.message ?: e.javaClass.simpleName}).")
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        _import.value = null
    }

    fun dismissError() { _import.value = null }

    fun delete(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.delete(book)
            com.nerfherder.walkthor.data.GameGuides.forget(getApplication(), book.id)
        }
    }

    fun rename(book: Book, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val fresh = repo.find(book.id) ?: return@launch
            repo.update(fresh.copy(title = title.trim()))
            // A clearer title can find better artwork.
            autoCover(fresh)
        }
    }

    // ---- artwork -------------------------------------------------------------------------

    fun linkArtFolder(tree: Uri) {
        viewModelScope.launch {
            art.linkFolder(tree)
            val n = autoCoverAll()
            val total = art.count.value
            _message.tryEmit(
                if (total == 0) "No images found in that folder"
                else "Found $total images · matched $n cover${if (n == 1) "" else "s"}"
            )
        }
    }

    fun rescanArt() {
        viewModelScope.launch {
            art.rescan()
            val n = autoCoverAll()
            _message.tryEmit("Artwork refreshed · updated $n cover${if (n == 1) "" else "s"}")
        }
    }

    fun unlinkArt() = art.unlinkFolder()

    suspend fun suggestions(query: String): List<ArtMatch> = art.suggestions(query)

    fun setCover(book: Book, source: Uri) {
        viewModelScope.launch {
            val name = art.saveCover(source, repo.coversDir, book.id)
            if (name == null) {
                _message.tryEmit("Couldn't read that image")
                return@launch
            }
            replaceCover(book.id, name, manual = true, source = source.toString())
        }
    }

    fun clearCover(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            val fresh = repo.find(book.id) ?: return@launch
            repo.coverFile(fresh)?.delete()
            // Manual, so automatic matching doesn't put one straight back.
            repo.update(fresh.copy(cover = null, coverManual = true, coverSource = null))
        }
    }

    private suspend fun replaceCover(bookId: String, name: String, manual: Boolean, source: String?) = withContext(Dispatchers.IO) {
        val fresh = repo.find(bookId) ?: return@withContext
        repo.coverFile(fresh)?.delete()
        repo.update(fresh.copy(cover = name, coverManual = manual, coverSource = source))
    }

    /**
     * Gives a guide the closest-matching artwork. Covers the user chose by hand are left alone;
     * automatic ones are replaced when a rescan finds a closer image.
     */
    private suspend fun autoCover(book: Book): Boolean {
        val fresh = repo.find(book.id) ?: return false
        if (fresh.coverManual) return false
        val match = art.autoMatch(fresh.title) ?: return false
        if (match.uri == fresh.coverSource && repo.coverFile(fresh) != null) return false
        val name = art.saveCover(Uri.parse(match.uri), repo.coversDir, fresh.id) ?: return false
        replaceCover(fresh.id, name, manual = false, source = match.uri)
        return true
    }

    private suspend fun autoCoverAll(): Int {
        var n = 0
        for (b in repo.books.value) if (autoCover(b)) n++
        return n
    }
}
