package com.nerfherder.walkthor.ui

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.nerfherder.walkthor.controls.PadButton
import com.nerfherder.walkthor.controls.Shape
import com.nerfherder.walkthor.controls.ThorPad
import com.nerfherder.walkthor.controls.keyName
import kotlin.math.max
import kotlin.math.min

private val Line = Color.White.copy(alpha = 0.55f)
private val BodyFill = Color(0xFF141417)
private val ButtonDark = Color(0xFF26262B)

/** Controls: the Thor's buttons, drawn generically, lighting up as you press them. */
@Composable
fun ControlsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showPresses by remember { mutableStateOf(ControlPrefs.showPresses(context) && ControlPrefs.keysConsent(context)) }
    var askConsent by remember { mutableStateOf(false) }
    var watchProblem by remember { mutableStateOf<String?>(null) }
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = BookColors.Text)
                }
                Column(Modifier.weight(1f)) {
                    Text("CONTROLS", style = EyebrowStyle)
                    Text("Thor buttons", color = BookColors.Text, fontSize = 15.sp, maxLines = 1)
                }
                Toggle("Show presses", showPresses) { on ->
                    if (on && !ControlPrefs.keysConsent(context)) askConsent = true
                    else { showPresses = on; ControlPrefs.setShowPresses(context, on) }
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                PadDiagram(pressed)
            }
            val message = when {
                watchProblem != null -> watchProblem!!
                showPresses && last != null -> {
                    val code = last!!
                    val known = ControllerInput.inputFor(code)
                    "You pressed ${known?.label ?: keyName(code)} · sent as ${keyName(code)}"
                }
                showPresses -> "Press any button. Sticks can't be shown (Android doesn't share them), and on some devices neither can the D-pad."
                else -> "Turn on Show presses to see the buttons light up as you play."
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
                    "To light up the buttons you press, WalkThor's accessibility service briefly sees each controller " +
                        "button press before your game does, and passes it straight on. It only does this while this " +
                        "Controls screen is open with Show presses on. Nothing is recorded, stored or sent, and " +
                        "nothing you type is read. You can turn it off here at any time.",
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

/** The Thor's buttons in a generic body, each lit while held. */
@Composable
private fun PadDiagram(pressed: Set<Int>) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        // The pad lives in a 2:1 box, as large as fits, centred.
        val bw = min(w, h * 2f)
        val bh = bw / 2f
        val box = Rect(Offset((w - bw) / 2f, (h - bh) / 2f), Size(bw, bh))
        Canvas(Modifier.fillMaxSize()) {
            drawBody(box)
            ThorPad.BUTTONS.forEach { b ->
                drawButton(b, box, b.input.keys.any { it in pressed }, pressed, measurer)
            }
        }
    }
}

private fun centerOf(b: PadButton, box: Rect) = Offset(box.left + b.x * box.width, box.top + b.y * box.height)

/** A plain, generic body of WalkThor's own: a rounded bar with two grips. */
private fun DrawScope.drawBody(box: Rect) {
    val bw = box.width
    val bh = box.height
    val bar = Rect(box.left + 0.07f * bw, box.top + 0.17f * bh, box.right - 0.07f * bw, box.top + 0.74f * bh)
    val r = CornerRadius(0.18f * bh, 0.18f * bh)
    listOf(0.24f, 0.76f).forEach { gx ->
        val tl = Offset(box.left + (gx - 0.13f) * bw, box.top + 0.5f * bh)
        val sz = Size(0.26f * bw, 0.48f * bh)
        drawOval(BodyFill, tl, sz)
        drawOval(Line.copy(alpha = 0.25f), tl, sz, style = Stroke(1.5f))
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
        Shape.FACE -> {
            drawCircle(fill, s / 2f, c)
            drawCircle(Line, s / 2f, c, style = Stroke(1.5f))
            label(b.label, c, ink, s * 0.48f, measurer)
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
            // The stick itself can't be seen moving; its press (L3/R3) lights the cap.
            drawCircle(ButtonDark.copy(alpha = 0.6f), s / 2f, c)
            drawCircle(Line, s / 2f, c, style = Stroke(1.5f))
            drawCircle(fill, s * 0.32f, c)
            drawCircle(Line, s * 0.32f, c, style = Stroke(1.5f))
        }
        Shape.DPAD -> {
            val arm = s / 3f
            drawRect(ButtonDark, Offset(c.x - arm / 2f, c.y - arm / 2f), Size(arm, arm))
            listOf(
                KeyEvent.KEYCODE_DPAD_UP to Offset(0f, -arm),
                KeyEvent.KEYCODE_DPAD_DOWN to Offset(0f, arm),
                KeyEvent.KEYCODE_DPAD_LEFT to Offset(-arm, 0f),
                KeyEvent.KEYCODE_DPAD_RIGHT to Offset(arm, 0f),
            ).forEach { (code, d) ->
                val tl = Offset(c.x + d.x - arm / 2f, c.y + d.y - arm / 2f)
                drawRoundRect(if (code in pressed) Color.White else ButtonDark, tl, Size(arm, arm), CornerRadius(arm * 0.18f))
            }
            val path = Path().apply {
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
