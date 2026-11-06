package com.nerfherder.walkthor.ui

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nerfherder.walkthor.ScreenControls
import com.nerfherder.walkthor.StickLights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * What the drawer shows when no game is running: a big clock and the battery, brightness, volume
 * and the stick lights as dials, and your pinned apps. "Open WalkThor" goes on to the rest of the
 * app (guides, library, settings). Laid out for a 620×540 dp screen and scaled to the real one.
 */
@Composable
fun HomeScreen(
    onOpenWalkThor: () -> Unit,
    onOpenApp: (String) -> Unit,
    /** Fixed apps, time and battery, for screenshots. */
    previewApps: List<InstalledApp>? = null,
    previewTime: Pair<String, String>? = null,
    /** Opens on Edit Home (for screenshots). */
    startEditing: Boolean = false,
) {
    var allApps by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(startEditing) }
    val context = LocalContext.current
    val config by HomePrefs.config(context).collectAsState()
    if (editing) {
        HomeEditor(config, onDone = { next -> if (next != null) HomePrefs.save(context, next); editing = false })
        return
    }
    if (allApps) {
        // Every app, to pin the ones you want here (press and hold an app).
        AppsScreen(
            topBar = {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "‹  Home", color = BookColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { allApps = false }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text("Press and hold an app to pin it to Home", color = BookColors.TextMuted, fontSize = 13.sp)
                }
            },
        )
        return
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            // Taps on empty space stay here (nothing underneath reacts).
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val k = minOf(maxWidth.value / 620f, maxHeight.value / 540f).coerceIn(0.55f, 1.6f)
        val base = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * k, base.fontScale),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 10.dp)) {
                // With six pinned apps or fewer they fit in one row, and the clock and dials get the
                // room the second row would have taken.
                val shown = config.order.filter { it in config.shown }
                // The apps sit in the dock now, so the clock and dials get the room, unless the
                // performance row is shown too.
                val roomy = HomeSection.DEVICE !in shown
                ClockRow(previewTime, roomy)
                // The sections you chose (Edit Home), in your order; the dock stays at the bottom.
                // Any spare height is shared out evenly between the sections, so nothing bunches up.
                val gap = @Composable { Spacer(Modifier.weight(1f).heightIn(min = 10.dp)) }
                shown.filter { it != HomeSection.APPS }.forEach { sec ->
                    gap()
                    when (sec) {
                        HomeSection.STATS -> DeviceChips(previewTime != null, config.stats, top = 0.dp)
                        HomeSection.GLANCE -> PlayAndBattery(previewTime != null)
                        // Beside play time and battery, the three sit on one line to make room.
                        HomeSection.LEVELS -> Dials(roomy, config.sliders, oneLine = HomeSection.GLANCE in shown)
                        HomeSection.DEVICE -> DeviceControls(compact = true)
                        HomeSection.APPS -> Unit
                    }
                }
                gap()
                Dock(
                    showApps = HomeSection.APPS in shown, preview = previewApps, onOpenApp = onOpenApp,
                    onAll = { allApps = true }, onEdit = { editing = true }, onWalkThor = onOpenWalkThor,
                )
            }
        }
    }
}

/** The time and date, and the battery as a ring. */
@Composable
private fun ClockRow(preview: Pair<String, String>?, roomy: Boolean) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    var level by remember { mutableIntStateOf(if (preview != null) 78 else -1) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) { now = Date(); delay(60_000L - System.currentTimeMillis() % 60_000L + 50) }
    }
    DisposableEffect(context) {
        fun read(intent: Intent?) {
            if (intent == null || preview != null) return
            val l = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) level = l * 100 / s
            val st = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        }
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, intent: Intent) = read(intent) }
        read(ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val h24 = remember { DateFormat.is24HourFormat(context) }
    val time = preview?.first ?: remember(now) { DateFormat.format(if (h24) "H:mm" else "h:mm", now).toString() }
    val ampm = if (h24) "" else if (preview != null) "AM" else remember(now) { DateFormat.format("a", now).toString() }
    val date = preview?.second ?: remember(now) { DateFormat.format("EEEE, MMM d", now).toString() }
    Column {
    // The time and date on the left, the battery level beside them on the right.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(time, color = BookColors.Text, fontSize = if (roomy) 60.sp else 50.sp, fontWeight = FontWeight.SemiBold, fontFamily = DisplayFont, letterSpacing = (-1.5).sp, maxLines = 1, lineHeight = if (roomy) 62.sp else 52.sp)
                if (ampm.isNotEmpty()) Text(ampm, color = BookColors.TextMuted, fontSize = 16.sp, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
            }
            Text(date, color = BookColors.TextMuted, fontSize = 15.sp, maxLines = 1)
        }
        if (level >= 0) {
            Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                val low = level <= 15 && !charging
                val ring = if (low) Color(0xFFE0614A) else BookColors.Text
                Canvas(Modifier.fillMaxSize()) {
                    val w = 4.5.dp.toPx()
                    val tl = Offset(w / 2, w / 2)
                    val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
                    drawArc(Color.White.copy(alpha = 0.1f), 0f, 360f, false, tl, sz, style = Stroke(w))
                    drawArc(ring, -90f, 360f * level / 100f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$level%", color = BookColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (charging) "charging" else "battery", color = BookColors.TextMuted, fontSize = 8.sp)
                }
            }
        }
    }
    }
}

