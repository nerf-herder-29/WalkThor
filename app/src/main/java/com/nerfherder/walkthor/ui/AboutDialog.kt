package com.nerfherder.walkthor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val REPO = "https://github.com/nerf-herder-29/WalkThor"

private data class Component(val name: String, val use: String, val license: String)

private val COMPONENTS = listOf(
    Component("PdfBox-Android (port of Apache PDFBox)", "Reading PDF text for search", "Apache License 2.0"),
    Component("Coil", "Showing cover artwork", "Apache License 2.0"),
    Component("Jetpack Compose & AndroidX", "Interface and app framework", "Apache License 2.0"),
    Component("Kotlin & kotlinx.coroutines", "Language runtime, background work", "Apache License 2.0"),
    Component("Space Grotesk", "The app's typeface", "SIL Open Font License 1.1"),
)

/**
 * Shown before swipe-to-open can be switched on: what the accessibility service does, what it
 * accesses, and what it doesn't, with an explicit choice. Required by Google Play for apps that
 * use an accessibility service without being an accessibility tool.
 */
@Composable
fun SwipeDisclosureDialog(onAgree: () -> Unit, onDecline: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BookColors.Surface,
        title = { Text("Before you allow accessibility access", fontFamily = DisplayFont) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "WalkThor uses an Android accessibility service, “WalkThor”, for two things: " +
                        "noticing when you swipe down from the top edge of the bottom screen, so it can open or " +
                        "close WalkThor, and handing your game back Android's top performance priority after " +
                        "WalkThor opens, so the game doesn't slow down.",
                    color = BookColors.Text,
                )
                Spacer(Modifier.height(12.dp))
                Text("What it does on the game's screen", style = EyebrowStyle)
                Text(
                    "While a game is running, a couple of seconds after WalkThor opens (or after you finish typing " +
                        "a search), it taps the very top edge " +
                        "of the game's screen once, in the middle, just as you could yourself. Android gives top " +
                        "priority to the screen you last touched, so this hands it back to the game. Turning off " +
                        "accessibility access stops it too.",
                    color = BookColors.TextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("What it accesses", style = EyebrowStyle)
                Text(
                    "Touches that begin in a thin strip along the top edge of the bottom screen. And, only if you " +
                        "agree on the Face Buttons screen, and only while that screen is open, your " +
                        "controller button presses, to light up the matching buttons. Every press is passed straight " +
                        "on to your game; none is recorded or stored. And, only if you turn on Open when an " +
                        "emulator starts, which app is in front, plus the running emulator's " +
                        "own window title and notifications, to tell which game you're playing; and with Recognise the game " +
                        "you pick, the text on your frontend's screen (in memory only).",
                    color = BookColors.TextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("What it doesn't do", style = EyebrowStyle)
                Text(
                    "It doesn't read what's on your screen or in other apps (beyond the above) and doesn't record what you type. " +
                        "It doesn't collect, store or share any data, and never sends anything it sees anywhere.",
                    color = BookColors.TextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "You can turn it off any time from Settings (⋮) or Android's Accessibility settings.",
                    color = BookColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAgree,
                colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
            ) { Text("Agree & continue", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDecline) { Text("No thanks", color = BookColors.TextMuted) } },
    )
}

/** Explains "Open when an emulator starts" before it's switched on. */
@Composable
fun EmulatorDisclosureDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BookColors.Surface,
        title = { Text("Open when an emulator starts", fontFamily = DisplayFont) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Whenever an emulator comes to the front, from any frontend, WalkThor opens on the bottom screen " +
                        "with the game's tabs. With Cocoon linked, Cocoon tells it which game. Without Cocoon, it " +
                        "works it out (or asks, with your picks for that emulator first) and remembers your pick.",
                    color = BookColors.Text,
                )
                Spacer(Modifier.height(12.dp))
                Text("What it accesses", style = EyebrowStyle)
                Text(
                    "Through WalkThor's accessibility service: which app comes to the front. For the emulator that's " +
                        "running, and only that one, also its window title and its own notifications, to spot a game " +
                        "name that matches one of your games or guides.",
                    color = BookColors.TextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("What it doesn't do", style = EyebrowStyle)
                Text(
                    "It never reads other apps' notifications or anything on the game's screen. What it sees is kept " +
                        "in memory only until the game ends and is never stored or sent. Only the games and guides you " +
                        "pick for each emulator are remembered, on this device.",
                    color = BookColors.TextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Needs accessibility access. Turn it off any time from Settings (⋮).",
                    color = BookColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAgree,
                colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
            ) { Text("Turn on", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", color = BookColors.TextMuted) } },
    )
}

/** Explains "Recognise the game you pick" before it's switched on. */
@Composable
fun PickDisclosureDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BookColors.Surface,
        title = { Text("Recognise the game you pick", fontFamily = DisplayFont) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Your frontend shows the name of the game you're on (in a list, under an icon or in a details " +
                        "panel). WalkThor reads the text on your frontend's screen, the same way a screen reader would, " +
                        "and when an emulator starts a moment later, it opens straight to the game you were on: the " +
                        "newest name on screen that matches a game in your ROMs folder or one of your guides.",
                    color = BookColors.Text,
                )
                Spacer(Modifier.height(12.dp))
                Text("What it accesses", style = EyebrowStyle)
                Text(
                    "Through WalkThor's accessibility service: the text on your frontend's screen (Cocoon, Daijisho, ES-DE " +
                        "and similar apps, or your home-screen app), and nothing from any other app. It's kept in memory, " +
                        "only until you launch a game; only names that match your games are ever used. Nothing is stored or sent.",
                    color = BookColors.TextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Some frontends don't tell Android what's on screen (ES-DE draws its menus like a game); then WalkThor " +
                        "falls back to your last pick. To check yours, turn on Settings → About → About & licences → Performance check: " +
                        "WalkThor then shows what it read and which of your games it found there.",
                    color = BookColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAgree,
                colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
            ) { Text("Turn on", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", color = BookColors.TextMuted) } },
    )
}

/** Version, license, privacy summary, open-source credits and trademark note. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f)
                .background(BookColors.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, BookColors.Hairline, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("About", style = EyebrowStyle)
                Text("WalkThor", fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = BookColors.Text)
                Text("Version $version · MIT License · © 2026 nerf-herder-29", color = BookColors.TextMuted, fontSize = 12.sp)

                Section("Privacy")
                Body(
                    "WalkThor doesn't collect or share any data. It only goes online to fetch artwork for your ROMs, " +
                        "and only if you add a ScreenScraper or SteamGridDB account and start a scrape; it then sends just " +
                        "each game's file name and system (and your account details) to that service. Guides, covers, " +
                        "reading positions and game links stay on this device and are removed if you uninstall. " +
                        "It reads only what you point it at: guides you import, an artwork folder you link, the file names " +
                        "in a ROMs folder you link, and Cocoon's launch_debug.log (to see which game you launched). The " +
                        "accessibility service responds " +
                        "only to swipes from the top edge of the bottom screen, and, during a game, taps the top edge " +
                        "of the game's screen once to hand it back top performance priority; with Open when an emulator starts " +
                        "on, it also notes which app comes to the front (and, without Cocoon, the running emulator's window " +
                        "title and notifications, and with Recognise the game you pick, the text on your frontend's screen, " +
                        "kept in memory only). It never reads other apps' screens. " +
                        "Mirror and the Stats tab's FPS meter use the top screen's picture only while you use them (Android " +
                        "asks first); the picture is never recorded, saved or sent. The Stats tab reads the battery, heat, memory " +
                        "and CPU speed from Android, on the device only. Face Buttons can light up the buttons you press, only " +
                        "while that screen is open; presses go straight on to the game and aren't kept."
                )

                // A saved crash report, if WalkThor has crashed, for sharing in a bug report.
                var crash by remember { mutableStateOf(com.nerfherder.walkthor.CrashLog.file(context)?.lastModified()) }
                crash?.let { at ->
                    Section("Last crash")
                    Body(
                        "WalkThor crashed on " +
                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at)) +
                            ". The report stays on this device; share it if you'd like to report the problem."
                    )
                    Row {
                        TextButton(onClick = { com.nerfherder.walkthor.CrashLog.share(context) }) { Text("Share report") }
                        TextButton(onClick = { com.nerfherder.walkthor.CrashLog.clear(context); crash = null }) {
                            Text("Delete", color = BookColors.TextMuted)
                        }
                    }
                }

                // Diagnostics: the performance check readout (moved here from Settings (⋮)).
                Section("Diagnostics")
                // If Android has closed WalkThor to free memory (heavy games), what happened.
                remember { com.nerfherder.walkthor.Revive.lastNote(context) }?.let { Body(it) }
                val perfOn by com.nerfherder.walkthor.PerfCheck.enabled.collectAsState()
                Body(
                    "Performance check shows on the dimmed screen whether WalkThor or your game has Android's " +
                        "top priority, and how often WalkThor is drawing. Only needed if a game seems slower with " +
                        "WalkThor open."
                )
                TextButton(onClick = { com.nerfherder.walkthor.PerfCheck.setEnabled(context, !perfOn) }) {
                    Text(if (perfOn) "Turn off performance check" else "Turn on performance check")
                }

                Section("Open-source software")
                COMPONENTS.forEach { c ->
                    Text(c.name, color = BookColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                    Text("${c.use} · ${c.license}", color = BookColors.TextMuted, fontSize = 12.sp)
                }
                Body(
                    "Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0. PdfBox-Android includes software " +
                        "developed at The Apache Software Foundation (https://www.apache.org/). Space Grotesk is " +
                        "copyright 2020 The Space Grotesk Project Authors (https://github.com/floriankarsten/space-grotesk); " +
                        "SIL Open Font License 1.1: https://openfontlicense.org."
                )

                Section("Trademarks")
                Body(
                    "AYN and Thor are trademarks of their respective owners; Cocoon is the work of its developer. " +
                        "WalkThor is an independent project, not affiliated with or endorsed by AYN, Cocoon or any " +
                        "game publisher. Guides and artwork shown come from your own files and belong to their creators."
                )
            }
            HorizontalDivider(color = BookColors.Hairline, modifier = Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { open("$REPO/blob/main/PRIVACY.md") }) { Text("Privacy policy") }
                TextButton(onClick = { open("$REPO/blob/main/THIRD_PARTY_NOTICES.md") }) { Text("Licences") }
                TextButton(onClick = { open(REPO) }) { Text("Source") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close", color = BookColors.TextMuted) }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = EyebrowStyle, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, color = BookColors.TextMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
}
