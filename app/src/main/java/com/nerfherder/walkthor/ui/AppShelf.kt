package com.nerfherder.walkthor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectVerticalDragGestures

/** One of WalkThor's own buttons at the end of the shelf. */
internal class ShelfTool(val label: String, val glyph: DockGlyphKind, val onClick: () -> Unit)

/**
 * Your pinned apps standing on a glass shelf, with their reflections under them (scroll for
 * more), then WalkThor's own [tools], which stay put at the end. Used by Home's dock, along the
 * bottom of Now playing, and in the panel you pull up over a game's tabs.
 */
@Composable
internal fun AppShelf(
    onOpenApp: (String) -> Unit,
    tools: List<ShelfTool>,
    modifier: Modifier = Modifier,
    /** Fixed apps, for screenshots. */
    preview: List<InstalledApp>? = null,
    showApps: Boolean = true,
    icon: Dp = 48.dp,
    emptyText: String = "Pin apps from All apps (press and hold one).",
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (preview == null) {
            AppCatalog.loadPins(context)
            if (AppCatalog.apps.value == null) AppCatalog.refresh(context)
        }
    }
    val loaded by AppCatalog.apps.collectAsState()
    val pins by AppCatalog.pinned.collectAsState()
    val apps = remember(loaded, pins, preview) {
        preview ?: run {
            val byPkg = loaded?.associateBy { it.packageName } ?: emptyMap()
            pins.mapNotNull { byPkg[it] }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(icon + 22.dp)) {
        // The shelf: a slab seen from a little above, so its back edge is narrower than its front.
        Canvas(Modifier.fillMaxWidth().height(20.dp).align(Alignment.TopStart).padding(horizontal = 4.dp).offset(y = icon - 12.dp)) {
            val inset = 14.dp.toPx()
            val r = 6.dp.toPx()
            val w = size.width
            val h = size.height
            val slab = androidx.compose.ui.graphics.Path().apply {
                moveTo(inset + r, 0f); lineTo(w - inset - r, 0f)
                quadraticTo(w - inset, 0f, w - inset + r * 0.4f, r * 0.8f)
                lineTo(w, h - r); quadraticTo(w, h, w - r, h)
                lineTo(r, h); quadraticTo(0f, h, 0f, h - r)
                lineTo(inset - r * 0.4f, r * 0.8f); quadraticTo(inset, 0f, inset + r, 0f)
                close()
            }
            // Dark smoked glass: see-through at the back, deeper at the front, with a bright edge.
            drawPath(slab, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.7f))))
            drawPath(slab, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent)))
            drawPath(slab, Color.White.copy(alpha = 0.16f), style = Stroke(1.dp.toPx()))
            // Light catching the back edge and the front lip.
            drawLine(Color.White.copy(alpha = 0.32f), Offset(inset + r, 0.5.dp.toPx()), Offset(w - inset - r, 0.5.dp.toPx()), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.1f), Offset(r, h - 1.dp.toPx()), Offset(w - r, h - 1.dp.toPx()), 1.dp.toPx())
        }
        // Your apps scroll; All apps, Edit Home and WalkThor stay put at the end. With only a few
        // apps, the lot sits in the middle of the shelf.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 26.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            if (showApps) {
                Row(
                    Modifier.weight(1f, fill = false).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    if (apps.isEmpty()) {
                        Text(
                            if (loaded == null && preview == null) "Loading…" else emptyText,
                            color = BookColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 16.dp),
                        )
                    }
                    apps.forEach { app ->
                        ShelfItem(icon, app.label, onClick = { onOpenApp(app.packageName) }) {
                            if (app.icon != null) androidx.compose.foundation.Image(app.icon, contentDescription = null, modifier = Modifier.fillMaxSize())
                            else Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)))
                        }
                    }
                }
                Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(icon).background(Color.White.copy(alpha = 0.13f)))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                tools.forEach { t -> ShelfItem(icon, t.label, onClick = t.onClick) { DockGlyph(t.glyph) } }
            }
        }
    }
}

/** One spot on the shelf: the icon, with a faded reflection of its lower part under it. */
@Composable
internal fun ShelfItem(size: androidx.compose.ui.unit.Dp, label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        Modifier.semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) { icon() }
        // The reflection: the icon upside down, fading out, cut short.
        Box(
            Modifier.padding(top = 3.dp).width(size).height(15.dp).clipToBounds()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen; alpha = 0.32f }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black, Color.Transparent)),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
        ) {
            Box(
                Modifier.wrapContentHeight(Alignment.Top, unbounded = true).requiredSize(size)
                    .graphicsLayer { scaleY = -1f },
            ) { icon() }
        }
    }
}