/** The stats you chose for Home (up to four), refreshed every few seconds. */
@Composable
private fun DeviceChips(preview: Boolean, which: List<HomeStat>, top: androidx.compose.ui.unit.Dp = 10.dp) {
    val context = LocalContext.current
    var chips by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(preview, which) {
        if (preview) {
            val sample = mapOf(
                HomeStat.CPU to ("CPU" to "62°C"), HomeStat.GPU to ("GPU" to "58°C"), HomeStat.FAN to ("Fan" to "40%"),
                HomeStat.STORAGE to ("Internal" to "212 GB${PART_SD}480 GB"), HomeStat.MEMORY to ("Memory" to "5.1 / 12 GB"),
                HomeStat.PLAYED to ("Played today" to "47m"), HomeStat.BATTERY to ("Battery" to "34°C"), HomeStat.SPEED to ("CPU" to "2.84 GHz"),
            )
            chips = which.mapNotNull { sample[it] }
            return@LaunchedEffect
        }
        while (true) {
            chips = withContext(Dispatchers.IO) {
                val s = runCatching { StatsReader.read(context) }.getOrNull()
                val gb = 1024.0 * 1024 * 1024
                which.mapNotNull { stat ->
                    when (stat) {
                        HomeStat.CPU -> s?.cpuTempC?.let { "CPU" to "${it.roundToInt()}°C" }
                        HomeStat.GPU -> s?.gpuTempC?.let { "GPU" to "${it.roundToInt()}°C" }
                        HomeStat.FAN -> s?.fanPct?.let { "Fan" to if (it == 0) "off" else "$it%" }
                        HomeStat.STORAGE -> freeStorage(context)
                        HomeStat.MEMORY -> s?.takeIf { it.memTotal > 0 }?.let {
                            "Memory" to "%.1f / %d GB".format(java.util.Locale.US, (it.memTotal - it.memFree) / gb, Math.ceil(it.memTotal / gb).toInt())
                        }
                        HomeStat.PLAYED -> "Played today" to hoursMinutes(com.nerfherder.walkthor.PlayToday.minutes(context))
                        HomeStat.BATTERY -> s?.batteryTempC?.let { "Battery" to "${it.roundToInt()}°C" }
                        HomeStat.SPEED -> s?.cpuGhz?.let { "CPU" to "%.2f GHz".format(it) }
                    }
                }
            }
            delay(5_000)
        }
    }
    if (chips.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = top), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (label, value) ->
            Row(
                // Wider chips for longer text (storage with an SD card, say).
                Modifier.weight((label.length + value.length + (if (PART_SD in value) 9 else 6)).toFloat()).clip(RoundedCornerShape(50)).background(Color(0xFF16131A))
                    .border(1.dp, Color(0xFF2A262B), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(label, color = BookColors.TextMuted, fontSize = 11.sp, maxLines = 1)
                // A second name in the value (the SD card) is gray too.
                if (PART_SD in value) {
                    Text("  " + value.substringBefore(PART_SD), color = BookColors.Text, fontSize = 11.sp, maxLines = 1)
                    Text(" · SD ", color = BookColors.TextMuted, fontSize = 11.sp, maxLines = 1)
                    Text(value.substringAfter(PART_SD), color = BookColors.Text, fontSize = 11.sp, maxLines = 1)
                } else Text("  $value", color = BookColors.Text, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/** Marks where the SD card's amount starts in the storage chip's value. */
private const val PART_SD = "\u0000"

/**
 * Free space: the device's own storage, and an SD card's beside it when one's in
 * ("212 GB · SD 480 GB"), or "212 GB free" with no card.
 */
private fun freeStorage(context: Context): Pair<String, String>? {
    val gb = 1024.0 * 1024 * 1024
    fun free(path: String) = runCatching { android.os.StatFs(path).availableBytes }.getOrNull()
    val internal = free(android.os.Environment.getDataDirectory().path) ?: return null
    val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
    val sd = if (android.os.Build.VERSION.SDK_INT < 30) null else runCatching {
        sm?.storageVolumes.orEmpty()
            .filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
            .mapNotNull { v -> v.directory?.path?.let(::free) }
            .sum().takeIf { it > 0 }
    }.getOrNull()
    fun gbText(b: Long) = if (b >= 1000 * gb) "%.1f TB".format(b / (1024 * gb)) else "${(b / gb).roundToInt()} GB"
    // Each name in gray and each amount in cream: "Internal 212 GB · SD 480 GB".
    return if (sd == null) "Storage" to "${gbText(internal)} free"
    else "Internal" to "${gbText(internal)}$PART_SD${gbText(sd)}"
}

/** Brightness (top, bottom or both), volume and the stick lights, as three dials. */
@Composable
private fun Dials(roomy: Boolean, sliders: Boolean, oneLine: Boolean = false) {
    val context = LocalContext.current
    val lightsHere = remember { StickLights.available() }
    var target by remember { mutableStateOf(ScreenControls.target(context)) }
    var canTop by remember { mutableStateOf(ScreenControls.canSetTop(context)) }
    var top by remember { mutableFloatStateOf(ScreenControls.top(context)) }
    var bottom by remember { mutableFloatStateOf(1f) }
    var volume by remember { mutableFloatStateOf(ScreenControls.volume(context)) }
    var dragging by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(StickLights.color(context)) }
    var lights by remember { mutableFloatStateOf(StickLights.level(context)) }
    val follow = remember { StickLights.followsScreens(context) }
    DisposableEffect(Unit) {
        com.nerfherder.walkthor.AynSystem.liveStart(context)
        onDispose { com.nerfherder.walkthor.AynSystem.liveStop() }
    }
    // Keep up with changes made elsewhere (AYN's drawer, the volume buttons).
    LaunchedEffect(Unit) {
        bottom = withContext(Dispatchers.IO) { ScreenControls.bottom(context) }
        while (true) {
            if (!dragging) {
                canTop = ScreenControls.canSetTop(context)
                top = ScreenControls.top(context)
                volume = ScreenControls.volume(context)
                if (follow) lights = StickLights.level(context)
            }
            delay(1_500)
        }
    }
    val shown = if (target == ScreenControls.Target.BOTTOM) bottom else top
    val needsTop = target != ScreenControls.Target.BOTTOM && !canTop
    val lightColor = if (color == 0) Color.White.copy(alpha = 0.3f) else Color(StickLights.swatch(color))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF0D0B0E))
            .border(1.dp, Color(0xFF1F1C20), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Light & Sound", color = BookColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            // Which screen the brightness dial changes.
            ScreenControls.Target.entries.forEachIndexed { i, tg ->
                if (i > 0) Text("·", color = BookColors.TextMuted, fontSize = 12.sp)
                val on = tg == target
                Text(
                    tg.label.removeSuffix(" screen"), fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) BookColors.Text else BookColors.TextMuted,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { target = tg; ScreenControls.setTarget(context, tg) }
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                )
            }
        }
        if (oneLine) {
            // The three side by side, each a name, its value and a slider.
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                @Composable
                fun Part(label: String, value: String, content: @Composable () -> Unit) {
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(label, color = BookColors.TextMuted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            Text(value, color = BookColors.Text, fontSize = 11.sp, maxLines = 1)
                        }
                        Box(Modifier.height(30.dp), contentAlignment = Alignment.CenterStart) { content() }
                    }
                }
                Part("Brightness", if (needsTop) "" else "${(shown * 100).roundToInt()}") {
                    if (needsTop) Text(
                        "Tap to allow", color = BookColors.Text, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { ScreenControls.askTop(context) }.padding(vertical = 4.dp),
                    ) else ThinSlider(shown, onDrag = { dragging = it }, onChange = { v ->
                        when (target) {
                            ScreenControls.Target.TOP -> { top = v; ScreenControls.setTop(context, v) }
                            ScreenControls.Target.BOTTOM -> { bottom = v; ScreenControls.setBottom(context, v) }
                            ScreenControls.Target.BOTH -> { top = v; bottom = v; ScreenControls.setTop(context, v); ScreenControls.setBottom(context, v) }
                        }
                        if (follow && target != ScreenControls.Target.BOTTOM) lights = v
                    }, modifier = Modifier.fillMaxWidth())
                }
                Part("Volume", "${(volume * 100).roundToInt()}") {
                    ThinSlider(volume, onDrag = { dragging = it }, onChange = { v -> volume = v; ScreenControls.setVolume(context, v) }, modifier = Modifier.fillMaxWidth())
                }
                if (lightsHere) Part("Stick lights", if (color == 0) "off" else if (follow) "follow" else "${(lights * 100).roundToInt()}") {
                    if (color != 0 && !follow) ThinSlider(lights, onDrag = {}, onChange = { v -> lights = v; StickLights.setLevel(context, v) }, modifier = Modifier.fillMaxWidth(), fill = lightColor)
                }
            }
        } else if (sliders) {
            // The same three, as sliders.
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
                if (needsTop) {
                    SliderRow("Brightness", "Allow") {
                        Text(
                            "Tap to let WalkThor change the brightness", color = BookColors.Text, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { ScreenControls.askTop(context) }.padding(vertical = 6.dp),
                        )
                    }
                } else SliderRow("Brightness", "${(shown * 100).roundToInt()}%") {
                    ThinSlider(shown, onDrag = { dragging = it }, onChange = { v ->
                        when (target) {
                            ScreenControls.Target.TOP -> { top = v; ScreenControls.setTop(context, v) }
                            ScreenControls.Target.BOTTOM -> { bottom = v; ScreenControls.setBottom(context, v) }
                            ScreenControls.Target.BOTH -> { top = v; bottom = v; ScreenControls.setTop(context, v); ScreenControls.setBottom(context, v) }
                        }
                        if (follow && target != ScreenControls.Target.BOTTOM) lights = v
                    }, modifier = Modifier.fillMaxWidth())
                }
                SliderRow("Volume", "${(volume * 100).roundToInt()}%") {
                    ThinSlider(volume, onDrag = { dragging = it }, onChange = { v -> volume = v; ScreenControls.setVolume(context, v) }, modifier = Modifier.fillMaxWidth())
                }
                if (lightsHere) SliderRow("Stick lights", if (color == 0) "off" else if (follow) "follow" else "${(lights * 100).roundToInt()}%") {
                    if (color != 0 && !follow) ThinSlider(lights, onDrag = {}, onChange = { v -> lights = v; StickLights.setLevel(context, v) }, modifier = Modifier.fillMaxWidth())
                }
            }
        } else Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceAround) {
            Dial(
                if (needsTop) null else shown, "Brightness", BookColors.Text, icon = { SunIcon() },
                onTapWhenOff = { ScreenControls.askTop(context) }, offText = "Allow", onDrag = { dragging = it }, big = roomy,
            ) { v ->
                when (target) {
                    ScreenControls.Target.TOP -> { top = v; ScreenControls.setTop(context, v) }
                    ScreenControls.Target.BOTTOM -> { bottom = v; ScreenControls.setBottom(context, v) }
                    ScreenControls.Target.BOTH -> { top = v; bottom = v; ScreenControls.setTop(context, v); ScreenControls.setBottom(context, v) }
                }
                if (follow && target != ScreenControls.Target.BOTTOM) lights = v
            }
            Dial(volume, "Volume", BookColors.Text, icon = { SpeakerIcon() }, onDrag = { dragging = it }, big = roomy) { v ->
                volume = v; ScreenControls.setVolume(context, v)
            }
            if (lightsHere) {
                Dial(
                    if (color == 0) null else lights, if (follow) "Sticks · follow" else "Stick lights", lightColor,
                    icon = { Box(Modifier.size(12.dp).clip(CircleShape).background(lightColor)) },
                    offText = "off", onDrag = {}, enabled = !follow, big = roomy,
                ) { v -> lights = v; StickLights.setLevel(context, v) }
            }
        }
        if (lightsHere) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                StickLights.COLORS.forEach { c ->
                    val on = c == color
                    Box(
                        Modifier.size(if (roomy) 22.dp else 18.dp).clip(CircleShape)
                            .background(if (c == 0) Color.Transparent else Color(StickLights.swatch(c)))
                            .border(if (on) 2.5.dp else 1.dp, if (on) BookColors.Text else Color.White.copy(alpha = 0.3f), CircleShape)
                            .clickable { color = c; StickLights.setColor(context, c) },
                    ) {
                        // "Off": a circle with a line through it.
                        if (c == 0) Canvas(Modifier.fillMaxSize()) {
                            drawLine(Color.White.copy(alpha = 0.45f), Offset(size.width * 0.78f, size.height * 0.22f), Offset(size.width * 0.22f, size.height * 0.78f), 1.5.dp.toPx())
                        }
                    }
                }
            }
        }
    }
}

