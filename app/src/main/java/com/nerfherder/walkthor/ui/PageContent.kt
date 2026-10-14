package com.nerfherder.walkthor.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nerfherder.walkthor.data.PdfSource
import com.nerfherder.walkthor.data.TxtPagination
import com.nerfherder.walkthor.data.TxtPaginator
import kotlin.math.roundToInt

/**
 * One page of a TXT guide, drawn with the exact StaticLayout settings used to paginate it,
 * with search matches highlighted.
 */
@Composable
fun TxtPage(pagination: TxtPagination, index: Int, query: String, modifier: Modifier = Modifier) {
    val colors = LocalPageColors.current
    val layout = remember(pagination, index, query, colors) {
        val text = pagination.pageText(index).trimEnd('\n')
        val spannable = SpannableString(text)
        if (query.isNotEmpty()) {
            var at = text.indexOf(query, ignoreCase = true)
            while (at >= 0) {
                spannable.setSpan(
                    BackgroundColorSpan(colors.highlight.toArgb()),
                    at, at + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                at = text.indexOf(query, at + query.length, ignoreCase = true)
            }
        }
        TxtPaginator.layout(spannable, TxtPaginator.paint(pagination.textSizePx, colors.ink.toArgb()), pagination.widthPx)
    }
    Canvas(modifier.fillMaxSize().clipToBounds()) {
        drawIntoCanvas { layout.draw(it.nativeCanvas) }
    }
}

/**
 * One page of a PDF, rendered to fit, with search matches highlighted.
 * [renderScale] > 1 renders extra detail for zooming.
 */
@Composable
fun PdfPage(source: PdfSource, index: Int, query: String, modifier: Modifier = Modifier, renderScale: Float = 1f) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val bitmap by produceState<ImageBitmap?>(null, source, index, w, h, renderScale) {
            value = source.render(index, (w * renderScale).roundToInt(), (h * renderScale).roundToInt())?.asImageBitmap()
        }
        val matches by produceState(emptyList<android.graphics.RectF>(), source, index, query) {
            value = if (query.isEmpty()) emptyList() else source.findMatches(index, query)
        }
        Canvas(Modifier.fillMaxSize()) {
            val bmp = bitmap ?: return@Canvas
            // Fit the page into the available space, centered.
            val scale = minOf(size.width / bmp.width, size.height / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val left = (size.width - dw) / 2f
            val top = (size.height - dh) / 2f
            drawImage(
                image = bmp,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
            )
            for (r in matches) {
                drawRect(
                    color = BookColors.Highlight.copy(alpha = 0.45f),
                    topLeft = Offset(left + r.left * dw, top + r.top * dh),
                    size = Size(r.width() * dw, r.height() * dh),
                )
            }
        }
    }
}
