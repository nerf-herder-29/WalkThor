package com.nerfherder.walkthor.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerfherder.walkthor.controls.JoyCons
import com.nerfherder.walkthor.controls.PadBluetooth
import com.nerfherder.walkthor.controls.PadJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Controllers tab: Joy-Cons as the Thor's own controller, pairing controllers over Bluetooth,
 * what's connected (and which one a press came from), and a check of how the Thor sees Joy-Cons.
 */
@Composable
fun ColumnScope.ControllersPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pads by remember { mutableStateOf(JoyCons.pads(context)) }
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            // Whether they're joined, asked of the system now and then (WalkThor may have restarted since).
            if (n++ % 5 == 0) withContext(Dispatchers.IO) { JoyCons.refresh(); PadJoin.refresh(context) }
            delay(2000)
            pads = JoyCons.pads(context)
        }
    }
    val active by JoyCons.active.collectAsState()
    val problem by JoyCons.problem.collectAsState()
    var joining by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(JoyCons.autoJoin(context)) }
    val hasLeft = pads.any { it.nintendo == "Left Joy-Con" }
    val hasRight = pads.any { it.nintendo == "Right Joy-Con" }
    var lastPress by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            // Any press shows which controller it came from.
            val k = e.nativeKeyEvent
            if (k.action == android.view.KeyEvent.ACTION_DOWN) {
                val from = pads.firstOrNull { it.id == k.deviceId }
                lastPress = android.view.KeyEvent.keyCodeToString(k.keyCode).removePrefix("KEYCODE_").removePrefix("BUTTON_") +
                    " on " + (from?.nintendo ?: from?.name ?: "device ${k.deviceId}")
            }
            false
        },
    ) {
        SettingsGroup("Joy-Cons") {
            SettingRow(
                if (active) "Joy-Cons are the Thor’s controller" else "Use Joy-Cons as the Thor’s controller",
                when {
                    active -> "Every emulator sees them as the Thor’s own controls, and Home is the Thor’s Back button"
                    joining -> "Joining… hold them still for a moment"
                    hasLeft && hasRight -> "Both are connected. Hold them still while they join (their sticks are measured at rest)"
                    hasLeft || hasRight -> "Connect the other Joy-Con too"
                    else -> "Connect a left and a right Joy-Con (pair them below)"
                },
                divider = false,
                value = if (active) "Joined" else "Not joined",
                buttons = {
                    if (active) PillButton("Separate", quiet = true) {
                        joining = true
                        scope.launch { withContext(Dispatchers.IO) { JoyCons.separate() }; joining = false }
                    } else if (hasLeft && hasRight && !joining) PillButton("Join") {
                        joining = true
                        scope.launch { withContext(Dispatchers.IO) { JoyCons.join(context) }; joining = false }
                    }
                },
            )
            SettingSwitch(
                "Join them by themselves",
                "Whenever a left and a right Joy-Con are both connected",
                checked = auto,
            ) { auto = it; JoyCons.setAutoJoin(context, it) }
            var sens by remember { mutableStateOf(JoyCons.sensitivity(context)) }
            SettingRow(
                "Stick sensitivity",
                "How quickly the Joy-Con sticks reach all the way. Gentle suits small sticks best",
                value = listOf("Gentle", "Normal", "Quick")[sens],
                buttons = {
                    listOf("Gentle", "Normal", "Quick").forEachIndexed { i, label ->
                        PillButton(label, quiet = sens != i) {
                            if (sens == i) return@PillButton
                            sens = i
                            JoyCons.setSensitivity(context, i)
                            // Joined already: join again so the new setting takes hold.
                            if (active) {
                                joining = true
                                scope.launch { withContext(Dispatchers.IO) { JoyCons.join(context) }; joining = false }
                            }
                        }
                    }
                },
            )
            problem?.let { if (!active) SettingsNote("Last try didn’t work: $it") }
        }
        SettingsNote("A, B, X and Y are the Thor’s A, B, X and Y, the Nintendo layout. Capture, motion and rumble aren’t passed on yet.")

        OtherControllersGroup(pads)

        BluetoothGroup()

        SettingsGroup("Connected") {
            if (pads.isEmpty()) SettingRow("No controllers found", divider = false)
            pads.forEachIndexed { i, p ->
                SettingRow(
                    p.nintendo ?: p.name,
                    listOfNotNull(
                        if (p.nintendo != null) p.name else null,
                        if (p.builtIn) "Built in" else "Connected",
                        p.battery?.let { "Battery $it%" },
                        if (p.axes.isEmpty()) "No sticks reported" else "${p.axes.size} sticks and triggers",
                    ).joinToString(" · "),
                    divider = i > 0,
                )
            }
        }
        SettingsNote(lastPress?.let { "Last press: $it" } ?: "Press a button on any controller to see which one it is.")

        CheckGroup()
    }
}

