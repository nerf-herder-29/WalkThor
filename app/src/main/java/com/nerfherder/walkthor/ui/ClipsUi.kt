package com.nerfherder.walkthor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.nerfherder.walkthor.Mirror
import com.nerfherder.walkthor.data.Clips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal val RecRed = Color(0xFFE0483C)

/**
 * Clip mode's controls, under the live view: keep the last seconds (with how many), save them,
 * record from now, and the picture quality.
 */
@Composable
internal fun ClipControls(game: String, enabled: Boolean, narrow: Boolean, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keeping by Clips.buffering.collectAsState()
    val recording by Clips.recording.collectAsState()
    val since by Clips.recordingSince.collectAsState()
    val error by Clips.error.collectAsState()
    var seconds by remember { mutableStateOf(Clips.seconds(context)) }
    var quality by remember { mutableStateOf(Clips.quality(context)) }
    var saving by remember { mutableStateOf(false) }
    // Game sound needs Android's "record audio" permission (it never uses the microphone).
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    fun withSound(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pending = action; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    fun projection() = Mirror.projection.value
    // On a short screen, only the controls (the live view keeps its room).
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 420

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        // ---- keep the last seconds ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep the last", color = BookColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (!compact) Text(
                    if (keeping) "Kept in memory only. Nothing is saved unless you press Save."
                    else "Off. Turn on to save moments after they happen.",
                    color = BookColors.TextMuted, fontSize = 11.sp, maxLines = 2,
                )
            }
            Switch(
                checked = keeping,
                enabled = enabled,
                onCheckedChange = { on ->
                    if (on) withSound { projection()?.let { Clips.startBuffer(context, it) } } else Clips.stopBuffer()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = RecRed, checkedThumbColor = Color.White),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = if (compact) 2.dp else 6.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Clips.LENGTHS.forEach { s ->
                SmallChip("$s s", s == seconds) {
                    seconds = s
                    Clips.setSeconds(context, s)
                    // Keeping fewer seconds than are there is fine; more fills in as it goes.
                    if (keeping) projection()?.let { Clips.startBuffer(context, it) }
                }
            }
        }
        // ---- save / record ----
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PillAction(
                "Save last $seconds s",
                enabled = keeping && !saving,
                modifier = Modifier.weight(1f),
            ) {
                saving = true
                scope.launch {
                    val uri = Clips.saveLast(context, game, seconds)
                    onSaved(if (uri != null) "Clip saved" else "Nothing to save yet")
                    saving = false
                }
            }
            Spacer(Modifier.width(10.dp))
            if (recording) RecordTimer(since)
            RecordButton(
                recording = recording,
                enabled = enabled || recording,
                size = if (narrow) 48.dp else 56.dp,
            ) {
                if (recording) scope.launch {
                    val uri = Clips.stopRecording()
                    onSaved(if (uri != null) "Clip saved" else "Couldn't save the clip")
                } else withSound { projection()?.let { Clips.startRecording(context, it, game) } }
            }
        }
        // ---- quality ----
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Quality", color = BookColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
            Clips.Quality.entries.forEach { q ->
                SmallChip(q.label, q == quality, enabled = !keeping && !recording) {
                    quality = q
                    Clips.setQuality(context, q)
                }
                Spacer(Modifier.width(6.dp))
            }
        }
        if (!compact || error != null) Text(
            error ?: "Saved to Movies/WalkThor. Lightest on game performance when you switch to another tab while it runs.",
            color = if (error != null) Color(0xFFE0A15A) else BookColors.TextMuted,
            fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun RecordTimer(since: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) { while (true) { now = SystemClock.elapsedRealtime(); delay(500) } }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    Text(
        "%d:%02d".format(s / 60, s % 60),
        color = Color(0xFFE88A80), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(end = 10.dp),
    )
}

/** Round record button: a red dot, or a red square to stop. */
@Composable
internal fun RecordButton(recording: Boolean, enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .border(3.dp, BookColors.Text.copy(alpha = alpha), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) Box(Modifier.size(size * 0.36f).background(RecRed, RoundedCornerShape(4.dp)))
        else Box(Modifier.size(size * 0.62f).background(RecRed.copy(alpha = alpha), CircleShape))
    }
}

@Composable
private fun SmallChip(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
        color = (if (on) Color.Black else BookColors.Text).copy(alpha = if (enabled || on) 1f else 0.45f),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) BookColors.Text.copy(alpha = if (enabled) 1f else 0.7f) else BookColors.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}

@Composable
private fun PillAction(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(BookColors.ButtonFill.copy(alpha = if (enabled) 1f else 0.5f))
            .border(1.dp, BookColors.ButtonEdge, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = BookColors.Text.copy(alpha = if (enabled) 1f else 0.5f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A still from the clip, for the list. */
@Composable
private fun rememberClipThumb(clip: Clips.Clip): Bitmap? {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, clip.uri) {
        value = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 29) runCatching { context.contentResolver.loadThumbnail(clip.uri, Size(480, 270), null) }.getOrNull() else null
        }
    }
    return thumb
}

@Composable
internal fun ClipThumb(clip: Clips.Clip, onClick: () -> Unit) {
    val thumb = rememberClipThumb(clip)
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(8.dp))
                .background(BookColors.Surface)
                .border(1.dp, BookColors.Hairline, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            thumb?.let { Image(it.asImageBitmap(), contentDescription = "Clip", contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
            PlayBadge()
            Text(
                duration(clip.durationMs),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Text(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(clip.takenAt)) + " · clip",
            color = BookColors.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun PlayBadge() {
    Canvas(Modifier.size(30.dp)) {
        drawCircle(Color(0xAA000000))
        val w = size.width
        val p = Path().apply {
            moveTo(w * 0.40f, w * 0.30f)
            lineTo(w * 0.72f, w * 0.50f)
            lineTo(w * 0.40f, w * 0.70f)
            close()
        }
        drawPath(p, Color.White)
        drawCircle(Color.White.copy(alpha = 0.5f), radius = w / 2 - 1f, center = Offset(w / 2, w / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
    }
}

private fun duration(ms: Long): String {
    val s = (ms + 500) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** One clip: play it (in the phone's video player), share it, or delete it. */
@Composable
internal fun ClipViewer(clip: Clips.Clip, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val thumb = rememberClipThumb(clip)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .background(BookColors.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, BookColors.Hairline, RoundedCornerShape(16.dp))
                .padding(14.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)
                    .clickable { play(context, clip) },
                contentAlignment = Alignment.Center,
            ) {
                thumb?.let { Image(it.asImageBitmap(), contentDescription = "Clip", contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize()) }
                PlayBadge()
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(clip.takenAt)) + " · " + duration(clip.durationMs),
                    color = BookColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { play(context, clip) }) { Text("Play") }
                TextButton(onClick = { share(context, clip) }) { Text("Share") }
                TextButton(onClick = { Clips.delete(context, clip); onDismiss() }) { Text("Delete", color = BookColors.TextMuted) }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

private fun play(context: android.content.Context, clip: Clips.Clip) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(clip.uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun share(context: android.content.Context, clip: Clips.Clip) {
    val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, clip.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, "Share clip").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
