package com.nerfherder.walkthor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Where each bubble sits in the cloud: a honeycomb that winds out from the middle, ring by ring
 * (the first one in the middle, the next six around it, and so on), so the cloud fills the
 * screen in every direction. Positions are in "steps" (one step is the gap between neighbours).
 */
object BubbleLayout {
    private val cache = HashMap<Int, List<Offset>>()

    private fun spiral(count: Int): List<Offset> = synchronized(cache) {
        cache.getOrPut(count) {
            val out = ArrayList<Offset>(count)
            fun add(q: Int, r: Int) { if (out.size < count) out += Offset(q + r / 2f, r * 0.866f) }
            add(0, 0)
            // The six directions around a honeycomb cell, in axial coordinates.
            val dirs = listOf(1 to 0, 1 to -1, 0 to -1, -1 to 0, -1 to 1, 0 to 1)
            var ring = 1
            while (out.size < count) {
                var q = -ring
                var r = ring // start bottom-left of the ring, then walk its six sides
                for ((dq, dr) in dirs) {
                    repeat(ring) {
                        add(q, r)
                        q += dq; r += dr
                    }
                }
                ring++
            }
            out
        }
    }

    fun position(i: Int, count: Int): Offset = spiral(count.coerceAtLeast(1)).getOrElse(i) { Offset.Zero }

    /**
     * The bubble next to [from] in a direction ([dx], [dy] each -1, 0 or 1): the closest one
     * that lies within 60° of it. Null at the edge.
     */
    fun neighbor(from: Int, count: Int, dx: Int, dy: Int): Int? {
        if (count == 0) return null
        val p = position(from, count)
        val want = atan2(dy.toFloat(), dx.toFloat())
        var best: Int? = null
        var bestD = Float.MAX_VALUE
        for (j in 0 until count) {
            if (j == from) continue
            val q = position(j, count)
            val vx = q.x - p.x
            val vy = q.y - p.y
            val d = hypot(vx, vy)
            if (d > 2.6f) continue
            var diff = abs(atan2(vy, vx) - want)
            if (diff > Math.PI) diff = (2 * Math.PI).toFloat() - diff
            if (diff > Math.PI / 3 + 0.01) continue
            val score = d + diff * 0.6f
            if (score < bestD) { bestD = score; best = j }
        }
        return best
    }
}

/**
 * Your games (or apps) as a cloud of round bubbles, like a watch's app cloud: the one in the
 * middle is big, and they grow smaller and dimmer toward the edges. Drag to drift through it
 * (it settles on the bubble nearest the middle), tap a bubble to choose it and tap it again to
 * open it; the D-pad moves between neighbours (the caller's keys do that, with [BubbleLayout]).
 */
