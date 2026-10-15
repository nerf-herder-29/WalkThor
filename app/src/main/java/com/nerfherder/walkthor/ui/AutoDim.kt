package com.nerfherder.walkthor.ui
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column

import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Rect
import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerfherder.walkthor.data.LaunchedGame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Dims WalkThor after a while without touches or button presses, so the bottom screen goes
 * nearly black while you play on the top one (less battery, less OLED burn-in).
 */
object AutoDim {
    /** Choices offered in settings, in milliseconds; 0 = off. */
    val OPTIONS = listOf(0L, 30_000L, 60_000L, 120_000L, 300_000L)

    val delayMs = MutableStateFlow(0L)
    /**
     * A screen-specific delay that replaces [delayMs] while that screen is up. The Now playing
     * screen (a game with no guide) uses a short one, since there's nothing on it to read.
     */
    val screenDelayMs = MutableStateFlow<Long?>(null)
    /** How long the Now playing screen (no guide) stays bright. */
    const val NO_GUIDE_DELAY_MS = 5_000L
    /** How dark the dimming gets: 0.5 = half, 1.0 = fully black (the status readout always stays bright). */
    val level = MutableStateFlow(DEFAULT_LEVEL)
    /** True while the user is adjusting [level], to show it live. */
    val previewing = MutableStateFlow(false)
    const val MIN_LEVEL = 0.5f
    const val DEFAULT_LEVEL = 0.95f
    /** Where the time/play-time/battery readout is on screen (kept bright while dimmed). */
    val statusBounds = MutableStateFlow<Rect?>(null)

    @Volatile internal var statusOwner: Any? = null
    val dimmed = MutableStateFlow(false)
    /**
     * True once the screen has stayed dimmed for [REST_AFTER_MS]: rendered pages and cover images
     * are let go (nothing can be seen under the dim anyway) and come back when you wake it.
     */
    val resting = MutableStateFlow(false)
    /** Set by the Now playing screen when the game has a guide: the dimmed screen offers it. */
    val openGuide = MutableStateFlow<(() -> Unit)?>(null)
    const val REST_AFTER_MS = 30_000L
    @Volatile private var lastInteraction = SystemClock.elapsedRealtime()

    fun label(ms: Long) = when (ms) {
        0L -> "Off"
        in 1 until 60_000L -> "${ms / 1000} seconds"
        60_000L -> "1 minute"
        else -> "${ms / 60_000L} minutes"
    }

    /** "Off", "30 sec", "1 min" — for the compact chips in settings. */
    fun shortLabel(ms: Long) = when {
        ms == 0L -> "Off"
        ms < 60_000L -> "${ms / 1000} sec"
        else -> "${ms / 60_000L} min"
    }

    fun init(context: Context) {
        val p = context.getSharedPreferences("dim", Context.MODE_PRIVATE)
        delayMs.value = p.getLong("delay", 0L)
        level.value = p.getFloat("level", DEFAULT_LEVEL).coerceIn(MIN_LEVEL, 1f)
    }

    fun setLevel(context: Context, value: Float) {
        level.value = value.coerceIn(MIN_LEVEL, 1f)
        context.getSharedPreferences("dim", Context.MODE_PRIVATE).edit().putFloat("level", level.value).apply()
    }

    fun setDelay(context: Context, ms: Long) {
        delayMs.value = ms
        context.getSharedPreferences("dim", Context.MODE_PRIVATE).edit().putLong("delay", ms).apply()
        wake()
    }

    /**
     * Records a touch or button press. Returns true if the screen was dimmed, i.e. this input only
     * woke it and shouldn't do anything else.
     */
    fun interact(): Boolean {
        lastInteraction = SystemClock.elapsedRealtime()
        val wasDimmed = dimmed.value
        dimmed.value = false
        return wasDimmed
    }

    fun wake() { interact() }

    internal fun idleFor() = SystemClock.elapsedRealtime() - lastInteraction
}

