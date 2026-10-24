package com.nerfherder.walkthor.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nerfherder.walkthor.data.Book
import java.io.File

/** What the boot screen shows. */
sealed interface BootStatus {
    /** Nothing to check; just the wordmark. */
    data object Idle : BootStatus
    /** Reading Cocoon's launch log. */
    data object Checking : BootStatus
    /** Found the game being played, and its guide if WalkThor has one. */
    data class Playing(
        val title: String,
        val guide: Book? = null,
        val cover: File? = null,
        /** The game's box art (content URI), if any. */
        val art: String? = null,
        /** Emulator, e.g. "Dolphin Emulator". */
        val player: String? = null,
        /** The game's wide art (content URI), shown across the top; box art stands in without it. */
        val hero: String? = null,
    ) : BootStatus
}

/**
 * WalkThor's boot screen, like a title card: a large wordmark in the lower left with a softly
 * pulsing dot while WalkThor reads Cocoon's launch log. When the game is found, its wide art
 * fades in across the top, settling as it fades into black, and the game's title takes the
 * wordmark's place. Fades out once [finished]; then [onGone]. Tap to skip.
 */
@Composable
fun BootScreen(
    status: BootStatus,
    finished: Boolean,
    onSkip: () -> Unit,
    onGone: () -> Unit,
    /**
     * Opening alongside a game that's just starting: no pulsing dot, so the bottom screen isn't
     * redrawn the whole time the game is loading.
     */
    light: Boolean = false,
) {
    val gone by rememberUpdatedState(onGone)
    val word = remember { Animatable(0f) }      // wordmark rising in
    val reveal = remember { Animatable(0f) }    // the game's art fading in
    val exit = remember { Animatable(0f) }      // 1 = faded out
    val playing = status as? BootStatus.Playing
    val picture = playing?.let { it.hero ?: it.art }

    LaunchedEffect(Unit) { word.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    LaunchedEffect(picture) {
        if (picture != null) reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        exit.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        gone()
    }
    val pulse = if (light) 1f else {
        val t = rememberInfiniteTransition(label = "boot")
        val v by t.animateFloat(
            0.35f, 1f,
            infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "dot",
        )
        v
    }
    val short = LocalConfiguration.current.screenHeightDp < 420

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(BookColors.Ink)
            .pointerInput(Unit) { detectTapGestures { onSkip() } },
    ) {
        if (picture != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(maxHeight * 0.62f)
                    .graphicsLayer {
                        alpha = reveal.value
                        // Settles in as it appears, and leans toward you as the screen leaves.
                        val sc = 1.06f - 0.06f * reveal.value + 0.04f * exit.value
                        scaleX = sc; scaleY = sc
                    },
            ) {
                AsyncImage(
                    model = Uri.parse(picture),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.15f),
                            0.5f to Color.Black.copy(alpha = 0.35f),
                            1f to Color.Black,
                        )
                    )
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 36.dp, end = 36.dp, bottom = if (short) 24.dp else 44.dp)
                .graphicsLayer {
                    alpha = word.value
                    translationY = (1f - word.value) * 14.dp.toPx()
                },
        ) {
            AnimatedContent(
                targetState = playing,
                transitionSpec = {
                    (fadeIn(tween(320, delayMillis = 120)) + slideInVertically(tween(420, delayMillis = 120)) { it / 6 }) togetherWith
                        fadeOut(tween(160))
                },
                label = "bootStatus",
            ) { p ->
                if (p == null) Column {
                    Text(
                        "WalkThor",
                        color = BookColors.Text,
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (short) 40.sp else 56.sp,
                        letterSpacing = (-1.5).sp,
                        lineHeight = if (short) 42.sp else 58.sp,
                    )
                    if (status == BootStatus.Checking) {
                        Spacer(Modifier.height(if (short) 10.dp else 16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .graphicsLayer { alpha = pulse }
                                    .background(Color(0xFFF5F5FA), CircleShape)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Checking what's playing", color = BookColors.TextMuted, fontSize = 15.sp)
                        }
                    }
                } else Column {
                    val sub = listOfNotNull("Now playing", p.player?.let(::emulatorName), if (p.guide == null) "No guide yet" else null)
                    Text(sub.joinToString("  ·  "), color = BookColors.TextMuted, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        displayTitle(p.title), // always the game, never the guide
                        color = BookColors.Text,
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (short) 30.sp else 46.sp,
                        letterSpacing = (-1).sp,
                        lineHeight = if (short) 34.sp else 50.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(if (short) 12.dp else 20.dp))
                    Box(Modifier.width(28.dp).height(3.dp).background(Color(0xFFF5F5FA), RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}
