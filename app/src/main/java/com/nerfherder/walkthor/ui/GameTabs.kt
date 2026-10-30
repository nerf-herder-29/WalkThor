package com.nerfherder.walkthor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow

/** The tabs across the top of the bottom screen while a game is playing. */
enum class GameTab(val label: String, val shortLabel: String = label) {
    /** Several tabs at a glance, in panels you choose (named Multi; ALL is kept so saved choices still match). */
    ALL("Multi"),
    GUIDE("Guide"),
    MIRROR("Mirror"),
    FACE("Face Buttons", "Buttons"),
    STATS("Stats"),
    /** Screenshots and clips of the top screen. */
    SHOTS("Shots"),
    /** Captions of the game's speech (a test for now). */
    CAPTIONS("Captions"),
    /** Your apps, opened on the bottom (or top) screen. */
    APPS("Apps"),
}

/**
 * What the tab bar shows right now, so the dimmed screen can draw the same tabs in the same place.
 * [pinned] is the tab this game opens on; [onPin] (long-press a tab) sets or clears it.
 */
data class TabsState(
    val selected: GameTab?,
    val pinned: GameTab? = null,
    val onPin: ((GameTab) -> Unit)? = null,
    val onSelect: (GameTab) -> Unit,
)

object GameTabs {
    /** Where the open tab's line was last drawn, so the next tab bar can glide it from there. */
    internal var indicatorX = Float.NaN

    /**
     * How far the tab bar is scrolled, shared by every tab bar (and the dimmed screen's copy of
     * the tabs), so the tabs stay put as you move between screens.
     */
    val scroll = androidx.compose.foundation.ScrollState(0)

    /** Set while a game's tabs are on screen; null otherwise. */
    val state = MutableStateFlow<TabsState?>(null)

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences("tabs", android.content.Context.MODE_PRIVATE)
    private fun key(title: String) = "open/" + title.trim().lowercase()

    /** The tab a game opens on (after the launch animation), or null for Now playing. */
    fun openOn(context: android.content.Context, title: String): GameTab? =
        GameTab.entries.firstOrNull { it.name == prefs(context).getString(key(title), null) }

    fun setOpenOn(context: android.content.Context, title: String, tab: GameTab?) {
        prefs(context).edit().apply { if (tab == null) remove(key(title)) else putString(key(title), tab.name) }.apply()
    }
}

/** Height of the bar the tabs sit in, the same everywhere so the tabs never move. */
val TabBarHeight = 64.dp

/** Width of one icon slot at either end of the bar. */
val TabBarSlot = 48.dp

/**
 * How many icon slots the right end of the bar keeps: one, the same as the left (back), so the
 * tabs sit exactly in the middle, the same size and in the same place on every screen.
 */
@Composable
fun tabBarRightSlots(): Int = 1

