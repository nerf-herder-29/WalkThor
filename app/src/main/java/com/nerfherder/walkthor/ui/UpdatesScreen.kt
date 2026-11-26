package com.nerfherder.walkthor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.nerfherder.walkthor.data.Updates
import com.nerfherder.walkthor.data.Updates.State

/** Your stick lights' colour, the same accent as everywhere else (white while they're off). */
@Composable
private fun accentColor(): Color = frontAccent()

/**
 * Updates for WalkThor and your emulators, as cards. [header] adds the title, when it was last
 * checked and Check now (the drawer's page has no header of its own to put them in).
 */
@Composable
fun UpdatesScreen(header: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Updates.loadAndMaybeCheck(context) }
    val entries by Updates.entries.collectAsState()
    val checking by Updates.checking.collectAsState()
    val offline by Updates.offline.collectAsState()
    val checkedAt by Updates.checkedAt.collectAsState()
    val progress by Updates.progress.collectAsState()
    var details by remember { mutableStateOf<Updates.Entry?>(null) }
    var settingPage by remember { mutableStateOf<Updates.Entry?>(null) }
    val accent = accentColor()

    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        val when_ = when {
            checking -> "Checking…"
            checkedAt == 0L -> "Not checked yet"
            else -> "Checked " + ago(checkedAt)
        }
        val waiting = Updates.waiting(entries)
        if (header) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$when_ · " + if (waiting == 0) "all up to date" else "$waiting update" + (if (waiting == 1) "" else "s"), color = BookColors.TextMuted, fontSize = 12.sp)
                    Text("Updates", color = BookColors.Text, fontSize = 26.sp, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold)
                }
                PillButton(if (checking) "Checking…" else "Check now") { if (!checking) Updates.check(context) }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$when_ · " + if (waiting == 0) "all up to date" else "$waiting update" + (if (waiting == 1) "" else "s"), color = BookColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                PillButton(if (checking) "Checking…" else "Check now") { if (!checking) Updates.check(context) }
            }
        }
        if (offline) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BookColors.SurfaceHigh).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(BookColors.Brass))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Offline" + (if (checkedAt > 0) " · showing what was found ${ago(checkedAt)}" else "") + ". Checks again when you're back online.",
                    color = BookColors.TextMuted, fontSize = 12.sp,
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 220.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(entries, key = { it.pkg }) { e ->
                UpdateCard(e, progress[e.pkg], offline, accent, onOpen = { details = e }, onSetPage = { settingPage = e })
            }
        }
    }

    // Panels drawn over the cards, not separate windows: the drawer has no app window for a
    // dialog to open from, and a panel gets the whole page for the release notes.
    if (!LocalInDrawer.current && (details != null || settingPage != null)) {
        androidx.activity.compose.BackHandler { if (settingPage != null) settingPage = null else details = null }
    }
    details?.let { e -> DetailsPanel(e, offline, accent, onClose = { details = null }, onChangePage = { details = null; settingPage = e }) }
    settingPage?.let { e -> PagePanel(e, onClose = { settingPage = null }) }
    }
}

/** A dimmed backdrop over the page; tapping it closes [onClose]. */
@Composable
private fun Scrim(onClose: () -> Unit, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val none = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(interactionSource = none, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Taps inside a panel stay there (rather than closing it). */
@Composable
private fun Modifier.keepTaps(): Modifier {
    val none = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    return this.clickable(interactionSource = none, indication = null) {}
}

/** One emulator's newest release: what's new, its page, and Update. */
@Composable
private fun DetailsPanel(e: Updates.Entry, offline: Boolean, accent: Color, onClose: () -> Unit, onChangePage: () -> Unit) {
    val context = LocalContext.current
    Scrim(onClose) {
        Column(
            Modifier
                .padding(14.dp)
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(BookColors.Surface)
                .border(1.dp, BookColors.Hairline, RoundedCornerShape(20.dp))
                .keepTaps()
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconImage(e.pkg)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name + (e.latest?.let { " $it" } ?: ""), color = BookColors.Text, fontSize = 20.sp, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            e.installed?.let { "You have $it" },
                            e.published.takeIf { it > 0 }?.let { "released " + ago(it) },
                            e.apkSize.takeIf { it > 0 }?.let { "%.0f MB".format(it / 1_048_576.0) },
                        ).joinToString(" · "),
                        color = BookColors.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                CardButton("Close", BookColors.Text) { onClose() }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(BookColors.Hairline))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 14.dp)) {
                Text("WHAT'S NEW", color = accent, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (e.notes.isNullOrBlank()) "This release doesn't say what changed. Its release page may have more." else plainNotes(e.notes),
                    color = BookColors.Text, fontSize = 14.sp, lineHeight = 21.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (e.page != null) CardButton("Release page", BookColors.Text) { openPage(context, e.page) }
                if (e.pkg != context.packageName) CardButton("Change page", BookColors.Text) { onChangePage() }
                Spacer(Modifier.weight(1f))
                if (e.state == State.PLAY_STORE) CardButton("Update in Play Store", accent) { onClose(); openPlay(context, e.pkg) }
                if (e.state == State.UPDATE) CardButton(if (offline) "Update (offline)" else "Update", accent, enabled = !offline) { onClose(); Updates.update(context, e) }
            }
        }
    }
}

