package com.nerfherder.walkthor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerfherder.walkthor.ScreenControls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Brightness (top screen, bottom screen or both) and volume, on Now playing. Brightness for the
 * top screen needs Android's "Modify system settings" for WalkThor once; the panel asks for it.
 */
@Composable
fun QuickControls(modifier: Modifier = Modifier, compact: Boolean = false, backdrop: String? = null) {
    val context = LocalContext.current
    var target by remember { mutableStateOf(ScreenControls.target(context)) }
    var canTop by remember { mutableStateOf(ScreenControls.canSetTop(context)) }
    var top by remember { mutableFloatStateOf(ScreenControls.top(context)) }
    var bottom by remember { mutableFloatStateOf(1f) }
    var volume by remember { mutableFloatStateOf(ScreenControls.volume(context)) }
    var dragging by remember { mutableStateOf(false) }
    // Keep up with changes made elsewhere (AYN's drawer, the volume buttons), and with the
    // permission being granted while you were in Android's settings.
    LaunchedEffect(Unit) {
        bottom = withContext(Dispatchers.IO) { ScreenControls.bottom(context) }
        while (true) {
            if (!dragging) {
                canTop = ScreenControls.canSetTop(context)
                top = ScreenControls.top(context)
                volume = ScreenControls.volume(context)
            }
            delay(1_500)
        }
    }
    val needsTop = target != ScreenControls.Target.BOTTOM && !canTop
    val shown = when (target) {
        ScreenControls.Target.TOP -> top
        ScreenControls.Target.BOTTOM -> bottom
        ScreenControls.Target.BOTH -> top
    }

    FrostedSurface(backdrop, RoundedCornerShape(18.dp), modifier.widthIn(max = 560.dp).fillMaxWidth()) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp)) {
        // Which screen the brightness slider changes (on a short screen it sits on the brightness row).
        val pick: @Composable (Modifier) -> Unit = { m ->
            Row(m.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).padding(3.dp)) {
                ScreenControls.Target.entries.forEach { t ->
                    val on = t == target
                    Text(
                        if (compact) t.label.removeSuffix(" screen") else t.label,
                        fontSize = if (compact) 12.sp else 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) Color.Black else BookColors.Text,
                        maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .then(if (compact) Modifier else Modifier.weight(1f))
                            .clip(RoundedCornerShape(50))
                            .background(if (on) BookColors.Text else Color.Transparent)
                            .clickable { target = t; ScreenControls.setTarget(context, t) }
                            .padding(horizontal = if (compact) 10.dp else 0.dp, vertical = if (compact) 3.dp else 6.dp),
                    )
                }
            }
        }
        if (!compact) {
            pick(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        if (needsTop) {
            // Android asks once before an app can change the brightness.
            Row(
                Modifier.fillMaxWidth().height(if (compact) 32.dp else 40.dp).clip(RoundedCornerShape(10.dp))
                    .clickable { ScreenControls.askTop(context) }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SunIcon()
                Spacer(Modifier.width(12.dp))
                Text("Tap to let WalkThor change the brightness", color = BookColors.Text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text("Allow", color = BookColors.Brass, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (compact) { Spacer(Modifier.width(8.dp)); pick(Modifier) }
            }
        } else {
            ControlRow(icon = { SunIcon() }, value = shown, compact = compact, onDrag = { dragging = it }, trailing = if (compact) ({ pick(Modifier) }) else null) { v ->
                when (target) {
                    ScreenControls.Target.TOP -> { top = v; ScreenControls.setTop(context, v) }
                    ScreenControls.Target.BOTTOM -> { bottom = v; ScreenControls.setBottom(context, v) }
                    ScreenControls.Target.BOTH -> { top = v; bottom = v; ScreenControls.setTop(context, v); ScreenControls.setBottom(context, v) }
                }
            }
        }
        ControlRow(icon = { SpeakerIcon() }, value = volume, compact = compact, onDrag = { dragging = it }) { v ->
            volume = v
            ScreenControls.setVolume(context, v)
        }
    }
    }
}

@Composable
private fun ControlRow(
    icon: @Composable () -> Unit,
    value: Float,
    compact: Boolean,
    onDrag: (Boolean) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(if (compact) 34.dp else 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(10.dp))
        ThinSlider(value, onDrag, onChange, Modifier.weight(1f))
        Text(
            "${(value * 100).roundToInt()}%",
            color = BookColors.TextMuted, fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
        if (trailing != null) { Spacer(Modifier.width(10.dp)); trailing() }
    }
}

/** A slim slider: a thin track and a round knob; tap or drag anywhere along it. */
@Composable
private fun ThinSlider(value: Float, onDrag: (Boolean) -> Unit, onChange: (Float) -> Unit, modifier: Modifier) {
    val track = Color.White.copy(alpha = 0.18f)
    val fill = BookColors.Text
    Canvas(
        modifier
            .height(30.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = { o ->
                    onDrag(true)
                    onChange((o.x / size.width).coerceIn(0f, 1f))
                    tryAwaitRelease()
                    onDrag(false)
                })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { onDrag(true) },
                    onDragEnd = { onDrag(false) },
                    onDragCancel = { onDrag(false) },
                ) { change, _ ->
                    change.consume()
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val r = 9.dp.toPx()
        val y = size.height / 2
        val left = r
        val right = size.width - r
        val x = left + (right - left) * value.coerceIn(0f, 1f)
        val h = 5.dp.toPx()
        drawRoundRect(track, topLeft = Offset(left, y - h / 2), size = androidx.compose.ui.geometry.Size(right - left, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        drawRoundRect(fill, topLeft = Offset(left, y - h / 2), size = androidx.compose.ui.geometry.Size(x - left, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = r + 1.dp.toPx(), center = Offset(x, y + 1.dp.toPx()))
        drawCircle(fill, radius = r, center = Offset(x, y))
    }
}

@Composable
private fun SunIcon() {
    val c = BookColors.Text.copy(alpha = 0.85f)
    Canvas(Modifier.size(18.dp)) {
        val r = size.minDimension / 2
        drawCircle(c, radius = r * 0.38f)
        for (i in 0 until 8) {
            val a = Math.PI * i / 4
            val s = Offset(center.x + (r * 0.6f * cos(a)).toFloat(), center.y + (r * 0.6f * sin(a)).toFloat())
            val e = Offset(center.x + (r * 0.95f * cos(a)).toFloat(), center.y + (r * 0.95f * sin(a)).toFloat())
            drawLine(c, s, e, strokeWidth = 1.6.dp.toPx())
        }
    }
}

@Composable
private fun SpeakerIcon() {
    val c = BookColors.Text.copy(alpha = 0.85f)
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val body = Path().apply {
            moveTo(w * 0.08f, h * 0.36f); lineTo(w * 0.3f, h * 0.36f); lineTo(w * 0.55f, h * 0.14f)
            lineTo(w * 0.55f, h * 0.86f); lineTo(w * 0.3f, h * 0.64f); lineTo(w * 0.08f, h * 0.64f); close()
        }
        drawPath(body, c)
        drawArc(c, -45f, 90f, false, topLeft = Offset(w * 0.45f, h * 0.28f), size = androidx.compose.ui.geometry.Size(w * 0.3f, h * 0.44f), style = Stroke(1.6.dp.toPx()))
        drawArc(c, -45f, 90f, false, topLeft = Offset(w * 0.45f, h * 0.12f), size = androidx.compose.ui.geometry.Size(w * 0.5f, h * 0.76f), style = Stroke(1.6.dp.toPx()))
    }
}

/**
 * The top screen's frame rate, read from the display driver once a second (no screen capture).
 * Shows nothing if the device doesn't allow it (Diagnostics says which).
 */
@Composable
fun TopFpsBadge(modifier: Modifier = Modifier, compact: Boolean = false, backdrop: String? = null) {
    val driver by produceState<Float?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { ScreenControls.topFps() }
            delay(1_000)
        }
    }
    val fps = driver ?: return
    FrostedSurface(backdrop, RoundedCornerShape(50), modifier) {
    Row(
        Modifier.padding(horizontal = if (compact) 9.dp else 12.dp, vertical = if (compact) 3.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val small = if (compact) 11.sp else 13.sp
        Text("${fps.roundToInt()}", color = Color(0xFF7FC48A), fontSize = if (compact) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Text("FPS", color = BookColors.Text, fontSize = small)
    }
    }
}

/**
 * Frosted glass: a blurred copy of [backdrop] (the screen's background art, lined up with where
 * this sits over it), a soft tint and a light edge. Without art, just the tint.
 */
@Composable
fun FrostedSurface(
    backdrop: String?,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    var at by remember { mutableStateOf(Offset.Zero) }
    var screen by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val blur = backdrop != null && android.os.Build.VERSION.SDK_INT >= 31
    Box(
        modifier
            .onGloballyPositioned {
                at = it.positionInRoot()
                screen = it.findRootCoordinates().size
            }
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
    ) {
        if (blur && screen.width > 0) {
            Box(Modifier.matchParentSize()) {
                coil.compose.AsyncImage(
                    model = android.net.Uri.parse(backdrop),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .layout { m, c ->
                            // The whole screen's worth of art, shifted so it lines up with the art behind.
                            val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(screen.width, screen.height))
                            layout(c.maxWidth, c.maxHeight) { p.place(-at.x.roundToInt(), -at.y.roundToInt()) }
                        }
                        .blur(28.dp),
                )
            }
            Box(Modifier.matchParentSize().background(Color(0x730D0D10)))
            Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.06f)))
        } else {
            Box(Modifier.matchParentSize().background(Color(0xCC1A1A1F)))
            Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.05f)))
        }
        content()
    }
}

/** Whether the top screen's frame rate can be read from the display driver (null until checked). */
object DriverFps {
    val available = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)

    suspend fun check(): Boolean = available.value ?: withContext(Dispatchers.IO) { ScreenControls.topFps() != null }.also { available.value = it }
}

/**
 * Feeds the FPS meter (Stats, and Multi's Stats panel) from the display driver once a second while
 * shown, so it needs no screen capture. Returns whether that works here (null until known).
 */
@Composable
fun rememberDriverFps(enabled: Boolean = true): Boolean? {
    val ok by DriverFps.available.collectAsState()
    LaunchedEffect(enabled) {
        if (!enabled || !DriverFps.check()) return@LaunchedEffect
        FpsMeter.resetIfStale()
        while (true) {
            withContext(Dispatchers.IO) { ScreenControls.topFps() }?.let { FpsMeter.push(it.roundToInt()) }
            delay(1_000)
        }
    }
    return ok
}