/**
 * The tabs: an icon over a label, with a short line gliding under the open one. Each tab keeps
 * the size it had when there were five, so they don't shrink as tabs are added; the row scrolls
 * sideways instead (with a soft fade at an edge that has more). [actOnDown] makes a tab act on
 * touch down (the dimmed screen, where the same touch also wakes it).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GameTabsRow(
    selected: GameTab?,
    onSelect: (GameTab) -> Unit,
    modifier: Modifier = Modifier,
    actOnDown: Boolean = false,
) {
    val narrow = LocalConfiguration.current.screenWidthDp < 480
    // Which tab this game opens on (marked with a small dot); long-press a tab to choose it.
    val state by GameTabs.state.collectAsState()
    val pinned = state?.pinned
    val onPin = state?.onPin
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // A short line under the open tab, gliding to whichever tab you pick.
    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<GameTab, Pair<Float, Float>>() }
    // Starts where the last tab bar left it, so it glides across when you switch tabs.
    val indicator = remember { androidx.compose.animation.core.Animatable(GameTabs.indicatorX) }
    val target = selected?.let { bounds[it] }?.let { (x, w) -> x + w / 2 }
    androidx.compose.runtime.LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        GameTabs.indicatorX = target
        if (indicator.value.isNaN() || actOnDown) indicator.snapTo(target)
        else indicator.animateTo(target, androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 420f))
    }
    val gap = if (narrow) 4.dp else 8.dp
    val scroll = GameTabs.scroll
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .clipToBounds()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Fade the edge that has more tabs past it.
                val fade = 28.dp.toPx()
                if (scroll.canScrollForward) drawRect(
                    Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
                if (scroll.canScrollBackward) drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
                // A small arrow at each edge with more tabs past it.
                val a = 4.dp.toPx()
                val cy = size.height / 2f - 4.dp.toPx()
                val arrow = Color.White.copy(alpha = 0.55f)
                val w = 1.6.dp.toPx()
                if (scroll.canScrollForward) {
                    val x = size.width - 3.dp.toPx()
                    drawLine(arrow, Offset(x - a, cy - a), Offset(x, cy), w, StrokeCap.Round)
                    drawLine(arrow, Offset(x, cy), Offset(x - a, cy + a), w, StrokeCap.Round)
                }
                if (scroll.canScrollBackward) {
                    val x = 3.dp.toPx()
                    drawLine(arrow, Offset(x + a, cy - a), Offset(x, cy), w, StrokeCap.Round)
                    drawLine(arrow, Offset(x, cy), Offset(x + a, cy + a), w, StrokeCap.Round)
                }
            },
    ) {
    // The size each tab had when the bar held five (Multi a little narrower, plus its divider).
    val unit = (maxWidth - gap * 5 - 1.dp) / 4.65f
    // Keep the open tab in view (not on the dimmed screen's copy, which follows the bar).
    androidx.compose.runtime.LaunchedEffect(selected, bounds[selected]) {
        val b = selected?.let { bounds[it] } ?: return@LaunchedEffect
        if (actOnDown) return@LaunchedEffect
        val view = constraints.maxWidth
        val (x, w) = b
        val to = when {
            x < scroll.value -> x.toInt()
            x + w > scroll.value + view -> (x + w - view).toInt()
            else -> return@LaunchedEffect
        }
        scroll.animateScrollTo(to.coerceIn(0, scroll.maxValue))
    }
    Row(
        Modifier
            .fillMaxHeight()
            .horizontalScroll(scroll)
            .padding(vertical = 6.dp)
            .drawWithContent {
                drawContent()
                val cx = indicator.value
                if (selected != null && !cx.isNaN()) {
                    val half = 14.dp.toPx()
                    val y = size.height - 2.dp.toPx()
                    drawLine(Color(0xFFF5F5FA), Offset(cx - half, y), Offset(cx + half, y), 3.dp.toPx(), StrokeCap.Round)
                }
            },
        horizontalArrangement = Arrangement.spacedBy(if (narrow) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GameTab.entries.forEach { tab ->
            val on = tab == selected
            val interaction = remember { MutableInteractionSource() }
            val hub = tab == GameTab.ALL
            // All is the game's home, set apart from the others by a thin line.
            Column(
                Modifier
                    .width(if (hub) unit * 0.65f else unit)
                    .fillMaxHeight()
                    .onGloballyPositioned { c -> bounds[tab] = c.positionInParent().x to c.size.width.toFloat() }
                    .then(
                        if (actOnDown) Modifier.pointerInput(tab) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                onSelect(tab)
                            }
                        } else Modifier.combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            onLongClick = onPin?.let { pin ->
                                {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    pin(tab)
                                }
                            },
                        ) { onSelect(tab) }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val tint = if (on) BookColors.Text else Color.White.copy(alpha = 0.55f)
                Box {
                    TabIcon(tab, tint)
                    if (tab == pinned) {
                        // This game opens on this tab.
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 7.dp, y = (-2).dp)
                                .size(6.dp)
                                .background(Color(0xFFF5F5FA), androidx.compose.foundation.shape.CircleShape)
                        )
                    }
                }
                val label = if (narrow) tab.shortLabel else tab.label
                if (label.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        label,
                        color = tint,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = if (narrow) 11.sp else 12.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (hub) {
                Box(Modifier.width(1.dp).fillMaxHeight(0.6f).background(Color.White.copy(alpha = 0.25f)))
            }
        }
    }
    }
}

/** Each tab's small icon: an open book, a picture-in-picture, the four face buttons. */
@Composable
private fun TabIcon(tab: GameTab, tint: Color) {
    when (tab) {
        GameTab.GUIDE -> Icon(AppIcons.Spread, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        GameTab.MIRROR -> Icon(PictureInPicture, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        GameTab.ALL -> Canvas(Modifier.size(20.dp)) {
            // Four small squares: everything at once.
            // A touch smaller than the box, like the other tabs' icons, so it doesn't crowd the border.
            val s = size.minDimension * 0.3f
            val g = size.minDimension * 0.1f
            val o = (size.minDimension - 2 * s - g) / 2
            for (i in 0..1) for (j in 0..1) {
                drawRoundRect(
                    tint,
                    topLeft = Offset(o + i * (s + g), o + j * (s + g)),
                    size = androidx.compose.ui.geometry.Size(s, s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s / 4),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx()),
                )
            }
        }
        GameTab.STATS -> Canvas(Modifier.size(20.dp)) {
            // Three bars, like a small chart.
            val w = size.width * 0.18f
            listOf(0.45f, 0.9f, 0.65f).forEachIndexed { i, h ->
                val x = size.width * (0.2f + 0.3f * i) - w / 2
                drawRoundRect(
                    tint,
                    topLeft = Offset(x, size.height * (0.95f - h)),
                    size = androidx.compose.ui.geometry.Size(w, size.height * h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 3),
                )
            }
        }
        GameTab.SHOTS -> Canvas(Modifier.size(20.dp)) {
            // A camera: body, lens and the bump on top.
            val stroke = Stroke(1.6.dp.toPx())
            val top = size.height * 0.28f
            drawRoundRect(
                tint,
                topLeft = Offset(size.width * 0.06f, top),
                size = androidx.compose.ui.geometry.Size(size.width * 0.88f, size.height * 0.62f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.12f),
                style = stroke,
            )
            drawRoundRect(
                tint,
                topLeft = Offset(size.width * 0.32f, size.height * 0.14f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.36f, size.height * 0.16f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.05f),
                style = stroke,
            )
            drawCircle(tint, size.minDimension * 0.17f, Offset(size.width / 2f, top + size.height * 0.31f), style = stroke)
        }
        GameTab.CAPTIONS -> Canvas(Modifier.size(20.dp)) {
            // A speech bubble with two lines of text.
            val stroke = Stroke(1.6.dp.toPx())
            drawRoundRect(
                tint,
                topLeft = Offset(size.width * 0.06f, size.height * 0.14f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.88f, size.height * 0.58f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.14f),
                style = stroke,
            )
            drawLine(tint, Offset(size.width * 0.3f, size.height * 0.72f), Offset(size.width * 0.22f, size.height * 0.9f), 1.6.dp.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(size.width * 0.24f, size.height * 0.36f), Offset(size.width * 0.76f, size.height * 0.36f), 1.6.dp.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(size.width * 0.24f, size.height * 0.52f), Offset(size.width * 0.6f, size.height * 0.52f), 1.6.dp.toPx(), StrokeCap.Round)
        }
        GameTab.APPS -> Canvas(Modifier.size(20.dp)) {
            // Nine dots: an app grid.
            val step = size.minDimension / 3f
            for (i in 0..2) for (j in 0..2) {
                drawCircle(tint, size.minDimension * 0.09f, Offset(step * (i + 0.5f), step * (j + 0.5f)))
            }
        }
        GameTab.FACE -> Canvas(Modifier.size(20.dp)) {
            val r = size.minDimension * 0.15f
            val d = size.minDimension * 0.32f
            val stroke = Stroke(1.6.dp.toPx())
            listOf(Offset(0f, -d), Offset(d, 0f), Offset(0f, d), Offset(-d, 0f)).forEach {
                drawCircle(tint, r, center + it, style = stroke)
            }
        }
    }
}

private val PictureInPicture: ImageVector by lazy {
    ImageVector.Builder("PictureInPicture", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M19,7h-8v6h8L19,7zM21,3L3,3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,1.98 2,1.98h18c1.1,0 2,-0.88 2,-1.98L23,5" +
                    "c0,-1.1 -0.9,-2 -2,-2zM21,19.01L3,19.01L3,4.98h18v14.03z"
            ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
        )
    }.build()
}

