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
 * Shows part of the top screen live on the bottom screen. The first time for a game you drag a
 * box over the part you want (a minimap, a HUD); it's remembered for that game.
 */
@Composable
fun MirrorScreen(game: String, onBack: () -> Unit, onStartCapture: () -> Unit) {
    val context = LocalContext.current
    val projection by Mirror.projection.collectAsState()
    val error by Mirror.error.collectAsState()
    var region by remember(game) { mutableStateOf(Mirror.region(context, game)) }
    var editing by remember(game) { mutableStateOf(region == null) }
    var draft by remember(game) { mutableStateOf(region ?: floatArrayOf(0f, 0f, 1f, 1f)) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var reader by remember { mutableStateOf<ImageReader?>(null) }

    // Ask Android to start capturing as soon as the screen opens (it shows its own prompt).
    LaunchedEffect(Unit) { if (Mirror.projection.value == null) onStartCapture() }

    // No dimming while you're watching the mirror; capture ends when you leave it or put WalkThor away.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(Unit) {
        AutoDim.wake()
        AutoDim.screenDelayMs.value = 0L
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) Mirror.stop(context) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            AutoDim.screenDelayMs.value = null
            Mirror.stop(context)
        }
    }

    // The capture: the top screen mirrored at reduced size into an image queue.
    DisposableEffect(projection) {
        val p = projection
        var display: VirtualDisplay? = null
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
                display = p.createVirtualDisplay(
                    "WalkThor mirror", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    images!!.surface, null, null,
                )
                reader = images
            }.onFailure { Mirror.error.value = "Couldn't capture the top screen (${it.javaClass.simpleName})" }
        }
        onDispose {
            reader = null
            frame = null
            runCatching { display?.release() }
            runCatching { images?.close() }
        }
    }

    // Pull the newest frame about 30 times a second. Frames are shown straight from the GPU buffer
    // (no copying); the last two are kept open so the one on screen is never reused mid-draw.
    LaunchedEffect(reader) {
        val r = reader ?: return@LaunchedEffect
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val img = frame
        when {
            Build.VERSION.SDK_INT < 29 -> Message("Mirroring needs Android 10 or newer.")
            projection == null -> StartPanel(error, onStartCapture)
            img == null -> Message("Starting the mirror…")
            editing -> AreaPicker(img, draft, onChange = { draft = it })
            else -> MirrorView(img, region ?: floatArrayOf(0f, 0f, 1f, 1f))
        }

        // ---- top bar ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White.copy(alpha = 0.85f))
            }
            Text(
                if (editing) "PICK THE AREA TO MIRROR" else "MIRROR",
                style = EyebrowStyle.copy(color = Color.White.copy(alpha = 0.75f)),
                modifier = Modifier.weight(1f),
            )
            if (projection != null && img != null) {
                if (editing) {
                    TextButton(onClick = { draft = floatArrayOf(0f, 0f, 1f, 1f) }) { Text("Whole screen", color = BookColors.TextMuted) }
                    Button(
                        onClick = {
                            region = draft.copyOf()
                            Mirror.setRegion(context, game, draft)
                            editing = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                    ) { Text("Done", fontWeight = FontWeight.SemiBold) }
                } else {
                    TextButton(onClick = { draft = region ?: floatArrayOf(0f, 0f, 1f, 1f); editing = true }) {
                        Text("Change area", color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        }
        if (editing && img != null && projection != null) {
            Text(
                "Drag the box over what you want to see here, like a minimap. Drag a corner to resize it.",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp, start = 24.dp, end = 24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
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

/** The chosen part of the top screen, as large as fits, keeping its shape. */
@Composable
private fun MirrorView(img: ImageBitmap, r: FloatArray) {
    Canvas(Modifier.fillMaxSize()) {
        val sx = (r[0] * img.width).roundToInt().coerceIn(0, img.width - 1)
        val sy = (r[1] * img.height).roundToInt().coerceIn(0, img.height - 1)
        val sw = ((r[2] - r[0]) * img.width).roundToInt().coerceIn(1, img.width - sx)
        val sh = ((r[3] - r[1]) * img.height).roundToInt().coerceIn(1, img.height - sy)
        val fit = fitRect(sw.toFloat(), sh.toFloat(), size)
        drawImage(
            img,
            srcOffset = IntOffset(sx, sy),
            srcSize = IntSize(sw, sh),
            dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
            dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}

/** The whole top screen with a box to drag and resize over the part to mirror. */
@Composable
private fun AreaPicker(img: ImageBitmap, r: FloatArray, onChange: (FloatArray) -> Unit) {
    val handle = with(LocalDensity.current) { 28.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val area = with(LocalDensity.current) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        // Leave room for the top bar and the hint.
        val pad = with(LocalDensity.current) { 56.dp.toPx() }
        val fit = fitRect(img.width.toFloat(), img.height.toFloat(), Size(area.width, area.height - pad * 2)).translate(0f, pad)
        var mode by remember { mutableStateOf(-1) } // 0..3 = a corner, 4 = move
        val current by androidx.compose.runtime.rememberUpdatedState(r)
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

