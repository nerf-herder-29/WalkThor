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
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.launch

/** Fixed content for store screenshots. */
data class AllPreview(val guideText: String?, val stats: StatsPreview, val apps: List<InstalledApp>? = null)

/** Where each of [n] panels sits, as fractions of the grid: left, top, right, bottom. */
private fun slotsFor(n: Int): List<List<Float>> = when (n) {
    1 -> listOf(listOf(0f, 0f, 1f, 1f))
    2 -> listOf(listOf(0f, 0f, 0.5f, 1f), listOf(0.5f, 0f, 1f, 1f))
    3 -> listOf(listOf(0f, 0f, 0.5f, 0.5f), listOf(0.5f, 0f, 1f, 0.5f), listOf(0f, 0.5f, 1f, 1f))
    else -> listOf(listOf(0f, 0f, 0.5f, 0.5f), listOf(0.5f, 0f, 1f, 0.5f), listOf(0f, 0.5f, 0.5f, 1f), listOf(0.5f, 0.5f, 1f, 1f))
}

/**
 * The Multi tab: the game's other tabs at a glance, in up to four panels you choose (⋮ → Choose
 * panels): Mirror, the guide where you left off, Face Buttons, Stats and your pinned apps. Tap a
 * panel to open that tab; press and hold one and drag it onto another to swap them.
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
        val holder = Any()
        AutoDim.wake()
        AutoDim.holdBright(holder, true)
        onDispose { AutoDim.holdBright(holder, false) }
    }

    Column(Modifier.fillMaxSize().background(backdropOr(BookColors.Ink))) {
        topBar?.invoke()
        // The panels, in your order: press and hold one and drag it onto another to swap them.
        val order by AllLayout.panels(context).collectAsState()
        val choosing by AllLayout.choosing.collectAsState()
        if (choosing) ChoosePanelsDialog(order, onDone = { picked ->
            AllLayout.choosing.value = false
            if (picked != null) AllLayout.choose(context, picked)
        })
        val n = order.size
        var dragging by remember { mutableStateOf<GameTab?>(null) }
        var dragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var grid by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        val open by androidx.compose.runtime.rememberUpdatedState(onOpen)
        fun drop(tab: GameTab) {
            val from = order.indexOf(tab)
            if (from >= 0 && grid.width > 0 && grid.height > 0) {
                // Where the middle of the dragged panel ended up decides the slot it lands in.
                val slots = slotsFor(n)
                val (l, t, r, b) = slots[from]
                val cx = (l + r) / 2f * grid.width + dragOffset.x
                val cy = (t + b) / 2f * grid.height + dragOffset.y
                val to = slots.indexOfFirst { (l2, t2, r2, b2) ->
                    cx >= l2 * grid.width && cx < r2 * grid.width && cy >= t2 * grid.height && cy < b2 * grid.height
                }
                if (to >= 0 && to != from) {
                    val next = order.toMutableList()
                    next[from] = next[to].also { next[to] = tab }
                    AllLayout.setOrder(context, next)
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
                GameTab.APPS -> Panel("Apps", compact, m) { AppsPanel(preview?.apps, compact) }
                GameTab.SHOTS -> Panel("Shots", compact, m) { ShotsPanel(game, frame, preview != null) }
                GameTab.CAPTIONS -> Panel("Captions", compact, m) { CaptionsPanel(onStartCapture, preview != null) }
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
            // One or two panels: one row. Three: two on top, one across the bottom. Four: 2×2.
            val rows = if (n <= 2) listOf(order) else listOf(order.take(2), order.drop(2))
            for (row in rows) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (tab in row) {
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

/**
 * Shots: the top screen (or the part framed for this game on the Shots tab) with a shutter
 * button, to take a screenshot without leaving Multi.
 */