/** Where an emulator publishes its releases. */
@Composable
private fun PagePanel(e: Updates.Entry, onClose: () -> Unit) {
    val context = LocalContext.current
    var text by remember(e.pkg) { mutableStateOf(e.page ?: "") }
    val valid = text.isBlank() || Updates.normalisePage(text) != null
    Scrim(onClose) {
        Column(
            Modifier
                .padding(14.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(BookColors.Surface)
                .border(1.dp, BookColors.Hairline, RoundedCornerShape(20.dp))
                .keepTaps()
                .padding(18.dp),
        ) {
            Text("${e.name}'s release page", color = BookColors.Text, fontSize = 20.sp, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Paste the link to where ${e.name} publishes its releases: a GitHub page (github.com/owner/project), or a Forgejo or Gitea site's. WalkThor checks its newest release there. (Dolphin's site, dolphin-emu.org, works too.)",
                color = BookColors.TextMuted, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("github.com/owner/project") }, modifier = Modifier.fillMaxWidth())
            if (!valid) Text("That doesn't look like a project link (it needs the owner and the project)", color = Color(0xFFE8B25A), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                CardButton("Cancel", BookColors.TextMuted) { onClose() }
                CardButton(if (text.isBlank()) "Clear" else "Save", BookColors.Text, enabled = valid) { Updates.setPage(context, e.pkg, text); onClose() }
            }
        }
    }
}

/**
 * A tab on the drawer's edge saying what a swipe brings up ([toLeft]: it sits on the left edge,
 * for going back). Tapping it does the same as the swipe. [count] shows as a badge.
 */
@Composable
fun SwipeTab(label: String, toLeft: Boolean, count: Int = 0, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = accentColor()
    val shape = if (toLeft) RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp) else RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
    Column(
        modifier
            .clip(shape)
            .background(BookColors.SurfaceHigh)
            .border(1.dp, BookColors.Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (toLeft) "‹" else "›", color = BookColors.TextMuted, fontSize = 15.sp, lineHeight = 15.sp)
        Spacer(Modifier.height(4.dp))
        // The label reads downwards along the edge.
        Text(
            label.uppercase(), color = BookColors.TextMuted, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            modifier = Modifier.verticalText(),
        )
        if (count > 0) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.size(16.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                Text("$count", color = Color.Black, fontSize = 9.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Turns text a quarter turn so it reads top to bottom, taking the room it needs that way. */
private fun Modifier.verticalText(): Modifier = this
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity, minHeight = 0, maxHeight = constraints.maxWidth.coerceAtLeast(1)))
        layout(p.height, p.width) { p.place(-(p.width - p.height) / 2, (p.width - p.height) / 2) }
    }
    .rotate(90f)

@Composable
private fun UpdateCard(e: Updates.Entry, progress: Float?, offline: Boolean, accent: Color, onOpen: () -> Unit, onSetPage: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(BookColors.Surface)
            .border(1.dp, BookColors.Hairline, RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconImage(e.pkg)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, color = BookColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (e.state) {
                        State.UPDATE, State.NO_APK, State.WRONG_SIGNER, State.PLAY_STORE -> "${e.installed ?: "?"} → ${e.latest}"
                        State.UP_TO_DATE -> "${e.installed ?: "?"} · up to date"
                        else -> e.installed ?: ""
                    },
                    color = BookColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        when {
            progress != null -> {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(BookColors.SurfaceHigh)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(5.dp).background(accent))
                }
                Spacer(Modifier.height(6.dp))
                Text(if (progress >= 1f) "Installing…" else "Downloading · ${(progress * 100).toInt()}%", color = BookColors.TextMuted, fontSize = 12.sp)
            }
            e.state == State.UPDATE -> CardButton(if (offline) "Update (offline)" else "Update", accent, enabled = !offline) { Updates.update(context, e) }
            e.state == State.UP_TO_DATE -> Text("Up to date", color = BookColors.Brass, fontSize = 13.sp)
            e.state == State.PLAY_STORE -> CardButton("Update in Play Store", accent) { openPlay(context, e.pkg) }
            e.state == State.CHECKING -> Text(if (offline) "Not checked yet (offline)" else "Checking…", color = BookColors.Brass, fontSize = 13.sp)
            e.state == State.NO_PAGE -> CardButton("Set release page", BookColors.Text) { onSetPage() }
            e.state == State.PAGE_NOT_FOUND -> Column {
                Text("Release page not found", color = Color(0xFFE8B25A), fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                CardButton("Change page", BookColors.Text) { onSetPage() }
            }
            e.state == State.NO_APK -> Text("No app file in this release: see its page", color = Color(0xFFE8B25A), fontSize = 12.sp, maxLines = 2)
            e.state == State.WRONG_SIGNER -> Text("Signed by a different developer: can't update over yours", color = Color(0xFFE8B25A), fontSize = 12.sp, maxLines = 2)
            e.state == State.FAILED -> Text("Couldn't check: " + (e.problem ?: "try again later"), color = Color(0xFFE8B25A), fontSize = 12.sp, maxLines = 2)
            else -> Unit
        }
    }
}

@Composable
private fun CardButton(text: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) color else BookColors.Brass,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(BookColors.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun AppIconImage(pkg: String) {
    val context = LocalContext.current
    val icon: ImageBitmap? = remember(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
    else Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(BookColors.SurfaceHigh))
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / (60 * 24)} days ago"
    }
}

/** Release notes as plain text: the markdown marks taken out, lists as bullets. */
private fun plainNotes(md: String): String = md.lines().joinToString("\n") { line ->
    line.trimEnd()
        .replace(Regex("^#{1,6}\\s*"), "")
        .replace(Regex("^\\s*[-*+]\\s+"), "• ")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
}.replace(Regex("\n{3,}"), "\n\n").trim().take(4000)

private fun openPage(context: android.content.Context, page: String) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(Updates.releasesUrl(page))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** [pkg]'s page in the Play Store app (or on the Play website, without it). */
private fun openPlay(context: android.content.Context, pkg: String) {
    val flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg")).setPackage("com.android.vending").addFlags(flags))
    }.recoverCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(flags))
    }
}
