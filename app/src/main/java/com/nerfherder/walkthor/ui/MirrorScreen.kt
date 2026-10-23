package com.nerfherder.walkthor.ui

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.view.Display
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nerfherder.walkthor.Mirror
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How often the mirror redraws: 30 a second is plenty for a map or HUD, and keeps the cost down. */
private const val FRAME_MS = 33L
/** The top screen is copied at half its resolution: sharp enough here, a quarter of the work. */
private const val CAPTURE_SCALE = 2

/**
 * The live picture of the top screen while a capture is running. Lives as long as the game's
 * screens do, so switching tabs doesn't end the capture (Android would ask permission again);
 * while [active] is false (another tab is showing) the capture is paused and costs nothing.
 * With [counting] (the Stats tab), frames aren't drawn, just counted for the FPS meter.
 * The capture ends when this leaves the screen or WalkThor is put away.
 */
@Composable
fun rememberMirrorFrame(active: Boolean, counting: Boolean = false): ImageBitmap? {
    val context = LocalContext.current
    val projection by Mirror.projection.collectAsState()
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var reader by remember { mutableStateOf<ImageReader?>(null) }
    var display by remember { mutableStateOf<VirtualDisplay?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) Mirror.stop(context) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            Mirror.stop(context)
        }
    }

    // The capture: the top screen mirrored at reduced size into an image queue.
    DisposableEffect(projection) {
        val p = projection
        var d: VirtualDisplay? = null
        var images: ImageReader? = null
        if (p != null && Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val dm = context.getSystemService(DisplayManager::class.java)
                val top = dm.getDisplay(Display.DEFAULT_DISPLAY)
                val size = Point()
                @Suppress("DEPRECATION") top.getRealSize(size)
                val w = max(2, size.x / CAPTURE_SCALE) and 1.inv()
                val h = max(2, size.y / CAPTURE_SCALE) and 1.inv()
                val dpi = max(80, context.resources.displayMetrics.densityDpi / CAPTURE_SCALE)
                images = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                d = p.createVirtualDisplay(
                    "WalkThor mirror", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    images!!.surface, null, null,
                )
                reader = images
                display = d
            }.onFailure { Mirror.error.value = "Couldn't capture the top screen (${it.javaClass.simpleName})" }
        }
        onDispose {
            reader = null
            display = null
            frame = null
            runCatching { d?.release() }
            runCatching { images?.close() }
        }
    }

    // Paused on another tab: the virtual display stops drawing until it has somewhere to draw again.
    LaunchedEffect(display, reader, active, counting) {
        val d = display ?: return@LaunchedEffect
        runCatching { d.surface = if (active || counting) reader?.surface else null }
    }

    // Stats: count every new picture of the top screen, once a second, on a thread of its own.
    LaunchedEffect(reader, counting) {
        val r = reader
        if (r == null || !counting) return@LaunchedEffect
        val thread = android.os.HandlerThread("WalkThor FPS").apply { start() }
        FpsMeter.reset()
        r.setOnImageAvailableListener({ ir ->
            while (true) {
                val img = runCatching { ir.acquireNextImage() }.getOrNull() ?: break
                img.close()
                FpsMeter.counter.incrementAndGet()
            }
        }, android.os.Handler(thread.looper))
        try {
            while (isActive) {
                delay(1_000)
                FpsMeter.push(FpsMeter.counter.getAndSet(0))
            }
        } finally {
            runCatching { r.setOnImageAvailableListener(null, null) }
            thread.quitSafely()
        }
    }

    // Pull the newest frame about 30 times a second. Frames are shown straight from the GPU buffer
    // (no copying); the last two are kept open so the one on screen is never reused mid-draw.
    LaunchedEffect(reader, active) {
        val r = reader
        if (r == null || !active) { frame = null; return@LaunchedEffect }
        val held = ArrayDeque<Image>()
        try {
            while (isActive) {
                delay(FRAME_MS)
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: continue
                val buffer = img.hardwareBuffer
                val bitmap = buffer?.let { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
                buffer?.close()
                if (bitmap == null) { img.close(); continue }
                frame = bitmap.asImageBitmap()
                held.addLast(img)
                while (held.size > 2) held.removeFirst().close()
            }
        } finally {
            held.forEach { runCatching { it.close() } }
        }
    }
    return frame
}

