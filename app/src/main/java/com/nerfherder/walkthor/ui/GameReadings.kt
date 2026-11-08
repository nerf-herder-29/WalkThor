package com.nerfherder.walkthor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The readings under Now playing's tabs (heat, and the top screen's frame rate), and where they
 * are, so the dimmed screen can draw them lit in exactly the same places, as it does the time.
 */
object GameReadings {
    /** CPU °C, GPU °C, fan %; each null where it can't be read. */
    val heat = MutableStateFlow<Triple<Float?, Float?, Int?>>(Triple(null, null, null))
    val heatBounds = MutableStateFlow<Rect?>(null)
    val fpsBounds = MutableStateFlow<Rect?>(null)
}

/** Reports where this sits (for the dimmed copy), until it goes. */
@Composable
private fun Modifier.reportBounds(anchor: Boolean, into: MutableStateFlow<Rect?>): Modifier {
    if (!anchor) return this
    val token = remember { Any() }
    var mine by remember { androidx.compose.runtime.mutableStateOf(false) }
    DisposableEffect(token) { onDispose { if (mine) into.value = null } }
    return onGloballyPositioned { mine = true; into.value = it.boundsInRoot() }
}

@Composable
private fun Part(value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(value, color = BookColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(" $label", color = BookColors.TextMuted, fontSize = 12.sp)
    }
}

/**
 * "62°C CPU  58°C GPU  40% fan", read every few seconds; whatever can't be read is left out.
 * With [anchor] false it's the dimmed screen's copy, showing the same readings.
 */
@Composable
internal fun HeatReadout(preview: Boolean, modifier: Modifier = Modifier, anchor: Boolean = true) {
    if (anchor) LaunchedEffect(preview) {
        if (preview) { GameReadings.heat.value = Triple(62f, 58f, 40); return@LaunchedEffect }
        while (true) {
            GameReadings.heat.value = withContext(Dispatchers.IO) { runCatching { StatsReader.heat() }.getOrDefault(Triple(null, null, null)) }
            delay(3_000)
        }
    }
    val heat by GameReadings.heat.collectAsState()
    val (cpu, gpu, fan) = heat
    Row(
        modifier.reportBounds(anchor, GameReadings.heatBounds),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        cpu?.let { Part("${it.roundToInt()}°C", "CPU") }
        gpu?.let { Part("${it.roundToInt()}°C", "GPU") }
        fan?.let { Part("$it%", "fan") }
    }
}

/**
 * "60 FPS": the top screen's frame rate, from the display driver once a second (left out where
 * that can't be read).
 */
@Composable
internal fun FpsReadout(preview: Boolean, modifier: Modifier = Modifier, anchor: Boolean = true) {
    if (anchor) rememberDriverFps(enabled = !preview)
    val live by FpsMeter.history.collectAsState()
    val fps = if (preview) 60 else live.lastOrNull()
    Row(modifier.reportBounds(anchor, GameReadings.fpsBounds), verticalAlignment = Alignment.CenterVertically) {
        fps?.let { Part("$it", "FPS") }
    }
}
