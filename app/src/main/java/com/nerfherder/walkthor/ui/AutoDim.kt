package com.nerfherder.walkthor.ui

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
    /** Where the time/play-time/battery readout is on screen (kept bright while dimmed). */
    val statusBounds = MutableStateFlow<Rect?>(null)
    @Volatile internal var statusOwner: Any? = null
    val dimmed = MutableStateFlow(false)
    @Volatile private var lastInteraction = SystemClock.elapsedRealtime()

    fun label(ms: Long) = when (ms) {
        0L -> "Off"
        in 1 until 60_000L -> "${ms / 1000} seconds"
        60_000L -> "1 minute"
        else -> "${ms / 60_000L} minutes"
    }

    fun init(context: Context) {
        delayMs.value = context.getSharedPreferences("dim", Context.MODE_PRIVATE).getLong("delay", 0L)
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
    val delayMs by AutoDim.delayMs.collectAsState()
    val dimmed by AutoDim.dimmed.collectAsState()

    LaunchedEffect(delayMs) {
        if (delayMs <= 0L) { AutoDim.dimmed.value = false; return@LaunchedEffect }
        while (true) {
            val left = delayMs - AutoDim.idleFor()
            if (left <= 0) {
                AutoDim.dimmed.value = true
                delay(1_000)
            } else {
                delay(left.coerceAtMost(5_000))
            }
        }
    }

    val alpha by animateFloatAsState(if (dimmed) 0.9f else 0f, tween(if (dimmed) 1_200 else 180), label = "dim")
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

    fun init(context: Context) {
        showTimer.value = context.getSharedPreferences("session", Context.MODE_PRIVATE).getBoolean("show", true)
    }

    fun setShowTimer(context: Context, on: Boolean) {
        showTimer.value = on
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("show", on).apply()
    }
}
