package com.nerfherder.walkthor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * True while a game's tabs sit over [GameBackdrop]: their screens then leave their background
 * see-through, so the game's blurred art shows behind them.
 */
val LocalBackdrop = compositionLocalOf { false }

/** A screen's background colour: [color] normally, see-through over the game's backdrop. */
@Composable
fun backdropOr(color: Color): Color = if (LocalBackdrop.current) Color.Transparent else color

/**
 * The game's wide art, heavily blurred and darkened, behind its tabs. The blur is made once, on a
 * tiny copy of the picture (so it costs nothing while you play), then stretched smoothly to fill
 * the screen. Without wide art, it's plain black.
 */
@Composable
fun GameBackdrop(heroUri: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val blurred by produceState<ImageBitmap?>(null, heroUri) {
        value = heroUri?.let { uri -> withContext(Dispatchers.IO) { runCatching { blurredArt(context, uri) }.getOrNull() } }
    }
    Box(modifier.fillMaxSize().background(BookColors.Ink)) {
        blurred?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
            )
            // Darkened so text and the brackets stay easy to read, a little more at the edges.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)))
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f)))
                )
            )
        }
    }
}

/** A tiny, smoothly blurred copy of the picture at [uri]. */
private fun blurredArt(context: Context, uri: String): ImageBitmap? {
    val parsed = Uri.parse(uri)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    // Decode small to start with: only a few dozen pixels survive the blur anyway.
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 192) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val src = context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val w = 96
    val h = (96f * src.height / src.width).toInt().coerceIn(8, 192)
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    if (small !== src) src.recycle()
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    repeat(2) { boxBlur(px, w, h, 2) }
    val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    small.recycle()
    return out.asImageBitmap()
}

/** One horizontal and one vertical box blur pass of [r] pixels, in place. */
private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
    val tmp = IntArray(px.size)
    fun pass(src: IntArray, dst: IntArray, len: Int, lines: Int, stride: Int, step: Int) {
        for (line in 0 until lines) {
            val base = line * stride
            for (i in 0 until len) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val c = src[base + j * step]
                    a += c ushr 24 and 0xff; rr += c shr 16 and 0xff; g += c shr 8 and 0xff; b += c and 0xff; n++
                }
                dst[base + i * step] = (a / n shl 24) or (rr / n shl 16) or (g / n shl 8) or (b / n)
            }
        }
    }
    pass(px, tmp, w, h, w, 1)   // along each row
    pass(tmp, px, h, w, 1, w)   // along each column
}