@Composable
fun BubbleCloud(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    onActivate: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    ring: Color,
    modifier: Modifier = Modifier,
    step: Dp = 84.dp,
    baseSize: Dp = 72.dp,
    /** The colour of the ring around each bubble (its system's), or null for none. */
    ringColor: (Int) -> Color? = { null },
    /** The words written along the bottom of the ring ("SWITCH"), on the bigger bubbles. */
    ringLabel: (Int) -> String? = { null },
    item: @Composable (index: Int) -> Unit,
) {
    val density = LocalDensity.current
    val stepPx = with(density) { step.toPx() }
    val scope = rememberCoroutineScope()
    // Where the middle of the screen is, in the cloud (in steps).
    val camera = remember { Animatable(BubbleLayout.position(selected.coerceAtLeast(0), count), Offset.VectorConverter) }
    var dragging by remember { mutableStateOf(false) }
    val latestSelected by rememberUpdatedState(selected)
    val latestCount by rememberUpdatedState(count)
    LaunchedEffect(selected, count) {
        if (count == 0 || dragging) return@LaunchedEffect
        camera.animateTo(
            BubbleLayout.position(selected.coerceIn(0, count - 1), count),
            spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessLow),
        )
    }

    /** How far from the middle a bubble shows, how big and how bright, from its real distance. */
    fun shown(d: Float): Float = d + 0.45f * exp(-d / 1.4f) * if (d > 0.01f) 1f else 0f
    fun scaleAt(shownD: Float): Float = 0.42f + 1.28f * exp(-shownD / 1.08f)
    fun alphaAt(shownD: Float): Float = (1.15f - shownD / 3.9f).coerceIn(0.1f, 1f)

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF16202A), Color(0xFF07080B))))
            .pointerInput(count) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        // Settle on the bubble nearest the middle.
                        val c = camera.value
                        val nearest = (0 until latestCount).minByOrNull { j ->
                            val p = BubbleLayout.position(j, latestCount); hypot(p.x - c.x, p.y - c.y)
                        }
                        if (nearest != null) {
                            if (nearest == latestSelected) {
                                scope.launch { camera.animateTo(BubbleLayout.position(nearest, latestCount), spring(0.86f, Spring.StiffnessLow)) }
                            }
                            onSelect(nearest)
                        }
                    },
                    onDragCancel = { dragging = false },
                ) { change, drag ->
                    change.consume()
                    scope.launch { camera.snapTo(camera.value - Offset(drag.x / stepPx, drag.y / stepPx)) }
                }
            },
    ) {
        val cx = constraints.maxWidth / 2f
        val cy = constraints.maxHeight / 2f
        val basePx = with(density) { baseSize.toPx() }
        val c = camera.value
        for (i in 0 until count) {
            val p = BubbleLayout.position(i, count)
            val vx = p.x - c.x
            val vy = p.y - c.y
            val d = hypot(vx, vy)
            // Far off the screen: not drawn at all.
            if (d > 6.5f) continue
            val sd = shown(d)
            val k = if (d > 0.001f) sd / d else 1f
            val x = cx + vx * k * stepPx
            val y = cy + vy * k * stepPx
            val s = scaleAt(sd)
            val isSel = i == selected
            val sysColor = ringColor(i)
            val label = if (s >= 0.8f) ringLabel(i) else null
            Box(
                Modifier
                    .offset { IntOffset((x - basePx / 2).toInt(), (y - basePx / 2).toInt()) }
                    .size(baseSize)
                    .graphicsLayer {
                        scaleX = s; scaleY = s
                        alpha = alphaAt(sd)
                    }
                    .pointerInput(i) {
                        detectTapGestures(
                            onTap = { if (i == latestSelected) onActivate(i) else onSelect(i) },
                            onLongPress = { onSelect(i); onLongPress(i) },
                        )
                    },
            ) {
                // The picture, inside the system's ring.
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(if (sysColor != null) 4.dp else 0.dp)
                        .clip(CircleShape),
                ) { item(i) }
                if (sysColor != null || isSel) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val w = 3.dp.toPx()
                        val r = size.minDimension / 2f
                        if (sysColor != null) {
                            drawCircle(sysColor, radius = r - w / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                            if (label != null) {
                                // A thicker band along the bottom, with the system's name around the curve.
                                val band = 11.dp.toPx()
                                val inset = band / 2f
                                val arc = androidx.compose.ui.geometry.Rect(inset, inset, size.width - inset, size.height - inset)
                                drawArc(
                                    sysColor, startAngle = 28f, sweepAngle = 124f, useCenter = false,
                                    topLeft = arc.topLeft, size = arc.size,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(band, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                                )
                                drawIntoCanvas { canvas ->
                                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                        color = if (sysColor.luminance() > 0.55f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                                        textSize = 7.dp.toPx()
                                        isFakeBoldText = true
                                        letterSpacing = 0.08f
                                    }
                                    // The path runs right to left along the bottom, so the words read upright.
                                    val path = android.graphics.Path().apply {
                                        addArc(arc.left, arc.top, arc.right, arc.bottom, 152f, -124f)
                                    }
                                    val length = android.graphics.PathMeasure(path, false).length
                                    val start = ((length - paint.measureText(label)) / 2f).coerceAtLeast(0f)
                                    canvas.nativeCanvas.drawTextOnPath(label, path, start, paint.textSize * 0.35f, paint)
                                }
                            }
                        }
                        // The chosen one: your accent, just outside.
                        if (isSel) drawCircle(ring, radius = r + 3.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx()))
                    }
                }
            }
        }
    }
}
