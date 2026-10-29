package com.nerfherder.walkthor.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nerfherder.walkthor.Mirror
import com.nerfherder.walkthor.data.Captions

/**
 * Captions (test): turns the game's spoken audio into text on the bottom screen. For now it's a
 * test, with a sound meter and a readout, to find out whether the game's sound arrives (with the
 * volume down or muted) and whether this device's speech recognizer can caption it.
 */
@Composable
fun CaptionsScreen(onStartCapture: () -> Unit, topBar: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    val projection by Mirror.projection.collectAsState()
    val on by Captions.on.collectAsState()
    val level by Captions.level.collectAsState()
    val peak by Captions.peak.collectAsState()
    val samples by Captions.samples.collectAsState()
    val recognizer by Captions.recognizer.collectAsState()
    val partial by Captions.partial.collectAsState()
    val lines by Captions.lines.collectAsState()
    val error by Captions.error.collectAsState()
    // Starting takes two of Android's prompts: sound capture, then screen access (as Mirror).
    var wantStart by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) { wantStart = false; Captions.error.value = "Android's sound permission is needed to hear the game." }
    }
    fun hasAudio() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(wantStart, projection) {
        if (!wantStart) return@LaunchedEffect
        val p = projection
        when {
            !hasAudio() -> permission.launch(Manifest.permission.RECORD_AUDIO)
            p == null -> onStartCapture()
            else -> { wantStart = false; Captions.start(context, p) }
        }
    }
    // Ends when you leave the tab (for now), so it never runs unnoticed.
    DisposableEffect(Unit) {
        val holder = Any()
        AutoDim.wake()
        AutoDim.holdBright(holder, true)
        onDispose { AutoDim.holdBright(holder, false); Captions.stop() }
    }

    Column(Modifier.fillMaxSize().background(backdropOr(Color.Black))) {
        topBar?.invoke()
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Captions", fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = BookColors.Text)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "TEST", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = BookColors.TextMuted,
                            modifier = Modifier.border(1.dp, BookColors.Hairline, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        "Captures the game's own sound (not the microphone) and captions speech, on this device only. Try it with the volume down and muted.",
                        color = BookColors.TextMuted, fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { if (on) Captions.stop() else { Captions.error.value = null; wantStart = true } },
                    colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
                ) { Text(if (on) "Stop" else "Start", fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(12.dp))
            // ---- the sound meter ----
            Text("Game sound", color = BookColors.TextMuted, fontSize = 11.sp)
            Box(
                Modifier.fillMaxWidth().height(14.dp).padding(top = 4.dp)
                    .background(BookColors.SurfaceHigh, RoundedCornerShape(50)),
            ) {
                Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).fillMaxHeight().background(Color(0xFF7FC48A), RoundedCornerShape(50)))
            }
            val seconds = samples / 16_000
            Text(
                when {
                    !on -> error ?: "Not running."
                    samples == 0L -> "Nothing arriving yet. If it stays at zero, Android isn't passing on this game's sound."
                    peak < 0.05f -> "Sound is arriving (${seconds}s) but silent: the game may be muted before WalkThor gets it."
                    else -> "Sound is arriving (${seconds}s, loudest ${(peak * 100).toInt()}%)."
                },
                color = if (on && peak >= 0.05f) Color(0xFF7FC48A) else BookColors.TextMuted,
                fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
            Text("Recognizer: $recognizer", color = BookColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(12.dp))
            // ---- the captions ----
            Column(
                Modifier.fillMaxWidth().weight(1f)
                    .background(Color(0xFF0B0B0D), RoundedCornerShape(12.dp))
                    .border(1.dp, BookColors.Hairline, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (lines.isEmpty() && partial.isBlank()) {
                    Text(
                        if (on) "Captions appear here when someone speaks." else "Press Start, then play a scene with speech.",
                        color = BookColors.TextMuted.copy(alpha = 0.7f), fontSize = 15.sp,
                    )
                }
                lines.forEach { Text(it, color = BookColors.Text.copy(alpha = 0.75f), fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp)) }
                if (partial.isNotBlank()) Text(partial, color = BookColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
