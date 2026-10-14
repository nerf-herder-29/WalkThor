package com.nerfherder.walkthor

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.BookRepository
import com.nerfherder.walkthor.data.BookType
import com.nerfherder.walkthor.data.PageLayout
import com.nerfherder.walkthor.data.PdfSource
import com.nerfherder.walkthor.data.Search
import com.nerfherder.walkthor.data.SearchHit
import com.nerfherder.walkthor.data.TxtPagination
import com.nerfherder.walkthor.data.TxtPaginator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ReaderViewModel(app: Application, bookId: String) : AndroidViewModel(app) {

    private val repo = BookRepository.get(app)
    private val prefs = app.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private val _book = MutableStateFlow(repo.find(bookId))
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ---- content -----------------------------------------------------------------------
    private var fullText: String? = null
    private val _pagination = MutableStateFlow<TxtPagination?>(null)
    val pagination: StateFlow<TxtPagination?> = _pagination.asStateFlow()
    private val _paginating = MutableStateFlow(false)
    val paginating: StateFlow<Boolean> = _paginating.asStateFlow()

    private val _pdf = MutableStateFlow<PdfSource?>(null)
    val pdf: StateFlow<PdfSource?> = _pdf.asStateFlow()
    private var pdfIndex: List<String> = emptyList()

    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    /** First visible page (the left page of a two-page spread). */
    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _layout = MutableStateFlow(_book.value?.effectiveLayout ?: PageLayout.SINGLE)
    val layout: StateFlow<PageLayout> = _layout.asStateFlow()

    private val _fontScale = MutableStateFlow(prefs.getFloat("fontScale", 1f))
    val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

    // TXT reading position as a character offset, so it survives re-pagination.
    private var anchorOffset = _book.value?.lastOffset ?: 0
    private var geometry: Pair<Int, Int>? = null
    private var paginateJob: Job? = null
    private var saveJob: Job? = null

    // ---- search ------------------------------------------------------------------------
    val query = MutableStateFlow("")
    private val contentVersion = MutableStateFlow(0)

    /** The query actually applied to pages (debounced, trimmed). */
    val appliedQuery: StateFlow<String> = query
        .debounce(250)
        .map { it.trim().takeIf { q -> q.length >= 2 } ?: "" }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val hits: StateFlow<List<SearchHit>> = combine(appliedQuery, contentVersion) { q, _ -> q }
        .mapLatest { q -> if (q.isEmpty()) emptyList() else withContext(Dispatchers.Default) { runSearch(q) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        val b = _book.value
        if (b == null) {
            _error.value = "This guide is no longer in your library."
        } else {
            viewModelScope.launch { load(b) }
        }
    }

    private suspend fun load(b: Book) {
        try {
            when (b.type) {
                BookType.TXT -> {
                    fullText = repo.readText(b)
                    geometry?.let { (w, h) -> repaginate(w, h) }
                }
                BookType.PDF -> {
                    val src = PdfSource.open(getApplication(), repo.bookFile(b))
                    pdfIndex = repo.readPdfIndex(b)
                    _pageCount.value = src.pageCount
                    _currentPage.value = align(b.lastPage.coerceIn(0, (src.pageCount - 1).coerceAtLeast(0)))
                    _pdf.value = src
                    contentVersion.value++
                }
            }
        } catch (e: Exception) {
            _error.value = "Couldn't open this guide (${e.message ?: e.javaClass.simpleName})."
        }
    }

    private fun runSearch(q: String): List<SearchHit> {
        val b = _book.value ?: return emptyList()
        return when (b.type) {
            BookType.TXT -> {
                val p = _pagination.value ?: return emptyList()
                Search.search(p.pageCount, q) { p.pageText(it) }
            }
            BookType.PDF -> {
                val n = minOf(pdfIndex.size, _pageCount.value)
                Search.search(n, q) { pdfIndex[it] }
            }
        }
    }

    // ---- layout & pagination -----------------------------------------------------------

    /** Called by the UI with the size (px) of a page's text area. TXT guides are paginated to it. */
    fun onPageGeometry(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (geometry == widthPx to heightPx) return
        geometry = widthPx to heightPx
        if (_book.value?.type == BookType.TXT) repaginate(widthPx, heightPx)
    }

    private fun repaginate(w: Int, h: Int) {
        val text = fullText ?: return
        val size = TxtPaginator.fitTextSize(w) * _fontScale.value
        val current = _pagination.value
        if (current != null && current.widthPx == w && current.heightPx == h && current.textSizePx == size) return
        paginateJob?.cancel()
        paginateJob = viewModelScope.launch {
            _paginating.value = true
            val p = TxtPaginator.paginate(text, w, h, size)
            _pagination.value = p
            _pageCount.value = p.pageCount
            _currentPage.value = align(p.pageOf(anchorOffset))
            _paginating.value = false
            contentVersion.value++
        }
    }

    fun align(page: Int): Int = if (_layout.value == PageLayout.SPREAD) page - page % 2 else page

    fun toggleLayout() {
        val b = _book.value ?: return
        val next = if (_layout.value == PageLayout.SPREAD) PageLayout.SINGLE else PageLayout.SPREAD
        _layout.value = next
        _currentPage.value = align(_currentPage.value)
        val updated = b.copy(layout = next)
        _book.value = updated
        viewModelScope.launch(Dispatchers.IO) { repo.update(updated) }
    }

    fun changeFontSize(bigger: Boolean) {
        val s = (_fontScale.value * if (bigger) 1.12f else 1 / 1.12f).coerceIn(0.5f, 3f)
        _fontScale.value = (s * 100).roundToInt() / 100f
        prefs.edit().putFloat("fontScale", _fontScale.value).apply()
        geometry?.let { (w, h) -> repaginate(w, h) }
    }

    // ---- position ----------------------------------------------------------------------

    fun onPageChanged(page: Int) {
        _currentPage.value = page
        _pagination.value?.let { p -> if (page in 0 until p.pageCount) anchorOffset = p.starts[page] }
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400)
            val b = repo.find(_book.value?.id ?: return@launch) ?: return@launch
            repo.update(b.copy(lastPage = page, lastOffset = anchorOffset, pageCount = _pageCount.value))
        }
    }

    /** Page of the next search hit after the visible page(s), wrapping around. */
    fun nextHitPage(): Int? {
        val h = hits.value
        if (h.isEmpty()) return null
        val step = if (_layout.value == PageLayout.SPREAD) 2 else 1
        val lastVisible = _currentPage.value + step - 1
        return (h.firstOrNull { it.page > lastVisible } ?: h.first()).page
    }

    fun prevHitPage(): Int? {
        val h = hits.value
        if (h.isEmpty()) return null
        return (h.lastOrNull { it.page < _currentPage.value } ?: h.last()).page
    }

    /** 1-based index of the hit shown on the current spread/page, for the "3 / 12" counter. */
    fun hitPosition(hits: List<SearchHit>, current: Int, layout: PageLayout): Int {
        val step = if (layout == PageLayout.SPREAD) 2 else 1
        val i = hits.indexOfFirst { it.page in current until current + step }
        return if (i < 0) 0 else i + 1
    }

    override fun onCleared() {
        _pdf.value?.close()
    }

    companion object {
        fun factory(app: Application, bookId: String) = viewModelFactory {
            initializer { ReaderViewModel(app, bookId) }
        }
    }
}
