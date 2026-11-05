package com.nerfherder.walkthor.ui

import android.os.Build
import android.view.Display
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nerfherder.walkthor.Mirror
import com.nerfherder.walkthor.data.Shots
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Shots tab: a live view of the top screen and a button to save a screenshot of it, the
 * whole screen or just the part you frame (remembered for each game). This game's screenshots
 * are listed alongside; tap one to view, share or delete it. Uses the same screen access as Mirror.
 */
@Composable
fun ShotsScreen(
    game: String,
    frame: ImageBitmap?,
    onStartCapture: () -> Unit,
    topBar: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projection by Mirror.projection.collectAsState()
    val error by Mirror.error.collectAsState()
    var useArea by remember(game) { mutableStateOf(Shots.useArea(context, game)) }
    var area by remember(game) { mutableStateOf(Shots.area(context, game) ?: floatArrayOf(0.25f, 0.2f, 0.75f, 0.8f)) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<Shots.Shot?>(null) }
    val version by Shots.version.collectAsState()
    val shots by produceState(emptyList<Shots.Shot>(), game, version) { value = Shots.list(context, game) }
    val clipsVersion by com.nerfherder.walkthor.data.Clips.version.collectAsState()
    val clips by produceState(emptyList<com.nerfherder.walkthor.data.Clips.Clip>(), game, clipsVersion) {
        value = com.nerfherder.walkthor.data.Clips.list(context, game)
    }
    var viewingClip by remember { mutableStateOf<com.nerfherder.walkthor.data.Clips.Clip?>(null) }
    val keepingClips by com.nerfherder.walkthor.data.Clips.buffering.collectAsState()
    val recordingClip by com.nerfherder.walkthor.data.Clips.recording.collectAsState()
    var clipMode by remember { mutableStateOf(keepingClips || recordingClip || ShotsPrefs.clipMode(context)) }
    // Plain buttons by default; the live view is there when you want to frame an area.
    var showPreview by remember { mutableStateOf(ShotsPrefs.preview(context)) }
    fun setPreview(on: Boolean) { showPreview = on; ShotsPrefs.setPreview(context, on) }
    // Screenshots and clips together, newest first.
    val items = remember(shots, clips) {
        (shots.map { it.takenAt to (it as Any) } + clips.map { it.takenAt to (it as Any) }).sortedByDescending { it.first }.map { it.second }
    }
    val narrow = LocalConfiguration.current.screenWidthDp < 520
    // If WalkThor is itself on the top screen, a screenshot would just show WalkThor.
    val onMainDisplay = remember {
        (com.nerfherder.walkthor.AppState.activityDisplayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY &&
            com.nerfherder.walkthor.DisplayHelper.bottomDisplay(context)?.displayId != Display.DEFAULT_DISPLAY &&
            com.nerfherder.walkthor.AppState.activityDisplayId != null
    }
    // Ask Android for screen access as soon as the tab opens (it shows its own prompt), as Mirror does.
    LaunchedEffect(Unit) { if (frame == null && Mirror.projection.value == null && !onMainDisplay) onStartCapture() }
    // No dimming while you're lining up a shot.
    DisposableEffect(Unit) {
        val holder = Any()
        AutoDim.wake()
        AutoDim.holdBright(holder, true)
        onDispose { AutoDim.holdBright(holder, false) }
    }
    LaunchedEffect(saved) { if (saved != null) { delay(2_500); saved = null } }

    fun take() {
        if (busy) return
        busy = true
        // A copy of the live picture, in case a full-size capture isn't possible.
        val preview = frame?.let { f -> runCatching { f.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false) }.getOrNull() }
        val chosen = if (useArea) area.copyOf() else null
        scope.launch {
            val shot = runCatching { Shots.take(context, Mirror.projection.value, preview, game, chosen) }.getOrNull()
            saved = if (shot != null) "Saved · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(shot.takenAt))
            else "Couldn't take the screenshot"
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(backdropOr(Color.Black))) {
        topBar?.invoke()
        when {
            Build.VERSION.SDK_INT < 29 -> CenterNote("Screenshots and clips need Android 10 or newer.")
            onMainDisplay -> CenterNote("WalkThor is on the top screen, so a screenshot or clip would only show WalkThor. Move it to the bottom screen first.")
            projection == null && frame == null -> StartShots(error, onStartCapture)
            !showPreview -> ShotButtons(
                game = game, items = items, shotsCount = shots.size, clipsCount = clips.size, narrow = narrow,
                ready = frame != null || projection != null, busy = busy, useArea = useArea, saved = saved,
                recording = recordingClip,
                onShot = ::take,
                onArea = { on -> useArea = on; Shots.setUseArea(context, game, on); if (on) Shots.setArea(context, game, area) },
                onPreview = { setPreview(true) },
                onSaved = { saved = it },
                onView = { item -> if (item is Shots.Shot) viewing = item else viewingClip = item as com.nerfherder.walkthor.data.Clips.Clip },
            )
            else -> Row(
                Modifier.fillMaxSize().padding(horizontal = if (narrow) 10.dp else 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (narrow) 10.dp else 16.dp),
            ) {
                // ---- the live view, the framing and the button ----
                Column(Modifier.weight(if (narrow) 0.6f else 0.62f).fillMaxHeight()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .heightIn(min = 90.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0B0B0D)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val img = frame
                        when {
                            img == null -> Text("Waiting for the top screen…", color = BookColors.TextMuted, fontSize = 13.sp)
                            useArea && !clipMode -> AreaPicker(img, area, shape = null, onChange = { area = it; Shots.setArea(context, game, it) }, pad = 0.dp)
                            else -> WholeScreen(img)
                        }
                        if (recordingClip) {
                            Row(
                                Modifier.align(Alignment.TopStart).padding(10.dp)
                                    .background(Color(0xCC0D0D10), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(8.dp).background(RecRed, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text("REC", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        saved?.let {
                            Text(
                                it,
                                color = BookColors.Text,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 10.dp)
                                    .background(Color(0xEE0D0D10), RoundedCornerShape(50))
                                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                    // Screenshot or clip.
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp)
                            .background(BookColors.SurfaceHigh, RoundedCornerShape(50)).padding(3.dp),
                    ) {
                        SegmentTab("Screenshot", !clipMode, Modifier.weight(1f)) { clipMode = false; ShotsPrefs.setClipMode(context, false) }
                        SegmentTab("Clip", clipMode, Modifier.weight(1f), dot = keepingClips || recordingClip) { clipMode = true; ShotsPrefs.setClipMode(context, true) }
                    }
                    if (clipMode) {
                        ClipControls(game, enabled = projection != null || com.nerfherder.walkthor.data.Clips.demoReady, narrow = narrow, onSaved = { saved = it })
                    } else {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ModeChip("Whole screen", !useArea) { useArea = false; Shots.setUseArea(context, game, false) }
                            Spacer(Modifier.width(8.dp))
                            ModeChip("Area", useArea) { useArea = true; Shots.setUseArea(context, game, true); Shots.setArea(context, game, area) }
                            Spacer(Modifier.weight(1f))
                            CaptureButton(enabled = frame != null && !busy, size = if (narrow) 48.dp else 60.dp, onClick = ::take)
                        }
                        Text(
                            if (useArea) "Drag the frame over the part you want; it's kept for this game."
                            else "The whole top screen.",
                            color = BookColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Text(
                        "Hide the live view", color = BookColors.TextMuted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).clickable { setPreview(false) }.padding(vertical = 4.dp),
                    )
                }
                // ---- this game's screenshots ----
                Column(Modifier.weight(if (narrow) 0.4f else 0.38f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Text("This game", color = BookColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            listOfNotNull(
                                "${shots.size} shot${if (shots.size == 1) "" else "s"}",
                                clips.size.takeIf { it > 0 }?.let { "$it clip${if (it == 1) "" else "s"}" },
                            ).joinToString(" · "),
                            color = BookColors.TextMuted, fontSize = 12.sp,
                        )
                    }
                    if (items.isEmpty()) {
                        Text("Your screenshots and clips of this game appear here.", color = BookColors.TextMuted.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (narrow) 1 else 2),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { (if (it is Shots.Shot) it.uri else (it as com.nerfherder.walkthor.data.Clips.Clip).uri).toString() }) { item ->
                            when (item) {
                                is Shots.Shot -> ShotThumb(item) { viewing = item }
                                is com.nerfherder.walkthor.data.Clips.Clip -> ClipThumb(item) { viewingClip = item }
                            }
                        }
                    }
                }
            }
        }
    }
    viewing?.let { s -> ShotViewer(s, onDismiss = { viewing = null }) }
    viewingClip?.let { c -> ClipViewer(c, onDismiss = { viewingClip = null }) }
}

@Composable
private fun WholeScreen(img: ImageBitmap) {
    Canvas(Modifier.fillMaxSize()) {
        val s = min(size.width / img.width, size.height / img.height)
        val w = img.width * s
        val h = img.height * s
        drawImage(
            img,
            dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}

/** Remembers whether the Shots tab was last on Screenshot or Clip, and whether it shows the live view. */
internal object ShotsPrefs {
    private fun prefs(c: android.content.Context) = c.getSharedPreferences("shots", android.content.Context.MODE_PRIVATE)
    fun clipMode(c: android.content.Context) = prefs(c).getBoolean("clipMode", false)
    fun setClipMode(c: android.content.Context, on: Boolean) = prefs(c).edit().putBoolean("clipMode", on).apply()
    fun preview(c: android.content.Context) = prefs(c).getBoolean("preview", false)
    fun setPreview(c: android.content.Context, on: Boolean) = prefs(c).edit().putBoolean("preview", on).apply()
}

/**
 * The Shots tab without the live view: a screenshot button, the clip controls, where they're
 * saved (and a way to change it), and this game's screenshots and clips beside them.
 */
@Composable
private fun ShotButtons(
    game: String, items: List<Any>, shotsCount: Int, clipsCount: Int, narrow: Boolean,
    ready: Boolean, busy: Boolean, useArea: Boolean, saved: String?, recording: Boolean,
    onShot: () -> Unit, onArea: (Boolean) -> Unit, onPreview: () -> Unit, onSaved: (String) -> Unit, onView: (Any) -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxSize().padding(horizontal = if (narrow) 10.dp else 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (narrow) 10.dp else 16.dp),
    ) {
        Column(Modifier.weight(0.6f).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            // ---- screenshot ----
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(BookColors.Surface)
                    .border(1.dp, BookColors.Hairline, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CaptureButton(enabled = ready && !busy, size = if (narrow) 52.dp else 62.dp, onClick = onShot)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Screenshot", color = BookColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        saved ?: if (useArea) "The area you framed for this game" else "The whole top screen",
                        color = if (saved != null) BookColors.Text else BookColors.TextMuted, fontSize = 12.sp,
                    )
                    Row(Modifier.padding(top = 8.dp)) {
                        ModeChip("Whole screen", !useArea) { onArea(false) }
                        Spacer(Modifier.width(8.dp))
                        ModeChip("Area", useArea) { onArea(true) }
                    }
                }
            }
            // ---- clips ----
            Column(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(BookColors.Surface)
                    .border(1.dp, BookColors.Hairline, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Clip", color = BookColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (recording) {
                        Box(Modifier.size(8.dp).background(RecRed, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text("REC", color = BookColors.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                ClipControls(game, enabled = ready || com.nerfherder.walkthor.data.Clips.demoReady, narrow = narrow, onSaved = onSaved)
            }
            // ---- where they go ----
            SaveFolderRows(Modifier.padding(top = 10.dp))
            Text(
                "Show the live view (to frame an area)", color = BookColors.TextMuted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).clickable(onClick = onPreview).padding(vertical = 4.dp),
            )
        }
        // ---- this game's screenshots and clips ----
        Column(Modifier.weight(0.4f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("This game", color = BookColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    listOfNotNull(
                        "$shotsCount shot${if (shotsCount == 1) "" else "s"}",
                        clipsCount.takeIf { it > 0 }?.let { "$it clip${if (it == 1) "" else "s"}" },
                    ).joinToString(" · "),
                    color = BookColors.TextMuted, fontSize = 12.sp,
                )
            }
            if (items.isEmpty()) {
                Text("Your screenshots and clips of this game appear here.", color = BookColors.TextMuted.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (narrow) 1 else 2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { (if (it is Shots.Shot) it.uri else (it as com.nerfherder.walkthor.data.Clips.Clip).uri).toString() }) { item ->
                    when (item) {
                        is Shots.Shot -> ShotThumb(item) { onView(item) }
                        is com.nerfherder.walkthor.data.Clips.Clip -> ClipThumb(item) { onView(item) }
                    }
                }
            }
        }
    }
}

/** Where screenshots and clips are saved, each with Change (Android's folder picker). */
@Composable
internal fun SaveFolderRows(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var shots by remember { mutableStateOf(com.nerfherder.walkthor.data.SaveFolders.shots(context)) }
    var clips by remember { mutableStateOf(com.nerfherder.walkthor.data.SaveFolders.clips(context)) }
    var problem by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf("shots") }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = com.nerfherder.walkthor.data.SaveFolders.pathOf(uri)
        val ok = path != null && if (picking == "shots") com.nerfherder.walkthor.data.SaveFolders.setShots(context, path)
        else com.nerfherder.walkthor.data.SaveFolders.setClips(context, path)
        problem = if (ok) null else if (picking == "shots") "Screenshots can go in a folder inside Pictures or DCIM." else "Clips can go in a folder inside Movies or DCIM."
        shots = com.nerfherder.walkthor.data.SaveFolders.shots(context)
        clips = com.nerfherder.walkthor.data.SaveFolders.clips(context)
    }
    Column(modifier) {
        listOf("shots" to ("Screenshots" to shots), "clips" to ("Clips" to clips)).forEach { (kind, pair) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pair.first, color = BookColors.TextMuted, fontSize = 12.sp, modifier = Modifier.width(84.dp))
                Text(pair.second, color = BookColors.Text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text(
                    "Change", color = BookColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { picking = kind; runCatching { picker.launch(null) } }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        problem?.let { Text(it, color = Color(0xFFE0675A), fontSize = 12.sp) }
    }
}

@Composable
private fun SegmentTab(label: String, on: Boolean, modifier: Modifier, dot: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) BookColors.Text else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) { Box(Modifier.size(7.dp).background(RecRed, CircleShape)); Spacer(Modifier.width(6.dp)) }
        Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) Color.Black else BookColors.Text)
    }
}

@Composable
private fun ModeChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
        color = if (on) Color.Black else BookColors.Text,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) BookColors.Text else BookColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** The big round shutter button. */