/** Any other controller as the Thor's own, like the Joy-Cons. */
@Composable
private fun OtherControllersGroup(pads: List<JoyCons.Pad>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by PadJoin.active.collectAsState()
    val problem by PadJoin.problem.collectAsState()
    var joining by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(PadJoin.autoJoin(context)) }
    var byPosition by remember { mutableStateOf(PadJoin.byPosition(context)) }
    // Refreshed with the list of controllers.
    val candidate = remember(pads) { PadJoin.candidates().firstOrNull() }
    fun rejoin() {
        joining = true
        scope.launch { withContext(Dispatchers.IO) { PadJoin.join(context) }; joining = false }
    }
    SettingsGroup("Other controllers") {
        SettingRow(
            active?.let { "$it is the Thor’s controller" } ?: "Use another controller as the Thor’s",
            when {
                active != null -> "Every emulator sees it as the Thor’s own controls, and its Home button is the Thor’s Back button"
                joining -> "Joining…"
                candidate != null -> "${candidate.name} is connected"
                else -> "Connect a Bluetooth or USB controller (pair one below)"
            },
            divider = false,
            value = if (active != null) "Joined" else "Not joined",
            buttons = {
                if (active != null) PillButton("Separate", quiet = true) {
                    joining = true
                    scope.launch { withContext(Dispatchers.IO) { PadJoin.separate(context) }; joining = false }
                } else if (candidate != null && !joining) PillButton("Join") { rejoin() }
            },
        )
        SettingSwitch(
            "Join it by itself",
            "Whenever a controller connects, unless one is joined already",
            checked = auto,
        ) { auto = it; PadJoin.setAutoJoin(context, it) }
        SettingRow(
            "Face buttons",
            if (byPosition) "By where they sit: the bottom button is the Thor’s bottom button, B, whatever its letter"
            else "By their letters: A is the Thor’s A, wherever it sits",
            value = if (byPosition) "By position" else "By letter",
            buttons = {
                listOf(false to "By letter", true to "By position").forEach { (pos, label) ->
                    PillButton(label, quiet = byPosition != pos) {
                        if (byPosition == pos) return@PillButton
                        byPosition = pos
                        PadJoin.setByPosition(context, pos)
                        if (active != null) rejoin()
                    }
                }
            },
        )
        problem?.let { if (active == null) SettingsNote("Last try didn’t work: $it") }
    }
}

@Composable
private fun BluetoothGroup() {
    val context = LocalContext.current
    val searching by PadBluetooth.searching.collectAsState()
    val found by PadBluetooth.found.collectAsState()
    val status by PadBluetooth.status.collectAsState()
    var allowed by remember { mutableStateOf(PadBluetooth.allowed(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        allowed = result.values.all { it }
        if (allowed) PadBluetooth.search(context)
    }
    DisposableEffect(Unit) { onDispose { PadBluetooth.close(context) } }
    SettingsGroup("Pair a controller") {
        SettingRow(
            "Find controllers",
            if (searching) "Looking… hold a Joy-Con’s small sync button (on its rail) until its lights run"
            else "Hold a Joy-Con’s small sync button (on its rail) until its lights run, then look",
            divider = false,
            buttons = {
                if (searching) PillButton("Stop", quiet = true) { PadBluetooth.stop(context) }
                else PillButton("Look") { if (allowed) PadBluetooth.search(context) else ask.launch(PadBluetooth.permissions) }
            },
        )
        found.forEach { f ->
            SettingRow(
                f.name,
                if (f.bonded) "Paired. Press any of its buttons to connect it" else "Ready to pair",
                buttons = { if (!f.bonded) PillButton("Pair") { PadBluetooth.pair(context, f) } },
            )
        }
    }
    status?.let { SettingsNote(it) }
}

@Composable
private fun CheckGroup() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    SettingsGroup("Check") {
        SettingRow(
            "Check Joy-Con support",
            "How this Thor sees Joy-Cons, to share if joining doesn’t work",
            divider = false,
            buttons = {
                if (report != null) PillButton("Share") {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                    runCatching { context.startActivity(Intent.createChooser(send, "Share controller check").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                PillButton(if (checking) "Checking…" else "Check", quiet = report != null) {
                    if (checking) return@PillButton
                    checking = true
                    scope.launch {
                        val probe = withContext(Dispatchers.IO) { JoyCons.probe() }
                        report = JoyCons.report(context, probe) +
                            "\n== Joining ==\n" + (withContext(Dispatchers.IO) { com.nerfherder.walkthor.AynSystem.joyConsLog() } ?: "(nothing yet)")
                        checking = false
                    }
                }
            },
        )
    }
    report?.let {
        Text(
            it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp, color = BookColors.TextMuted,
            modifier = Modifier.padding(bottom = 13.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF15131A)).padding(8.dp),
        )
    }
}
