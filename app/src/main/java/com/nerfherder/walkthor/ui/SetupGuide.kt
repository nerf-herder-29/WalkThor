package com.nerfherder.walkthor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/** Where each setup step stands, for the tick or the button beside it. */
class SetupState(
    val hasGuides: Boolean,
    val romsLinked: Boolean,
    val swipeReady: Boolean,
    val homeApp: Boolean = false,
    val artLinked: Boolean = false,
)

/**
 * Settings › About › How to set up: every step to get WalkThor working, in order, with a tick for
 * what's already done and a button for what isn't. Then how everything works.
 */
@Composable
fun SetupGuideDialog(
    state: SetupState,
    onAddGuide: () -> Unit,
    onLinkRoms: () -> Unit,
    onSetUpSwipe: () -> Unit,
    onDismiss: () -> Unit,
    onSetHome: () -> Unit = {},
    onLinkArt: () -> Unit = {},
) {
    // The bottom screen is short; at denser display settings use the space more tightly.
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(if (compact) 0.97f else 0.94f)
                .fillMaxHeight(if (compact) 0.97f else 0.92f)
                .background(BookColors.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, BookColors.Hairline, RoundedCornerShape(16.dp))
                .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 10.dp else 18.dp)
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Instructions", style = EyebrowStyle)
                Text(
                    "How to set up WalkThor",
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (compact) 19.sp else 24.sp,
                    color = BookColors.Text,
                )
                Text(
                    "Do these once. Then the Thor starts on your games: their pictures on the top screen, the " +
                        "games themselves on the bottom, and a press of A starts one in its emulator.",
                    color = BookColors.TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )

                Step(
                    1, "Allow accessibility access", state.swipeReady, "Set up", onSetUpSwipe,
                    "Button shortcuts, the drawer during a game, knowing when a game is running, the performance overlay " +
                        "and the keyboard all run through it. WalkThor explains exactly what it does, then Android asks you to " +
                        "switch on “WalkThor” in Accessibility settings.",
                )
                Step(
                    2, "Make WalkThor your home screen", state.homeApp, "Choose", onSetHome,
                    "In Android’s home app setting, pick WalkThor. Then pressing Home takes you to your games, games " +
                        "start on the top screen with WalkThor below, and apps can add their game shortcuts to WalkThor.",
                )
                Step(
                    3, "Link your ROMs folder", state.romsLinked, "Link folder", onLinkRoms,
                    "The folder with one folder per system, named the way ES-DE names them (gba, psx, ps2, n3ds, " +
                        "switch, c64…). WalkThor reads only the names, to list your games.",
                )
                Step(
                    4, "Link your art folder (optional)", state.artLinked, "Link folder", onLinkArt,
                    "A folder of covers, backgrounds and logos you already have, such as ES-DE’s or Cocoon’s. Settings › " +
                        "Artwork can also download what’s missing, and you can pick your own picture for any game.",
                )
                Step(
                    5, "Check your emulators", null, null, null,
                    "Settings › Emulators › Starting games shows, for each system, every emulator you have that " +
                        "WalkThor can start games in, and which it uses. Leave it to WalkThor, or pick one.",
                )
                Step(
                    6, "Add guides (optional)", state.hasGuides, "Add guide", onAddGuide,
                    "PDF or TXT walkthroughs, read on the bottom screen while you play. Press X on a game and choose " +
                        "Add a guide. WalkThor keeps its own copy.",
                )

                Heading("Your games")
                Para(
                    "The bottom screen shows your games as a cloud of bubbles, each ringed in its system’s colour. The " +
                        "top screen shows the highlighted game: its picture and logo, its system, when you last played and " +
                        "for how long."
                )
                Bullet("D-pad or left stick: move between games. L2 / R2: jump to the next letter. Right stick: look around without moving off your game.")
                Bullet("A: play. Y: its guide. X: options (favourite, hide, pictures, its guide, and Start it in… to pick an emulator for that game alone).")
                Bullet("Select, or the magnifying glass: search. B clears a search.")
                Bullet("L1 / R1: the tabs along the top. Games, Apps, Updates, Controllers, then Settings.")

                Heading("While you play")
                Para(
                    "The game runs on the top screen. The bottom screen goes black (tap it to bring WalkThor back) " +
                        "unless the game uses both screens or you set the drawer to open when a game starts."
                )
                Para(
                    "Back on the bottom screen, Home shows the game you started: its picture and logo, its system and " +
                        "emulator, and how long you’ve been playing. Swipe up for your apps. The tabs along the top give " +
                        "you its Guide (it opens on that game’s guide), Mirror, Controls, Stats, Shots, Captions, Light & " +
                        "Sound and Apps."
                )
                Para(
                    "Shortcuts (Settings › Shortcuts) work in every game: the AYN button, Back pressed twice, and Back held " +
                        "with another button. Back + Select puts the Thor to sleep."
                )

                Heading("Apps, updates and controllers")
                Bullet("Apps: every app, as a cloud too. Press A on one to open it on the top or bottom screen (or always the same one: Settings › Screen).")
                Bullet("Updates: each emulator’s version, what’s new and an Update button that installs it.")
                Bullet("Controllers: pair controllers, and use a pair of Joy-Cons as the Thor’s own controls in every emulator (they join by themselves when both connect).")

                Heading("Reading a guide")
                Bullet("Turn pages with L1 / R1 or the D-pad, by tapping the outer edges, or by dragging a page.")
                Bullet("Search from the top bar. Results show every matching page, and ▲ / ▼ step through matches.")
                Bullet("Tap the middle of a page for a zoomable, full-width view. Press and hold for a magnifier.")
                Bullet("WalkThor remembers your place in every guide.")

                Heading("Good to know")
                Bullet("Everything can be done with the controller: the ring in your accent colour shows where you are, and A picks.")
                Bullet("Tapping WalkThor never slows your game: the game keeps top priority.")
                Bullet("WalkThor goes online only to check for updates, download artwork you ask for, and download the speech model for Captions.")
            }
            Row(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 8.dp), horizontalArrangement = Arrangement.End) {
                FocusTextButton(onClick = onDismiss, modifier = if (compact) Modifier.height(36.dp) else Modifier) { Text("Close") }
            }
        }
    }
}

/** One numbered setup step: a tick when done ([done] true), else its button; null = can't tell. */
@Composable
private fun Step(
    number: Int,
    title: String,
    done: Boolean?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    body: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(BookColors.SurfaceHigh, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(if (done == true) BookColors.ButtonFill else Color.Transparent, CircleShape)
                .border(1.5.dp, BookColors.Brass, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done == true) {
                Icon(Icons.Filled.Check, contentDescription = "Done", tint = BookColors.Text, modifier = Modifier.size(16.dp))
            } else {
                Text("$number", color = BookColors.Brass, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = BookColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                if (done == true) {
                    Text("Done", color = BookColors.Brass, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                } else if (actionLabel != null && onAction != null) {
                    Button(
                        onClick = onAction,
                        colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) { Text(actionLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
                }
            }
            Text(body, color = BookColors.TextMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = EyebrowStyle, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
}

@Composable
private fun Para(text: String) {
    Text(text, color = BookColors.TextMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(top = 4.dp)) {
        Text("•", color = BookColors.Brass, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        Text(text, color = BookColors.TextMuted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