@Composable
private fun CaptureButton(enabled: Boolean, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .border(3.dp, BookColors.Text.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp)
            .clip(CircleShape)
            .background(BookColors.Text.copy(alpha = if (enabled) 1f else 0.4f)),
    )
}

@Composable
private fun ShotThumb(s: Shots.Shot, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            s.uri,
            contentDescription = "Screenshot",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(8.dp))
                .background(BookColors.Surface)
                .border(1.dp, BookColors.Hairline, RoundedCornerShape(8.dp)),
        )
        Text(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.takenAt)) + if (s.area) " · area" else "",
            color = BookColors.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** One screenshot, large, with Share and Delete. */
@Composable
private fun ShotViewer(s: Shots.Shot, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .background(BookColors.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, BookColors.Hairline, RoundedCornerShape(16.dp))
                .padding(14.dp),
        ) {
            AsyncImage(
                s.uri,
                contentDescription = "Screenshot",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black),
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.takenAt)),
                    color = BookColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { Shots.share(context, s) }) { Text("Share") }
                TextButton(onClick = { Shots.delete(context, s); onDismiss() }) { Text("Delete", color = BookColors.TextMuted) }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = BookColors.TextMuted, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StartShots(error: String?, onStart: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Screenshots and clips of the top screen", fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = BookColors.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Save the whole top screen, or just the part you frame, without leaving your game. Or record clips " +
                    "with the game's sound: keep the last few seconds and save them after something happens, or record " +
                    "from now until you stop. Android asks for screen access each time. Nothing is saved until you press " +
                    "a button: screenshots go to Pictures/WalkThor, clips to Movies/WalkThor.",
                color = BookColors.TextMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFE0675A), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
            ) { Text("Start", fontWeight = FontWeight.SemiBold) }
        }
    }
}
