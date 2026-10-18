package com.nerfherder.walkthor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerfherder.walkthor.SwipeGestureService
import com.nerfherder.walkthor.controls.ControlPrefs
import com.nerfherder.walkthor.controls.ControllerInput
import com.nerfherder.walkthor.controls.Layouts
import com.nerfherder.walkthor.controls.PadButton
import com.nerfherder.walkthor.controls.PadLayout
import com.nerfherder.walkthor.controls.Shape
import com.nerfherder.walkthor.controls.Side
import com.nerfherder.walkthor.controls.ThorInput
import com.nerfherder.walkthor.data.LaunchedGame
import android.view.KeyEvent
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val Line = Color.White.copy(alpha = 0.55f)
private val BodyFill = Color(0xFF141417)
private val ButtonDark = Color(0xFF26262B)

/**
 * Controls: the original controller for the game's system, drawn generically, with what each
 * button is on the Thor. Optionally lights up the buttons you press.
 */
@Composable
fun ControlsScreen(game: LaunchedGame, onBack: () -> Unit) {
    val context = LocalContext.current
    var layout by remember(game.player, game.file) { mutableStateOf(ControlPrefs.layoutFor(context, game.player, game.file)) }
    var version by remember { mutableIntStateOf(0) } // bumps when a mapping changes
    val mapping = remember(layout, version) {
        layout.buttons.associate { it.id to ControlPrefs.mapping(context, game.player, layout, it) }
    }
    var showPresses by remember { mutableStateOf(ControlPrefs.showPresses(context) && ControlPrefs.keysConsent(context)) }
    var askConsent by remember { mutableStateOf(false) }
    var watchProblem by remember { mutableStateOf<String?>(null) }
    var pickLayout by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PadButton?>(null) }
    val pressed by ControllerInput.pressed.collectAsState()
    val last by ControllerInput.last.collectAsState()

    // Watch presses only while this screen is open with the option on; the screen stays bright
    // then, since you're looking at it while you play.
    LaunchedEffect(showPresses) {
        AutoDim.wake()
        if (showPresses) {
            ControllerInput.watching.value = true
            AutoDim.screenDelayMs.value = 0L
            val ok = SwipeGestureService.instance?.get()?.setKeyWatch(true)
            watchProblem = when (ok) {
                null -> "Button presses need Accessibility access switched on (⋮ menu)."
                false -> "Switch WalkThor off and on in Android's Accessibility settings to let it see button presses."
                else -> null
            }
        } else {
            ControllerInput.watching.value = false
            AutoDim.screenDelayMs.value = null
            SwipeGestureService.instance?.get()?.setKeyWatch(false)
            watchProblem = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            ControllerInput.watching.value = false
            SwipeGestureService.instance?.get()?.setKeyWatch(false)
            AutoDim.screenDelayMs.value = null
        }
    }

    Box(Modifier.fillMaxSize().background(BookColors.Ink)) {
        Column(Modifier.fillMaxSize()) {
            // ---- top bar ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = BookColors.Text)
                }
                Column(Modifier.weight(1f)) {
                    Text("CONTROLS", style = EyebrowStyle)
                    Text(layout.name, color = BookColors.Text, fontSize = 15.sp, maxLines = 1)
                }
                TextButton(onClick = { pickLayout = true }) { Text("Layout", color = BookColors.TextMuted) }
                Toggle("Show presses", showPresses) { on ->
                    if (on && !ControlPrefs.keysConsent(context)) askConsent = true
                    else { showPresses = on; ControlPrefs.setShowPresses(context, on) }
                }
                Spacer(Modifier.width(8.dp))
            }
            // ---- the controller ----
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                PadDiagram(layout, mapping, pressed, onTap = { editing = it })
            }
            // ---- what you pressed, or how to change things ----
            val lastInput = last?.let { ControllerInput.inputFor(it) }
            val message = when {
                watchProblem != null -> watchProblem!!
                showPresses && lastInput != null -> {
                    val originals = layout.buttons.filter { mapping[it.id] == lastInput }.map { nameOf(it) }
                    "You pressed ${lastInput.label}" + if (originals.isEmpty()) " (not used here)" else " → ${originals.joinToString(", ")}"
                }
                showPresses -> "Press any button to see what it is."
                else -> "Tap a button to change which Thor button does it."
            }
            Text(
                message,
                color = if (watchProblem != null) Color(0xFFE0675A) else BookColors.TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp, top = 4.dp),
            )
        }
    }

    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            containerColor = BookColors.Surface,
            title = { Text("Show button presses?", fontFamily = SerifDisplay) },
            text = {
                Text(
                    "To light up the buttons you press, WalkThor's accessibility service briefly sees each button " +
                        "press before your game does, and passes it straight on. It only does this while this " +
                        "Controls screen is open with Show presses on. Nothing is recorded, stored or sent, and " +
                        "nothing you type elsewhere is read. You can turn it off here at any time.",
                    color = BookColors.TextMuted,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        askConsent = false
                        ControlPrefs.setKeysConsent(context, true)
                        ControlPrefs.setShowPresses(context, true)
                        showPresses = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                ) { Text("Agree & turn on", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { askConsent = false }) { Text("No thanks", color = BookColors.TextMuted) } },
        )
    }

    if (pickLayout) {
        AlertDialog(
            onDismissRequest = { pickLayout = false },
            containerColor = BookColors.Surface,
            title = { Text("Controller layout", fontFamily = SerifDisplay) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "WalkThor guessed this from the emulator. Pick another if it's wrong; it's remembered for this emulator.",
                        color = BookColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Layouts.ALL.forEach { l ->
                        Text(
                            l.name,
                            color = if (l.id == layout.id) BookColors.Text else BookColors.TextMuted,
                            fontWeight = if (l.id == layout.id) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    layout = l
                                    ControlPrefs.setLayout(context, game.player, l)
                                    pickLayout = false
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickLayout = false }) { Text("Close") } },
        )
    }

    editing?.let { button ->
        RemapDialog(
            button = button,
            current = mapping[button.id] ?: button.default,
            listening = showPresses && watchProblem == null,
            onPick = { input ->
                ControlPrefs.setMapping(context, game.player, layout, button, input)
                version++
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private fun nameOf(b: PadButton) = when (b.shape) {
    Shape.DPAD -> "D-pad"
    Shape.STICK -> if (b.label.isBlank()) "Stick" else "${b.label} stick".replace("L3 stick", "Left stick").replace("R3 stick", "Right stick")
    else -> b.label.ifBlank { b.id }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .background(if (on) BookColors.ButtonFill else Color.Transparent, RoundedCornerShape(16.dp))
            .border(1.dp, if (on) BookColors.ButtonFill else BookColors.Hairline, RoundedCornerShape(16.dp))
            .clickable { onChange(!on) }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) BookColors.Text else BookColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The generic controller with every button and its Thor equivalent; tap a button to change it. */
@Composable
private fun PadDiagram(
    layout: PadLayout,
    mapping: Map<String, ThorInput>,
    pressed: Set<Int>,
    onTap: (PadButton) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        // The controller lives in a 2:1 box, as large as fits, centred.
        val bw = min(w, h * 2f)
        val bh = bw / 2f
        val box = Rect(Offset((w - bw) / 2f, (h - bh) / 2f), Size(bw, bh))
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(layout, box) {
                    detectTapGestures { p ->
                        val hit = layout.buttons.minByOrNull { b ->
                            val c = centerOf(b, box)
                            hypot(p.x - c.x, p.y - c.y) / max(b.size * box.width * 0.75f, 28.dp.toPx())
                        }
                        if (hit != null) {
                            val c = centerOf(hit, box)
                            if (hypot(p.x - c.x, p.y - c.y) <= max(hit.size * box.width * 0.75f, 28.dp.toPx())) onTap(hit)
                        }
                    }
                }
        ) {
            drawBody(box)
            layout.buttons.forEach { b ->
                val input = mapping[b.id] ?: b.default
                val down = input.keys.any { it in pressed }
                drawButton(b, box, down, pressed, measurer)
            }
            layout.buttons.forEach { b -> drawBadge(b, box, mapping[b.id] ?: b.default, measurer) }
        }
    }
}

private fun centerOf(b: PadButton, box: Rect) = Offset(box.left + b.x * box.width, box.top + b.y * box.height)

/** A plain, generic controller body of WalkThor's own: a rounded bar with two grips. */
private fun DrawScope.drawBody(box: Rect) {
    val bw = box.width
    val bh = box.height
    val bar = Rect(box.left + 0.07f * bw, box.top + 0.17f * bh, box.right - 0.07f * bw, box.top + 0.74f * bh)
    val r = CornerRadius(0.18f * bh, 0.18f * bh)
    listOf(0.24f, 0.76f).forEach { gx ->
        drawOval(BodyFill, Offset(box.left + (gx - 0.13f) * bw, box.top + 0.5f * bh), Size(0.26f * bw, 0.48f * bh))
        drawOval(Line.copy(alpha = 0.25f), Offset(box.left + (gx - 0.13f) * bw, box.top + 0.5f * bh), Size(0.26f * bw, 0.48f * bh), style = Stroke(1.5f))
    }
    drawRoundRect(BodyFill, bar.topLeft, bar.size, r)
    drawRoundRect(Line.copy(alpha = 0.25f), bar.topLeft, bar.size, r, style = Stroke(1.5f))
}

private fun DrawScope.drawButton(b: PadButton, box: Rect, down: Boolean, pressed: Set<Int>, measurer: TextMeasurer) {
    val c = centerOf(b, box)
    val s = b.size * box.width
    val fill = if (down) Color.White else ButtonDark
    val ink = if (down) Color.Black else Color.White
    when (b.shape) {
        Shape.FACE, Shape.BIG_FACE -> {
            drawCircle(fill, s / 2f, c)
            drawCircle(Line, s / 2f, c, style = Stroke(1.5f))
            label(b.label, c, ink, s * if (b.label.length > 1) 0.32f else 0.48f, measurer)
        }
        Shape.PILL -> {
            val sz = Size(s * 1.5f, s * 0.75f)
            val tl = Offset(c.x - sz.width / 2f, c.y - sz.height / 2f)
            drawRoundRect(fill, tl, sz, CornerRadius(sz.height / 2f))
            drawRoundRect(Line, tl, sz, CornerRadius(sz.height / 2f), style = Stroke(1.5f))
            label(b.label, c, ink, s * 0.42f, measurer)
        }
        Shape.SHOULDER, Shape.TRIGGER -> {
            val sz = if (b.shape == Shape.SHOULDER) Size(s, s * 0.3f) else Size(s * 0.8f, s * 0.36f)
            val tl = Offset(c.x - sz.width / 2f, c.y - sz.height / 2f)
            drawRoundRect(fill, tl, sz, CornerRadius(sz.height / 2f))
            drawRoundRect(Line, tl, sz, CornerRadius(sz.height / 2f), style = Stroke(1.5f))
            label(b.label, c, ink, sz.height * 0.62f, measurer)
        }
        Shape.SMALL -> {
            val text = measurer.measure(b.label, TextStyle(fontSize = (s * 0.32f).toSp(), fontWeight = FontWeight.SemiBold))
            val sz = Size(max(s * 1.4f, text.size.width + s * 0.5f), s * 0.55f)
            val tl = Offset(c.x - sz.width / 2f, c.y - sz.height / 2f)
            drawRoundRect(fill, tl, sz, CornerRadius(sz.height / 2f))
            drawRoundRect(Line, tl, sz, CornerRadius(sz.height / 2f), style = Stroke(1.5f))
            label(b.label, c, ink, s * 0.32f, measurer)
        }
        Shape.STICK -> {
            drawCircle(ButtonDark.copy(alpha = 0.6f), s / 2f, c)
            drawCircle(Line, s / 2f, c, style = Stroke(1.5f))
            drawCircle(fill, s * 0.32f, c)
            drawCircle(Line, s * 0.32f, c, style = Stroke(1.5f))
            if (b.label.isNotBlank()) label(b.label, c, ink, s * 0.2f, measurer)
        }
        Shape.DPAD -> {
            val arm = s / 3f
            val dirs = listOf(
                KeyEvent.KEYCODE_DPAD_UP to Offset(0f, -arm),
                KeyEvent.KEYCODE_DPAD_DOWN to Offset(0f, arm),
                KeyEvent.KEYCODE_DPAD_LEFT to Offset(-arm, 0f),
                KeyEvent.KEYCODE_DPAD_RIGHT to Offset(arm, 0f),
            )
            // Centre square, then each arm (lit separately when the D-pad is the Thor's D-pad).
            drawRect(ButtonDark, Offset(c.x - arm / 2f, c.y - arm / 2f), Size(arm, arm))
            dirs.forEach { (code, d) ->
                val armDown = if (down && code in pressed) true else false
                val tl = Offset(c.x + d.x - arm / 2f, c.y + d.y - arm / 2f)
                drawRoundRect(if (armDown) Color.White else ButtonDark, tl, Size(arm, arm), CornerRadius(arm * 0.18f))
            }
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x - arm / 2f, c.y - arm * 1.5f); lineTo(c.x + arm / 2f, c.y - arm * 1.5f)
                lineTo(c.x + arm / 2f, c.y - arm / 2f); lineTo(c.x + arm * 1.5f, c.y - arm / 2f)
                lineTo(c.x + arm * 1.5f, c.y + arm / 2f); lineTo(c.x + arm / 2f, c.y + arm / 2f)
                lineTo(c.x + arm / 2f, c.y + arm * 1.5f); lineTo(c.x - arm / 2f, c.y + arm * 1.5f)
                lineTo(c.x - arm / 2f, c.y + arm / 2f); lineTo(c.x - arm * 1.5f, c.y + arm / 2f)
                lineTo(c.x - arm * 1.5f, c.y - arm / 2f); lineTo(c.x - arm / 2f, c.y - arm / 2f); close()
            }
            drawPath(path, Line, style = Stroke(1.5f))
        }
    }
}

