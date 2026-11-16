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
)

/**
 * Settings → About → How to set up: every step to get WalkThor working, in order, with a tick for
 * what's already done and a button for what isn't. Then how playing and reading work.
 */
@Composable
fun SetupGuideDialog(
    state: SetupState,
    onAddGuide: () -> Unit,
    onLinkRoms: () -> Unit,
    onSetUpSwipe: () -> Unit,
    onDismiss: () -> Unit,
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
                    "Do these once. Then WalkThor's drawer is a button press away on the bottom screen, whatever " +
                        "you're playing and whichever frontend you start it from.",
                    color = BookColors.TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )

                Step(
                    1, "Allow accessibility access", state.swipeReady, "Set up", onSetUpSwipe,
                    "The drawer, the button shortcuts, the FPS counter and the keyboard all run through it. WalkThor " +
                        "explains exactly what it does, then Android asks you to switch on “WalkThor” in Accessibility settings.",
                )
                Step(
                    2, "Choose your shortcuts", null, null, null,
                    "Settings → Shortcuts: what a tap, a double press and a hold of the AYN button do, and Back pressed " +
                        "twice or held. Set the AYN button's tap to Open the drawer.",
                )
                Step(
                    3, "Link your ROMs folder", state.romsLinked, "Link folder", onLinkRoms,
                    "The folder that holds one folder per system (3ds, gba, switch…). WalkThor only reads the names, " +
                        "to list your games by system. Optional: without it, your library is just your guides.",
                )
                Step(
                    4, "Add your guides", state.hasGuides, "Add guide", onAddGuide,
                    "Import PDF or TXT walkthroughs (GameFAQs-style text guides work well): tap Add a guide beside a " +
                        "game, or Add guide at the top. WalkThor keeps its own copy, so you can move or delete the original.",
                )

                Heading("The drawer")
                Para(
                    "The drawer slides up over the bottom screen and away again; the game on the top screen keeps running " +
                        "at full speed. It opens on Home: the time, battery, play time, brightness, volume and stick " +
                        "lights, and your pinned apps along the bottom."
                )
                Para(
                    "While a game is in front, a row of tabs sits above Home: Multi, Guide, Mirror, Controls, Stats, Shots, " +
                        "Captions, Light & Sound and Apps. If there are more than fit, swipe them sideways. Home dims after the " +
                        "time set in Settings → Screen → Auto-dim (tap anywhere to wake it); the other tabs stay bright."
                )
                Para(
                    "Guide lists your guides each time, for you to pick the one you want. WalkThor remembers your place in each."
                )
                Para(
                    "To have the drawer open straight on one tab (say, Mirror), press and hold that tab. A small dot marks " +
                        "it. Press and hold it again, or Home, to go back to opening on Home."
                )
                Para(
                    "Which apps count as games is in Settings → Emulators → Choose emulators. Tick any WalkThor doesn't " +
                        "know, such as an app that runs games from shortcuts."
                )
                Para(
                    "Mirror shows part of the top screen here, like a minimap or a HUD: drag a box over the part you want. " +
                        "Add up to four areas with +. Android asks for permission to capture the screen the first time."
                )
                Para(
                    "Multi shows up to four tabs at once; choose them with ⋮ → Choose panels. Tap any of them to open that tab."
                )
                Para(
                    "Stats shows the top screen's frame rate with a graph of the last minute, plus the battery's power draw " +
                        "and time left, heat, free memory and CPU speed."
                )

                Heading("Reading a guide")
                Bullet("Turn pages with L1 / R1 or the D-pad, by tapping the outer edges, or by dragging a page.")
                Bullet("Search from the top bar (during a game, tap the magnifying glass at the bottom left, next to the lens). Results show every matching page, and ▲ / ▼ step through matches.")
                Bullet("Tap the middle of a page (or the magnifying glass) for a zoomable, full-width view.")
                Bullet("Press and hold on a page for a magnifier you can slide around.")
                Bullet("WalkThor remembers your place in every guide.")

                Heading("Good to know")
                Bullet("Tapping WalkThor never slows your game: the game keeps top priority while you read.")
                Bullet("Typing in search briefly gives WalkThor focus for the keyboard. When you're done, WalkThor hands it back to the game on its own.")
                Bullet("WalkThor only goes online for one thing: downloading the speech model the first time you turn on Captions.")
            }
            Row(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = if (compact) Modifier.height(36.dp) else Modifier) { Text("Close") }
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
