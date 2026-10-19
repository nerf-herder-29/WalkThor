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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Where each setup step stands, for the tick or the button beside it. */
class SetupState(
    val hasGuides: Boolean,
    val cocoonLinked: Boolean,
    val artLinked: Boolean,
    val swipeReady: Boolean,
)

/**
 * ⋮ → How to set up: every step to get WalkThor working with Cocoon, in order, with a tick for
 * what's already done and a button for what isn't. Then how playing and reading work.
 */
@Composable
fun SetupGuideDialog(
    state: SetupState,
    onAddGuide: () -> Unit,
    onLinkCocoon: () -> Unit,
    onLinkArt: () -> Unit,
    onSetUpSwipe: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The Thor's bottom screen is short; at denser display settings use the space more tightly.
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
                Text("INSTRUCTIONS", style = EyebrowStyle)
                Text(
                    "How to set up WalkThor",
                    fontFamily = SerifDisplay,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (compact) 19.sp else 24.sp,
                    color = BookColors.Text,
                )
                Text(
                    "Do these once, in order. Then, whenever you start a game from Cocoon, WalkThor opens on " +
                        "the bottom screen with that game's guide ready.",
                    color = BookColors.TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )

                Step(
                    1, "Add your guides", state.hasGuides, "Add guide", onAddGuide,
                    "Import PDF or TXT walkthroughs (GameFAQs-style text guides work well). Tap Add guide, or " +
                        "open a guide from a file manager with Open with or Share → WalkThor. WalkThor keeps its own " +
                        "copy, so you can move or delete the original.",
                )
                Step(
                    2, "Link Cocoon's folder", state.cocoonLinked, "Link folder", onLinkCocoon,
                    "Pick Cocoon's data folder, the one with launch_debug.log in it. WalkThor only reads that log, " +
                        "to see which game you started, so it can open the right guide and show play time. " +
                        "It never changes anything there.",
                )
                Step(
                    3, "Link your artwork", state.artLinked, "Link folder", onLinkArt,
                    "Pick the folder with your game artwork (Cocoon's media folder works). WalkThor uses it for " +
                        "guide covers and for the Now playing screen: hero or fan art as the background and the " +
                        "logo on top. Optional, but it makes everything look much better. After adding new art, " +
                        "use ⋮ → Rescan artwork.",
                )
                Step(
                    4, "Make WalkThor Cocoon's companion app", null, null, null,
                    "In Cocoon, choose WalkThor as the companion app for the bottom screen (for every game, or " +
                        "just the ones you want). Cocoon then opens WalkThor whenever it starts a game.",
                )
                Step(
                    5, "Allow accessibility access", state.swipeReady, "Set up", onSetUpSwipe,
                    "Lets you swipe down from the top edge of the bottom screen to open or put away WalkThor. " +
                        "It also hands your game its full speed back after WalkThor opens or you search, with one tap on the " +
                        "top screen. WalkThor explains exactly what it does, then Android asks you to switch on " +
                        "“WalkThor” in Accessibility settings. Strongly recommended.",
                )

                Heading("When you play")
                Para(
                    "Start a game in Cocoon. The bottom screen shows Now playing with the game's art, then dims " +
                        "after 5 seconds to save power. Tap anywhere to wake it, or tap Open guide."
                )
                Para(
                    "The first time you play a game, WalkThor looks for a guide whose title matches it. If it " +
                        "doesn't find one, tap Add guide or From my shelf, and WalkThor remembers that guide for " +
                        "the game next time. Other guide switches to a different one."
                )
                Para("Back from a guide takes you to Now playing, and back again to your shelf.")
                Para(
                    "Mirror (on Now playing and the dimmed screen) shows part of the top screen here, like a " +
                        "minimap or a HUD. The first time for a game, drag a box over the part you want; it keeps this " +
                        "screen's shape so the mirror fills it (tap Don't keep aspect ratio to resize freely). WalkThor remembers it. Add up to four areas with +, switch between them with the numbers at the top, or " +
                        "tap All to see them side by side. Android asks for permission each time you start it."
                )
                Para(
                    "Face Buttons (also on Now playing and the dimmed screen) shows the four face buttons the way " +
                        "the game's system labels them (△ ○ × □, or letters), each where its Thor button is, and " +
                        "lights up the one you press.")

                Heading("Reading a guide")
                Bullet("Turn pages with L1 / R1 or the D-pad, by tapping the outer edges, or by dragging a page.")
                Bullet("Search from the top bar. Results show every matching page, and ▲ / ▼ step through matches.")
                Bullet("Tap the middle of a page (or the magnifying glass) for a zoomable, full-width view.")
                Bullet("Press and hold on a page for a magnifier you can slide around.")
                Bullet("WalkThor remembers your place in every guide.")
                Bullet("Inside a guide, the screen dims on its own after the time set in ⋮ → Auto-dim.")

                Heading("Good to know")
                Bullet("Tapping WalkThor never slows your game: the game keeps top priority while you read.")
                Bullet("Typing in search briefly gives WalkThor focus for the keyboard. When you're done, WalkThor hands it back to the game on its own.")
                Bullet("If WalkThor ever opens on the wrong screen, use ⋮ → Move to other screen.")
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
    Text(text.uppercase(), style = EyebrowStyle, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
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