internal enum class DockGlyphKind { GRID, EDIT, BOOK }

/** WalkThor's own dock buttons: a soft tile with a simple line drawing. */
@Composable
internal fun DockGlyph(kind: DockGlyphKind) {
    val ink = BookColors.Text
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f))) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val w = size.width
            val h = size.height
            val sw = 1.8.dp.toPx()
            when (kind) {
                DockGlyphKind.GRID -> for (r in 0..2) for (c in 0..2) drawCircle(ink, sw * 1.15f, Offset(w * (0.12f + 0.38f * c), h * (0.12f + 0.38f * r)))
                DockGlyphKind.EDIT -> {
                    drawLine(ink, Offset(w * 0.2f, h * 0.8f), Offset(w * 0.8f, h * 0.2f), sw * 2.2f, StrokeCap.Round)
                    drawLine(ink, Offset(w * 0.12f, h * 0.95f), Offset(w * 0.6f, h * 0.95f), sw, StrokeCap.Round)
                }
                DockGlyphKind.BOOK -> {
                    val s = Stroke(sw, cap = StrokeCap.Round)
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.5f, h * 0.22f); quadraticTo(w * 0.3f, h * 0.08f, w * 0.04f, h * 0.14f); lineTo(w * 0.04f, h * 0.84f)
                        quadraticTo(w * 0.3f, h * 0.78f, w * 0.5f, h * 0.92f); quadraticTo(w * 0.7f, h * 0.78f, w * 0.96f, h * 0.84f)
                        lineTo(w * 0.96f, h * 0.14f); quadraticTo(w * 0.7f, h * 0.08f, w * 0.5f, h * 0.22f); lineTo(w * 0.5f, h * 0.92f)
                    }
                    drawPath(p, ink, style = s)
                }
            }
        }
    }
}

/**
 * Over a game's tabs: a small handle at the bottom edge. Swipe up from it (or tap it) and a panel
 * slides up with your apps on the shelf; swipe it down, tap outside it or press back to put it
 * away. Opening an app puts it away too.
 */
@Composable
internal fun ShelfPullUp(
    onOpenApp: (String) -> Unit,
    onAllApps: () -> Unit,
    modifier: Modifier = Modifier,
    /** Starts open, for screenshots. */
    startOpen: Boolean = false,
    preview: List<InstalledApp>? = null,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 0 = put away, 1 = all the way up.
    val shown = remember { androidx.compose.animation.core.Animatable(if (startOpen) 1f else 0f) }
    val panelH = 128.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val panelPx = with(density) { panelH.toPx() }
    fun settle(open: Boolean) { scope.launch { shown.animateTo(if (open) 1f else 0f, androidx.compose.animation.core.tween(240)) } }
    androidx.activity.compose.BackHandler(enabled = shown.value > 0f) { settle(false) }
    val drag = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragEnd = { settle(shown.value > 0.35f) },
            onDragCancel = { settle(shown.value > 0.5f) },
        ) { change: androidx.compose.ui.input.pointer.PointerInputChange, dy: Float ->
            change.consume()
            scope.launch { shown.snapTo((shown.value - dy / panelPx).coerceIn(0f, 1f)) }
        }
    }
    Box(modifier.fillMaxSize()) {
        if (shown.value > 0f) {
            // Dims what's behind; a tap there puts the panel away.
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { settle(false) },
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(panelH)
                    .graphicsLayer { translationY = panelPx * (1f - shown.value) }
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xF216131A), Color(0xFA0D0B0E))))
                    .then(drag)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.padding(top = 8.dp).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f)))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AppShelf(
                        preview = preview,
                        onOpenApp = { pkg -> scope.launch { shown.snapTo(0f) }; onOpenApp(pkg) },
                        tools = listOf(ShelfTool("All apps", DockGlyphKind.GRID) { scope.launch { shown.snapTo(0f) }; onAllApps() }),
                        emptyText = "Press and hold an app in Apps to pin it here.",
                    )
                }
            }
        }
        if (shown.value < 1f) {
            // The handle: a short bar at the bottom edge, with a strip around it to catch the swipe.
            Box(
                Modifier.align(Alignment.BottomCenter).width(160.dp).height(16.dp)
                    .graphicsLayer { alpha = 1f - shown.value }
                    .then(drag)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { settle(true) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(44.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f)))
            }
        }
    }
}
