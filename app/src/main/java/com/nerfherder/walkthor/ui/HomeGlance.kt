package com.nerfherder.walkthor.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerfherder.walkthor.PlayToday
import com.nerfherder.walkthor.StickLights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Home's play time (this week, by day) and battery (time left, power draw) side by side. */
@Composable
internal fun PlayAndBattery(preview: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(124.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PlayCard(preview, Modifier.weight(1f).fillMaxHeight())
        BatteryCard(preview, Modifier.weight(1f).fillMaxHeight())
    }
}

private fun Modifier.glanceCard() = clip(RoundedCornerShape(16.dp)).background(Color(0xFF0D0B0E))
    .border(1.dp, Color(0xFF1F1C20), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp)

@Composable
private fun PlayCard(preview: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val sample = remember { listOf("M" to 38, "T" to 72, "W" to 0, "T" to 95, "F" to 51, "S" to 120, "S" to 47) }
    var week by remember { mutableStateOf(if (preview) sample else emptyList()) }
    LaunchedEffect(preview) {
        if (preview) return@LaunchedEffect
        while (true) {
            week = withContext(Dispatchers.IO) { PlayToday.week(context) }
            delay(30_000)
        }
    }
    val color = StickLights.color(context)
    val todayColor = if (color == 0) BookColors.Text else Color(StickLights.swatch(color))
    Column(modifier.glanceCard()) {
        Text("Play time", color = BookColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
            Text(hoursMinutes(week.lastOrNull()?.second ?: 0), color = BookColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
            Text(" today", color = BookColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
            Text(
                "${hoursMinutes(week.sumOf { it.second })} this week", color = BookColors.TextMuted, fontSize = 10.5.sp, maxLines = 1,
                modifier = Modifier.padding(start = 14.dp, bottom = 2.dp),
            )
        }
        // A bar per day, today last in the stick lights' colour.
        val most = (week.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            week.forEachIndexed { i, (day, mins) ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.weight(1f).fillMaxWidth()) {
                        val w = 15.dp.toPx().coerceAtMost(size.width * 0.7f)
                        val h = (size.height * mins / most).coerceAtLeast(2.dp.toPx())
                        drawRoundRect(
                            if (i == week.lastIndex) todayColor else Color(0xFF3A3540),
                            topLeft = Offset((size.width - w) / 2, size.height - h), size = Size(w, h),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                    }
                    Text(day, color = BookColors.TextMuted.copy(alpha = 0.7f), fontSize = 9.sp, lineHeight = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

/** What the battery's doing now: its level, the power going in or out, and how long that lasts. */
internal data class BatteryNow(
    val level: Int,
    val charging: Boolean,
    /** Watts going out (or in, when charging); null where the battery doesn't say. */
    val watts: Double?,
    /** Minutes until empty (or full, when charging); null when unknown. */
    val minutesLeft: Int?,
    /** Minutes a full charge lasts at this rate; null when unknown. */
    val fullLasts: Int?,
)

internal object BatteryReader {
    /** The current, smoothed over the last readings (it jumps around a lot moment to moment). */
    private var smoothed = 0.0

    fun read(context: Context): BatteryNow? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val l = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (l < 0 || s <= 0) return null
        val level = l * 100 / s
        val st = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING
        val volts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).let { if (it > 100) it / 1000.0 else it.toDouble() }
        val bm = context.getSystemService(BatteryManager::class.java) ?: return BatteryNow(level, charging, null, null, null)
        // Microamps, though some batteries report milliamps; the sign differs from one to the next.
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong()
        val amps = when {
            raw == Long.MIN_VALUE || raw == Int.MIN_VALUE.toLong() || raw == 0L -> null
            abs(raw) < 20_000 -> abs(raw) / 1000.0
            else -> abs(raw) / 1_000_000.0
        }
        if (amps == null) { smoothed = 0.0; return BatteryNow(level, charging, null, null, null) }
        smoothed = if (smoothed == 0.0) amps else smoothed * 0.7 + amps * 0.3
        val a = smoothed
        val watts = if (volts > 0) a * volts else null
        // Charge left in amp-hours: from the battery's own count, or worked out from the level.
        val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong()
        val ahNow = if (counter > 0 && counter != Int.MIN_VALUE.toLong()) counter / 1_000_000.0 else null
        val ahFull = ahNow?.let { if (level > 0) it * 100 / level else null }
        val left = if (charging) {
            val chargeMs = if (android.os.Build.VERSION.SDK_INT >= 28) bm.computeChargeTimeRemaining() else -1L
            if (chargeMs > 0) (chargeMs / 60_000L).toInt()
            else if (ahNow != null && ahFull != null) ((ahFull - ahNow) / a * 60).toInt() else null
        } else ahNow?.let { (it / a * 60).toInt() }
        return BatteryNow(
            level, charging, watts, left?.takeIf { it in 0..(72 * 60) },
            ahFull?.let { (it / a * 60).toInt() }?.takeIf { it in 1..(72 * 60) },
        )
    }
}

@Composable
private fun BatteryCard(preview: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(if (preview) BatteryNow(78, false, 7.4, 250, 320) else null) }
    LaunchedEffect(preview) {
        if (preview) return@LaunchedEffect
        while (true) {
            now = withContext(Dispatchers.IO) { runCatching { BatteryReader.read(context) }.getOrNull() }
            delay(5_000)
        }
    }
    Column(modifier.glanceCard()) {
        Text("Battery", color = BookColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        val b = now
        val big = when {
            b == null -> "—"
            b.minutesLeft == null -> "${b.level}%"
            b.charging -> "full in ${hoursMinutes(b.minutesLeft)}"
            else -> "about ${hoursMinutes(b.minutesLeft)}"
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
            Text(big, color = BookColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, maxLines = 1)
            if (b?.minutesLeft != null && !b.charging) Text(" left", color = BookColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
            if (b != null && b.minutesLeft == null) Text(if (b.charging) " charging" else " left", color = BookColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        // The level as a bar.
        Box(
            Modifier.padding(top = 7.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF2A262B)),
        ) {
            Box(Modifier.fillMaxWidth((b?.level ?: 0) / 100f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(BookColors.Text))
        }
        val lines = when {
            b == null -> listOf("Battery details aren't available")
            b.watts == null -> listOf("${b.level}%", "Power use isn't reported here")
            b.charging -> listOf("Charging at %.1f W".format(b.watts), "${b.level}% now")
            else -> listOfNotNull(
                "Using %.1f W now".format(b.watts),
                b.fullLasts?.let { "Full charge at this rate: ${hoursMinutes(it)}" },
            )
        }
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            lines.forEach { Text(it, color = BookColors.TextMuted, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, textAlign = TextAlign.Start) }
        }
    }
}
