package com.nerfherder.walkthor.ui

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import android.widget.Toast
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Date

private val LowBattery = Color(0xFFE0675A)

/**
 * The time and battery level, for the Thor's bottom screen (which has no status bar of its own).
 * Follows the system 12/24-hour setting; shows a bolt while charging and turns red at 15% or less.
 */
@Composable
fun StatusClock(modifier: Modifier = Modifier, anchor: Boolean = true) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    var level by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }

    // Tick on the minute boundary so the clock never lags behind the system's.
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50)
        }
    }

    DisposableEffect(context) {
        fun read(intent: Intent?) {
            if (intent == null) return
            val l = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) level = l * 100 / s
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = read(intent)
        }
        // Battery updates are a sticky broadcast: registering returns the current state right away.
        read(
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val time = remember(now) { DateFormat.getTimeFormat(context).format(now) }
    val low = level in 0..15 && !charging
    val tint = if (low) LowBattery else BookColors.TextMuted

    val game by PlaySession.current.collectAsState()
    val showTimer by PlaySession.showTimer.collectAsState()

    // Report where the readout sits, so auto-dim can keep a full-brightness copy on top of the dimming.
    val token = remember { Any() }
    if (anchor) {
        DisposableEffect(token) {
            onDispose { if (AutoDim.statusOwner == token) AutoDim.statusBounds.value = null }
        }
    }
    val placed = if (anchor) Modifier.onGloballyPositioned {
        AutoDim.statusOwner = token
        AutoDim.statusBounds.value = it.boundsInRoot()
    } else Modifier


    val keeping by com.nerfherder.walkthor.data.Clips.buffering.collectAsState()
    val recordingClip by com.nerfherder.walkthor.data.Clips.recording.collectAsState()

    Row(modifier.then(placed), verticalAlignment = Alignment.CenterVertically) {
        // While clips are kept or recorded, so it's never on without you seeing it.
        if (keeping || recordingClip) ClipsBadge(recordingClip)
        val g = game
        if (showTimer && g != null && g.at > 0) {
            val played = playTime(now.time - g.at)
            Row(
                Modifier
                    .clickable { Toast.makeText(context, "Playing ${g.title}" + (g.player?.let { " · $it" } ?: ""), Toast.LENGTH_SHORT).show() }
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayGlyph()
                Spacer(Modifier.width(4.dp))
                Text(played, color = BookColors.Brass, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        Text(time, color = BookColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (level >= 0) {
            Spacer(Modifier.width(10.dp))
            BatteryGlyph(level, charging, tint)
            Spacer(Modifier.width(4.dp))
            Text("$level%", color = tint, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BatteryGlyph(level: Int, charging: Boolean, tint: Color) {
    Canvas(Modifier.size(width = 20.dp, height = 11.dp)) {
        val stroke = 1.2.dp.toPx()
        val nub = 2.dp.toPx()
        val body = Size(size.width - nub - stroke, size.height - stroke)
        drawRoundRect(
            color = tint,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = body,
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(stroke),
        )
        // The terminal nub.
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width - nub, size.height * 0.3f),
            size = Size(nub, size.height * 0.4f),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        // Charge level.
        val pad = stroke + 1.dp.toPx()
        val fullW = body.width - 2 * pad + stroke
        drawRoundRect(
            color = if (charging) BookColors.Brass else tint,
            topLeft = Offset(pad, pad),
            size = Size(fullW * (level.coerceIn(0, 100) / 100f), size.height - 2 * pad),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        if (charging) {
            val w = body.width
            val h = size.height
            val bolt = Path().apply {
                moveTo(w * 0.56f, h * 0.08f)
                lineTo(w * 0.30f, h * 0.56f)
                lineTo(w * 0.48f, h * 0.56f)
                lineTo(w * 0.40f, h * 0.94f)
                lineTo(w * 0.70f, h * 0.42f)
                lineTo(w * 0.52f, h * 0.42f)
                close()
            }
            drawPath(bolt, BookColors.Text)
        }
    }
}

/**
 * Library-style titles read naturally: "Legend of Zelda, The - The Wind Waker" becomes
 * "The Legend of Zelda - The Wind Waker".
 */
internal fun displayTitle(title: String): String {
    val m = Regex("""^(.+?), (The|A|An)(\s*[-:].*)?$""").find(title.trim()) ?: return title
    val (name, article, rest) = m.destructured
    return "$article $name$rest"
}

/**
 * The game a guide is for, from the guide's title: "Lanterns of Emberfall - Complete Walkthrough"
 * and "Moss & Marrow 100% Guide" become "Lanterns of Emberfall" and "Moss & Marrow".
 */
fun gameTitleOf(guideTitle: String): String {
    val base = guideTitle.substringBefore(" - ").substringBefore(" – ").substringBefore(": ").trim()
    val trimmed = base.replace(
        Regex("""(?i)\s+(?:(?:100%|complete|full|official|unofficial)\s+)*(?:walkthrough|guide|faq|strategy guide|guidebook|manual)s?\s*$"""),
        "",
    ).trim()
    return trimmed.ifEmpty { guideTitle.trim() }
}

/** "Dolphin Emulator" → "Dolphin"; names without the suffix are left alone. */
internal fun emulatorName(player: String): String =
    player.removeSuffix(" Emulator").removeSuffix(" emulator").trim().ifEmpty { player }

/** "45m", "1h 05m". */
private fun playTime(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) / 60_000L)
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${"%02d".format(minutes % 60)}m"
}

/** A small "play" triangle in brass, marking the play-time readout. */
@Composable
private fun PlayGlyph() {
    Canvas(Modifier.size(width = 8.dp, height = 9.dp)) {
        val p = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(0f, size.height)
            close()
        }
        drawPath(p, BookColors.Brass)
    }
}

/** A red dot with "REC" while recording, or "Clips" while the last seconds are being kept. */
@Composable
private fun ClipsBadge(recording: Boolean) {
    val context = LocalContext.current
    Row(
        Modifier
            .clickable {
                Toast.makeText(
                    context,
                    if (recording) "Recording a clip. Stop it in the Shots tab."
                    else "Keeping the last seconds of the top screen in memory. Nothing is saved unless you press Save.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(8.dp)) { drawCircle(Color(0xFFE0483C)) }
        Spacer(Modifier.width(5.dp))
        Text(if (recording) "REC" else "Clips", color = Color(0xFFE88A80), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
