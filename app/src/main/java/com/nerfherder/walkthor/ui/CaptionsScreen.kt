package com.nerfherder.walkthor.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.launch

/**
 * Starting captions takes two of Android's prompts: capturing sound (its "record audio"
 * permission), then screen access (as Mirror). Returns the function that starts it.
 */
@Composable
fun rememberCaptionsStarter(onStartCapture: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val projection by Mirror.projection.collectAsState()
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
    return { Captions.error.value = null; wantStart = true }
}

/**
 * Captions: what's said in your game, as text on the bottom screen, for playing with the sound
 * down. WalkThor captures the game's own sound (never the microphone) and turns speech into text
 * on the device. Captions stop when you leave the tab.
 */
@Composable
fun CaptionsScreen(onStartCapture: () -> Unit, topBar: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    val on by Captions.on.collectAsState()
    val recognizer by Captions.recognizer.collectAsState()
    val partial by Captions.partial.collectAsState()
    val lines by Captions.lines.collectAsState()
    val error by Captions.error.collectAsState()
    val model by com.nerfherder.walkthor.data.SpeechModel.state.collectAsState()
    LaunchedEffect(Unit) { com.nerfherder.walkthor.data.SpeechModel.refresh(context) }
    val start = rememberCaptionsStarter(onStartCapture)
    DisposableEffect(Unit) {
        val holder = Any()
        AutoDim.wake()
        AutoDim.holdBright(holder, true)
        onDispose { AutoDim.holdBright(holder, false); Captions.stop() }
    }
    // One short line about what's happening; only problems are spelled out.
    val problem = error ?: recognizer.takeIf { on && (it.startsWith("no ") || it.startsWith("couldn") || "couldn't" in it || it.startsWith("needs")) }
    val status = when {
        problem != null -> problem.replaceFirstChar { it.uppercase() }
        on && recognizer.startsWith("loading") -> "Loading the speech model…"
        on -> "Listening"
        else -> "Off"
    }
    val modelReady = model is com.nerfherder.walkthor.data.SpeechModel.State.Ready

    Column(Modifier.fillMaxSize().background(backdropOr(Color.Black))) {
        topBar?.invoke()
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Captions", fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = BookColors.Text)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        if (on && problem == null) {
                            Box(Modifier.size(7.dp).background(Color(0xFF7FC48A), androidx.compose.foundation.shape.CircleShape))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(status, color = if (problem != null) Color(0xFFE0A15A) else BookColors.TextMuted, fontSize = 12.sp, maxLines = 2)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { if (on) Captions.stop() else start() },
                    colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
                ) { Text(if (on) "Stop" else "Start", fontWeight = FontWeight.SemiBold) }
            }
            // The speech model, until it's downloaded.
            if (!modelReady) SpeechModelRow()
            Spacer(Modifier.height(12.dp))
            // ---- the captions ----
            Column(
                Modifier.fillMaxWidth().weight(1f)
                    .background(Color(0xFF0B0B0D), RoundedCornerShape(12.dp))
                    .border(1.dp, BookColors.Hairline, RoundedCornerShape(12.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (lines.isEmpty() && partial.isBlank()) {
                    Text(
                        if (on) "Captions appear here when someone speaks."
                        else "Press Start to caption what's said in your game. Works with the volume all the way down.",
                        color = BookColors.TextMuted.copy(alpha = 0.7f), fontSize = 15.sp,
                    )
                }
                lines.forEach { Text(it, color = BookColors.Text.copy(alpha = 0.7f), fontSize = 19.sp, modifier = Modifier.padding(top = 6.dp)) }
                if (partial.isNotBlank()) Text(partial, color = BookColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            }
            if (modelReady && !on) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                    Text(
                        "Remove speech model (${com.nerfherder.walkthor.data.SpeechModel.SIZE_LABEL})",
                        color = BookColors.TextMuted.copy(alpha = 0.7f), fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { com.nerfherder.walkthor.data.SpeechModel.delete(context) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** WalkThor's own (offline) speech recognizer: download it once, or remove it. */
@Composable
private fun SpeechModelRow() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state by com.nerfherder.walkthor.data.SpeechModel.state.collectAsState()
    LaunchedEffect(Unit) { com.nerfherder.walkthor.data.SpeechModel.refresh(context) }
    val on by Captions.on.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .background(BookColors.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, BookColors.Hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val s = state
        Column(Modifier.weight(1f)) {
            Text("Speech model", color = BookColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                when (s) {
                    is com.nerfherder.walkthor.data.SpeechModel.State.Missing ->
                        "Captions need a speech model: a one-time download (${com.nerfherder.walkthor.data.SpeechModel.SIZE_LABEL}, English), then it works offline."
                    is com.nerfherder.walkthor.data.SpeechModel.State.Downloading -> "Downloading… ${s.percent}%"
                    is com.nerfherder.walkthor.data.SpeechModel.State.Unpacking -> "Unpacking…"
                    is com.nerfherder.walkthor.data.SpeechModel.State.Ready ->
                        if (on) "Ready. Stop and start captions to switch to it." else "Ready. Captions use it from now on, offline."
                    is com.nerfherder.walkthor.data.SpeechModel.State.Failed -> s.message
                },
                color = BookColors.TextMuted, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        when (s) {
            is com.nerfherder.walkthor.data.SpeechModel.State.Missing, is com.nerfherder.walkthor.data.SpeechModel.State.Failed ->
                PillButton(if (s is com.nerfherder.walkthor.data.SpeechModel.State.Failed) "Try again" else "Download") {
                    scope.launch { com.nerfherder.walkthor.data.SpeechModel.download(context) }
                }
            is com.nerfherder.walkthor.data.SpeechModel.State.Ready ->
                PillButton("Remove", quiet = true) { if (on) Captions.stop(); com.nerfherder.walkthor.data.SpeechModel.delete(context) }
            else -> Unit
        }
    }
}