/**
 * Shows part of the top screen live on the bottom screen. The first time for a game you drag a
 * box over the part you want (a minimap, a HUD); it's remembered for that game.
 *
 * [frame] is the live picture from [rememberMirrorFrame] (or a still, for screenshots).
 * [topBar] is the game's tab bar, drawn above the mirror.
 */
@Composable
fun MirrorScreen(
    game: String,
    onBack: () -> Unit,
    onStartCapture: () -> Unit,
    frame: ImageBitmap?,
    topBar: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val projection by Mirror.projection.collectAsState()
    val error by Mirror.error.collectAsState()
    var areas by remember(game) { mutableStateOf(Mirror.areas(context, game)) }
    var selected by remember(game) {
        mutableStateOf(Mirror.selected(context, game).let { if (it == Mirror.ALL || it in areas.indices) it else 0 })
    }
    // The area being edited: an index into [areas], or areas.size for a new one; null = not editing.
    var editingIndex by remember(game) { mutableStateOf<Int?>(if (areas.isEmpty()) 0 else null) }
    val editing = editingIndex != null
    var draft by remember(game) { mutableStateOf(floatArrayOf(0f, 0f, 1f, 1f)) }
    // Whether the box keeps the shape of the space the mirror fills, so the mirror fills it.
    var keepShape by remember(game) { mutableStateOf(true) }
    fun save(newAreas: List<FloatArray>, newSelected: Int) {
        areas = newAreas
        selected = newSelected
        Mirror.setAreas(context, game, newAreas)
        Mirror.setSelected(context, game, newSelected)
    }
    // Android mirrors its main display (the Thor's top screen). If WalkThor itself is there, the
    // mirror would just show itself.
    val onMainDisplay = remember {
        (com.nerfherder.walkthor.AppState.activityDisplayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY &&
            com.nerfherder.walkthor.DisplayHelper.bottomDisplay(context)?.displayId != Display.DEFAULT_DISPLAY &&
            com.nerfherder.walkthor.AppState.activityDisplayId != null
    }

    // Ask Android to start capturing as soon as the screen opens (it shows its own prompt).
    LaunchedEffect(Unit) { if (frame == null && Mirror.projection.value == null && !onMainDisplay) onStartCapture() }

    // No dimming while you're watching the mirror.
    DisposableEffect(Unit) {
        AutoDim.wake()
        AutoDim.screenDelayMs.value = 0L
        onDispose { AutoDim.screenDelayMs.value = null }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
    topBar?.invoke()
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
        // The shape of the space the mirror fills here.
        val screenAspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
        val img = frame
        val live = projection != null || frame != null
        // The box's width over its height in the 0..1 coordinates the areas use, for the
        // bottom screen's shape on this capture.
        val shape = if (img != null) screenAspect * img.height / img.width.coerceAtLeast(1) else screenAspect
        // A fresh box in the bottom screen's shape (new areas start with one).
        fun startBox() { keepShape = true; draft = shaped(floatArrayOf(0.25f, 0.25f, 0.75f, 0.75f), shape) }
        // The first time, once the picture arrives, start with a box in the right shape.
        LaunchedEffect(img != null) {
            if (img != null && editing && draft.contentEquals(floatArrayOf(0f, 0f, 1f, 1f)) && areas.isEmpty()) startBox()
        }
        when {
            Build.VERSION.SDK_INT < 29 -> Message("Mirroring needs Android 10 or newer.")
            onMainDisplay -> Message("WalkThor is on the main screen, so there's nothing else to mirror.")
            !live -> StartPanel(error, onStartCapture)
            img == null -> Message("Starting the mirror…")
            editing -> AreaPicker(img, draft, if (keepShape) shape else null, onChange = { draft = it })
            areas.isEmpty() -> MirrorView(img, listOf(floatArrayOf(0f, 0f, 1f, 1f)))
            selected == Mirror.ALL -> MirrorView(img, areas)
            else -> MirrorView(img, listOf(areas[selected.coerceIn(0, areas.lastIndex)]))
        }

        // ---- top bar ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)
                .then(if (topBar != null) Modifier.padding(start = 8.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // With the tab bar above, back lives there.
            if (topBar == null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White.copy(alpha = 0.85f))
                }
            }
            val ready = live && img != null
            if (topBar != null && !editing && (!ready || areas.size <= 1)) {
                Spacer(Modifier.weight(1f))
            } else if (editing || !ready || areas.size <= 1) {
                Text(
                    when {
                        editing && (editingIndex ?: 0) >= areas.size && areas.isNotEmpty() -> "PICK A NEW AREA"
                        editing -> "PICK THE AREA TO MIRROR"
                        else -> "MIRROR"
                    },
                    style = EyebrowStyle.copy(color = Color.White.copy(alpha = 0.75f)),
                    modifier = Modifier.weight(1f),
                )
            } else {
                // One chip per area, plus All to see them side by side.
                Row(
                    Modifier.weight(1f).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    areas.indices.forEach { i ->
                        AreaChip("${i + 1}", selected == i) { save(areas, i) }
                    }
                    AreaChip("All", selected == Mirror.ALL) { save(areas, Mirror.ALL) }
                }
            }
            if (ready) {
                if (editing) {
                    TextButton(onClick = { keepShape = false; draft = floatArrayOf(0f, 0f, 1f, 1f) }) { Text("Whole screen", color = BookColors.TextMuted) }
                    if (areas.isNotEmpty()) {
                        TextButton(onClick = { editingIndex = null }) { Text("Cancel", color = BookColors.TextMuted) }
                    }
                    Button(
                        onClick = {
                            val i = editingIndex ?: 0
                            val list = areas.toMutableList()
                            if (i >= list.size) list.add(draft.copyOf()) else list[i] = draft.copyOf()
                            save(list, i.coerceAtMost(list.lastIndex))
                            editingIndex = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                    ) { Text("Done", fontWeight = FontWeight.SemiBold) }
                } else {
                    val one = selected != Mirror.ALL && areas.isNotEmpty()
                    if (one) {
                        IconButton(onClick = {
                            draft = areas[selected].copyOf()
                            // Keep the shape locked if this area already has the bottom screen's shape.
                            val d = draft
                            keepShape = kotlin.math.abs((d[2] - d[0]) / (d[3] - d[1]).coerceAtLeast(0.001f) / shape - 1f) < 0.03f
                            editingIndex = selected
                        }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Change this area", tint = Color.White.copy(alpha = 0.85f))
                        }
                    }
                    if (one) {
                        IconButton(onClick = {
                            val list = areas.toMutableList().also { it.removeAt(selected) }
                            save(list, (selected - 1).coerceAtLeast(0))
                            // Removed the only one: pick a new area straight away.
                            if (list.isEmpty()) { startBox(); editingIndex = 0 }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove this area", tint = Color.White.copy(alpha = 0.85f))
                        }
                    }
                    if (areas.size < Mirror.MAX_AREAS) {
                        IconButton(onClick = { startBox(); editingIndex = areas.size }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add another area", tint = Color.White.copy(alpha = 0.85f))
                        }
                    }
                }
            }
        }
        if (editing && img != null && live) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 10.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (keepShape) "Drag the box over what you want to see here. Drag a corner to resize; it keeps this screen's shape."
                    else "Drag the box over what you want to see here. Drag a corner to resize it.",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                // Off: resize freely. On again: back to this screen's shape.
                AreaChip(if (keepShape) "Don't keep aspect ratio" else "Keep aspect ratio", selected = !keepShape) {
                    keepShape = !keepShape
                    if (keepShape) draft = shaped(draft, shape)
                }
            }
        }
    }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = BookColors.TextMuted, fontSize = 15.sp)
    }
}

