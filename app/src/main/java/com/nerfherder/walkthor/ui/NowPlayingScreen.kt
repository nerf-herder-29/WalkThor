package com.nerfherder.walkthor.ui

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.GameArt
import com.nerfherder.walkthor.data.LaunchedGame
import com.nerfherder.walkthor.data.Matcher
import java.io.File

/**
 * Shown when Cocoon launches a game WalkThor has no guide for: the game's artwork (from the
 * linked artwork folder) where the guide would be, a "no guide yet" note, and ways to fix that.
 * Whatever guide is added or picked from here is remembered for this game.
 */
@Composable
fun NowPlayingScreen(
    game: LaunchedGame,
    art: GameArt,
    books: List<Book>,
    coverFile: (Book) -> File?,
    onAddGuide: () -> Unit,
    onPickGuide: (Book) -> Unit,
    onLibrary: () -> Unit,
    onClose: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(game.title, game.at) { enter.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }

    // Pure OLED black like the rest of WalkThor; auto-dim applies here too.
    Column(Modifier.fillMaxSize().background(BookColors.Ink)) {
        // Top bar: back to the shelf, and close.
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onLibrary) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Library", tint = BookColors.TextMuted)
            }
            Text("NOW PLAYING", style = EyebrowStyle, modifier = Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close WalkThor", tint = BookColors.TextMuted)
            }
        }

        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 40.dp, end = 40.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The game's art, where its guide would be.
            Box(
                Modifier
                    .fillMaxHeight(0.92f)
                    .widthIn(max = 300.dp)
                    .weight(0.9f)
                    .graphicsLayer {
                        alpha = enter.value
                        val s = 0.94f + 0.06f * enter.value
                        scaleX = s; scaleY = s
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (art.cover != null) {
                    AsyncImage(
                        model = Uri.parse(art.cover),
                        contentDescription = game.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    NoArtPlaceholder(game.title)
                }
            }
            Spacer(Modifier.width(36.dp))
            Column(
                Modifier
                    .weight(1.25f)
                    .graphicsLayer {
                        alpha = enter.value
                        translationX = (1f - enter.value) * 24.dp.toPx()
                    },
            ) {
                Text(
                    game.title,
                    color = BookColors.Text,
                    fontFamily = SerifDisplay,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                game.player?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = BookColors.TextMuted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(22.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(BookColors.Surface.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                        .border(1.dp, BookColors.Hairline, RoundedCornerShape(12.dp))
                        .padding(18.dp),
                ) {
                    Text(
                        "No guide for this game yet",
                        color = BookColors.Text,
                        fontFamily = SerifDisplay,
                        fontSize = 18.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Add one, or pick one from your shelf, and WalkThor will open it for this game from now on.",
                        color = BookColors.TextMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = onAddGuide,
                            colors = ButtonDefaults.buttonColors(containerColor = BookColors.Brass, contentColor = Color(0xFF1E1709)),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add guide", fontWeight = FontWeight.SemiBold)
                        }
                        if (books.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { picking = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BookColors.Text),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            ) { Text("From my shelf") }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onLibrary) {
                    Text("Go to my shelf", color = BookColors.TextMuted)
                }
            }
        }
        // Time, play time and battery in the same spot as in the reader.
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            StatusClock(Modifier.padding(start = 4.dp, end = 16.dp))
        }
    }

    if (picking) {
        // Closest titles first, so the right guide is usually at the top.
        val ranked = remember(books, game) {
            val names = listOfNotNull(game.title, game.file?.substringBeforeLast('.'))
            books.sortedByDescending { b -> names.maxOf { Matcher.gameScore(b.title, it) } }
        }
        AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = BookColors.Surface,
            title = { Text("Which guide is for this game?", fontFamily = SerifDisplay) },
            text = {
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(ranked, key = { it.id }) { book ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { picking = false; onPickGuide(book) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GuideTile(book, remember(book.id, book.cover) { coverFile(book) }, Modifier.size(44.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(book.title, color = BookColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }
}

/** A plain card standing in for the game's art when none was found. */
@Composable
private fun NoArtPlaceholder(title: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(260.dp)
            .shadow(10.dp, RoundedCornerShape(10.dp))
            .background(BookColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .border(1.dp, BookColors.Hairline, RoundedCornerShape(10.dp))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            color = BookColors.TextMuted,
            fontFamily = SerifDisplay,
            fontStyle = FontStyle.Italic,
            fontSize = 20.sp,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
