package com.nerfherder.walkthor.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerfherder.walkthor.controls.JoyCons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › General › Controllers: what's connected (Joy-Cons marked), the last button pressed
 * on each, and a check of how the Thor sees Joy-Cons, to share so joining a pair can be built.
 */
@Composable
fun ControllersDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pads by remember { mutableStateOf(JoyCons.pads(context)) }
    // Looked at again every couple of seconds, for controllers connecting or going.
    LaunchedEffect(Unit) { while (true) { delay(2000); pads = JoyCons.pads(context) } }
    var lastPress by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Controllers") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())
                    .focusRequester(focus)
                    // Any press shows which controller it came from (Back still closes this).
                    .onPreviewKeyEvent { e ->
                        val k = e.nativeKeyEvent
                        if (k.action == android.view.KeyEvent.ACTION_DOWN && k.keyCode != android.view.KeyEvent.KEYCODE_BACK) {
                            val from = pads.firstOrNull { it.id == k.deviceId }
                            lastPress = android.view.KeyEvent.keyCodeToString(k.keyCode).removePrefix("KEYCODE_").removePrefix("BUTTON_") +
                                " on " + (from?.nintendo ?: from?.name ?: "device ${k.deviceId}")
                        }
                        false
                    }
                    .focusable(),
            ) {
                if (pads.isEmpty()) Text("No controllers found.", fontSize = 14.sp)
                pads.forEach { p ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(
                            p.nintendo ?: p.name,
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                            color = if (p.nintendo != null) frontAccent() else Color.Unspecified,
                        )
                        Text(
                            listOfNotNull(
                                if (p.nintendo != null) p.name else null,
                                if (p.builtIn) "Built in" else "Connected",
                                p.battery?.let { "Battery $it%" },
                                if (p.axes.isEmpty()) "No sticks reported" else "${p.axes.size} sticks and triggers",
                            ).joinToString(" · "),
                            fontSize = 12.sp, color = BookColors.TextMuted,
                        )
                    }
                }
                Text(
                    lastPress?.let { "Last press: $it" } ?: "Press a button on any controller to see which one it is.",
                    fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "To use two Joy-Cons as one controller: pair each in Android’s Bluetooth settings (hold the small sync button on the rail until the lights run), then run the check below and share it. It tells WalkThor how this Thor sees them.",
                    fontSize = 12.sp, color = BookColors.TextMuted, modifier = Modifier.padding(top = 10.dp),
                )
                report?.let {
                    Text(
                        it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp,
                        modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF15131A)).padding(8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (report == null) TextButton(enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    val probe = withContext(Dispatchers.IO) { JoyCons.probe() }
                    val text = JoyCons.report(context, probe)
                    report = text
                    checking = false
                    com.nerfherder.walkthor.MemoryWatch.noteFrom(context, "Controller check: " + text.lines().drop(3).filter { it.isNotBlank() }.joinToString(" | ").take(1500))
                }
            }) { Text(if (checking) "Checking…" else "Check Joy-Con support") }
            else TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                runCatching { context.startActivity(Intent.createChooser(send, "Share controller check").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