@Composable
private fun ShotsPanel(game: LaunchedGame, frame: ImageBitmap?, still: Boolean) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val projection by Mirror.projection.collectAsState()
    val area = remember(game.title) {
        if (com.nerfherder.walkthor.data.Shots.useArea(context, game.title)) com.nerfherder.walkthor.data.Shots.area(context, game.title) else null
    }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(note) { if (note != null) { kotlinx.coroutines.delay(2_000); note = null } }
    Box(Modifier.fillMaxSize()) {
        when {
            frame != null -> MirrorView(frame, listOf(area ?: floatArrayOf(0f, 0f, 1f, 1f)))
            projection == null && !still -> Note("Tap to start Shots")
            else -> Note("Starting…")
        }
        if (frame != null || still) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(44.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .border(3.dp, BookColors.Text, androidx.compose.foundation.shape.CircleShape)
                    .clickable(enabled = !busy && frame != null) {
                        busy = true
                        val copy = frame?.let { f -> runCatching { f.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false) }.getOrNull() }
                        scope.launch {
                            val shot = runCatching {
                                com.nerfherder.walkthor.data.Shots.take(context, Mirror.projection.value, copy, game.title, area)
                            }.getOrNull()
                            note = if (shot != null) "Saved" else "Couldn't save"
                            busy = false
                        }
                    }
                    .padding(5.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(BookColors.Text),
            )
        }
        note?.let {
            Text(
                it, color = BookColors.Text, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp)
                    .background(Color(0xEE0D0D10), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** Captions: what's being said in the game, with Start and Stop (ends when you leave Multi). */
@Composable
private fun CaptionsPanel(onStartCapture: () -> Unit, still: Boolean) {
    val on by com.nerfherder.walkthor.data.Captions.on.collectAsState()
    val partial by com.nerfherder.walkthor.data.Captions.partial.collectAsState()
    val lines by com.nerfherder.walkthor.data.Captions.lines.collectAsState()
    val recognizer by com.nerfherder.walkthor.data.Captions.recognizer.collectAsState()
    val start = rememberCaptionsStarter(onStartCapture)
    if (!still) DisposableEffect(Unit) { onDispose { com.nerfherder.walkthor.data.Captions.stop() } }
    Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.Bottom) {
        if (lines.isEmpty() && partial.isBlank()) {
            Text(
                if (on) recognizer.replaceFirstChar { it.uppercase() } else "Captions of the game's speech",
                color = BookColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
            lines.takeLast(2).forEach { Text(it, color = BookColors.Text.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 2) }
            if (partial.isNotBlank()) Text(partial, color = BookColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
        }
        Text(
            if (on) "Stop" else "Start",
            color = BookColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(50))
                .background(BookColors.SurfaceHigh)
                .clickable(enabled = !still) { if (on) com.nerfherder.walkthor.data.Captions.stop() else start() }
                .padding(horizontal = 14.dp, vertical = 5.dp),
        )
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
        val holder = Any()
        if (agreed && !still) {
            ControllerInput.hold(holder, true)
            SwipeGestureService.instance?.get()?.setKeyWatch(true)
        }
        onDispose {
            if (agreed && !still && !ControllerInput.hold(holder, false)) {
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
                rollingNumber(fps.lastOrNull())?.toString() ?: "—",
                color = BookColors.Text,
                fontWeight = FontWeight.Medium,
                        style = androidx.compose.ui.text.TextStyle(fontFamily = AppFont.family, fontFeatureSettings = "tnum"),
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

/** Your pinned apps; tap one to open it on this screen. */
@Composable
private fun AppsPanel(preview: List<InstalledApp>?, compact: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (preview == null) {
            AppCatalog.loadPins(context)
            if (AppCatalog.apps.value == null) AppCatalog.refresh(context)
        }
    }
    val loaded by AppCatalog.apps.collectAsState()
    val pins by AppCatalog.pinned.collectAsState()
    val all = preview ?: loaded
    val apps = remember(all, pins) {
        val byPkg = all?.associateBy { it.packageName } ?: emptyMap()
        if (preview != null) preview else pins.mapNotNull { byPkg[it] }
    }
    if (apps.isEmpty()) { Note(if (all == null) "Loading…" else "Pin apps on the Apps tab to see them here."); return }
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(if (compact) 64.dp else 78.dp),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        userScrollEnabled = false,
    ) {
        items(apps.size) { i ->
            AppIcon(apps[i], compact = true, onOpen = { AppCatalog.open(context, apps[i].packageName, here = true) }, onHold = null)
        }
    }
}

/** The ⋮ on the Multi tab's bar: choose its panels, or close WalkThor. */
@Composable
fun MultiMenu(onClose: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.IconButton(onClick = { open = true }) {
            Icon(androidx.compose.material.icons.Icons.Filled.MoreVert, contentDescription = "More", tint = BookColors.TextMuted)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Choose panels…") },
                onClick = { open = false; AllLayout.choosing.value = true },
            )
            androidx.compose.material3.DropdownMenuItem(text = { Text("Close WalkThor") }, onClick = { open = false; onClose() })
        }
    }
}

/** Pick which tabs the Multi tab shows (one to four). */
@Composable
private fun ChoosePanelsDialog(current: List<GameTab>, onDone: (List<GameTab>?) -> Unit) {
    var picked by remember { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Multi panels", fontFamily = DisplayFont) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text("Choose up to four.", color = BookColors.TextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                AllLayout.CHOICES.forEach { tab ->
                    val on = tab in picked
                    val enabled = on || picked.size < 4
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled && !(on && picked.size == 1)) {
                                picked = if (on) picked - tab else picked + tab
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = on,
                            onCheckedChange = null,
                            enabled = enabled,
                            colors = androidx.compose.material3.CheckboxDefaults.colors(
                                checkedColor = BookColors.Text,
                                checkmarkColor = Color.Black,
                                uncheckedColor = BookColors.TextMuted,
                            ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(tab.label, color = if (enabled) BookColors.Text else BookColors.TextMuted, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onDone(picked) }) { Text("Done", color = BookColors.Text) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { onDone(null) }) { Text("Cancel", color = BookColors.TextMuted) }
        },
    )
}

/** Which panels the Multi tab shows, and in what order (the same for every game). */
object AllLayout {
    /** The tabs that can be a panel, in the order they're offered. */
    val CHOICES = listOf(GameTab.MIRROR, GameTab.GUIDE, GameTab.FACE, GameTab.STATS, GameTab.SHOTS, GameTab.CAPTIONS, GameTab.APPS)
    private val DEFAULT = listOf(GameTab.MIRROR, GameTab.GUIDE, GameTab.FACE, GameTab.STATS)
    private var flow: kotlinx.coroutines.flow.MutableStateFlow<List<GameTab>>? = null
    /** True while the "Choose panels" dialog is open (⋮ on the Multi tab). */
    val choosing = kotlinx.coroutines.flow.MutableStateFlow(false)

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences("tabs", android.content.Context.MODE_PRIVATE)

    fun panels(context: android.content.Context): kotlinx.coroutines.flow.StateFlow<List<GameTab>> =
        flow ?: kotlinx.coroutines.flow.MutableStateFlow(order(context)).also { flow = it }

    fun order(context: android.content.Context): List<GameTab> {
        flow?.let { return it.value }
        val saved = prefs(context).getString("allOrder", null)?.split(',')
            ?.mapNotNull { n -> CHOICES.firstOrNull { it.name == n } }?.distinct()
        return if (saved != null && saved.size in 1..4) saved else DEFAULT
    }

    /** Saves exactly this order (after dragging panels around). */
    fun setOrder(context: android.content.Context, order: List<GameTab>) {
        val next = order.distinct().take(4).ifEmpty { DEFAULT }
        prefs(context).edit().putString("allOrder", next.joinToString(",") { it.name }).apply()
        (flow ?: kotlinx.coroutines.flow.MutableStateFlow(next).also { flow = it }).value = next
    }

    /** Saves a new choice of panels: kept ones stay where they were, newly added ones go at the end. */
    fun choose(context: android.content.Context, picked: List<GameTab>) {
        val prev = order(context)
        setOrder(context, prev.filter { it in picked } + picked.filter { it !in prev })
    }
}
