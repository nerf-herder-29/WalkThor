package com.nerfherder.walkthor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.abs

/** A page turn in progress, from the visible page [from] to [to]. */
data class Flip(val from: Int, val to: Int) {
    val forward get() = to > from
}

@Stable
class BookState(initialPage: Int) {
    /** First visible page (left page of a spread). */
    var current by mutableIntStateOf(initialPage)
    var flip by mutableStateOf<Flip?>(null)
        internal set
    internal val progress = Animatable(0f)
    internal var animating = false
    internal val requests = Channel<Int>(Channel.CONFLATED)

    val isTurning get() = flip != null

    /** Animate a page turn to [page] (adjacent or a long jump — both use one turn). */
    fun turnTo(page: Int) { requests.trySend(page) }
}

@Composable
fun rememberBookState(initialPage: Int) = remember { BookState(initialPage) }

private enum class Spine { Left, Right }

/**
 * An open book. In [spread] mode two pages face each other and the right-hand page turns over
 * the spine; in single mode one page is bound on its left edge and turns away to the left.
 *
 * Drag horizontally to turn (the page follows your finger), tap the outer edges to turn, tap
 * the middle of a page to call [onPageTapped].
 */
@Composable
fun BookView(
    state: BookState,
    pageCount: Int,
    spread: Boolean,
    onPageChanged: (Int) -> Unit,
    onPageTapped: (Int) -> Unit,
    onPageSize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
    page: @Composable (index: Int) -> Unit,
) {
    val step = if (spread) 2 else 1
    val lastStart = if (pageCount <= 0) 0 else if (spread) (pageCount - 1) - (pageCount - 1) % 2 else pageCount - 1
    val scope = rememberCoroutineScope()
    val pageChanged by rememberUpdatedState(onPageChanged)
    val pageTapped by rememberUpdatedState(onPageTapped)

    suspend fun turn(target: Int, durationMs: Int? = null) {
        val to = (if (spread) target - target % 2 else target).coerceIn(0, lastStart)
        val from = state.current
        if (to == from || state.animating) return
        state.animating = true
        try {
            state.flip = Flip(from, to)
            state.progress.snapTo(0f)
            val ms = durationMs ?: if (abs(to - from) <= step) 430 else 560
            state.progress.animateTo(1f, tween(ms, easing = FastOutSlowInEasing))
            state.current = to
            state.flip = null
            pageChanged(to)
        } finally {
            if (state.flip != null) state.flip = null
            state.animating = false
        }
    }

    LaunchedEffect(state, spread, lastStart) {
        for (target in state.requests) turn(target)
    }

    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(BookColors.LeatherLight, BookColors.Leather)))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clipToBounds()
    ) {
        val density = LocalDensity.current
        val slotW = if (spread) constraints.maxWidth / 2 else constraints.maxWidth
        val slotH = constraints.maxHeight
        LaunchedEffect(slotW, slotH) { if (slotW > 0 && slotH > 0) onPageSize(IntSize(slotW, slotH)) }

        val widthPx = constraints.maxWidth.toFloat()
        val travel = (if (spread) widthPx else widthPx * 0.8f).coerceAtLeast(1f)

        @Composable
        fun PageAt(index: Int, spine: Spine) = PageSurface(spine) {
            if (index in 0 until pageCount) page(index) else BlankPage(isEnd = index == pageCount)
        }

        val gestures = Modifier
            .pointerInput(spread, pageCount, lastStart) {
                val tracker = VelocityTracker()
                var total = 0f
                fun settle() {
                    val f = state.flip ?: return
                    if (state.animating) return
                    val v = tracker.calculateVelocity().x
                    val p = state.progress.value
                    val complete = if (f.forward) (p > 0.3f || v < -900f) && v < 700f
                    else (p > 0.3f || v > 900f) && v > -700f
                    scope.launch {
                        state.animating = true
                        try {
                            if (complete) {
                                state.progress.animateTo(1f, tween(((1f - p) * 380).toInt().coerceAtLeast(120), easing = LinearOutSlowInEasing))
                                state.current = f.to
                                state.flip = null
                                pageChanged(f.to)
                            } else {
                                state.progress.animateTo(0f, tween((p * 320).toInt().coerceAtLeast(100)))
                                state.flip = null
                            }
                        } finally {
                            state.animating = false
                        }
                    }
                }
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f; tracker.resetTracking() },
                    onDragEnd = { settle() },
                    onDragCancel = { settle() },
                    onHorizontalDrag = { change, dx ->
                        if (state.animating) return@detectHorizontalDragGestures
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        total += dx
                        if (state.flip == null) {
                            if (abs(total) < 4f) return@detectHorizontalDragGestures
                            val target = state.current + if (total < 0) step else -step
                            if (target < 0 || target > lastStart) return@detectHorizontalDragGestures
                            state.flip = Flip(state.current, target)
                        }
                        val f = state.flip ?: return@detectHorizontalDragGestures
                        val signed = if (f.forward) -total else total
                        val p = (signed / travel).coerceIn(0f, 1f)
                        scope.launch { state.progress.snapTo(p) }
                    },
                )
            }
            .pointerInput(spread, pageCount, lastStart) {
                detectTapGestures { pos ->
                    if (state.isTurning) return@detectTapGestures
                    val frac = pos.x / size.width
                    when {
                        frac < 0.16f -> scope.launch { turn(state.current - step) }
                        frac > 0.84f -> scope.launch { turn(state.current + step) }
                        else -> {
                            val idx = if (spread && frac >= 0.5f) state.current + 1 else state.current
                            if (idx in 0 until pageCount) pageTapped(idx)
                        }
                    }
                }
            }

        val flip = state.flip
        val p = state.progress.value

        Box(Modifier.fillMaxSize().then(gestures)) {
            if (pageCount == 0) {
                PageSurface(Spine.Left) { BlankPage(isEnd = false) }
            } else if (spread) {
                // The configuration for a turn between spreads a < b is the same in both directions;
                // only the progress runs the other way.
                val lo = flip?.let { minOf(it.from, it.to) } ?: state.current
                val hi = flip?.let { maxOf(it.from, it.to) }
                val q = if (flip == null) 0f else if (flip.forward) p else 1f - p
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { PageAt(lo, Spine.Right) }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        PageAt(if (hi != null) hi + 1 else lo + 1, Spine.Left)
                        if (hi != null) Shade(0.28f * (1f - q))
                    }
                }
                if (hi != null) {
                    Row(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            Leaf(
                                rotation = -180f * q,
                                front = { PageAt(lo + 1, Spine.Left) },
                                back = { PageAt(hi, Spine.Right) },
                            )
                        }
                    }
                }
            } else {
                val q = if (flip == null) 0f else if (flip.forward) p else 1f - p
                val lo = flip?.let { minOf(it.from, it.to) } ?: state.current
                val hi = flip?.let { maxOf(it.from, it.to) }
                if (hi != null) {
                    Box(Modifier.fillMaxSize()) {
                        PageAt(hi, Spine.Left)
                        Shade(0.28f * (1f - q))
                    }
                    Leaf(
                        rotation = -180f * q,
                        front = { PageAt(lo, Spine.Left) },
                        back = { PageSurface(Spine.Right) { BlankPage(isEnd = false) } },
                    )
                } else {
                    PageAt(lo, Spine.Left)
                }
            }
        }
    }
}