/** A slider on one line: its name, the slider, and its value. */
@Composable
private fun SliderRow(label: String, value: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = BookColors.TextMuted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(78.dp))
        Box(Modifier.weight(1f)) { content() }
        Text(value, color = BookColors.Text, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(54.dp))
    }
}

/** The dial's arc: three quarters of a circle, open at the bottom, from 135° round to 405°. */
private const val ARC_START = 135f
private const val ARC_SWEEP = 270f

/**
 * One dial: a three-quarter arc filled to [value] (0–1), the number in the middle, [label] under it.
 * Drag round the arc, or tap a spot on it, to set it. A null [value] shows [offText] instead (tap
 * for [onTapWhenOff]).
 */
@Composable
private fun Dial(
    value: Float?, label: String, color: Color, icon: @Composable () -> Unit,
    onTapWhenOff: (() -> Unit)? = null, offText: String = "", onDrag: (Boolean) -> Unit, enabled: Boolean = true, big: Boolean = false,
    onChange: (Float) -> Unit,
) {
    val change by rememberUpdatedState(onChange)
    val drag by rememberUpdatedState(onDrag)
    val live by rememberUpdatedState(value != null && enabled)
    val tapOff by rememberUpdatedState(onTapWhenOff)
    // A touch at [o] in a box of [w]×[h]: where on the arc that is, 0–1 (ends clamp).
    fun at(o: Offset, w: Float, h: Float): Float {
        var a = Math.toDegrees(atan2((o.y - h / 2).toDouble(), (o.x - w / 2).toDouble())).toFloat()
        if (a < 0) a += 360f
        var rel = a - ARC_START
        if (rel < 0) rel += 360f
        return if (rel > ARC_SWEEP) (if (rel > ARC_SWEEP + (360f - ARC_SWEEP) / 2) 0f else 1f) else rel / ARC_SWEEP
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(if (big) 104.dp else 74.dp)
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        if (live) change(at(o, size.width.toFloat(), size.height.toFloat())) else tapOff?.invoke()
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { if (live) drag(true) },
                        onDragEnd = { drag(false) },
                        onDragCancel = { drag(false) },
                    ) { ch, _ ->
                        if (!live) return@detectDragGestures
                        ch.consume()
                        change(at(ch.position, size.width.toFloat(), size.height.toFloat()))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 8.dp.toPx()
                val tl = Offset(w / 2, w / 2)
                val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
                drawArc(Color(0xFF1D1A1E), ARC_START, ARC_SWEEP, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                if (value != null && value > 0.001f) {
                    drawArc(color, ARC_START, ARC_SWEEP * value.coerceIn(0f, 1f), false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (value != null) "${(value * 100).roundToInt()}" else offText,
                    color = BookColors.Text, fontSize = if (value != null) (if (big) 28.sp else 21.sp) else 14.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Box(Modifier.padding(top = 2.dp).size(16.dp), contentAlignment = Alignment.Center) { icon() }
            }
        }
        Text(label, color = BookColors.TextMuted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * The dock along the bottom of Home: your pinned apps (scroll for more), then All apps, Edit and
 * WalkThor, standing on a glass shelf with their reflections under them.
 */
@Composable
private fun Dock(
    showApps: Boolean, preview: List<InstalledApp>?, onOpenApp: (String) -> Unit,
    onAll: () -> Unit, onEdit: () -> Unit, onWalkThor: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (preview == null) {
            AppCatalog.loadPins(context)
            if (AppCatalog.apps.value == null) AppCatalog.refresh(context)
        }
    }
    val loaded by AppCatalog.apps.collectAsState()
    val pins by AppCatalog.pinned.collectAsState()
    val apps = remember(loaded, pins, preview) {
        preview ?: run {
            val byPkg = loaded?.associateBy { it.packageName } ?: emptyMap()
            pins.mapNotNull { byPkg[it] }
        }
    }
    val icon = 48.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(icon + 22.dp)) {
        // The shelf: a slab seen from a little above, so its back edge is narrower than its front.
        Canvas(Modifier.fillMaxWidth().height(20.dp).align(Alignment.TopStart).padding(horizontal = 4.dp).offset(y = icon - 12.dp)) {
            val inset = 14.dp.toPx()
            val r = 6.dp.toPx()
            val w = size.width
            val h = size.height
            val slab = androidx.compose.ui.graphics.Path().apply {
                moveTo(inset + r, 0f); lineTo(w - inset - r, 0f)
                quadraticTo(w - inset, 0f, w - inset + r * 0.4f, r * 0.8f)
                lineTo(w, h - r); quadraticTo(w, h, w - r, h)
                lineTo(r, h); quadraticTo(0f, h, 0f, h - r)
                lineTo(inset - r * 0.4f, r * 0.8f); quadraticTo(inset, 0f, inset + r, 0f)
                close()
            }
            drawPath(slab, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xE62B2730), Color(0xE6141217))))
            drawPath(slab, Color(0xFF3A3540), style = Stroke(1.dp.toPx()))
            // A faint highlight along the back edge.
            drawLine(Color.White.copy(alpha = 0.08f), Offset(inset + r, 1.dp.toPx()), Offset(w - inset - r, 1.dp.toPx()), 1.dp.toPx())
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            Row(
                Modifier.widthIn(min = maxWidth).padding(horizontal = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
            ) {
                if (showApps) {
                    if (apps.isEmpty()) {
                        Text(
                            if (loaded == null && preview == null) "Loading…" else "Pin apps from All apps (press and hold one).",
                            color = BookColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 16.dp),
                        )
                    }
                    apps.forEach { app ->
                        ShelfItem(icon, app.label, onClick = { onOpenApp(app.packageName) }) {
                            if (app.icon != null) androidx.compose.foundation.Image(app.icon, contentDescription = null, modifier = Modifier.fillMaxSize())
                            else Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)))
                        }
                    }
                    Box(Modifier.padding(horizontal = 3.dp).width(1.dp).height(icon).background(Color.White.copy(alpha = 0.13f)))
                }
                ShelfItem(icon, "All apps", onClick = onAll) { DockGlyph(DockGlyphKind.GRID) }
                ShelfItem(icon, "Edit Home", onClick = onEdit) { DockGlyph(DockGlyphKind.EDIT) }
                ShelfItem(icon, "WalkThor", onClick = onWalkThor) { DockGlyph(DockGlyphKind.BOOK) }
            }
        }
    }
}

