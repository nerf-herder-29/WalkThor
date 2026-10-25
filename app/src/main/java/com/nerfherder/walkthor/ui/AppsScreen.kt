package com.nerfherder.walkthor.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.nerfherder.walkthor.AppState
import com.nerfherder.walkthor.DisplayHelper
import com.nerfherder.walkthor.Revive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** An installed app that can be opened from the home screen. */
data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap?)

/**
 * The apps you can open from the Apps tab, and the ones you've pinned. The list is read on demand
 * (when the tab opens) and kept for the session; nothing about your apps leaves the device.
 */
object AppCatalog {
    val apps = MutableStateFlow<List<InstalledApp>?>(null)
    val pinned = MutableStateFlow<List<String>>(emptyList())
    private var pinsLoaded = false

    private fun prefs(context: Context) = context.getSharedPreferences("apps", Context.MODE_PRIVATE)

    fun loadPins(context: Context) {
        if (pinsLoaded) return
        pinsLoaded = true
        pinned.value = prefs(context).getString("pinned", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    }

    fun setPinned(context: Context, pkg: String, on: Boolean) {
        val now = pinned.value.filter { it != pkg } + (if (on) listOf(pkg) else emptyList())
        pinned.value = now
        prefs(context).edit().putString("pinned", now.joinToString(",")).apply()
    }

    /** Reads the installed apps (everything with a home-screen icon, except WalkThor). */
    suspend fun refresh(context: Context) {
        val list = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val size = (56 * context.resources.displayMetrics.density).toInt()
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { ri ->
                    InstalledApp(
                        packageName = ri.activityInfo.packageName,
                        label = ri.loadLabel(pm).toString(),
                        icon = runCatching { ri.loadIcon(pm).toBitmap(size, size).asImageBitmap() }.getOrNull(),
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
        apps.value = list
    }

    /**
     * Opens [pkg] on WalkThor's own screen ([here]) or on the other one. Opening it here puts it
     * over WalkThor, so WalkThor won't reopen itself over it if Android closes WalkThor meanwhile
     * (it carries on as usual once you come back).
     */
    fun open(context: Context, pkg: String, here: Boolean) {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Toast.makeText(context, "That app can't be opened", Toast.LENGTH_SHORT).show()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = context as? Activity
        val target = if (here) {
            AppState.activityDisplayId ?: activity?.let { DisplayHelper.currentDisplayId(it) }
        } else {
            activity?.let { DisplayHelper.gameDisplay(it)?.displayId }
        }
        if (here) {
            Revive.closedOnPurpose(context)
            // It opens beside the game: it doesn't end the game or start a new one.
            com.nerfherder.walkthor.EmulatorWatch.openedByWalkThor(pkg)
        }
        val options = target?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        runCatching { context.startActivity(launch, options) }
            .onFailure { Toast.makeText(context, "Couldn't open that app", Toast.LENGTH_SHORT).show() }
    }

    fun openInfo(context: Context, pkg: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Revive.closedOnPurpose(context)
        val display = AppState.activityDisplayId
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        runCatching { context.startActivity(intent, options) }
    }
}

/**
 * The Apps tab: your pinned apps, then every app, with a search box. Tap to open an app on this
 * screen; press and hold for more (open on the top screen, pin, app info).
 */
@Composable
fun AppsScreen(
    topBar: (@Composable () -> Unit)? = null,
    /** Fixed apps for screenshots. */
    preview: List<InstalledApp>? = null,
) {
    val context = LocalContext.current
    val compact = LocalConfiguration.current.screenHeightDp < 480
    LaunchedEffect(Unit) {
        if (preview == null) {
            AppCatalog.loadPins(context)
            AppCatalog.refresh(context)
        }
    }
    val loaded by AppCatalog.apps.collectAsState()
    val all = preview ?: loaded
    val pinnedIds by AppCatalog.pinned.collectAsState()
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<InstalledApp?>(null) }

    Column(Modifier.fillMaxSize().background(backdropOr(BookColors.Ink))) {
        topBar?.invoke()
        if (all == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Loading your apps…", color = BookColors.TextMuted)
            }
        } else {
            val byPkg = remember(all) { all.associateBy { it.packageName } }
            val pinnedApps = pinnedIds.mapNotNull { byPkg[it] }
            val shown = if (query.isBlank()) all else all.filter { it.label.contains(query.trim(), ignoreCase = true) }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (compact) 76.dp else 92.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.padding(top = if (compact) 8.dp else 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (query.isBlank()) "Pinned" else "Results",
                            style = EyebrowStyle,
                            modifier = Modifier.weight(1f),
                        )
                        SearchBox(query, { query = it }, Modifier.width(if (compact) 180.dp else 220.dp))
                    }
                }
                if (query.isBlank()) {
                    if (pinnedApps.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "Press and hold an app below to pin it here.",
                                color = BookColors.TextMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    } else {
                        items(pinnedApps, key = { "pin:" + it.packageName }) { app ->
                            AppIcon(app, compact, onOpen = { AppCatalog.open(context, app.packageName, here = true) }, onHold = { menuFor = app })
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                            Text("All apps", style = EyebrowStyle, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                items(shown, key = { it.packageName }) { app ->
                    AppIcon(app, compact, onOpen = { AppCatalog.open(context, app.packageName, here = true) }, onHold = { menuFor = app })
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
            }
            Text(
                "Tap to open on this screen  ·  Hold for more",
                color = BookColors.TextMuted.copy(alpha = 0.8f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) 4.dp else 8.dp),
            )
        }
    }

    menuFor?.let { app ->
        val isPinned = app.packageName in pinnedIds
        AppMenu(
            app = app,
            pinned = isPinned,
            onDismiss = { menuFor = null },
            onOpenHere = { menuFor = null; AppCatalog.open(context, app.packageName, here = true) },
            onOpenTop = { menuFor = null; AppCatalog.open(context, app.packageName, here = false) },
            onTogglePin = { menuFor = null; AppCatalog.setPinned(context, app.packageName, !isPinned) },
            onInfo = { menuFor = null; AppCatalog.openInfo(context, app.packageName) },
        )
    }
}

@Composable
internal fun SearchBox(query: String, onChange: (String) -> Unit, modifier: Modifier) {
    Row(
        modifier
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = BookColors.TextMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search apps", color = BookColors.TextMuted, fontSize = 13.sp)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = AppFont.family, color = BookColors.Text, fontSize = 13.sp),
                cursorBrush = SolidColor(BookColors.Text),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One app: its icon over its name. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AppIcon(app: InstalledApp, compact: Boolean, onOpen: () -> Unit, onHold: (() -> Unit)?) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onHold?.let { hold ->
                    {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        hold()
                    }
                },
                onClick = onOpen,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val s = if (compact) 40.dp else 52.dp
        if (app.icon != null) Image(app.icon, contentDescription = null, modifier = Modifier.size(s))
        else Box(Modifier.size(s).background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp)))
        Spacer(Modifier.height(6.dp))
        Text(
            app.label,
            color = BookColors.Text,
            fontSize = if (compact) 11.sp else 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun AppMenu(
    app: InstalledApp,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onOpenHere: () -> Unit,
    onOpenTop: () -> Unit,
    onTogglePin: () -> Unit,
    onInfo: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(300.dp)
                .background(Color(0xFF0B0B0D), RoundedCornerShape(18.dp))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (app.icon != null) Image(app.icon, contentDescription = null, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(app.label, color = BookColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (pinned) Text("Pinned", style = EyebrowStyle)
                }
            }
            Spacer(Modifier.height(8.dp))
            MenuRow("Open on this screen", onOpenHere)
            MenuRow("Open on the top screen", onOpenTop)
            MenuRow(if (pinned) "Unpin" else "Pin", onTogglePin)
            MenuRow("App info", onInfo, muted = true)
        }
    }
}

@Composable
private fun MenuRow(text: String, onClick: () -> Unit, muted: Boolean = false) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        Text(
            text,
            color = if (muted) BookColors.TextMuted else BookColors.Text,
            fontSize = 15.sp,
            modifier = Modifier.padding(vertical = 13.dp, horizontal = 4.dp),
        )
    }
}
