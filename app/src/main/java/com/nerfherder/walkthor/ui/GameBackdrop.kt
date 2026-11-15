package com.nerfherder.walkthor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** True while a screen sits over something of its own to show through; its background is then left see-through. */
val LocalBackdrop = compositionLocalOf { false }

/** A screen's background colour: [color] normally, see-through over a backdrop. */
@Composable
fun backdropOr(color: Color): Color = if (LocalBackdrop.current) Color.Transparent else color
