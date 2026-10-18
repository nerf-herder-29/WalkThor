package com.nerfherder.walkthor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
import com.nerfherder.walkthor.controls.FaceStyle
import com.nerfherder.walkthor.controls.ThorPad
import com.nerfherder.walkthor.controls.buttonLabel
import com.nerfherder.walkthor.controls.keyName
import com.nerfherder.walkthor.data.LaunchedGame
import kotlin.math.min

private val Line = Color.White.copy(alpha = 0.55f)
private val ButtonDark = Color(0xFF26262B)

/**
 * Face Buttons: the four face buttons, labelled the way the game's system labels them (shapes for
 * PlayStation, letters for Xbox and Nintendo), each at the same spot as the Thor button that
 * presses it. With Show presses on, the one you press lights up.
 */
@Composable
fun ControlsScreen(game: LaunchedGame?, onBack: () -> Unit) {
    val context = LocalContext.current
    var style by remember(game?.player, game?.file) { mutableStateOf(ControlPrefs.faceStyle(context, game?.player, game?.file)) }
    // Presses are always shown here, once you've agreed to the explanation (asked on first open).
    var agreed by remember { mutableStateOf(ControlPrefs.keysConsent(context)) }
    val showPresses = agreed
    var watchProblem by remember { mutableStateOf<String?>(null) }
    val pressed by ControllerInput.pressed.collectAsState()
    val last by ControllerInput.last.collectAsState()
    val faceCodes = remember { ThorPad.FACE_ORDER.associateWith { ControlPrefs.faceCode(context, it) } }

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
                Text("FACE BUTTONS", style = EyebrowStyle, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
            }
            // Which system's labels: guessed from the emulator, remembered if you change it.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                FaceStyle.entries.filter { it != FaceStyle.THOR }.forEach { s ->
                    Toggle(s.title, s == style) {
                        style = s
                        ControlPrefs.setFaceStyle(context, game?.player, s)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                FaceDiagram(style, faceCodes, pressed, null)
            }
            val message = when {
                watchProblem != null -> watchProblem!!
                showPresses && last != null -> "You pressed ${buttonLabel(last!!)} · sent as ${keyName(last!!)}"
                else -> "Press A, B, X or Y on the Thor to see which button it is here."
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

    if (!agreed) {
        AlertDialog(
            onDismissRequest = onBack,
            containerColor = BookColors.Surface,
            title = { Text("Light up button presses?", fontFamily = SerifDisplay) },
            text = {
                Text(
                    "To light up the buttons you press, WalkThor's accessibility service briefly sees each controller " +
                        "button press before your game does, and passes it straight on. It only does this while this " +
                        "Face Buttons screen is open. Nothing is recorded, stored or sent, and nothing you type is read.",
                    color = BookColors.TextMuted,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        ControlPrefs.setKeysConsent(context, true)
                        agreed = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                ) { Text("Agree", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = onBack) { Text("Not now", color = BookColors.TextMuted) } },
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

/**
 * The four face buttons in a diamond, as large as fits. Each position shows the system's label,
 * lights when the Thor button at that position is held, and names that Thor button underneath.
 */
@Composable
private fun FaceDiagram(style: FaceStyle, faceCodes: Map<String, Int>, pressed: Set<Int>, asking: String?) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        val unit = min(size.width / 4.6f, size.height / 3.6f)
        val r = unit * 0.6f
        val center = Offset(size.width / 2f, size.height / 2f)
        val spots = mapOf(
            "top" to Offset(0f, -unit), "right" to Offset(unit, 0f),
            "bottom" to Offset(0f, unit), "left" to Offset(-unit, 0f),
        )
        ThorPad.FACE_ORDER.forEachIndexed { i, pos ->
            val c = center + spots.getValue(pos)
            val code = faceCodes[pos]
            val down = code != null && code in pressed
            drawCircle(if (down) Color.White else ButtonDark, r, c)
            drawCircle(Line, r, c, style = Stroke(2.dp.toPx()))
            val ink = if (down) Color.Black else Color.White
            val label = style.labels?.get(i) ?: code?.let { buttonLabel(it) } ?: "?"
            when (label) {
                "triangle", "circle", "cross", "square" -> drawSymbol(label, c, r * 0.46f, ink)
                else -> {
                    val t = measurer.measure(label, TextStyle(color = ink, fontSize = (r * 0.85f).toSp(), fontWeight = FontWeight.SemiBold))
                    drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - t.size.height / 2f))
                }
            }
            if (pos == asking) drawCircle(Color.White, r * 1.18f, c, style = Stroke(3.dp.toPx()))
            // Which Thor button this is, unless the label already says it.
            if (style != FaceStyle.THOR && code != null) {
                val thor = buttonLabel(code)
                if (!thor.equals(label, ignoreCase = true)) {
                    val t = measurer.measure("Thor $thor", TextStyle(color = BookColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                    // Outward from the diamond, so it never sits between two buttons.
                    val gap = 6.dp.toPx()
                    val tl = when (pos) {
                        "top" -> Offset(c.x - t.size.width / 2f, c.y - r - gap - t.size.height)
                        "bottom" -> Offset(c.x - t.size.width / 2f, c.y + r + gap)
                        "left" -> Offset(c.x - r - gap - t.size.width, c.y - t.size.height / 2f)
                        else -> Offset(c.x + r + gap, c.y - t.size.height / 2f)
                    }
                    drawText(t, topLeft = tl)
                }
            }
        }
    }
}

/** Plain outline shapes for the PlayStation-style buttons (no colours). */
private fun DrawScope.drawSymbol(kind: String, c: Offset, s: Float, color: Color) {
    val w = s * 0.2f
    when (kind) {
        "triangle" -> {
            val p = Path().apply {
                moveTo(c.x, c.y - s * 0.95f)
                lineTo(c.x + s * 0.95f, c.y + s * 0.7f)
                lineTo(c.x - s * 0.95f, c.y + s * 0.7f)
                close()
            }
            drawPath(p, color, style = Stroke(w))
        }
        "circle" -> drawCircle(color, s * 0.85f, c, style = Stroke(w))
        "cross" -> {
            val d = s * 0.75f
            drawLine(color, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), w, StrokeCap.Round)
            drawLine(color, Offset(c.x + d, c.y - d), Offset(c.x - d, c.y + d), w, StrokeCap.Round)
        }
        "square" -> {
            val d = s * 0.75f
            drawRect(color, Offset(c.x - d, c.y - d), Size(d * 2, d * 2), style = Stroke(w))
        }
    }
}
