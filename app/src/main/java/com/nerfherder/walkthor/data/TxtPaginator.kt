package com.nerfherder.walkthor.data

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.min

/** How a TXT guide was split into pages for a given page size and font size. */
class TxtPagination(
    val text: String,
    /** Offset of the first character of every page. */
    val starts: IntArray,
    val widthPx: Int,
    val heightPx: Int,
    val textSizePx: Float,
) {
    val pageCount get() = starts.size
    fun pageRange(page: Int): IntRange {
        val s = starts[page]
        val e = if (page + 1 < starts.size) starts[page + 1] else text.length
        return s until e
    }
    fun pageText(page: Int): String {
        val r = pageRange(page)
        return text.substring(r.first, r.last + 1)
    }
    /** Page containing the character at [offset]. */
    fun pageOf(offset: Int): Int {
        var i = starts.binarySearch(offset)
        if (i < 0) i = -i - 2
        return i.coerceIn(0, starts.size - 1)
    }
}

/**
 * Splits text into screen-sized pages with [StaticLayout]. The same paint and layout settings
 * are used when drawing a page, so the split always lines up with what's on screen.
 */
object TxtPaginator {

    /** Characters per chunk handed to StaticLayout; chunks always end at a newline. */
    private const val CHUNK = 40_000

    fun paint(textSizePx: Float, color: Int = 0xFF2A241D.toInt()) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = textSizePx
        this.color = color
    }

    fun layout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()

    /** Text size that fits [columns] monospace characters into [widthPx] (GameFAQs guides are ~79 wide). */
    fun fitTextSize(widthPx: Int, columns: Int = 80): Float {
        val probe = paint(100f)
        val w = probe.measureText("M".repeat(columns))
        return floor(100f * widthPx / w * 0.99f).coerceAtLeast(6f)
    }

    suspend fun paginate(text: String, widthPx: Int, heightPx: Int, textSizePx: Float): TxtPagination =
        withContext(Dispatchers.Default) {
            val ctx = coroutineContext
            val paint = paint(textSizePx)
            val starts = ArrayList<Int>()
            starts.add(0)
            var usedOnPage = 0      // px of the current page already filled (carried across chunks)
            var linesOnPage = 0
            var chunkStart = 0
            while (chunkStart < text.length) {
                ctx.ensureActive()
                var chunkEnd = min(text.length, chunkStart + CHUNK)
                if (chunkEnd < text.length) {
                    val nl = text.lastIndexOf('\n', chunkEnd - 1)
                    if (nl >= chunkStart) chunkEnd = nl + 1
                }
                val layout = layout(text.subSequence(chunkStart, chunkEnd), paint, widthPx)
                // Lines of this chunk are positioned as if the page started `usedOnPage` px earlier.
                var pageTop = -usedOnPage
                // StaticLayout adds an empty line after a trailing '\n'; the next chunk supplies that line.
                var lastLine = layout.lineCount - 1
                if (chunkEnd < text.length && lastLine > 0 &&
                    layout.getLineStart(lastLine) >= chunkEnd - chunkStart
                ) lastLine--
                for (line in 0..lastLine) {
                    val bottom = layout.getLineBottom(line)
                    if (bottom - pageTop > heightPx && linesOnPage > 0) {
                        pageTop = layout.getLineTop(line)
                        linesOnPage = 0
                        starts.add(chunkStart + layout.getLineStart(line))
                    }
                    linesOnPage++
                }
                if (lastLine >= 0) usedOnPage = layout.getLineBottom(lastLine) - pageTop
                chunkStart = chunkEnd
            }
            // Drop a trailing page that holds only whitespace.
            while (starts.size > 1 && text.substring(starts.last()).isBlank()) starts.removeAt(starts.lastIndex)
            TxtPagination(text, starts.toIntArray(), widthPx, heightPx, textSizePx)
        }
}