/** One spot on the shelf: the icon, with a faded reflection of its lower part under it. */
@Composable
private fun ShelfItem(size: androidx.compose.ui.unit.Dp, label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        Modifier.semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) { icon() }
        // The reflection: the icon upside down, fading out, cut short.
        Box(
            Modifier.padding(top = 3.dp).width(size).height(15.dp).clipToBounds()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen; alpha = 0.32f }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black, Color.Transparent)),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
        ) {
            Box(
                Modifier.wrapContentHeight(Alignment.Top, unbounded = true).requiredSize(size)
                    .graphicsLayer { scaleY = -1f },
            ) { icon() }
        }
    }
}

private enum class DockGlyphKind { GRID, EDIT, BOOK }

/** WalkThor's own dock buttons: a soft tile with a simple line drawing. */
@Composable
private fun DockGlyph(kind: DockGlyphKind) {
    val ink = BookColors.Text
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f))) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val w = size.width
            val h = size.height
            val sw = 1.8.dp.toPx()
            when (kind) {
                DockGlyphKind.GRID -> for (r in 0..2) for (c in 0..2) drawCircle(ink, sw * 1.15f, Offset(w * (0.12f + 0.38f * c), h * (0.12f + 0.38f * r)))
                DockGlyphKind.EDIT -> {
                    drawLine(ink, Offset(w * 0.2f, h * 0.8f), Offset(w * 0.8f, h * 0.2f), sw * 2.2f, StrokeCap.Round)
                    drawLine(ink, Offset(w * 0.12f, h * 0.95f), Offset(w * 0.6f, h * 0.95f), sw, StrokeCap.Round)
                }
                DockGlyphKind.BOOK -> {
                    val s = Stroke(sw, cap = StrokeCap.Round)
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.5f, h * 0.22f); quadraticTo(w * 0.3f, h * 0.08f, w * 0.04f, h * 0.14f); lineTo(w * 0.04f, h * 0.84f)
                        quadraticTo(w * 0.3f, h * 0.78f, w * 0.5f, h * 0.92f); quadraticTo(w * 0.7f, h * 0.78f, w * 0.96f, h * 0.84f)
                        lineTo(w * 0.96f, h * 0.14f); quadraticTo(w * 0.7f, h * 0.08f, w * 0.5f, h * 0.22f); lineTo(w * 0.5f, h * 0.92f)
                    }
                    drawPath(p, ink, style = s)
                }
            }
        }
    }
}

