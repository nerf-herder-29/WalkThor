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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nerfherder.walkthor.ScreenControls
import com.nerfherder.walkthor.StickLights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.roundToInt

/**
 * What the drawer shows when no game is running: a big clock, brightness, volume and the stick
 * lights, and your pinned apps. "Open WalkThor" goes on to the rest of the app (guides, library,
 * settings). Laid out for the bottom screen, scaled to its size.
 */
@Composable
fun HomeScreen(
    onOpenWalkThor: () -> Unit,
    onOpenApp: (String) -> Unit,
    /** Fixed apps and time, for screenshots. */
    previewApps: List<InstalledApp>? = null,
    previewTime: Pair<String, String>? = null,
) {
    val context = LocalContext.current
    var allApps by remember { mutableStateOf(false) }
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
    // The stick lights' colour runs through the whole screen (cream when they're off).
    var lightColor by remember { mutableStateOf(StickLights.color(context)) }
    val accent by androidx.compose.animation.animateColorAsState(
        if (lightColor == 0 || (!StickLights.available() && previewApps == null)) BookColors.Text else Color(StickLights.swatch(lightColor)),
        androidx.compose.animation.core.tween(450), label = "accent",
    )
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            // A soft glow in the light's colour from the top left and the bottom right.
            .drawBehind {
                drawRect(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.08f, 0f), radius = size.width * 0.5f,
                    ),
                )
                drawRect(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.13f), Color.Transparent),
                        center = Offset(size.width, size.height), radius = size.width * 0.55f,
                    ),
                )
            }
            // Taps on empty space stay here (nothing underneath reacts).
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // Everything is sized for a 620×540 dp screen and scaled to this one (sliders and all).
        val k = minOf(maxWidth.value / 620f, maxHeight.value / 540f).coerceIn(0.55f, 1.6f)
        val base = androidx.compose.ui.platform.LocalDensity.current
        val d: (Float) -> Dp = { it.dp }
        val t: (Float) -> TextUnit = { it.sp }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * k, base.fontScale),
        ) {
        Column(Modifier.fillMaxSize().padding(horizontal = d(22f)).padding(top = d(24f), bottom = d(20f))) {
            Row(Modifier.fillMaxWidth().weight(0.55f)) {
                Clock(Modifier.weight(0.42f), d, t, previewTime, accent, onOpenWalkThor)
                Spacer(Modifier.width(d(22f)))
                Levels(Modifier.weight(0.58f).align(Alignment.Top).glass().padding(d(15f)), d, t, accent, lightColor) { lightColor = it }
            }
            Spacer(Modifier.height(d(16f)))
            Apps(Modifier.weight(0.45f).fillMaxWidth().glass().padding(horizontal = d(14f), vertical = d(10f)), d, t, previewApps, onAll = { allApps = true }, onOpenApp = onOpenApp)
        }
        }
    }
}

@Composable
private fun Clock(modifier: Modifier, d: (Float) -> Dp, t: (Float) -> TextUnit, preview: Pair<String, String>?, accent: Color, onOpenWalkThor: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    var level by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) { now = Date(); delay(60_000L - System.currentTimeMillis() % 60_000L + 50) }
    }
    DisposableEffect(context) {
        fun read(intent: Intent?) {
            if (intent == null) return
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
    val ampm = if (preview != null) "" else if (h24) "" else remember(now) { DateFormat.format("a", now).toString() }
    val date = preview?.second ?: remember(now) { DateFormat.format("EEEE, MMM d", now).toString() }
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            // White at the top, fading into the light's colour at the foot of the numbers.
            Text(
                time, maxLines = 1,
                style = androidx.compose.ui.text.TextStyle(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.White, 0.55f to BookColors.Text, 1f to androidx.compose.ui.graphics.lerp(BookColors.Text, accent, 0.75f),
                    ),
                    fontSize = t(76f), fontWeight = FontWeight.SemiBold, fontFamily = DisplayFont, letterSpacing = (-1.5).sp, lineHeight = t(80f),
                    shadow = androidx.compose.ui.graphics.Shadow(accent.copy(alpha = 0.25f), blurRadius = 40f),
                ),
            )
            if (ampm.isNotEmpty()) Text(ampm, color = BookColors.TextMuted, fontSize = t(18f), modifier = Modifier.padding(start = d(6f), bottom = d(14f)))
        }
        Text(date, color = BookColors.TextMuted, fontSize = t(16f), maxLines = 1)
        if (level >= 0 || preview != null) {
            Row(Modifier.padding(top = d(14f)), verticalAlignment = Alignment.CenterVertically) {
                Battery(if (preview != null) 78 else level, d)
                Text(
                    "${if (preview != null) 78 else level}%" + if (charging) " · charging" else "",
                    color = BookColors.TextMuted, fontSize = t(14f), modifier = Modifier.padding(start = d(8f)),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Open WalkThor  ›", color = BookColors.Text, fontSize = t(14f), fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = d(14f)).clip(RoundedCornerShape(50))
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(50))
                .clickable(onClick = onOpenWalkThor).padding(horizontal = d(14f), vertical = d(8f)),
        )
    }
}

