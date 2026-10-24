package com.nerfherder.walkthor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One button under the game's name: the first (Open guide / Add guide) is filled. */
data class StackButton(val label: String, val filled: Boolean, val action: () -> Unit)

/**
 * The game's name, its emulator and the buttons under them, centred on the screen. Used by both
 * the Now playing screen and the dimmed screen, so everything stays in exactly the same place
 * when the screen dims or wakes.
 *
 * [link] ("Other guide" / "From my shelf") sits under the buttons; with [linkVisible] false it
 * still takes its space, so the dimmed and awake layouts line up.
 * [actOnDown] makes the buttons act on touch down (the dimmed screen, where the same touch also
 * wakes it).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NowPlayingStack(
    title: String,
    player: String?,
    buttons: List<StackButton>,
    link: Pair<String, () -> Unit>?,
    linkVisible: Boolean,
    actOnDown: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.widthIn(max = 620.dp).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Now playing", style = EyebrowStyle.copy(color = Color.White.copy(alpha = 0.7f)))
        Spacer(Modifier.height(8.dp))
        Text(
            displayTitle(title),
            color = Color.White.copy(alpha = 0.92f),
            fontFamily = DisplayFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        player?.let {
            Spacer(Modifier.height(6.dp))
            Text(emulatorName(it), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        }
        if (buttons.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                buttons.forEach { b -> StackButtonView(b, actOnDown) }
            }
        }
        if (link != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                link.first,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .alpha(if (linkVisible) 1f else 0f)
                    .clip(RoundedCornerShape(14.dp))
                    .then(if (linkVisible) Modifier.clickable { link.second() } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun StackButtonView(b: StackButton, actOnDown: Boolean) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .clip(shape)
            .then(
                if (b.filled) Modifier.background(BookColors.ButtonFill).border(1.dp, BookColors.ButtonEdge, shape)
                else Modifier.background(Color.Black.copy(alpha = 0.35f)).border(1.dp, Color.White.copy(alpha = 0.45f), shape)
            )
            .then(
                if (actOnDown) Modifier.pointerInput(b.action) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        b.action()
                    }
                } else Modifier.clickable { b.action() }
            )
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(b.label, color = BookColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, softWrap = false)
    }
}