/** Your pinned apps, six to a row. */
@Composable
private fun Apps(modifier: Modifier, preview: List<InstalledApp>?, onOpenApp: (String) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (preview == null) {
            AppCatalog.loadPins(context)
            if (AppCatalog.apps.value == null) AppCatalog.refresh(context)
        }
    }
    val loaded by AppCatalog.apps.collectAsState()
    val pins by AppCatalog.pinned.collectAsState()
    val apps = remember(loaded, pins, preview) {
        preview ?: run {
            val byPkg = loaded?.associateBy { it.packageName } ?: emptyMap()
            pins.mapNotNull { byPkg[it] }
        }
    }
    Column(modifier) {
        if (apps.isEmpty()) {
            Text(
                if (loaded == null && preview == null) "Loading…" else "Tap All apps, then press and hold an app to pin it here.",
                color = BookColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp),
            )
            return@Column
        }
        // Six to a row, up to twelve; the rows spread out to fill the space.
        val shown = apps.take(12)
        val cols = 6
        val rows = shown.chunked(cols)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Only as many rows as fit in the room left (the rest are in All apps).
            val fit = (maxHeight.value / 66f).toInt().coerceIn(0, 2)
            val rows = rows.take(fit)
            val rowH = maxHeight / rows.size.coerceAtLeast(1)
            val icon = (rowH * 0.5f).coerceIn(42.dp, 58.dp)
            val label = (11f + (icon.value - 42f) / 34f * 4f).coerceIn(12f, 16f).sp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { app ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                HomeApp(app, icon, label) { onOpenApp(app.packageName) }
                            }
                        }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeApp(app: InstalledApp, s: androidx.compose.ui.unit.Dp, label: androidx.compose.ui.unit.TextUnit, onOpen: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(vertical = 2.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (app.icon != null) androidx.compose.foundation.Image(app.icon, contentDescription = null, modifier = Modifier.size(s))
        else Box(Modifier.size(s).background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)))
        Text(
            app.label, color = BookColors.Text, fontSize = label, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).width(s + 44.dp),
        )
    }
}

