package com.nerfherder.walkthor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.nerfherder.walkthor.data.Book
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Plays when a guide is opened: the guide's tile swings in from the right, its title fades in,
 * then the tile pushes toward you as everything fades into the guide underneath. Tap to skip.
 */
@Composable
fun GuideIntro(book: Book, cover: File?, onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    val slide = remember { Animatable(1f) }      // 1 = off to the right, 0 = centred
    val tileAlpha = remember { Animatable(0f) }
    val titleAlpha = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }       // 0 = showing, 1 = gone

    LaunchedEffect(book.id) {
        coroutineScope {
            launch { tileAlpha.animateTo(1f, tween(260)) }
            launch { slide.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)) }
            launch { delay(140); glow.animateTo(1f, tween(600, easing = LinearOutSlowInEasing)) }
            launch { delay(300); titleAlpha.animateTo(1f, tween(380)) }
        }
        delay(420)
        exit.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        done()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(BookColors.Ink)
            .pointerInput(Unit) { detectTapGestures { done() } },
        contentAlignment = Alignment.Center,
    ) {
        val tileSize = min(min(maxWidth, maxHeight) * 0.52f, 260.dp)
        val travel = with(LocalDensity.current) { maxWidth.toPx() * 0.75f }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // A warm brass glow blooming behind the tile.
                Box(
                    Modifier
                        .size(tileSize * 1.7f)
                        .graphicsLayer { alpha = glow.value * (1f - exit.value) }
                        .drawBehind {
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(BookColors.Brass.copy(alpha = 0.32f), Color.Transparent),
                                    center = center,
                                    radius = size.minDimension / 2f,
                                )
                            )
                        }
                )
                GuideTile(
                    book = book,
                    cover = cover,
                    modifier = Modifier
                        .size(tileSize)
                        .graphicsLayer {
                            translationX = slide.value * travel
                            rotationZ = slide.value * 9f
                            rotationY = -slide.value * 28f
                            cameraDistance = 14f * density
                            alpha = tileAlpha.value
                            // Push toward the viewer as the guide takes over.
                            val push = 1f + exit.value * 0.35f
                            scaleX = push
                            scaleY = push
                        },
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                book.title,
                color = BookColors.Text,
                fontFamily = SerifDisplay,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .graphicsLayer {
                        alpha = titleAlpha.value * (1f - exit.value)
                        translationY = (1f - titleAlpha.value) * 12.dp.toPx()
                    },
            )
        }
    }
}