/** Watches for inactivity and draws the dimming layer over everything when it's time. */
@Composable
fun AutoDimOverlay() {
    val settingDelay by AutoDim.delayMs.collectAsState()
    val screenDelay by AutoDim.screenDelayMs.collectAsState()
    val delayMs = screenDelay ?: settingDelay
    val dimmed by AutoDim.dimmed.collectAsState()

    // After a while dimmed, free the memory held by what's under the dim, for the game's benefit.
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(dimmed) {
        if (!dimmed) { AutoDim.resting.value = false; return@LaunchedEffect }
        delay(AutoDim.REST_AFTER_MS)
        AutoDim.resting.value = true
        delay(500) // let pages and covers drop their images first
        com.nerfherder.walkthor.data.PdfSource.trimAll()
        runCatching { coil.Coil.imageLoader(context).memoryCache?.clear() }
    }

    LaunchedEffect(delayMs) {
        if (delayMs <= 0L) { AutoDim.dimmed.value = false; return@LaunchedEffect }
        while (true) {
            val left = delayMs - AutoDim.idleFor()
            if (left <= 0) {
                AutoDim.dimmed.value = true
                delay(1_000)
            } else {
                delay(left.coerceAtMost(1_000))
            }
        }
    }

    val level by AutoDim.level.collectAsState()
    val previewing by AutoDim.previewing.collectAsState()
    val target = when {
        previewing -> level
        dimmed -> level
        else -> 0f
    }
    val alpha by animateFloatAsState(target, tween(if (previewing) 0 else if (dimmed) 400 else 150), label = "dim")
    val status by AutoDim.statusBounds.collectAsState()

    if (alpha <= 0.001f) return
    Box(
        Modifier
            .fillMaxSize()
            // While dimmed, swallow the waking touch so it doesn't also turn a page.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(Color.Black))
        // The time, play time and battery stay at full brightness, drawn exactly over the originals.
        status?.let { b ->
            StatusClock(Modifier.offset { IntOffset(b.left.roundToInt(), b.top.roundToInt()) }, anchor = false)
        }
        // While dimmed during a game: what's playing, centred on the black screen, in full.
        val game by PlaySession.current.collectAsState()
        val g = game
        if (g != null && !previewing) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 32.dp)
                    .graphicsLayer { this.alpha = (alpha / level.coerceAtLeast(0.01f)).coerceIn(0f, 1f) }
                    // A soft pool of black so the dimmed page doesn't show through the letters.
                    .drawBehind {
                        drawRect(
                            androidx.compose.ui.graphics.Brush.radialGradient(
                                listOf(Color.Black, Color.Black.copy(alpha = 0.85f), Color.Transparent),
                                center = center,
                                radius = size.maxDimension * 0.75f,
                            ),
                            topLeft = androidx.compose.ui.geometry.Offset(-size.width * 0.25f, -size.height * 0.6f),
                            size = androidx.compose.ui.geometry.Size(size.width * 1.5f, size.height * 2.2f),
                        )
                    }
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("NOW PLAYING", style = EyebrowStyle.copy(color = BookColors.Brass.copy(alpha = 0.7f)))
                Spacer(Modifier.height(8.dp))
                Text(
                    displayTitle(g.title),
                    color = BookColors.Text.copy(alpha = 0.85f),
                    fontFamily = SerifDisplay,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    textAlign = TextAlign.Center,
                )
                g.player?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(emulatorName(it), color = BookColors.TextMuted, fontSize = 14.sp)
                }
                // The game has a guide: open it straight from the dimmed screen. Acts on touch
                // down, since that same touch also wakes the screen and removes this layer.
                val open by AutoDim.openGuide.collectAsState()
                open?.let { action ->
                    Spacer(Modifier.height(22.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(BookColors.Brass.copy(alpha = 0.85f))
                            .pointerInput(action) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    action()
                                }
                            }
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                    ) {
                        Text("Open guide", color = Color(0xFF1E1709), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
            }
        }
        // Performance check readout (⋮ → Performance check).
        val perfOn by com.nerfherder.walkthor.PerfCheck.enabled.collectAsState()
        if (perfOn) {
            val isTop by com.nerfherder.walkthor.PerfCheck.walkThorIsTop.collectAsState()
            val since by com.nerfherder.walkthor.PerfCheck.since.collectAsState()
            val frames by com.nerfherder.walkthor.PerfCheck.recentFrames.collectAsState()
            val secs = ((android.os.SystemClock.elapsedRealtime() - since) / 1000).coerceAtLeast(0)
            val (label, color) = when (isTop) {
                true -> "TOP APP: WalkThor (the game is NOT getting top priority)" to Color(0xFFE0675A)
                false -> "TOP APP: not WalkThor (the game can have top priority)" to Color(0xFF7FC48A)
                null -> "TOP APP: unknown yet" to BookColors.TextMuted
            }
            Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Text("PERFORMANCE CHECK", style = EyebrowStyle.copy(color = BookColors.TextMuted))
                Spacer(Modifier.height(4.dp))
                Text(label, color = color, fontSize = 13.sp)
                Text("for ${secs / 60}m ${secs % 60}s · as of this readout", color = BookColors.TextMuted, fontSize = 11.sp)
                Text(
                    "WalkThor drew $frames frame${if (frames == 1) "" else "s"} in the last 10 s (0–1 = idle)",
                    color = if (frames > 5) Color(0xFFE0675A) else BookColors.TextMuted,
                    fontSize = 12.sp,
                )
                val handBack by com.nerfherder.walkthor.PerfCheck.handBack.collectAsState()
                Text(
                    "Hand-back: ${handBack ?: "not tried yet"}",
                    color = BookColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        }
        Text(
            "Tap to wake",
            color = BookColors.TextMuted.copy(alpha = 0.35f * alpha),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        )
    }
}

/** The game Cocoon is playing now (from its launch log), for the play-time readout. */
object PlaySession {
    val current = MutableStateFlow<LaunchedGame?>(null)
    val showTimer = MutableStateFlow(true)

    /** "Off", "30 sec", "1 min" — for the compact chips in settings. */
    fun shortLabel(ms: Long) = when {
        ms == 0L -> "Off"
        ms < 60_000L -> "${ms / 1000} sec"
        else -> "${ms / 60_000L} min"
    }

    fun init(context: Context) {
        showTimer.value = context.getSharedPreferences("session", Context.MODE_PRIVATE).getBoolean("show", true)
    }

    fun setShowTimer(context: Context, on: Boolean) {
        showTimer.value = on
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("show", on).apply()
    }
}
