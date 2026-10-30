package com.nerfherder.walkthor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.nerfherder.walkthor.GameFocus

/*
 * Dialogs and menus that never take focus from a running game.
 *
 * While a game is playing, WalkThor's own window is set so it can't take focus (the game keeps
 * Android's top-app priority, and with it full speed). A normal dialog or menu opens a window of
 * its own that *does* take focus, so opening one mid-game (say, Choose panels) quietly took that
 * priority away, and closing it didn't give it back. These stand in for Compose's Dialog,
 * AlertDialog and DropdownMenu: while WalkThor is yielding to a game they open as windows that
 * can't take focus; otherwise they're the usual ones (so typing in them works).
 */

/** Places a popup over the whole window. */
private object WholeWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

/** A dialog that keeps the game's focus while one is playing. Same use as Compose's Dialog. */
@Composable
fun Dialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) {
    val yielding by GameFocus.yielding.collectAsState()
    if (!yielding) {
        androidx.compose.ui.window.Dialog(onDismissRequest, properties, content)
        return
    }
    val outside = remember { MutableInteractionSource() }
    val inside = remember { MutableInteractionSource() }
    Popup(
        popupPositionProvider = WholeWindow,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false, clippingEnabled = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = outside, indication = null) { if (properties.dismissOnClickOutside) onDismissRequest() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                (if (properties.usePlatformDefaultWidth) Modifier.fillMaxWidth(0.9f).widthIn(max = 560.dp) else Modifier)
                    // Taps on the dialog itself never count as outside it.
                    .clickable(interactionSource = inside, indication = null) {},
                contentAlignment = Alignment.Center,
            ) { content() }
        }
    }
}

/** An alert dialog that keeps the game's focus while one is playing. Same use as Material's AlertDialog. */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = AlertDialogDefaults.containerColor,
) {
    val yielding by GameFocus.yielding.collectAsState()
    if (!yielding) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier,
            dismissButton = dismissButton, title = title, text = text, containerColor = containerColor,
        )
        return
    }
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(modifier, shape = RoundedCornerShape(28.dp), color = containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            Column(Modifier.padding(24.dp)) {
                title?.let {
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) { it() }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                text?.let {
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            Box(Modifier.weight(1f, fill = false)) { it() }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                    ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.End) {
                            dismissButton?.let { it(); Spacer(Modifier.width(8.dp)) }
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

/** A menu that keeps the game's focus while one is playing. Same use as Material's DropdownMenu. */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val yielding by GameFocus.yielding.collectAsState()
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = PopupProperties(focusable = !yielding),
        content = content,
    )
}