/** A page that rotates around its left edge. Past 90° its back face is shown. */
@Composable
private fun Leaf(
    rotation: Float,
    front: @Composable () -> Unit,
    back: @Composable () -> Unit,
) {
    val density = LocalDensity.current.density
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0.5f)
                rotationY = rotation
                cameraDistance = 18f * density
            }
    ) {
        if (rotation > -90f) {
            front()
            val tilt = -rotation / 90f
            // Light falls off toward the lifting edge.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.05f * tilt), Color.Black.copy(alpha = 0.4f * tilt))
                    )
                )
            )
        } else {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = -1f }) {
                back()
                val tilt = (180f + rotation) / 90f
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            listOf(Color.Black.copy(alpha = 0.4f * tilt), Color.Black.copy(alpha = 0.05f * tilt))
                        )
                    )
                )
            }
        }
    }
}

@Composable
private fun Shade(alpha: Float) {
    if (alpha <= 0.001f) return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)))
}

@Composable
private fun PageSurface(spine: Spine, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(BookColors.Paper)) {
        content()
        // Shadow where the paper curves into the binding.
        Box(
            Modifier
                .align(if (spine == Spine.Left) Alignment.CenterStart else Alignment.CenterEnd)
                .fillMaxHeight()
                .width(20.dp)
                .background(
                    Brush.horizontalGradient(
                        if (spine == Spine.Left) listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent)
                        else listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f))
                    )
                )
        )
    }
}

@Composable
private fun BoxScope.BlankPage(isEnd: Boolean) {
    if (isEnd) {
        Text(
            "~ fin ~",
            modifier = Modifier.align(Alignment.Center),
            color = BookColors.InkFaint,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 16.sp,
        )
    }
}
