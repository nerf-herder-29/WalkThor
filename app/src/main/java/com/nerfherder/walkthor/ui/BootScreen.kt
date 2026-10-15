package com.nerfherder.walkthor.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerfherder.walkthor.data.Book
import kotlinx.coroutines.delay
import android.net.Uri
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the boot screen says under the wordmark. */
sealed interface BootStatus {
    /** Nothing to check; just the logo. */
    data object Idle : BootStatus
    /** Reading Cocoon's launch log. */
    data object Checking : BootStatus
    /**
     * Found the game being played, and its guide if WalkThor has one; with no guide, [art] is
     * the game's artwork (content URI) to flip into instead.
     */
    data class Playing(
        val title: String,
        val guide: Book? = null,
        val cover: File? = null,
        val art: String? = null,
    ) : BootStatus
}

/**
 * WalkThor's boot screen: the app's book opens, the magnifier sweeps across its pages while
 * WalkThor reads Cocoon's launch log. When the game's guide is found, the book flips around into
 * that guide's tile, which then pushes toward you as everything fades into the guide (one
 * continuous animation, no separate guide intro). Fades out once [finished]; then [onGone].
 * Tap to skip.
 */
@Composable
fun BootScreen(status: BootStatus, finished: Boolean, onSkip: () -> Unit, onGone: () -> Unit) {
    val gone by rememberUpdatedState(onGone)
    val open = remember { Animatable(0f) }      // pages folding open from the spine
    val ink = remember { Animatable(0f) }       // text lines + magnifier appearing
    val word = remember { Animatable(0f) }      // wordmark
    val exit = remember { Animatable(0f) }      // 1 = faded out
    val flip = remember { Animatable(0f) }      // 0 = the book, 1 = the guide's tile
    val guide = (status as? BootStatus.Playing)?.guide
    val cover = (status as? BootStatus.Playing)?.cover
    val art = (status as? BootStatus.Playing)?.art?.takeIf { guide == null }

    LaunchedEffect(guide?.id, art) {
        if (guide != null || art != null) flip.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
    }

    LaunchedEffect(Unit) {
        open.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        delay(260)
        ink.animateTo(1f, tween(420))
    }
    LaunchedEffect(Unit) {
        delay(380)
        word.animateTo(1f, tween(460))
    }
    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        exit.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        gone()
    }

    // The magnifier drifts over the pages, as if reading.
    val sweep = rememberInfiniteTransition(label = "boot")
    val t by sweep.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(BookColors.Ink)
            .pointerInput(Unit) { detectTapGestures { onSkip() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Box(
            Modifier
                .size(200.dp)
                .graphicsLayer {
                    // Leaving: a gentle swell for the logo, a push toward you for a guide's tile.
                    val s = 1f + exit.value * (0.06f + 0.3f * flip.value)
                    scaleX = s; scaleY = s
                },
            contentAlignment = Alignment.Center,
          ) {
            Canvas(
                Modifier
                    .size(200.dp)
                    .graphicsLayer {
                        // First half of the flip: the book turns away...
                        val f = flip.value
                        alpha = if (f < 0.5f) 1f else 0f
                        rotationY = f * 180f
                        cameraDistance = 14f * density
                    }
            ) {
                // Glow behind the book.
                drawCircle(
                    Brush.radialGradient(
                        listOf(BookColors.Brass.copy(alpha = 0.22f * open.value), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f,
                    )
                )
                // Icon artwork is laid out on a 108-unit grid; frame the book (x/y 22..86).
                val u = size.minDimension / 64f
                scale(u, u, pivot = Offset.Zero) {
                    translate(-22f, -22f) {
                        val spineTop = Offset(54f, 38f)
                        // Pages fold open from the spine.
                        scale(open.value, 1f, pivot = spineTop) {
                            drawPath(leftPage, BookPaperLeft)
                            drawPath(rightPage, BookPaperRight)
                        }
                        // Text lines write in.
                        val a = ink.value
                        lines.forEachIndexed { i, (from, to) ->
                            val p = ((a * lines.size) - i).coerceIn(0f, 1f)
                            if (p > 0f) {
                                drawLine(
                                    BookLineInk,
                                    from,
                                    Offset(from.x + (to.x - from.x) * p, from.y + (to.y - from.y) * p),
                                    strokeWidth = 1.6f,
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                        // Magnifier: a slow loop over both pages.
                        val ang = 2f * PI.toFloat() * t
                        val c = Offset(54f + 15f * cos(ang), 52f + 7f * sin(2f * ang))
                        val lensA = a
                        drawCircle(BookColors.Brass.copy(alpha = 0.16f * lensA), radius = 8f, center = c)
                        drawCircle(LensBrass.copy(alpha = lensA), radius = 8f, center = c, style = Stroke(3.5f))
                        drawLine(
                            LensBrass.copy(alpha = lensA),
                            Offset(c.x + 6f, c.y + 6f),
                            Offset(c.x + 13f, c.y + 13f),
                            strokeWidth = 4f,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            // ...second half: the guide's tile turns into view.
            if (guide != null) {
                GuideTile(
                    book = guide,
                    cover = cover,
                    modifier = Modifier
                        .size(184.dp)
                        .graphicsLayer {
                            val f = flip.value
                            alpha = if (f >= 0.5f) 1f else 0f
                            rotationY = (f - 1f) * 180f
                            cameraDistance = 14f * density
                        },
                )
            } else if (art != null) {
                // No guide: the game's own artwork turns into view instead.
                AsyncImage(
                    model = Uri.parse(art),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(196.dp)
                        .graphicsLayer {
                            val f = flip.value
                            alpha = if (f >= 0.5f) 1f else 0f
                            rotationY = (f - 1f) * 180f
                            cameraDistance = 14f * density
                        }
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
          }
            Spacer(Modifier.height(6.dp))
            Text(
                "WalkThor",
                color = BookColors.Text,
                fontFamily = SerifDisplay,
                fontWeight = FontWeight.SemiBold,
                fontSize = 34.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = word.value * (1f - flip.value)
                    translationY = (1f - word.value) * 10.dp.toPx()
                },
            )
            Spacer(Modifier.height(14.dp))
            Box(Modifier.height(74.dp).widthIn(max = 520.dp), contentAlignment = Alignment.TopCenter) {
                AnimatedContent(
                    targetState = status,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "bootStatus",
                ) { s ->
                    when (s) {
                        BootStatus.Idle -> Spacer(Modifier.height(1.dp))
                        BootStatus.Checking -> Text(
                            "CHECKING WHAT'S PLAYING",
                            style = EyebrowStyle,
                            modifier = Modifier.graphicsLayer { alpha = word.value * (0.55f + 0.45f * pulse(t)) },
                        )
                        is BootStatus.Playing -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("NOW PLAYING", style = EyebrowStyle)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                s.guide?.title ?: s.title,
                                color = BookColors.Text,
                                fontFamily = SerifDisplay,
                                fontSize = 17.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (s.guide == null) {
                                Spacer(Modifier.height(4.dp))
                                Text("No guide yet", color = BookColors.TextMuted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 0..1..0 twice per sweep, for a gentle pulse. */
private fun pulse(t: Float) = (0.5f - 0.5f * cos(4f * PI.toFloat() * t))

private val BookPaperLeft = Color(0xFFF3EAD3)
private val BookPaperRight = Color(0xFFE9DDBF)
private val BookLineInk = Color(0xFF8A6A4A)
private val LensBrass = Color(0xFFD9A441)

private val leftPage = Path().apply {
    moveTo(54f, 38f)
    cubicTo(46f, 33f, 36f, 32f, 28f, 34f)
    lineTo(28f, 72f)
    cubicTo(36f, 70f, 46f, 71f, 54f, 76f)
    close()
}

private val rightPage = Path().apply {
    moveTo(54f, 38f)
    cubicTo(62f, 33f, 72f, 32f, 80f, 34f)
    lineTo(80f, 72f)
    cubicTo(72f, 70f, 62f, 71f, 54f, 76f)
    close()
}

private val lines = listOf(
    Offset(33f, 42f) to Offset(48f, 43f),
    Offset(33f, 48f) to Offset(48f, 49f),
    Offset(33f, 54f) to Offset(48f, 55f),
    Offset(33f, 60f) to Offset(44f, 61f),
    Offset(60f, 43f) to Offset(74f, 42f),
    Offset(60f, 49f) to Offset(68f, 48f),
    Offset(60f, 55f) to Offset(74f, 54f),
    Offset(60f, 61f) to Offset(71f, 60f),
)
