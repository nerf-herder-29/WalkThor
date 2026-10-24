package com.nerfherder.walkthor.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerfherder.walkthor.Mirror
import com.nerfherder.walkthor.data.LaunchedGame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/** Frames counted from the top screen's capture, one number per second (the last minute of them). */
object FpsMeter {
    val counter = AtomicInteger(0)
    val history = MutableStateFlow<List<Int>>(emptyList())
    @Volatile private var lastPush = 0L
    fun push(n: Int) {
        lastPush = android.os.SystemClock.elapsedRealtime()
        history.value = (history.value + n).takeLast(60)
    }
    fun reset() { counter.set(0); history.value = emptyList() }
    /** Starts a fresh graph unless counting just paused for a moment (switching between All and Stats). */
    fun resetIfStale() {
        counter.set(0)
        if (android.os.SystemClock.elapsedRealtime() - lastPush > 5_000L) history.value = emptyList()
    }
}

/** One reading of the Thor's battery, heat, memory and CPU. Any value can be missing. */
data class StatsSnapshot(
    val watts: Float?,
    val charging: Boolean,
    val minutesLeft: Int?,
    val heatWord: String,
    /** 0 = cool, 1 = about to slow down from heat. */
    val heatFraction: Float?,
    val batteryTempC: Float?,
    val memFree: Long,
    val memTotal: Long,
    val cpuGhz: Float?,
)

/** Reads the stats from Android. Nothing here needs special access. */
object StatsReader {
    fun read(context: Context): StatsSnapshot {
        val bm = context.getSystemService(BatteryManager::class.java)
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val volts = (sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val charging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10f)

        // Current: most devices report microamps; some report milliamps.
        val raw = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0L
        val amps = when {
            raw == 0L || raw == Long.MIN_VALUE -> null
            abs(raw) > 20_000 -> abs(raw) / 1_000_000f
            else -> abs(raw) / 1_000f
        }
        val watts = if (amps != null && volts > 0f) amps * volts else null
        val counter = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 && it != Long.MIN_VALUE }
        val remainingWh = if (counter != null && volts > 0f) counter / 1_000_000f * volts else null
        val minutesLeft = if (!charging && remainingWh != null && watts != null && watts > 0.3f) (remainingWh / watts * 60).roundToInt() else null

        val pm = context.getSystemService(PowerManager::class.java)
        val heatStatus = if (Build.VERSION.SDK_INT >= 29) pm?.currentThermalStatus ?: 0 else 0
        val heatWord = when (heatStatus) {
            0 -> "Normal"
            1 -> "Warm"
            2 -> "Hot"
            3 -> "Very hot"
            else -> "Critical"
        }
        val headroom = if (Build.VERSION.SDK_INT >= 30) runCatching { pm?.getThermalHeadroom(0) }.getOrNull() else null
        val heatFraction = headroom?.takeIf { !it.isNaN() }?.coerceIn(0f, 1f)
            ?: tempC?.let { ((it - 20f) / 30f).coerceIn(0f, 1f) }

        val mem = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }

        val khz = (0 until 16).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong() }.getOrNull()
        }.maxOrNull()

        return StatsSnapshot(
            watts = watts,
            charging = charging,
            minutesLeft = minutesLeft,
            heatWord = heatWord,
            heatFraction = heatFraction,
            batteryTempC = tempC,
            memFree = mem.availMem,
            memTotal = mem.totalMem,
            cpuGhz = khz?.let { it / 1_000_000f },
        )
    }

    /** How many hours a full charge lasts at [watts], if the battery reports enough to tell. */
    fun fullChargeHours(context: Context, watts: Float): Float? {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val volts = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 && it != Long.MIN_VALUE }
        if (counter == null || volts <= 0f || level <= 0 || watts <= 0.3f) return null
        val fullWh = counter / 1_000_000f * volts / (level.toFloat() / scale)
        return fullWh / watts
    }
}

/**
 * Learns how much power each game uses, from a reading every half minute while it's played with
 * WalkThor open, so the Stats tab can say how long that game usually lasts on a charge.
 */
object PlayStats {
    private fun prefs(context: Context) = context.getSharedPreferences("stats", Context.MODE_PRIVATE)
    private fun key(title: String) = title.trim().lowercase()

    fun record(context: Context, title: String) {
        val s = StatsReader.read(context)
        val w = s.watts ?: return
        if (s.charging || w < 0.5f || w > 60f) return
        val p = prefs(context)
        val n = p.getInt("n/" + key(title), 0)
        val old = p.getFloat("w/" + key(title), w)
        val avg = if (n == 0) w else old * 0.9f + w * 0.1f
        p.edit().putFloat("w/" + key(title), avg).putInt("n/" + key(title), n + 1).apply()
    }

    /** The game's usual power draw, once there are a few minutes of readings. */
    fun usualWatts(context: Context, title: String): Float? {
        val p = prefs(context)
        return if (p.getInt("n/" + key(title), 0) >= 6) p.getFloat("w/" + key(title), 0f).takeIf { it > 0f } else null
    }
}

