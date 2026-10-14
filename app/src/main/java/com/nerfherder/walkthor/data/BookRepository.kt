package com.nerfherder.walkthor.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Page separator used in the extracted-text index of PDFs. */
const val PAGE_SEPARATOR = '\u000C'

class ImportException(message: String) : Exception(message)

/**
 * Stores imported guides in app-private storage (so the original file can be moved or deleted)
 * and keeps a small JSON catalogue of them.
 */
class BookRepository private constructor(private val context: Context) {

    private val dir = File(context.filesDir, "books").apply { mkdirs() }
    private val catalogue = File(dir, "library.json")
    private val lock = Any()

    private val _books = MutableStateFlow(loadCatalogue())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    fun bookFile(book: Book) = File(dir, book.fileName)
    fun coverFile(book: Book): File? = book.cover?.let { File(coversDir, it) }?.takeIf { it.exists() }
    fun indexFile(book: Book) = File(dir, "${book.id}.idx")

    fun find(id: String): Book? = _books.value.firstOrNull { it.id == id }

    fun update(book: Book) = synchronized(lock) {
        _books.value = _books.value.map { if (it.id == book.id) book else it }
        saveCatalogue(_books.value)
    }

    fun delete(book: Book) = synchronized(lock) {
        bookFile(book).delete()
        indexFile(book).delete()
        coverFile(book)?.delete()
        _books.value = _books.value.filterNot { it.id == book.id }
        saveCatalogue(_books.value)
    }

    /** Reads the whole (already normalized, UTF-8) text of a TXT guide. */
    suspend fun readText(book: Book): String = withContext(Dispatchers.IO) { bookFile(book).readText() }

    /** Extracted text of each PDF page, used for search. */
    suspend fun readPdfIndex(book: Book): List<String> = withContext(Dispatchers.IO) {
        val f = indexFile(book)
        if (!f.exists()) emptyList() else f.readText().split(PAGE_SEPARATOR)
    }

    /**
     * Copies the document into the library. For PDFs the text of every page is extracted
     * once so searching is instant afterwards.
     */
    suspend fun importGuide(uri: Uri, onProgress: (String) -> Unit): Book = withContext(Dispatchers.IO) {
        val displayName = displayName(uri) ?: "Guide"
        val mime = context.contentResolver.getType(uri)
        val id = UUID.randomUUID().toString()
        val tmp = File(dir, "$id.tmp")

        onProgress("Copying $displayName…")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: throw ImportException("Couldn't open that file.")
        } catch (e: SecurityException) {
            throw ImportException("WalkThor wasn't given permission to read that file.")
        }

        val type = detectType(displayName, mime, tmp)
            ?: run { tmp.delete(); throw ImportException("Only PDF and TXT walkthroughs are supported.") }
        val title = displayName.substringBeforeLast('.').ifBlank { displayName }

        try {
            val book = when (type) {
                BookType.TXT -> {
                    onProgress("Reading text…")
                    val text = normalizeText(decodeText(tmp.readBytes()))
                    if (text.isBlank()) throw ImportException("That text file is empty.")
                    val fileName = "$id.txt"
                    File(dir, fileName).writeText(text)
                    Book(id, title, BookType.TXT, fileName, System.currentTimeMillis())
                }
                BookType.PDF -> {
                    val fileName = "$id.pdf"
                    val target = File(dir, fileName)
                    tmp.copyTo(target, overwrite = true)
                    val pages = try {
                        extractPdfText(target) { done, total -> onProgress("Indexing page $done of $total…") }
                    } catch (e: Exception) {
                        target.delete()
                        if (e is ImportException || e is kotlinx.coroutines.CancellationException) throw e
                        throw ImportException("Couldn't read that PDF (${e.message ?: e.javaClass.simpleName}).")
                    }
                    File(dir, "$id.idx").writeText(pages.joinToString(PAGE_SEPARATOR.toString()))
                    Book(id, title, BookType.PDF, fileName, System.currentTimeMillis(), pageCount = pages.size)
                }
            }
            synchronized(lock) {
                _books.value = listOf(book) + _books.value
                saveCatalogue(_books.value)
            }
            book
        } finally {
            tmp.delete()
        }
    }

    private suspend fun extractPdfText(file: File, progress: (Int, Int) -> Unit): List<String> {
        PDFBoxResourceLoader.init(context.applicationContext)
        val ctx = coroutineContext
        val doc = try {
            PDDocument.load(file)
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw ImportException("That PDF is password protected.")
        }
        return doc.use { d ->
            val total = d.numberOfPages
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            List(total) { i ->
                ctx.ensureActive()
                progress(i + 1, total)
                stripper.startPage = i + 1
                stripper.endPage = i + 1
                // Keep the index free of our separator character.
                runCatching { stripper.getText(d) }.getOrDefault("").replace(PAGE_SEPARATOR, ' ')
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun detectType(name: String, mime: String?, file: File): BookType? {
        val lower = name.lowercase()
        val head = ByteArray(1024)
        val n = file.inputStream().use { it.read(head) }.coerceAtLeast(0)
        val looksPdf = n >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "%PDF"
        return when {
            looksPdf || lower.endsWith(".pdf") || mime == "application/pdf" -> if (looksPdf) BookType.PDF else null
            lower.endsWith(".txt") || mime?.startsWith("text/") == true -> BookType.TXT
            // Unknown extension: accept it as text if it has no NUL bytes (i.e. isn't binary).
            n > 0 && (0 until n).none { head[it] == 0.toByte() } -> BookType.TXT
            else -> null
        }
    }

    private fun loadCatalogue(): List<Book> = synchronized(lock) {
        if (!catalogue.exists()) return emptyList()
        runCatching {
            val arr = JSONArray(catalogue.readText())
            List(arr.length()) { Book.fromJson(arr.getJSONObject(it)) }
                .filter { File(dir, it.fileName).exists() }
        }.getOrDefault(emptyList())
    }

    private fun saveCatalogue(books: List<Book>) {
        val arr = JSONArray()
        books.forEach { arr.put(it.toJson()) }
        val tmp = File(dir, "library.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(catalogue)
    }

    companion object {
        @Volatile private var instance: BookRepository? = null
        fun get(context: Context): BookRepository =
            instance ?: synchronized(this) {
                instance ?: BookRepository(context.applicationContext).also { instance = it }
            }

        /** UTF-8/UTF-16 with BOM detection, falling back to Windows-1252 (common for older GameFAQs guides). */
        fun decodeText(bytes: ByteArray): String {
            fun has(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
            return when {
                has(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
                has(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                has(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                else -> try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                } catch (e: CharacterCodingException) {
                    String(bytes, Charset.forName("windows-1252"))
                }
            }
        }

        /** Unifies line endings, expands tabs (8 columns, so ASCII tables line up) and drops control chars. */
        fun normalizeText(s: String): String {
            val src = s.replace("\r\n", "\n").replace('\r', '\n')
            val sb = StringBuilder(src.length)
            var col = 0
            for (c in src) {
                when {
                    c == '\n' || c == PAGE_SEPARATOR -> { sb.append('\n'); col = 0 }
                    c == '\t' -> { val n = 8 - col % 8; repeat(n) { sb.append(' ') }; col += n }
                    c < ' ' -> Unit
                    else -> { sb.append(c); col++ }
                }
            }
            return sb.toString()
        }
    }
}