@Composable
private fun Battery(level: Int, d: (Float) -> Dp) {
    val c = BookColors.TextMuted
    Canvas(Modifier.size(d(20f), d(11f))) {
        val w = size.width - 2.dp.toPx()
        val sw = 1.2.dp.toPx()
        drawRoundRect(c, size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        val inner = (w - 4 * sw) * (level.coerceIn(0, 100) / 100f)
        drawRect(c, topLeft = Offset(2 * sw, 2 * sw), size = androidx.compose.ui.geometry.Size(inner, size.height - 4 * sw))
        drawRect(c, topLeft = Offset(w + 0.5.dp.toPx(), size.height * 0.3f), size = androidx.compose.ui.geometry.Size(1.5.dp.toPx(), size.height * 0.4f))
    }
}

/** Brightness (top, bottom or both), volume, and the stick lights. */
@Composable
private fun Levels(modifier: Modifier, d: (Float) -> Dp, t: (Float) -> TextUnit, accent: Color, color: Int, onColor: (Int) -> Unit) {
    val context = LocalContext.current
    val lightsHere = remember { StickLights.available() }
    var target by remember { mutableStateOf(ScreenControls.target(context)) }
    var canTop by remember { mutableStateOf(ScreenControls.canSetTop(context)) }
    var top by remember { mutableFloatStateOf(ScreenControls.top(context)) }
    var bottom by remember { mutableFloatStateOf(1f) }
    var volume by remember { mutableFloatStateOf(ScreenControls.volume(context)) }
    var dragging by remember { mutableStateOf(false) }
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d(10f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Light & Sound", color = BookColors.TextMuted, fontSize = t(14f), fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).padding(d(3f))) {
                ScreenControls.Target.entries.forEach { tg ->
                    val on = tg == target
                    Text(
                        tg.label.removeSuffix(" screen"), fontSize = t(12f),
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) onAccent(accent) else BookColors.TextMuted,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) accent else Color.Transparent)
                            .clickable { target = tg; ScreenControls.setTarget(context, tg) }
                            .padding(horizontal = d(10f), vertical = d(5f)),
                    )
                }
            }
        }
        if (target != ScreenControls.Target.BOTTOM && !canTop) {
            Labelled("Brightness", "Allow", t) {
                Text(
                    "Tap to let WalkThor change the brightness", color = BookColors.Text, fontSize = t(13f),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { ScreenControls.askTop(context) }.padding(vertical = d(6f)),
                )
            }
        } else {
            Labelled("Brightness", "${(shown * 100).roundToInt()}%", t) {
                ThinSlider(shown, accent = accent, onDrag = { dragging = it }, onChange = { v ->
                    when (target) {
                        ScreenControls.Target.TOP -> { top = v; ScreenControls.setTop(context, v) }
                        ScreenControls.Target.BOTTOM -> { bottom = v; ScreenControls.setBottom(context, v) }
                        ScreenControls.Target.BOTH -> { top = v; bottom = v; ScreenControls.setTop(context, v); ScreenControls.setBottom(context, v) }
                    }
                    if (follow && target != ScreenControls.Target.BOTTOM) lights = v
                }, modifier = Modifier.fillMaxWidth())
            }
        }
        Labelled("Volume", "${(volume * 100).roundToInt()}%", t) {
            ThinSlider(volume, accent = accent, onDrag = { dragging = it }, onChange = { v -> volume = v; ScreenControls.setVolume(context, v) }, modifier = Modifier.fillMaxWidth())
        }
        if (lightsHere) {
            Labelled("Stick lights", if (color == 0) "off" else if (follow) "follow the screens" else "${(lights * 100).roundToInt()}%", t) {
                if (!follow) ThinSlider(lights, accent = accent, onDrag = {}, onChange = { v -> lights = v; StickLights.setLevel(context, v) }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(d(9f)), modifier = Modifier.padding(top = d(4f))) {
                StickLights.COLORS.forEach { c ->
                    val on = c == color
                    Box(
                        Modifier.size(d(22f))
                            // The chosen colour glows.
                            .drawBehind { if (on && c != 0) drawCircle(Color(StickLights.swatch(c)).copy(alpha = 0.45f), radius = size.minDimension * 0.85f) }
                            .clip(CircleShape)
                            .background(if (c == 0) Color.Transparent else Color(StickLights.swatch(c)))
                            .border(if (on) 2.5.dp else 1.dp, if (on) Color.White else Color.White.copy(alpha = 0.3f), CircleShape)
                            .clickable { onColor(c); StickLights.setColor(context, c) },
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

@Composable
private fun Labelled(label: String, value: String, t: (Float) -> TextUnit, content: @Composable () -> Unit) {
    Column {
        Row {
            Text(label, color = BookColors.TextMuted, fontSize = t(13f))
            Spacer(Modifier.weight(1f))
            Text(value, color = BookColors.Text, fontSize = t(13f))
        }
        content()
    }
}

/** Your pinned apps, six to a row; "All apps" to pin more. */
@Composable
private fun Apps(
    modifier: Modifier, d: (Float) -> Dp, t: (Float) -> TextUnit, preview: List<InstalledApp>?,
    onAll: () -> Unit, onOpenApp: (String) -> Unit,
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
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Apps", color = BookColors.TextMuted, fontSize = t(14f), fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(
                "All apps  ›", color = BookColors.TextMuted, fontSize = t(14f),
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onAll).padding(horizontal = d(8f), vertical = d(4f)),
            )
        }
        if (apps.isEmpty()) {
            Text(
                if (loaded == null && preview == null) "Loading…" else "Tap All apps, then press and hold an app to pin it here.",
                color = BookColors.TextMuted, fontSize = t(14f), modifier = Modifier.padding(top = d(18f)),
            )
            return@Column
        }
        Spacer(Modifier.height(d(10f)))
        apps.take(12).chunked(6).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = d(8f))) {
                row.forEach { app ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        HomeApp(app, d, t) { onOpenApp(app.packageName) }
                    }
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun HomeApp(app: InstalledApp, d: (Float) -> Dp, t: (Float) -> TextUnit, onOpen: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(vertical = d(4f), horizontal = d(2f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val s = d(46f)
        if (app.icon != null) androidx.compose.foundation.Image(app.icon, contentDescription = null, modifier = Modifier.size(s))
        else Box(Modifier.size(s).background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)))
        Text(
            app.label, color = BookColors.Text, fontSize = t(12f), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = d(5f)).width(d(84f)),
        )
    }
}

/** Frosted glass: a faint light fill, a hairline edge. */
private fun Modifier.glass(): Modifier {
    val shape = RoundedCornerShape(16.dp)
    return clip(shape).background(Color.White.copy(alpha = 0.045f)).border(1.dp, Color.White.copy(alpha = 0.09f), shape)
}

/** Text on the light's colour: dark on light colours, white on dark ones. */
private fun onAccent(c: Color): Color = if (c.luminance() > 0.45f) Color(0xFF0A1414) else Color.White

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
