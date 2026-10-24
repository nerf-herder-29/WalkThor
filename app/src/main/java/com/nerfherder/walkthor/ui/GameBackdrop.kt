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
 * see-through, so the game's art strip shows behind the tab bar.
 */
val LocalBackdrop = compositionLocalOf { false }

/** A screen's background colour: [color] normally, see-through over the game's backdrop. */
@Composable
fun backdropOr(color: Color): Color = if (LocalBackdrop.current) Color.Transparent else color

/**
 * The game's wide art in a strip behind the tab bar only, fading to black by its bottom edge; the rest of the
 * screen stays OLED black. It's decoded once at about the screen's size (so it costs nothing while
 * you play). Without wide art, it's plain black.
 */
@Composable
fun GameBackdrop(heroUri: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val art by produceState<ImageBitmap?>(null, heroUri) {
        value = heroUri?.let { uri -> withContext(Dispatchers.IO) { runCatching { screenSizedArt(context, uri) }.getOrNull() } }
    }
    Box(modifier.fillMaxSize().background(BookColors.Ink)) {
        val image = art ?: return@Box
        Box(Modifier.fillMaxWidth().height(TabBarHeight).clipToBounds()) {
            ArtImage(image, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.5f),
                        0.55f to Color.Black.copy(alpha = 0.62f),
                        1f to Color.Black,
                    )
                )
            )
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

/** The picture at [uri], decoded no bigger than it needs to be to fill the screen's width. */
private fun screenSizedArt(context: Context, uri: String): ImageBitmap? {
    val parsed = Uri.parse(uri)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val target = context.resources.displayMetrics.widthPixels.coerceAtLeast(640)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
}
