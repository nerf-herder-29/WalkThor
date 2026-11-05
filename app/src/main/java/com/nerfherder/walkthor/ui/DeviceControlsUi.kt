package com.nerfherder.walkthor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerfherder.walkthor.DeviceModes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The modes as shown, kept up to date with changes made elsewhere (AYN's drawer or settings). */
private class ModesState(val perf: DeviceModes.Performance, val fan: DeviceModes.Fan?, val layout: DeviceModes.Layout)

/**
 * Performance, fan and controller layout: one row of choices each ([compact] = false), or three
 * pills that step to the next choice when tapped ([compact] = true, for Home). Hidden behind a
 * short note where the Thor's system helper can't be reached.
 */
@Composable
fun DeviceControls(compact: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    var helper by remember { mutableStateOf(if (preview) true else DeviceModes.canChange().takeIf { it }) }
    var state by remember { mutableStateOf(ModesState(DeviceModes.performance(context), DeviceModes.fan(context), DeviceModes.layout(context))) }
    fun reread() { state = ModesState(DeviceModes.performance(context), DeviceModes.fan(context), DeviceModes.layout(context)) }
    LaunchedEffect(Unit) {
        if (helper == null) helper = withContext(Dispatchers.IO) { DeviceModes.check() }
        while (true) { delay(2_000); reread() }
    }
    // After a change, read the setting back a moment later (it's set off the main thread).
    var pending by remember { mutableStateOf(0) }
    LaunchedEffect(pending) { if (pending > 0) { delay(400); reread() } }

    if (helper == false) {
        Text(
            "Performance, fan and button layout need the Thor's system helper. About → Diagnostics shows why it isn't reachable.",
            color = BookColors.TextMuted, fontSize = if (compact) 11.sp else 13.sp, modifier = modifier,
        )
        return
    }
    val setPerf = { m: DeviceModes.Performance -> state = ModesState(m, state.fan, state.layout); DeviceModes.setPerformance(m) { pending++ } }
    val setFan = { f: DeviceModes.Fan -> state = ModesState(state.perf, f, state.layout); DeviceModes.setFan(f) { pending++ } }
    val setLayout = { l: DeviceModes.Layout -> state = ModesState(state.perf, state.fan, l); DeviceModes.setLayout(l) { pending++ } }

    if (compact) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CyclePill("Performance", state.perf.label, Modifier.weight(1f)) {
                val all = DeviceModes.Performance.entries
                setPerf(all[(all.indexOf(state.perf) + 1) % all.size])
            }
            CyclePill("Fan", state.fan?.label ?: "Custom", Modifier.weight(1f)) {
                val all = DeviceModes.Fan.entries
                setFan(all[((state.fan?.let { all.indexOf(it) } ?: -1) + 1) % all.size])
            }
            CyclePill("Buttons", if (state.layout == DeviceModes.Layout.XBOX) "A at bottom" else "A on right", Modifier.weight(1f)) {
                setLayout(if (state.layout == DeviceModes.Layout.XBOX) DeviceModes.Layout.NINTENDO else DeviceModes.Layout.XBOX)
            }
        }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Choices("Performance", DeviceModes.Performance.entries.map { it.label }, DeviceModes.Performance.entries.indexOf(state.perf)) {
            setPerf(DeviceModes.Performance.entries[it])
        }
        Choices(
            "Fan", DeviceModes.Fan.entries.map { it.label }, state.fan?.let { DeviceModes.Fan.entries.indexOf(it) } ?: -1,
            note = if (state.fan == null) "Using AYN's custom fan curve" else null,
        ) { setFan(DeviceModes.Fan.entries[it]) }
        Choices(
            "Button layout", listOf("A on the right", "A at the bottom"),
            if (state.layout == DeviceModes.Layout.XBOX) 1 else 0,
        ) { setLayout(if (it == 1) DeviceModes.Layout.XBOX else DeviceModes.Layout.NINTENDO) }
    }
}

/** A label and a row of choices, one picked. */
@Composable
private fun Choices(label: String, options: List<String>, selected: Int, note: String? = null, onPick: (Int) -> Unit) {
    Column {
        Text(label, color = BookColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(
            Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.07f)).padding(3.dp),
        ) {
            options.forEachIndexed { i, o ->
                val on = i == selected
                Text(
                    o, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) Color.Black else BookColors.Text,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(50))
                        .background(if (on) BookColors.Text else Color.Transparent)
                        .clickable { onPick(i) }.padding(vertical = 7.dp),
                )
            }
        }
        note?.let { Text(it, color = BookColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** A small pill naming a setting and its value; tapping it steps to the next value. */
@Composable
private fun CyclePill(label: String, value: String, modifier: Modifier, onTap: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF16131A))
            .border(1.dp, Color(0xFF2A262B), RoundedCornerShape(12.dp))
            .clickable(onClick = onTap).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = BookColors.TextMuted, fontSize = 10.sp, maxLines = 1)
        Text(value, color = BookColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