/** Fixed numbers for store screenshots. */
data class StatsPreview(val fps: List<Int>, val snapshot: StatsSnapshot, val fullChargeHours: Float?)

/**
 * The Stats tab: the top screen's frame rate (counted from the screen capture Mirror uses) with a
 * graph of the last minute, and the Thor's battery draw, heat, memory and CPU speed.
 */
@Composable
fun StatsScreen(
    game: LaunchedGame,
    onStartCapture: () -> Unit,
    topBar: (@Composable () -> Unit)? = null,
    preview: StatsPreview? = null,
) {
    val context = LocalContext.current
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val projection by Mirror.projection.collectAsState()
    val error by Mirror.error.collectAsState()
    val liveFps by FpsMeter.history.collectAsState()
    val fps = preview?.fps ?: liveFps
    val capturing = preview != null || projection != null
    val onMainDisplay = remember {
        preview == null &&
            (com.nerfherder.walkthor.AppState.activityDisplayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY &&
            com.nerfherder.walkthor.DisplayHelper.bottomDisplay(context)?.displayId != Display.DEFAULT_DISPLAY &&
            com.nerfherder.walkthor.AppState.activityDisplayId != null
    }

    // Counting needs the screen capture; ask for it as the tab opens (once per game's screens).
    LaunchedEffect(Unit) { if (preview == null && Mirror.projection.value == null && !onMainDisplay) onStartCapture() }
    // Stays bright while you watch it.
    DisposableEffect(Unit) {
        val holder = Any()
        AutoDim.wake()
        AutoDim.holdBright(holder, true)
        onDispose { AutoDim.holdBright(holder, false) }
    }

    var snap by remember { mutableStateOf(preview?.snapshot) }
    var recentWatts by remember { mutableStateOf(listOf<Float>()) }
    LaunchedEffect(preview) {
        if (preview != null) return@LaunchedEffect
        while (true) {
            val s = StatsReader.read(context)
            s.watts?.let { recentWatts = (recentWatts + it).takeLast(5) }
            snap = s
            delay(2_000)
        }
    }
    val s = snap
    // Power and time left, smoothed over the last ten seconds (the instant reading jumps around).
    val watts = preview?.snapshot?.watts ?: recentWatts.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val minutesLeft = when {
        preview != null -> preview.snapshot.minutesLeft
        s?.minutesLeft != null && s.watts != null && watts != null -> (s.minutesLeft * s.watts / watts).roundToInt()
        else -> null
    }
    val usualHours = preview?.fullChargeHours
        ?: PlayStats.usualWatts(context, game.title)?.let { StatsReader.fullChargeHours(context, it) }

    Column(Modifier.fillMaxSize().background(backdropOr(BookColors.Ink))) {
        topBar?.invoke()
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FpsPanel(
                    fps = fps,
                    capturing = capturing,
                    onMainDisplay = onMainDisplay,
                    error = error,
                    onStart = onStartCapture,
                    compact = compact,
                    modifier = Modifier.weight(1.15f).fillMaxHeight(),
                )
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            "Battery",
                            when {
                                s?.charging == true -> "Charging"
                                watts != null -> "%.1f".format(watts)
                                else -> "—"
                            },
                            if (s?.charging != true && watts != null) "W" else "",
                            when {
                                s?.charging == true -> "plugged in"
                                minutesLeft != null -> "${hoursMinutes(minutesLeft)} left"
                                else -> "power use"
                            },
                            meter = null,
                            compact = compact,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        StatTile(
                            "Heat",
                            s?.heatWord ?: "—",
                            "",
                            s?.batteryTempC?.let { "battery %.0f°C".format(it) } ?: "",
                            meter = s?.heatFraction,
                            compact = compact,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            "Memory",
                            s?.let { "%.1f".format(it.memFree / 1e9) } ?: "—",
                            if (s != null) "GB" else "",
                            s?.let { "free of %.0f".format(it.memTotal / 1e9) + if (compact) "" else " GB" } ?: "",
                            meter = s?.takeIf { it.memTotal > 0 }?.let { 1f - it.memFree.toFloat() / it.memTotal },
                            compact = compact,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        StatTile(
                            "CPU",
                            s?.cpuGhz?.let { "%.2f".format(it) } ?: "—",
                            if (s?.cpuGhz != null) "GHz" else "",
                            if (s != null && s.cpuGhz == null) "not shown on this device" else "fastest core",
                            meter = null,
                            compact = compact,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
            // How long this game usually lasts on a charge, then play time, the time and battery.
            val lasts = usualHours?.let {
                if (compact) "Lasts ~${hoursMinutes((it * 60).roundToInt())} on a charge"
                else "${displayTitle(game.title)} usually lasts about ${hoursMinutes((it * 60).roundToInt())} on a full charge"
            } ?: if (compact) "Learning battery life" else "Learning how long ${displayTitle(game.title)} lasts on a charge"
            Row(
                Modifier.fillMaxWidth().height(if (compact) 36.dp else 44.dp).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    lasts,
                    color = BookColors.Text.copy(alpha = 0.85f),
                    fontSize = if (compact) 11.sp else 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusClock(Modifier.padding(start = 8.dp, end = 10.dp), anchor = false)
            }
        }
    }
}

@Composable
private fun FpsPanel(
    fps: List<Int>,
    capturing: Boolean,
    onMainDisplay: Boolean,
    error: String?,
    onStart: () -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp)) {
        Text("Top screen", style = EyebrowStyle.copy(color = Color.White.copy(alpha = 0.6f)))
        when {
            onMainDisplay -> PanelNote("WalkThor is on the main screen, so there's no other screen to measure.")
            !capturing -> {
                PanelNote(
                    error ?: "The FPS meter counts how often the top screen changes, using Android's screen capture " +
                        "(the same as Mirror). Nothing is recorded or saved."
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
                ) { Text("Start FPS meter", fontWeight = FontWeight.SemiBold) }
            }
            else -> {
                val now = fps.lastOrNull()
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        rollingNumber(now)?.toString() ?: "—",
                        color = BookColors.Text,
                        fontWeight = FontWeight.Medium,
                        style = androidx.compose.ui.text.TextStyle(fontFamily = AppFont.family, fontFeatureSettings = "tnum"),
                        fontSize = if (compact) 52.sp else 84.sp,
                        lineHeight = if (compact) 54.sp else 86.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "FPS",
                        color = BookColors.Text.copy(alpha = 0.75f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (compact) 16.sp else 22.sp,
                        modifier = Modifier.padding(bottom = if (compact) 8.dp else 14.dp),
                    )
                }
                Text(
                    if (fps.isEmpty()) "Measuring…"
                    else "avg ${fps.average().roundToInt()}  ·  low ${fps.min()}  ·  last minute",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = if (compact) 11.sp else 13.sp,
                )
                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                FpsGraph(fps, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PanelNote(text: String) {
    Text(text, color = BookColors.TextMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
}

/** The last minute of frame rates: a line over a soft fade, on a faint baseline. */
@Composable
internal fun FpsGraph(fps: List<Int>, modifier: Modifier) {
    Canvas(modifier) {
        val top = maxOf(60, fps.maxOrNull() ?: 60).toFloat()
        // Just a faint baseline: the line itself does the talking.
        drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
        if (fps.size < 2) return@Canvas
        val step = size.width / 59f
        val x0 = size.width - step * (fps.size - 1)
        fun pt(i: Int) = Offset(x0 + step * i, size.height * (1f - (fps[i] / top).coerceIn(0f, 1f)))
        val line = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until fps.size) lineTo(pt(i).x, pt(i).y) }
        val fill = Path().apply { addPath(line); lineTo(pt(fps.size - 1).x, size.height); lineTo(x0, size.height); close() }
        val peak = (0 until fps.size).minOf { pt(it).y }
        drawPath(
            fill,
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), startY = peak, endY = size.height,
            ),
        )
        drawPath(line, Color(0xFFEDEDF2), style = Stroke(2.dp.toPx(), join = StrokeJoin.Round, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

@Composable
private fun StatTile(
    title: String,
    value: String,
    unit: String,
    sub: String,
    meter: Float?,
    compact: Boolean,
    modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp)) {
        Text(title, style = EyebrowStyle.copy(color = Color.White.copy(alpha = 0.6f), fontSize = if (compact) 10.sp else 12.sp))
        Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = BookColors.Text,
                fontWeight = FontWeight.SemiBold,
                style = androidx.compose.ui.text.TextStyle(fontFamily = AppFont.family, fontFeatureSettings = "tnum"),
                fontSize = if (compact) 16.sp else 24.sp,
                lineHeight = if (compact) 18.sp else 28.sp,
                maxLines = 1,
                softWrap = false,
            )
            if (unit.isNotEmpty()) {
                Text(
                    " $unit",
                    color = BookColors.Text.copy(alpha = 0.75f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (compact) 10.sp else 13.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(bottom = if (compact) 2.dp else 3.dp),
                )
            }
        }
        Text(
            sub,
            color = BookColors.Text.copy(alpha = 0.7f),
            fontSize = if (compact) 10.sp else 12.sp,
            // Space Grotesk runs a little wide: tighten it on a crowded screen so "2h 40m left" fits.
            letterSpacing = if (compact) (-0.5).sp else 0.sp,
            lineHeight = if (compact) 12.sp else 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        if (meter != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
            ) {
                Box(Modifier.fillMaxWidth(meter.coerceIn(0.02f, 1f)).fillMaxHeight().background(Color(0xFFEDEDF2)))
            }
        }
    }
}

internal fun hoursMinutes(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

/** [value], counting smoothly to each new reading instead of jumping (null stays null). */
@Composable
internal fun rollingNumber(value: Int?): Int? {
    if (value == null) return null
    val shown by androidx.compose.animation.core.animateIntAsState(
        value, androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "fps",
    )
    return shown
}