/**
 * The bar across the top during a game: back, the tabs across the full width, then the right-hand
 * icon slots ([trailing], ending at the right edge; close by default). With [overlay] it fades
 * over the Now playing art instead of having a solid background.
 */
@Composable
fun GameTabBar(
    selected: GameTab?,
    onSelect: (GameTab) -> Unit,
    onBack: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    overlay: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val tint = if (overlay) Color.White.copy(alpha = 0.85f) else BookColors.TextMuted
    TabBarFrame(
        overlay = overlay,
        modifier = modifier,
        start = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = tint)
            }
        },
        middle = { GameTabsRow(selected, onSelect, Modifier.weight(1f)) },
        end = trailing ?: {
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close WalkThor", tint = tint)
                }
            }
        },
    )
}

/**
 * The bar's frame: a back slot, the middle (tabs, or the guide's search box) taking the rest, and
 * a fixed set of slots on the right, so the middle is always the same size.
 */
@Composable
fun TabBarFrame(
    overlay: Boolean,
    modifier: Modifier = Modifier,
    /** No background at all (the dimmed screen draws the tabs straight onto its dim). */
    bare: Boolean = false,
    start: @Composable RowScope.() -> Unit,
    middle: @Composable RowScope.() -> Unit,
    end: @Composable RowScope.() -> Unit,
) {
    val slots = tabBarRightSlots()
    Row(
        modifier
            .fillMaxWidth()
            .height(TabBarHeight)
            .then(
                if (bare) Modifier
                else if (overlay) Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                else Modifier.background(backdropOr(BookColors.Ink))
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(TabBarSlot), verticalAlignment = Alignment.CenterVertically) { start() }
        middle()
        Row(
            Modifier.width(TabBarSlot * slots),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) { end() }
    }
}

/** The tabs alone, placed exactly where [GameTabBar] puts them (for the dimmed screen). */
@Composable
fun GameTabPillInBarPosition(selected: GameTab?, onSelect: (GameTab) -> Unit, modifier: Modifier = Modifier) {
    TabBarFrame(
        overlay = true,
        modifier = modifier,
        bare = true,
        start = {},
        middle = { GameTabsRow(selected, onSelect, Modifier.weight(1f), actOnDown = true) },
        end = {},
    )
}
