package com.nerfherder.walkthor.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders PDF pages with the platform [PdfRenderer] (fast, no extra native libs) and finds
 * on-page positions of search matches with PdfBox (loaded lazily, only when highlighting).
 */
class PdfSource private constructor(
    private val context: Context,
    private val file: File,
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Page sizes in PDF points, used for aspect ratio. */
    val pageSizes: List<Pair<Int, Int>>,
) : Closeable {

    val pageCount get() = pageSizes.size

    // PdfRenderer allows only one open page at a time.
    private val renderLock = Mutex()
    private var closed = false

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init { synchronized(live) { live.add(this) } }

    /** Renders [index] to fit inside [boxW]×[boxH] px. */
    suspend fun render(index: Int, boxW: Int, boxH: Int): Bitmap? {
        if (index !in pageSizes.indices || boxW <= 0 || boxH <= 0) return null
        val (pw, ph) = pageSizes[index]
        val aspect = pw.toFloat() / ph.toFloat()
        var w: Int
        var h: Int
        if (boxW.toFloat() / boxH > aspect) { h = boxH; w = (h * aspect).roundToInt() } else { w = boxW; h = (w / aspect).roundToInt() }
        val longest = max(w, h)
        if (longest > MAX_DIMENSION) { val s = MAX_DIMENSION.toFloat() / longest; w = (w * s).roundToInt(); h = (h * s).roundToInt() }
        w = w.coerceAtLeast(1); h = h.coerceAtLeast(1)

        val key = "$index:$w:$h"
        cache.get(key)?.let { return it }
        return renderLock.withLock {
            cache.get(key)?.let { return@withLock it }
            if (closed) return@withLock null
            withContext(Dispatchers.IO) {
                runCatching {
                    renderer.openPage(index).use { page ->
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }.getOrNull()
            }?.also { cache.put(key, it) }
        }
    }

    /** Drops cached pages and the parsed PDF used for highlighting; both come back on demand. */
    private fun trim() {
        cache.evictAll()
        if (textLock.tryLock()) {
            try { runCatching { textDoc?.close() }; textDoc = null } finally { textLock.unlock() }
        }
    }

    // ---- match highlighting -------------------------------------------------------------

    private val textLock = Mutex()
    private var textDoc: PDDocument? = null

    /**
     * Rectangles (as fractions of the page, 0..1) around every occurrence of [query] on [index].
     */
    suspend fun findMatches(index: Int, query: String): List<RectF> {
        if (query.isBlank() || index !in pageSizes.indices) return emptyList()
        return textLock.withLock {
            if (closed) return@withLock emptyList()
            withContext(Dispatchers.IO) {
                runCatching {
                    val doc = textDoc ?: run {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        PDDocument.load(file).also { textDoc = it }
                    }
                    locate(doc, index, query)
                }.getOrDefault(emptyList())
            }
        }
    }

    private fun locate(doc: PDDocument, index: Int, query: String): List<RectF> {
        val stripper = PositionStripper().apply {
            sortByPosition = true
            startPage = index + 1
            endPage = index + 1
        }
        stripper.getText(doc)
        val page = doc.getPage(index)
        val box = page.cropBox
        val rotated = page.rotation % 180 != 0
        val pw = if (rotated) box.height else box.width
        val ph = if (rotated) box.width else box.height
        if (pw <= 0f || ph <= 0f) return emptyList()

        val text = stripper.chars.toString()
        val out = ArrayList<RectF>()
        var from = 0
        while (true) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            var rect: RectF? = null
            for (i in at until at + query.length) {
                val tp = stripper.positions[i] ?: continue
                val h = max(tp.heightDir, tp.fontSizeInPt * 0.7f)
                val r = RectF(
                    tp.xDirAdj / pw,
                    (tp.yDirAdj - h) / ph,
                    (tp.xDirAdj + tp.widthDirAdj) / pw,
                    (tp.yDirAdj + h * 0.25f) / ph,
                )
                if (rect == null) rect = r
                else if (r.top > rect.bottom || r.bottom < rect.top) { out.add(rect); rect = r } // wrapped onto next line
                else rect.union(r)
            }
            rect?.let { out.add(it) }
            from = at + query.length
        }
        return out
    }

    /** Collects every character of a page together with its position. */
    private class PositionStripper : PDFTextStripper() {
        val chars = StringBuilder()
        val positions = ArrayList<TextPosition?>()

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            textPositions?.forEach { tp ->
                val u = tp.unicode ?: return@forEach
                for (c in u) { chars.append(c); positions.add(tp) }
            }
            chars.append(' '); positions.add(null)
        }
    }

    override fun close() {
        closed = true
        // Close once any in-flight render/search has finished, without blocking the caller.
        CoroutineScope(Dispatchers.IO).launch {
            renderLock.withLock {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
            textLock.withLock {
                runCatching { textDoc?.close() }
                textDoc = null
            }
            cache.evictAll()
        }
    }

    companion object {
        private const val MAX_DIMENSION = 3000

        /** Open PDFs, so rendered pages can be dropped when Android is short of memory. */
        private val live: MutableSet<PdfSource> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

        /** Frees every cached page bitmap (they're re-rendered on demand). */
        fun trimAll() {
            synchronized(live) { live.toList() }.forEach { it.trim() }
        }

        suspend fun open(context: Context, file: File): PdfSource = withContext(Dispatchers.IO) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val sizes = List(renderer.pageCount) { i -> renderer.openPage(i).use { it.width to it.height } }
            PdfSource(context, file, pfd, renderer, sizes)
        }
    }
}