/**
 * Opens [pkg] from the drawer's Home: an emulator on the game screen, anything else on the bottom
 * screen ([drawerDisplay]). Returns whether it was started.
 */
fun openFromHome(context: Context, pkg: String, drawerDisplay: Int?): Boolean {
    val launch = context.packageManager.getLaunchIntentForPackage(pkg)
    if (launch == null) {
        Toast.makeText(context, "That app can't be opened", Toast.LENGTH_SHORT).show()
        return false
    }
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val target = if (com.nerfherder.walkthor.EmulatorWatch.isEmulator(context, pkg)) {
        // The game screen: whichever screen isn't the drawer's.
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        dm?.displays?.firstOrNull { it.displayId != drawerDisplay }?.displayId
    } else drawerDisplay
    val options = target?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
    return runCatching { context.startActivity(launch, options) }
        .onFailure { Toast.makeText(context, "Couldn't open that app", Toast.LENGTH_SHORT).show() }
        .isSuccess
}

/** The parts of Home under the clock that can be shown, hidden and moved. */
enum class HomeSection(val label: String) {
    STATS("Device stats"), GLANCE("Play time and battery"), LEVELS("Light & Sound"), DEVICE("Performance, fan and buttons"), APPS("Apps in the dock"),
}

/** The stats Home can show (up to four). */
enum class HomeStat(val label: String) {
    CPU("CPU temperature"), GPU("GPU temperature"), FAN("Fan"), STORAGE("Free storage"),
    MEMORY("Memory"), PLAYED("Played today"), BATTERY("Battery temp"), SPEED("CPU speed"),
}

