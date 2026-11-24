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
import androidx.compose.ui.layout.findRootCoordinates
import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
     * A screen-specific delay that replaces [delayMs] while that screen is up (0 = never dim,
     * for Mirror and Face Buttons, which you watch while you play).
     */
    val screenDelayMs = MutableStateFlow<Long?>(null)
    private val brightHolders = mutableSetOf<Any>()

    /**
     * Keeps the screen from dimming ([on]) on behalf of [holder], or lets go. It stays bright while
     * any holder wants it, so a tab fading out can't undo the one fading in.
     */
    fun holdBright(holder: Any, on: Boolean) = synchronized(brightHolders) {
        if (on) brightHolders.add(holder) else brightHolders.remove(holder)
        screenDelayMs.value = if (brightHolders.isEmpty()) null else 0L
    }
    /** How dark the dimming gets: 0.5 = half, 1.0 = fully black (the status readout always stays bright). */
    val level = MutableStateFlow(DEFAULT_LEVEL)
    /** True while the user is adjusting [level], to show it live. */
    val previewing = MutableStateFlow(false)
    const val MIN_LEVEL = 0.5f
    const val DEFAULT_LEVEL = 0.95f
    /** Where the time/play-time/battery readout is on screen (kept bright while dimmed). */
    val statusBounds = MutableStateFlow<Rect?>(null)

    @Volatile internal var statusOwner: Any? = null
    /**
     * The readout's own layout, to ask where it is right as the screen dims: the position reported
     * when it was laid out can be stale, if it was laid out mid-animation (WalkThor zooming in
     * when it comes up by itself) and nothing has moved it since.
     */
    @Volatile internal var statusCoords: androidx.compose.ui.layout.LayoutCoordinates? = null
    /** Every readout laid out now (pages side by side can each have one): the one on screen is used. */
    internal val allStatusCoords = java.util.concurrent.ConcurrentHashMap<Any, androidx.compose.ui.layout.LayoutCoordinates>()

    /** Where the readout that's actually on screen is now, or null. */
    internal fun liveStatusBounds(): Rect? {
        val candidates = allStatusCoords.values.toList() + listOfNotNull(statusCoords)
        return candidates.asSequence().filter { it.isAttached }.mapNotNull { c ->
            runCatching {
                val b = c.boundsInRoot()
                val root = c.findRootCoordinates().size
                b.takeIf { it.width > 0f && it.left >= 0f && it.right <= root.width + 0.5f && it.top >= 0f && it.bottom <= root.height + 0.5f }
            }.getOrNull()
        }.firstOrNull()
    }
    val dimmed = MutableStateFlow(false)
    /**
     * True once the screen has stayed dimmed for [REST_AFTER_MS]: rendered pages and cover images
     * are let go (nothing can be seen under the dim anyway) and come back when you wake it.
     */
    val resting = MutableStateFlow(false)
    const val REST_AFTER_MS = 30_000L
    /** True while Home is up during a game: the one game screen that dims. */
    val nowPlayingShown = MutableStateFlow(false)
    private val nowPlayingHolders = mutableSetOf<Any>()

    /**
     * Home is on screen during a game ([on]) for [holder], or not. Counted, so the old copy going
     * away as a new one comes in (a quick trip to a tab and back) can't leave it marked as gone.
     */
    fun showingNowPlaying(holder: Any, on: Boolean) = synchronized(nowPlayingHolders) {
        if (on) nowPlayingHolders.add(holder) else nowPlayingHolders.remove(holder)
        nowPlayingShown.value = nowPlayingHolders.isNotEmpty()
    }

    /** The screens for while you play (Home, the tabs, a guide opened from them) are up. */
    val gameScreensShown = MutableStateFlow(false)
    private val gameHolders = mutableSetOf<Any>()
    fun showingGameScreens(holder: Any, on: Boolean) = synchronized(gameHolders) {
        if (on) gameHolders.add(holder) else gameHolders.remove(holder)
        gameScreensShown.value = gameHolders.isNotEmpty()
    }

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
    // During a game, only Home dims, after the Auto-dim time; every other screen (each tab, a
    // guide) stays bright. Outside a game, screens can ask to stay bright.
    val onNowPlaying by AutoDim.nowPlayingShown.collectAsState()
    val onGameScreens by AutoDim.gameScreensShown.collectAsState()
    val delayMs = when {
        onNowPlaying -> settingDelay
        onGameScreens -> 0L
        else -> screenDelay ?: settingDelay
    }
    val dimmed by AutoDim.dimmed.collectAsState()

    // After a while dimmed fully black, free the memory held by what's under the dim, for the
    // game's benefit. Only when nothing shows through: at a lighter dim the screen stays exactly
    // as it dimmed (no going black later).
    val context = androidx.compose.ui.platform.LocalContext.current
    val dimLevel by AutoDim.level.collectAsState()
    val fullyBlack = dimLevel >= 0.99f
    LaunchedEffect(dimmed, fullyBlack) {
        if (!dimmed || !fullyBlack) { AutoDim.resting.value = false; return@LaunchedEffect }
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
    val reported by AutoDim.statusBounds.collectAsState()
    // Where the readout is now (as the screen dims), rather than where it was last laid out.
    val status = remember(target > 0f, reported) {
        AutoDim.liveStatusBounds() ?: reported
    }

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
        // During a game: the tabs stay, exactly where the tab bar has them (a tab opens on touch
        // down, since that same touch also wakes the screen), and the time in the middle.
        if (onGameScreens && !previewing) {
            val tabs by GameTabs.state.collectAsState()
            val shade = Modifier.graphicsLayer { this.alpha = (alpha / level.coerceAtLeast(0.01f)).coerceIn(0f, 1f) }
            tabs?.let { t -> GameTabPillInBarPosition(t.selected, t.onSelect, Modifier.align(Alignment.TopStart).then(shade)) }
            if (status == null) StatusClock(Modifier.align(Alignment.Center).then(shade), anchor = false)
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
                Text("Performance check", style = EyebrowStyle.copy(color = BookColors.TextMuted))
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
