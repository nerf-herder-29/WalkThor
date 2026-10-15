package com.nerfherder.walkthor.ui

import android.content.Context
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.nerfherder.walkthor.data.Background
import kotlinx.coroutines.withContext

/**
 * The main colour of a piece of artwork (Zelda blue, Mario red), for glows and accents.
 * Worked out once from a tiny 40-px copy on the low-priority thread; brass until it's ready,
 * or if the art is mostly grey.
 */
@Composable
fun rememberArtAccent(uri: String?): Color {
    val context = LocalContext.current
    var color by remember(uri) { mutableStateOf(BookColors.Brass) }
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        runCatching { accentOf(context, uri) }.getOrNull()?.let { color = it }
    }
    val shown by animateColorAsState(color, tween(600), label = "accent")
    return shown
}

private suspend fun accentOf(context: Context, uri: String): Color? {
    val request = ImageRequest.Builder(context)
        .data(Uri.parse(uri))
        .size(40)
        .allowHardware(false)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    val bitmap = result.drawable.toBitmap(40, 40)
    return withContext(Background.dispatcher) {
        // Bucket colourful pixels by hue; the heaviest bucket's average colour wins.
        val weight = FloatArray(12)
        val r = FloatArray(12)
        val g = FloatArray(12)
        val b = FloatArray(12)
        val hsv = FloatArray(3)
        var total = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            total++
            if (android.graphics.Color.alpha(p) < 128) continue
            android.graphics.Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue
            val bin = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
            val w = hsv[1] * hsv[2]
            weight[bin] += w
            r[bin] += android.graphics.Color.red(p) * w
            g[bin] += android.graphics.Color.green(p) * w
            b[bin] += android.graphics.Color.blue(p) * w
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return@withContext null
        // Mostly grey art: keep WalkThor's brass.
        if (weight[best] < total * 0.03f) return@withContext null
        val avg = android.graphics.Color.rgb(
            (r[best] / weight[best]).toInt().coerceIn(0, 255),
            (g[best] / weight[best]).toInt().coerceIn(0, 255),
            (b[best] / weight[best]).toInt().coerceIn(0, 255),
        )
        android.graphics.Color.colorToHSV(avg, hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = 0.85f
        Color(android.graphics.Color.HSVToColor(hsv))
    }
}
