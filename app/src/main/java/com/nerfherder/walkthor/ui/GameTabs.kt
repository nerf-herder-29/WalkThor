package com.nerfherder.walkthor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow

/** The tabs across the top of the bottom screen while a game is playing. */
enum class GameTab(val label: String) {
    GUIDE("Guide"),
    MIRROR("Mirror"),
    FACE("Face Buttons"),
}

/** What the tab bar shows right now, so the dimmed screen can draw the same tabs in the same place. */
data class TabsState(val selected: GameTab?, val onSelect: (GameTab) -> Unit)

object GameTabs {
    /** Set while a game's tabs are on screen; null otherwise. */
    val state = MutableStateFlow<TabsState?>(null)
}

/** Height of the bar the tabs sit in, the same everywhere so the tabs never move. */
val TabBarHeight = 50.dp

/**
 * The three tabs as one pill. [actOnDown] makes a tab act on touch down (the dimmed screen, where
 * the same touch also wakes it).
 */
@Composable
fun GameTabPill(
    selected: GameTab?,
    onSelect: (GameTab) -> Unit,
    modifier: Modifier = Modifier,
    actOnDown: Boolean = false,
) {
    val narrow = LocalConfiguration.current.screenWidthDp < 480
    val outer = RoundedCornerShape(19.dp)
    Row(
        modifier
            .height(38.dp)
            .background(Color.Black.copy(alpha = 0.35f), outer)
            .border(1.dp, Color.White.copy(alpha = 0.28f), outer)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GameTab.entries.forEach { tab ->
            val on = tab == selected
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .height(32.dp)
                    .clip(shape)
                    .then(if (on) Modifier.background(BookColors.ButtonFill, shape) else Modifier)
                    .then(
                        if (actOnDown) Modifier.pointerInput(tab) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                onSelect(tab)
                            }
                        } else Modifier.clickable { onSelect(tab) }
                    )
                    .padding(horizontal = if (narrow) 10.dp else 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    color = if (on) BookColors.Text else Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (narrow) 13.sp else 14.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * The bar across the top during a game: back, the tabs, then whatever the screen adds on the
 * right ([trailing]), ending with close. With [overlay] it has no background of its own (over
 * the Now playing art).
 */
@Composable
fun GameTabBar(
    selected: GameTab?,
    onSelect: (GameTab) -> Unit,
    onBack: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    overlay: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(TabBarHeight)
            .then(if (overlay) Modifier else Modifier.background(BookColors.Ink))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (overlay) Color.White.copy(alpha = 0.85f) else BookColors.TextMuted
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = tint)
        }
        GameTabPill(selected, onSelect)
        Spacer(Modifier.weight(1f))
        trailing()
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close WalkThor", tint = tint)
            }
        }
    }
}

/** The tabs alone, placed exactly where [GameTabBar] puts them (for the dimmed screen). */
@Composable
fun GameTabPillInBarPosition(selected: GameTab?, onSelect: (GameTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(TabBarHeight).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(48.dp))
        GameTabPill(selected, onSelect, actOnDown = true)
    }
}
