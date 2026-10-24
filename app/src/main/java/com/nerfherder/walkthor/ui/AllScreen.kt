package com.nerfherder.walkthor.ui

import android.view.Display
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerfherder.walkthor.Mirror
import com.nerfherder.walkthor.SwipeGestureService
import com.nerfherder.walkthor.controls.ControlPrefs
import com.nerfherder.walkthor.controls.ControllerInput
import com.nerfherder.walkthor.controls.ThorPad
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.BookRepository
import com.nerfherder.walkthor.data.BookType
import com.nerfherder.walkthor.data.LaunchedGame
import com.nerfherder.walkthor.data.PdfSource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Fixed content for store screenshots. */
data class AllPreview(val guideText: String?, val stats: StatsPreview)

/**
 * The All tab: the game's other tabs at a glance, in a 2×2 grid (Mirror, the guide where you left
 * off, Face Buttons and Stats). Tap a panel to open that tab.
 */
@Composable
fun AllScreen(
    game: LaunchedGame,
    guide: Book?,
    frame: ImageBitmap?,
    onOpen: (GameTab) -> Unit,
    onStartCapture: () -> Unit,
    topBar: (@Composable () -> Unit)? = null,
    preview: AllPreview? = null,
) {
    val context = LocalContext.current
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val onMainDisplay = remember {
        preview == null &&
            (com.nerfherder.walkthor.AppState.activityDisplayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY &&
            com.nerfherder.walkthor.DisplayHelper.bottomDisplay(context)?.displayId != Display.DEFAULT_DISPLAY &&
            com.nerfherder.walkthor.AppState.activityDisplayId != null
    }
    // Mirror and the FPS meter need the screen capture; ask as the tab opens (once per game's screens).
    LaunchedEffect(Unit) { if (preview == null && Mirror.projection.value == null && !onMainDisplay) onStartCapture() }
    // Stays bright while you watch it.
    DisposableEffect(Unit) {
        AutoDim.wake()
        AutoDim.screenDelayMs.value = 0L
        onDispose { AutoDim.screenDelayMs.value = null }
    }

    Column(Modifier.fillMaxSize().background(backdropOr(BookColors.Ink))) {
        topBar?.invoke()
        // The panels, in your order: press and hold one and drag it onto another to swap them.
        val order = remember { androidx.compose.runtime.mutableStateListOf(*AllLayout.order(context).toTypedArray()) }
        var dragging by remember { mutableStateOf<GameTab?>(null) }
        var dragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var grid by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        val open by androidx.compose.runtime.rememberUpdatedState(onOpen)
        fun drop(tab: GameTab) {
            val from = order.indexOf(tab)
            if (from >= 0 && grid.width > 0 && grid.height > 0) {
                // Where the middle of the dragged panel ended up decides the slot it lands in.
                val cx = ((from % 2) + 0.5f) * grid.width / 2f + dragOffset.x
                val cy = ((from / 2) + 0.5f) * grid.height / 2f + dragOffset.y
                val to = (if (cy < grid.height / 2f) 0 else 2) + (if (cx < grid.width / 2f) 0 else 1)
                if (to != from) {
                    order[from] = order[to].also { order[to] = tab }
                    AllLayout.setOrder(context, order.toList())
                }
            }
            dragging = null
            dragOffset = androidx.compose.ui.geometry.Offset.Zero
        }
        @Composable
        fun PanelFor(tab: GameTab, modifier: Modifier) {
            val lifted = dragging == tab
            val m = modifier
                .zIndex(if (lifted) 1f else 0f)
                .graphicsLayer {
                    if (lifted) {
                        translationX = dragOffset.x; translationY = dragOffset.y
                        scaleX = 1.03f; scaleY = 1.03f; alpha = 0.92f
                    }
                }
                .pointerInput(tab) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var cancelled = false
                        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation().also { if (it == null) cancelled = true }
                        }
                        if (up != null) { open(tab); return@awaitEachGesture }
                        if (cancelled) return@awaitEachGesture
                        // Held: pick the panel up and follow the finger until it's let go.
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        dragging = tab
                        dragOffset = androidx.compose.ui.geometry.Offset.Zero
                        drag(down.id) { change ->
                            dragOffset += change.positionChange()
                            change.consume()
                        }
                        drop(tab)
                    }
                }
            when (tab) {
                GameTab.MIRROR -> Panel("Mirror", compact, m) { MirrorPanel(game, frame, preview != null) }
                GameTab.GUIDE -> Panel("Guide" + (guide?.let { "  ·  p. ${it.lastPage + 1}" } ?: ""), compact, m) {
                    GuidePanel(guide, preview?.guideText)
                }
                GameTab.FACE -> Panel("Face buttons", compact, m) { FacePanel(game, preview != null) }
                else -> Panel("Stats", compact, m) { StatsPanel(preview?.stats, compact) }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp)
                .onSizeChanged { grid = it },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in 0..1) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (col in 0..1) {
                        val tab = order[row * 2 + col]
                        androidx.compose.runtime.key(tab) { PanelFor(tab, Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** One bracketed panel with its name and "Open ›" (tapping and dragging are handled by its caller). */
@Composable
private fun Panel(
    title: String,
    compact: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxHeight()
            .padding(horizontal = 10.dp, vertical = if (compact) 5.dp else 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = EyebrowStyle.copy(color = Color.White.copy(alpha = 0.6f), fontSize = if (compact) 10.sp else 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("Open", color = Color.White.copy(alpha = 0.55f), fontSize = if (compact) 9.sp else 11.sp, fontWeight = FontWeight.SemiBold)
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(if (compact) 14.dp else 18.dp),
            )
        }
        Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = BookColors.TextMuted, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
}

/** The game's mirror areas (all of them side by side), or the whole top screen if none are set. */
@Composable
private fun MirrorPanel(game: LaunchedGame, frame: ImageBitmap?, still: Boolean) {
    val context = LocalContext.current
    val projection by Mirror.projection.collectAsState()
    val areas = remember(game.title) {
        val all = Mirror.areas(context, game.title)
        val sel = Mirror.selected(context, game.title)
        when {
            all.isEmpty() -> listOf(floatArrayOf(0f, 0f, 1f, 1f))
            sel == Mirror.ALL || sel !in all.indices -> all
            else -> listOf(all[sel])
        }
    }
    when {
        frame != null -> MirrorView(frame, areas)
        projection == null && !still -> Note("Tap to start Mirror")
        else -> Note("Starting…")
    }
}

/** The guide where you left off: a PDF page, or the text from your place onward. */
@Composable
private fun GuidePanel(guide: Book?, previewText: String?) {
    val context = LocalContext.current
    if (guide == null && previewText == null) { Note("No guide yet. Tap to add one."); return }
    if (previewText != null || guide?.type == BookType.TXT) {
        val text by produceState(previewText, guide?.id, guide?.lastOffset) {
            if (previewText != null || guide == null) return@produceState
            val all = runCatching { BookRepository.get(context).readText(guide) }.getOrNull() ?: return@produceState
            val from = guide.lastOffset.coerceIn(0, all.length)
            value = all.substring(from, minOf(all.length, from + 6_000))
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Sized so a GameFAQs-style 80-column line just fits the panel's width.
            val size = with(LocalDensity.current) { (maxWidth / 50f).toSp() }
            Text(
                text ?: "",
                color = LocalPageColors.current.ink,
                fontFamily = FontFamily.Monospace,
                fontSize = size,
                lineHeight = size * 1.25f,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalPageColors.current.paper)
                    .padding(6.dp),
            )
        }
    } else if (guide != null) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = with(LocalDensity.current) { maxWidth.roundToPx() }
            val h = with(LocalDensity.current) { maxHeight.roundToPx() }
            val page by produceState<ImageBitmap?>(null, guide.id, guide.lastPage, w, h) {
                val src = runCatching { PdfSource.open(context, BookRepository.get(context).bookFile(guide)) }.getOrNull()
                    ?: return@produceState
                try {
                    value = runCatching { src.render(guide.lastPage, w, h) }.getOrNull()?.asImageBitmap()
                    awaitDispose { }
                } finally {
                    runCatching { src.close() }
                }
            }
            page?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/** The four face buttons for this game's system, lighting up as you press them. */
@Composable
private fun FacePanel(game: LaunchedGame, still: Boolean) {
    val context = LocalContext.current
    val style = remember(game.player, game.file) { ControlPrefs.faceStyle(context, game.player, game.file) }
    val faceCodes = remember { ThorPad.FACE_ORDER.associateWith { ControlPrefs.faceCode(context, it) } }
    val pressed by ControllerInput.pressed.collectAsState()
    val agreed = remember { ControlPrefs.keysConsent(context) }
    // Watch presses while this tab is open, once you've agreed on the Face Buttons tab.
    DisposableEffect(agreed) {
        if (agreed && !still) {
            ControllerInput.watching.value = true
            SwipeGestureService.instance?.get()?.setKeyWatch(true)
        }
        onDispose {
            if (agreed && !still) {
                ControllerInput.watching.value = false
                SwipeGestureService.instance?.get()?.setKeyWatch(false)
            }
        }
    }
    // Room around the diamond for the "Thor X" labels drawn outside it.
    Box(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 14.dp)) {
        FaceDiagram(style, faceCodes, if (agreed || still) pressed else emptySet(), null)
        if (!agreed && !still) {
            Text(
                "Open to light up presses",
                color = BookColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** FPS with the last minute's graph, then power, time left, play time, the time and battery. */
@Composable
private fun StatsPanel(preview: StatsPreview?, compact: Boolean) {
    val context = LocalContext.current
    val live by FpsMeter.history.collectAsState()
    val fps = preview?.fps ?: live
    var snap by remember { mutableStateOf(preview?.snapshot) }
    var recent by remember { mutableStateOf(listOf<Float>()) }
    LaunchedEffect(preview) {
        if (preview != null) return@LaunchedEffect
        while (true) {
            val s = StatsReader.read(context)
            s.watts?.let { recent = (recent + it).takeLast(5) }
            snap = s
            delay(2_000)
        }
    }
    val watts = preview?.snapshot?.watts ?: recent.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val s = snap
    val minutesLeft = when {
        preview != null -> preview.snapshot.minutesLeft
        s?.minutesLeft != null && s.watts != null && watts != null -> (s.minutesLeft * s.watts / watts).roundToInt()
        else -> null
    }
    val power = when {
        s?.charging == true -> "Charging"
        watts != null -> "%.1f W".format(watts) + (minutesLeft?.let { " · ${hoursMinutes(it)}" } ?: "")
        else -> ""
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                fps.lastOrNull()?.toString() ?: "—",
                color = BookColors.Text,
                fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                fontSize = if (compact) 34.sp else 52.sp,
                lineHeight = if (compact) 36.sp else 54.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "FPS",
                color = BookColors.Text.copy(alpha = 0.75f),
                fontWeight = FontWeight.SemiBold,
                fontSize = if (compact) 12.sp else 16.sp,
                modifier = Modifier.padding(bottom = if (compact) 4.dp else 8.dp),
            )
            // On a short screen the power reading sits up here, beside the FPS.
            if (compact) {
                Spacer(Modifier.weight(1f))
                Text(power, color = BookColors.Text.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        FpsGraph(fps, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!compact) {
                Text(
                    power,
                    color = BookColors.Text.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            } else Spacer(Modifier.weight(1f))
            StatusClock(anchor = false)
        }
    }
}

/** The order of the panels on the All tab (the same for every game), as you arranged them. */
object AllLayout {
    private val DEFAULT = listOf(GameTab.MIRROR, GameTab.GUIDE, GameTab.FACE, GameTab.STATS)
    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences("tabs", android.content.Context.MODE_PRIVATE)

    fun order(context: android.content.Context): List<GameTab> {
        val saved = prefs(context).getString("allOrder", null)?.split(',')
            ?.mapNotNull { n -> GameTab.entries.firstOrNull { it.name == n } }
        return if (saved != null && saved.toSet() == DEFAULT.toSet() && saved.size == 4) saved else DEFAULT
    }

    fun setOrder(context: android.content.Context, order: List<GameTab>) {
        prefs(context).edit().putString("allOrder", order.joinToString(",") { it.name }).apply()
    }
}