@Composable
private fun StartPanel(error: String?, onStart: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Mirror the top screen", fontFamily = SerifDisplay, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = BookColors.Text)
            Spacer(Modifier.height(8.dp))
            Text(
                "Show part of the game, like a minimap or a HUD, here on the bottom screen. Android asks " +
                    "for permission each time. The picture stays on this device: nothing is recorded, saved or sent.",
                color = BookColors.TextMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFE0675A), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
            ) { Text("Start mirroring", fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * The chosen parts of the top screen, as large as they fit, each keeping its shape. Several are
 * laid out in whichever grid (one row, two rows…) makes them biggest.
 */
@Composable
private fun MirrorView(img: ImageBitmap, areas: List<FloatArray>) {
    Canvas(Modifier.fillMaxSize()) {
        val gap = 8.dp.toPx()
        val srcs = areas.map { r ->
            val sx = (r[0] * img.width).roundToInt().coerceIn(0, img.width - 1)
            val sy = (r[1] * img.height).roundToInt().coerceIn(0, img.height - 1)
            val sw = ((r[2] - r[0]) * img.width).roundToInt().coerceIn(1, img.width - sx)
            val sh = ((r[3] - r[1]) * img.height).roundToInt().coerceIn(1, img.height - sy)
            intArrayOf(sx, sy, sw, sh)
        }
        val cells = bestGrid(srcs.map { Size(it[2].toFloat(), it[3].toFloat()) }, size, gap)
        srcs.forEachIndexed { i, s ->
            val fit = fitRect(s[2].toFloat(), s[3].toFloat(), cells[i].size).translate(cells[i].left, cells[i].top)
            drawImage(
                img,
                srcOffset = IntOffset(s[0], s[1]),
                srcSize = IntSize(s[2], s[3]),
                dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

/** Cells for [shapes] in the grid (columns × rows) where they come out largest. */
private fun bestGrid(shapes: List<Size>, space: Size, gap: Float): List<Rect> {
    val n = shapes.size
    if (n <= 1) return listOf(Rect(Offset.Zero, space))
    var best: List<Rect> = emptyList()
    var bestArea = -1f
    for (cols in 1..n) {
        val rows = (n + cols - 1) / cols
        val cw = (space.width - gap * (cols - 1)) / cols
        val ch = (space.height - gap * (rows - 1)) / rows
        if (cw <= 0f || ch <= 0f) continue
        val cellsHere = (0 until n).map { i ->
            val c = i % cols
            val r = i / cols
            // Centre a short last row.
            val inRow = if (r == rows - 1) n - cols * (rows - 1) else cols
            val rowOffset = (cols - inRow) * (cw + gap) / 2f
            Rect(Offset(rowOffset + c * (cw + gap), r * (ch + gap)), Size(cw, ch))
        }
        val shown = shapes.indices.sumOf { i ->
            val f = fitRect(shapes[i].width, shapes[i].height, cellsHere[i].size)
            (f.width * f.height).toDouble()
        }.toFloat()
        if (shown > bestArea) { bestArea = shown; best = cellsHere }
    }
    return best
}

@Composable
private fun AreaChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(30.dp)
            .background(if (selected) BookColors.ButtonFill else Color.Black.copy(alpha = 0.4f), RoundedCornerShape(15.dp))
            .border(1.dp, if (selected) BookColors.ButtonFill else Color.White.copy(alpha = 0.3f), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White.copy(alpha = if (selected) 0.95f else 0.75f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The whole top screen with a box to drag and resize over the part to mirror. */
@Composable
private fun AreaPicker(img: ImageBitmap, r: FloatArray, shape: Float?, onChange: (FloatArray) -> Unit) {
    val handle = with(LocalDensity.current) { 28.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val area = with(LocalDensity.current) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        // Leave room for the top bar and the hint.
        val pad = with(LocalDensity.current) { 56.dp.toPx() }
        val fit = fitRect(img.width.toFloat(), img.height.toFloat(), Size(area.width, area.height - pad * 2)).translate(0f, pad)
        var mode by remember { mutableStateOf(-1) } // 0..3 = a corner, 4 = move
        val current by androidx.compose.runtime.rememberUpdatedState(r)
        val currentShape by androidx.compose.runtime.rememberUpdatedState(shape)
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(fit) {
                    detectDragGestures(
                        onDragStart = { p ->
                            val box = boxIn(current, fit)
                            val corners = listOf(box.topLeft, box.topRight, box.bottomLeft, box.bottomRight)
                            val near = corners.indexOfFirst { (it - p).getDistance() < handle * 1.4f }
                            mode = if (near >= 0) near else if (box.contains(p)) 4 else -1
                        },
                        onDragEnd = { mode = -1 },
                        onDrag = { change, d ->
                            change.consume()
                            val dx = d.x / fit.width
                            val dy = d.y / fit.height
                            val c = current.copyOf()
                            val minSize = 0.05f
                            val k = currentShape
                            if (k != null && mode in 0..3) {
                                onChange(resizeShaped(c, mode, dx, dy, k))
                                return@detectDragGestures
                            }
                            when (mode) {
                                4 -> {
                                    val w = c[2] - c[0]; val h = c[3] - c[1]
                                    c[0] = (c[0] + dx).coerceIn(0f, 1f - w); c[1] = (c[1] + dy).coerceIn(0f, 1f - h)
                                    c[2] = c[0] + w; c[3] = c[1] + h
                                }
                                0 -> { c[0] = (c[0] + dx).coerceIn(0f, c[2] - minSize); c[1] = (c[1] + dy).coerceIn(0f, c[3] - minSize) }
                                1 -> { c[2] = (c[2] + dx).coerceIn(c[0] + minSize, 1f); c[1] = (c[1] + dy).coerceIn(0f, c[3] - minSize) }
                                2 -> { c[0] = (c[0] + dx).coerceIn(0f, c[2] - minSize); c[3] = (c[3] + dy).coerceIn(c[1] + minSize, 1f) }
                                3 -> { c[2] = (c[2] + dx).coerceIn(c[0] + minSize, 1f); c[3] = (c[3] + dy).coerceIn(c[1] + minSize, 1f) }
                                else -> return@detectDragGestures
                            }
                            onChange(c)
                        },
                    )
                }
        ) {
            drawImage(
                img,
                dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
            val box = boxIn(r, fit)
            // Shade everything outside the box.
            val shade = Color.Black.copy(alpha = 0.55f)
            drawRect(shade, Offset(fit.left, fit.top), Size(fit.width, box.top - fit.top))
            drawRect(shade, Offset(fit.left, box.bottom), Size(fit.width, fit.bottom - box.bottom))
            drawRect(shade, Offset(fit.left, box.top), Size(box.left - fit.left, box.height))
            drawRect(shade, Offset(box.right, box.top), Size(fit.right - box.right, box.height))
            drawRect(Color.White, box.topLeft, box.size, style = Stroke(width = 2.dp.toPx()))
            for (c in listOf(box.topLeft, box.topRight, box.bottomLeft, box.bottomRight)) {
                drawCircle(Color.White, radius = 7.dp.toPx(), center = c)
            }
        }
    }
}

/**
 * [r] reshaped to width/height [k] (in 0..1 coordinates): about the same size, around the same
 * centre, kept inside the picture.
 */
internal fun shaped(r: FloatArray, k: Float): FloatArray {
    val area = ((r[2] - r[0]) * (r[3] - r[1])).coerceAtLeast(0.0025f)
    var w = kotlin.math.sqrt(area * k)
    var h = w / k
    if (w > 1f) { w = 1f; h = w / k }
    if (h > 1f) { h = 1f; w = h * k }
    val cx = ((r[0] + r[2]) / 2f).coerceIn(w / 2f, 1f - w / 2f)
    val cy = ((r[1] + r[3]) / 2f).coerceIn(h / 2f, 1f - h / 2f)
    return floatArrayOf(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
}

/**
 * Drags corner [corner] (0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right) of [r] by
 * [dx], [dy], keeping width/height [k]; the opposite corner stays put.
 */
internal fun resizeShaped(r: FloatArray, corner: Int, dx: Float, dy: Float, k: Float): FloatArray {
    val right = corner == 1 || corner == 3
    val down = corner == 2 || corner == 3
    val ax = if (right) r[0] else r[2]
    val ay = if (down) r[1] else r[3]
    val w = r[2] - r[0]
    // Follow whichever way the finger moved more.
    val byX = if (right) dx else -dx
    val byY = (if (down) dy else -dy) * k
    val grow = if (kotlin.math.abs(byX) >= kotlin.math.abs(byY)) byX else byY
    val maxW = min(if (right) 1f - ax else ax, (if (down) 1f - ay else ay) * k)
    val minW = maxOf(0.05f, 0.05f * k).coerceAtMost(maxW)
    val nw = (w + grow).coerceIn(minW, maxW)
    val nh = nw / k
    val left = if (right) ax else ax - nw
    val top = if (down) ay else ay - nh
    return floatArrayOf(left, top, left + nw, top + nh)
}

private fun boxIn(r: FloatArray, fit: Rect) = Rect(
    fit.left + r[0] * fit.width, fit.top + r[1] * fit.height,
    fit.left + r[2] * fit.width, fit.top + r[3] * fit.height,
)

/** The largest rectangle of the given shape that fits in [space], centred. */
private fun fitRect(w: Float, h: Float, space: Size): Rect {
    if (w <= 0f || h <= 0f) return Rect(Offset.Zero, space)
    val s = min(space.width / w, space.height / h)
    val dw = w * s
    val dh = h * s
    val left = (space.width - dw) / 2f
    val top = (space.height - dh) / 2f
    return Rect(left, top, left + dw, top + dh)
}