/** How Home is laid out: which sections, in what order, which stats, and dials or sliders. */
data class HomeConfig(
    val order: List<HomeSection>,
    val shown: Set<HomeSection>,
    val stats: List<HomeStat>,
    val sliders: Boolean,
)

object HomePrefs {
    val DEFAULT = HomeConfig(
        order = listOf(HomeSection.STATS, HomeSection.GLANCE, HomeSection.LEVELS, HomeSection.DEVICE, HomeSection.APPS),
        shown = setOf(HomeSection.STATS, HomeSection.GLANCE, HomeSection.LEVELS, HomeSection.APPS),
        stats = listOf(HomeStat.CPU, HomeStat.STORAGE, HomeStat.MEMORY, HomeStat.PLAYED),
        sliders = false,
    )
    private var flow: kotlinx.coroutines.flow.MutableStateFlow<HomeConfig>? = null
    private fun prefs(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun config(c: Context): kotlinx.coroutines.flow.StateFlow<HomeConfig> = flow ?: kotlinx.coroutines.flow.MutableStateFlow(load(c)).also { flow = it }

    private fun load(c: Context): HomeConfig {
        val p = prefs(c)
        val order = p.getString("order", null)?.split(',')?.mapNotNull { n -> HomeSection.entries.firstOrNull { it.name == n } }?.distinct()
        val shown = p.getString("shown", null)?.split(',')?.mapNotNull { n -> HomeSection.entries.firstOrNull { it.name == n } }?.toSet()
        val stats = p.getString("stats", null)?.split(',')?.mapNotNull { n -> HomeStat.entries.firstOrNull { it.name == n } }?.distinct()
        // Play time and battery came later: it starts out shown, under the stats, for everyone.
        val fresh = p.getInt("v", 1) < 2
        val saved = order?.let { o -> if (fresh && HomeSection.GLANCE !in o) o.toMutableList().apply { add(indexOf(HomeSection.STATS) + 1, HomeSection.GLANCE) } else o }
        return HomeConfig(
            // Sections added later go at the end of a saved order.
            order = (saved ?: DEFAULT.order).let { o -> o + HomeSection.entries.filter { it !in o } },
            shown = shown?.let { if (fresh) it + HomeSection.GLANCE else it } ?: DEFAULT.shown,
            stats = stats?.take(4) ?: DEFAULT.stats,
            sliders = p.getBoolean("sliders", DEFAULT.sliders),
        )
    }

    fun save(c: Context, cfg: HomeConfig) {
        prefs(c).edit()
            .putString("order", cfg.order.joinToString(",") { it.name })
            .putString("shown", cfg.shown.joinToString(",") { it.name })
            .putString("stats", cfg.stats.take(4).joinToString(",") { it.name })
            .putBoolean("sliders", cfg.sliders)
            .putInt("v", 2)
            .apply()
        (flow ?: kotlinx.coroutines.flow.MutableStateFlow(cfg).also { flow = it }).value = cfg
    }
}

/** Edit Home: show, hide and reorder its sections, pick its stats, and dials or sliders. */
@Composable
private fun HomeEditor(start: HomeConfig, onDone: (HomeConfig?) -> Unit) {
    var cfg by remember { mutableStateOf(start) }
    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Edit Home", fontFamily = DisplayFont, fontSize = 24.sp, color = BookColors.Text, modifier = Modifier.weight(1f))
            Text(
                "Reset", color = BookColors.TextMuted, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { cfg = HomePrefs.DEFAULT }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                "Cancel", color = BookColors.TextMuted, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onDone(null) }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                "Done", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(BookColors.Text)
                    .clickable { onDone(cfg) }.padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            // ---- sections: on/off and order (the clock always stays on top) ----
            Column(Modifier.weight(1f)) {
                Text("Sections, top to bottom", color = BookColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("Under the clock. The dock always sits at the bottom.", color = BookColors.TextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                cfg.order.forEachIndexed { i, sec ->
                    val on = sec in cfg.shown
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF121014)).border(1.dp, Color(0xFF221F23), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Switch(
                            checked = on,
                            onCheckedChange = { v -> cfg = cfg.copy(shown = if (v) cfg.shown + sec else cfg.shown - sec) },
                            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = BookColors.Text, checkedThumbColor = Color.Black),
                        )
                        Text(sec.label, color = if (on) BookColors.Text else BookColors.TextMuted, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        MoveButton("↑", enabled = i > 0) {
                            cfg = cfg.copy(order = cfg.order.toMutableList().also { it[i] = it[i - 1]; it[i - 1] = sec })
                        }
                        MoveButton("↓", enabled = i < cfg.order.lastIndex) {
                            cfg = cfg.copy(order = cfg.order.toMutableList().also { it[i] = it[i + 1]; it[i + 1] = sec })
                        }
                    }
                }
            }
            // ---- stats and controls ----
            Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text("Stats (up to four)", color = BookColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                HomeStat.entries.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { st ->
                            val on = st in cfg.stats
                            val can = on || cfg.stats.size < 4
                            Text(
                                st.label, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (on) Color.Black else if (can) BookColors.Text else BookColors.TextMuted,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(50))
                                    .background(if (on) BookColors.Text else Color(0xFF16131A))
                                    .clickable(enabled = can) { cfg = cfg.copy(stats = if (on) cfg.stats - st else cfg.stats + st) }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                }
                Text("Brightness, volume and stick lights", color = BookColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
                Row(
                    Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.07f)).padding(3.dp),
                ) {
                    listOf(false to "Dials", true to "Sliders").forEach { (v, label) ->
                        val on = cfg.sliders == v
                        Text(
                            label, fontSize = 13.sp, textAlign = TextAlign.Center,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (on) Color.Black else BookColors.Text,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(50))
                                .background(if (on) BookColors.Text else Color.Transparent)
                                .clickable { cfg = cfg.copy(sliders = v) }.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveButton(arrow: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(arrow, color = if (enabled) BookColors.Text else BookColors.TextMuted.copy(alpha = 0.4f), fontSize = 18.sp)
    }
}