private fun DrawScope.label(text: String, c: Offset, color: Color, px: Float, measurer: TextMeasurer) {
    if (text.isBlank()) return
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = px.toSp(), fontWeight = FontWeight.SemiBold))
    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
}

/**
 * The Thor equivalent of each button. Face buttons get a small diamond tucked against the
 * button's lower-right edge; other inputs get a name tag on the button's [Side]. Tags that would
 * just repeat the button's own name ("Start" on Start) are left out.
 */
private fun DrawScope.drawBadge(b: PadButton, box: Rect, input: ThorInput, measurer: TextMeasurer) {
    val c = centerOf(b, box)
    val s = b.size * box.width
    if (input == ThorInput.NONE) return
    if (input.isFace) {
        // Small enough not to cover the button's own letter on denser screens.
        val g = min(15.dp.toPx(), s * 0.42f).coerceAtLeast(9.dp.toPx())
        val r = when (b.shape) {
            Shape.PILL -> Offset(s * 0.7f, s * 0.32f)
            Shape.SHOULDER, Shape.TRIGGER, Shape.SMALL -> Offset(s * 0.55f, s * 0.15f)
            else -> Offset(s * 0.42f, s * 0.42f)
        }
        val center = c + r
        drawCircle(Color(0xFF0B0B0D), g * 0.72f, center)
        drawCircle(BookColors.Hairline, g * 0.72f, center, style = Stroke(1f))
        faceGlyph(center, g, input)
        return
    }
    if (input.short.equals(b.label, ignoreCase = true) || input.label.equals(b.label, ignoreCase = true)) return
    val textStyle = TextStyle(color = BookColors.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val text = measurer.measure(input.short, textStyle)
    // How far the button reaches from its centre towards the tag.
    val halfW = when (b.shape) {
        Shape.SHOULDER -> s * 0.5f
        Shape.TRIGGER -> s * 0.4f
        Shape.PILL -> s * 0.75f
        Shape.SMALL -> {
            val t = measurer.measure(b.label, TextStyle(fontSize = (s * 0.32f).toSp(), fontWeight = FontWeight.SemiBold))
            max(s * 1.4f, t.size.width + s * 0.5f) / 2f
        }
        Shape.DPAD -> s * 0.5f
        else -> s * 0.5f
    }
    val halfH = when (b.shape) {
        Shape.SHOULDER -> s * 0.15f
        Shape.TRIGGER -> s * 0.18f
        Shape.SMALL -> s * 0.28f
        Shape.PILL -> s * 0.38f
        else -> s * 0.5f
    }
    val gap = 6.dp.toPx()
    val pad = 5.dp.toPx()
    val bw = text.size.width + pad * 2
    val bh = text.size.height + pad
    val center = when (b.side) {
        Side.LEFT -> Offset(c.x - halfW - gap - bw / 2f, c.y)
        Side.RIGHT -> Offset(c.x + halfW + gap + bw / 2f, c.y)
        Side.TOP -> Offset(c.x, c.y - halfH - gap - bh / 2f)
        Side.BOTTOM -> Offset(c.x, c.y + halfH + gap + bh / 2f)
    }
    val tl = Offset(center.x - bw / 2f, center.y - bh / 2f)
    drawRoundRect(Color(0xFF0B0B0D), tl, Size(bw, bh), CornerRadius(bh / 2f))
    drawRoundRect(BookColors.Hairline, tl, Size(bw, bh), CornerRadius(bh / 2f), style = Stroke(1f))
    drawText(text, topLeft = Offset(center.x - text.size.width / 2f, center.y - text.size.height / 2f))
}

/** Four dots in a diamond with the Thor face button's position filled in. */
private fun DrawScope.faceGlyph(c: Offset, size: Float, input: ThorInput) {
    val r = size * 0.16f
    val d = size * 0.34f
    listOf(
        ThorInput.NORTH to Offset(0f, -d), ThorInput.EAST to Offset(d, 0f),
        ThorInput.SOUTH to Offset(0f, d), ThorInput.WEST to Offset(-d, 0f),
    ).forEach { (which, o) ->
        if (which == input) drawCircle(Color.White, r, c + o)
        else drawCircle(Color.White.copy(alpha = 0.45f), r, c + o, style = Stroke(1.2f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemapDialog(
    button: PadButton,
    current: ThorInput,
    listening: Boolean,
    onPick: (ThorInput?) -> Unit,
    onDismiss: () -> Unit,
) {
    // With Show presses on, pressing a Thor button assigns it.
    if (listening) {
        val count by ControllerInput.count.collectAsState()
        val start = remember { ControllerInput.count.value }
        LaunchedEffect(count) {
            if (count != start) ControllerInput.last.value?.let { ControllerInput.inputFor(it) }?.let(onPick)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BookColors.Surface,
        title = { Text("${nameOf(button)} on the Thor", fontFamily = SerifDisplay) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (listening) "Press the Thor button that does this, or pick it below."
                    else "Pick the Thor button that does this in your emulator.",
                    color = BookColors.TextMuted, fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThorInput.entries.forEach { input ->
                        val selected = input == current
                        Row(
                            Modifier
                                .background(if (selected) BookColors.ButtonFill else BookColors.SurfaceHigh, RoundedCornerShape(16.dp))
                                .clickable { onPick(input) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (input.isFace) {
                                Canvas(Modifier.size(14.dp)) { faceGlyph(center, size.minDimension, input) }
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(input.label, color = BookColors.Text, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { onPick(null) }) { Text("Reset", color = BookColors.TextMuted) } },
    )
}
