package com.nerfherder.walkthor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

sealed interface ImportState {
    data class Working(val message: String) : ImportState
    data class Failed(val message: String) : ImportState
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = BookRepository.get(app)
    val books: StateFlow<List<Book>> = repo.books

    private val _import = MutableStateFlow<ImportState?>(null)
    val importState: StateFlow<ImportState?> = _import.asStateFlow()

    /** Emits the id of a freshly imported guide so the UI can open it. */
    private val _opened = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val opened: SharedFlow<String> = _opened.asSharedFlow()

    private var importJob: Job? = null

    fun importGuide(uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _import.value = ImportState.Working("Opening…")
            try {
                val book = repo.importGuide(uri) { msg -> _import.value = ImportState.Working(msg) }
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
        viewModelScope.launch(Dispatchers.IO) { repo.delete(book) }
    }

    fun rename(book: Book, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { repo.update(book.copy(title = title.trim())) }
    }
}
