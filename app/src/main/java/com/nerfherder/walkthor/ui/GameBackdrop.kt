package com.nerfherder.walkthor.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
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
 * see-through, so the game's dimmed art shows behind them.
 */
val LocalBackdrop = compositionLocalOf { false }

/** A screen's background colour: [color] normally, see-through over the game's backdrop. */
@Composable
fun backdropOr(color: Color): Color = if (LocalBackdrop.current) Color.Transparent else color

/** How the game's art sits behind its tabs. */
enum class BackdropStyle {
    /** The whole art, sharp but heavily dimmed. */
    DIMMED,
    /** A band of art across the top, fading into black. */
    RIBBON,
    /** The art only behind the tab bar, with a short fade below it. */
    BAR,
    /** No picture: the art's main colour as a faint glow from the top. */
    TINT,
    /** Plain black. */
    PLAIN,
}

private class Art(val image: ImageBitmap, val average: Color)

/**
 * The game's wide art behind its tabs, drawn as [style]. It's decoded once at about the screen's
 * size (so it costs nothing while you play). Without wide art, it's plain black.
 */
@Composable
fun GameBackdrop(heroUri: String?, modifier: Modifier = Modifier, style: BackdropStyle = BackdropStyle.DIMMED) {
    val context = LocalContext.current
    val art by produceState<Art?>(null, heroUri, style) {
        value = if (style == BackdropStyle.PLAIN) null
        else heroUri?.let { uri -> withContext(Dispatchers.IO) { runCatching { screenSizedArt(context, uri) }.getOrNull() } }
    }
    Box(modifier.fillMaxSize().background(BookColors.Ink)) {
        val a = art ?: return@Box
        when (style) {
            BackdropStyle.DIMMED -> {
                ArtImage(a.image, Modifier.fillMaxSize())
                // Dimmed well down so text and the brackets stay easy to read, a little more at the edges.
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)))
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)))))
            }
            BackdropStyle.RIBBON -> Box(Modifier.fillMaxWidth().height(190.dp).clipToBounds()) {
                ArtImage(a.image, Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.35f to Color.Black.copy(alpha = 0.65f),
                            1f to Color.Black,
                        )
                    )
                )
            }
            BackdropStyle.BAR -> Box(Modifier.fillMaxWidth().height(96.dp).clipToBounds()) {
                ArtImage(a.image, Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.5f),
                            0.62f to Color.Black.copy(alpha = 0.6f),
                            1f to Color.Black,
                        )
                    )
                )
            }
            BackdropStyle.TINT -> Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to a.average.copy(alpha = 0.32f),
                        0.55f to Color.Transparent,
                    )
                )
            )
            BackdropStyle.PLAIN -> Unit
        }
    }
}

@Composable
private fun ArtImage(image: ImageBitmap, modifier: Modifier) = Image(
    image,
    contentDescription = null,
    contentScale = ContentScale.Crop,
    filterQuality = FilterQuality.High,
    modifier = modifier,
)

/** The picture at [uri], decoded no bigger than it needs to be to fill the screen, and its average colour. */
private fun screenSizedArt(context: Context, uri: String): Art? {
    val parsed = Uri.parse(uri)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val target = context.resources.displayMetrics.widthPixels.coerceAtLeast(640)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val dot = android.graphics.Bitmap.createScaledBitmap(bmp, 1, 1, true)
    val c = dot.getPixel(0, 0)
    if (dot !== bmp) dot.recycle()
    // Brighten the average a little so the glow reads as the game's colour, not mud.
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c, hsv)
    hsv[1] = (hsv[1] * 1.3f).coerceAtMost(1f); hsv[2] = hsv[2].coerceAtLeast(0.55f)
    return Art(bmp.asImageBitmap(), Color(android.graphics.Color.HSVToColor(hsv)))
}
