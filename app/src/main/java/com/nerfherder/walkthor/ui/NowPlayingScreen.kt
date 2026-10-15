package com.nerfherder.walkthor.ui

import androidx.compose.runtime.collectAsState

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.GameArt
import com.nerfherder.walkthor.data.LaunchedGame
import com.nerfherder.walkthor.data.Matcher
import java.io.File

/**
 * Shown whenever Cocoon launches a game. With a [guide]: its tile and an Open guide button.
 * Without one: the game's artwork (from the linked artwork folder), a "no guide yet" note, and
 * ways to add one. Either way it dims after a few seconds; with a guide, the dimmed screen
 * keeps an Open guide button. Whatever guide is added or picked here is remembered for the game.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    guide: Book? = null,
    onOpenGuide: () -> Unit = {},
) {
    var picking by remember { mutableStateOf(false) }
    // Nothing to read here, so dim after a few seconds (whatever the Auto-dim setting is);
    // with a guide, the dimmed screen offers to open it.
    val openGuide by androidx.compose.runtime.rememberUpdatedState(onOpenGuide)
    androidx.compose.runtime.DisposableEffect(guide?.id) {
        AutoDim.wake()
        AutoDim.screenDelayMs.value = AutoDim.NO_GUIDE_DELAY_MS
        AutoDim.openGuide.value = if (guide != null) ({ openGuide() }) else null
        onDispose {
            AutoDim.screenDelayMs.value = null
            AutoDim.openGuide.value = null
        }
    }
    // The Thor's bottom screen is short; at denser display settings tighten everything up.
    val compact = LocalConfiguration.current.screenHeightDp < 480
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
                .padding(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The game's art, where its guide would be.
            Box(
                Modifier
                    .fillMaxHeight(0.92f)
                    .widthIn(max = 300.dp)
                    .weight(if (compact) 0.55f else 0.8f)
                    .graphicsLayer {
                        alpha = enter.value
                        val s = 0.94f + 0.06f * enter.value
                        scaleX = s; scaleY = s
                    },
                contentAlignment = Alignment.Center,
            ) {
                val resting by AutoDim.resting.collectAsState()
                if (guide != null) {
                    // The guide itself, where the boot screen's flip left it.
                    GuideTile(
                        guide,
                        if (resting) null else remember(guide.id, guide.cover) { coverFile(guide) },
                        Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                } else if (art.cover != null && !resting) {
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
            Spacer(Modifier.width(if (compact) 18.dp else 28.dp))
            Column(
                Modifier
                    .weight(if (compact) 1.45f else 1.25f)
                    // Never cut anything off: scroll if it still doesn't fit.
                    .verticalScroll(rememberScrollState())
                    .graphicsLayer {
                        alpha = enter.value
                        translationX = (1f - enter.value) * 24.dp.toPx()
                    },
            ) {
                Text(
                    displayTitle(game.title),
                    color = BookColors.Text,
                    fontFamily = SerifDisplay,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (compact) 20.sp else 30.sp,
                    lineHeight = if (compact) 24.sp else 36.sp,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                game.player?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(emulatorName(it), color = BookColors.TextMuted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(if (compact) 12.dp else 22.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(BookColors.Surface.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                        .border(1.dp, BookColors.Hairline, RoundedCornerShape(12.dp))
                        .padding(if (compact) 12.dp else 18.dp),
                ) {
                    if (guide != null) {
                        Text("YOUR GUIDE", style = EyebrowStyle)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            guide.title,
                            color = BookColors.Text,
                            fontFamily = SerifDisplay,
                            fontSize = if (compact) 15.sp else 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (compact) {
                        Text("NO GUIDE YET", style = EyebrowStyle)
                    } else {
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
                    }
                    Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
                    // Wraps onto a second line on narrow screens instead of cutting a button off.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val pad = PaddingValues(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 4.dp else 8.dp)
                        if (guide != null) {
                            Button(
                                onClick = onOpenGuide,
                                colors = ButtonDefaults.buttonColors(containerColor = BookColors.Brass, contentColor = Color(0xFF1E1709)),
                                contentPadding = pad,
                            ) { Text("Open guide", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
                            if (books.size > 1) {
                                OutlinedButton(
                                    onClick = { picking = true },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BookColors.Text),
                                    contentPadding = pad,
                                ) { Text("Other guide", maxLines = 1, softWrap = false) }
                            }
                        } else {
                            Button(
                                onClick = onAddGuide,
                                colors = ButtonDefaults.buttonColors(containerColor = BookColors.Brass, contentColor = Color(0xFF1E1709)),
                                contentPadding = pad,
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add guide", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            }
                            if (books.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { picking = true },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BookColors.Text),
                                    contentPadding = pad,
                                ) { Text("From my shelf", maxLines = 1, softWrap = false) }
                            }
                        }
                    }
                }
                if (!compact) {
                    // (The back arrow at the top does the same; skipped when space is tight.)
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onLibrary) {
                        Text("Go to my shelf", color = BookColors.TextMuted)
                    }
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
