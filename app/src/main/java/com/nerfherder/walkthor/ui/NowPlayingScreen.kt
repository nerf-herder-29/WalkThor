package com.nerfherder.walkthor.ui

import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.draw.drawBehind

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
 * Shown whenever Cocoon launches a game, before it dims after a few seconds.
 *
 * The whole screen takes on the box art's colour. The left half is the game's wide art (a
 * hero, fan art or screenshot; else the box art) with its logo on top; the right half is the
 * guide: Open guide with a guide, or Add guide / From my shelf without one. With a guide, the
 * dimmed screen keeps an Open guide button. Whatever guide is added or picked here is
 * remembered for the game.
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
    val resting by AutoDim.resting.collectAsState()
    val guideCover = remember(guide?.id, guide?.cover) { guide?.let(coverFile) }
    // The box art's colour fills the screen (deepened so white text reads well on it).
    val accent = rememberArtAccent(art.cover ?: art.hero ?: guideCover?.let { Uri.fromFile(it).toString() })
    val fill = deepShade(accent)
    val banner = art.hero ?: art.cover

    Box(Modifier.fillMaxSize().background(fill)) {
        // ---- the game's wide art across the whole screen ----
        if (banner != null && !resting) {
            AsyncImage(
                model = Uri.parse(banner),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = enter.value },
            )
        }
        // Fade it into the game's colour toward the right, so the guide side reads clearly...
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.35f to Color.Transparent,
                    0.55f to fill.copy(alpha = 0.78f),
                    0.75f to fill.copy(alpha = 0.92f),
                    1f to fill.copy(alpha = 0.96f),
                )
            )
        )
        // ...and gently at the top and bottom, for the bar icons and the logo.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to fill.copy(alpha = 0.5f),
                    0.25f to Color.Transparent,
                    0.6f to Color.Transparent,
                    1f to fill.copy(alpha = 0.8f),
                )
            )
        )
        Row(Modifier.fillMaxSize()) {
            // ---- left half: the logo over the art ----
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = enter.value },
            ) {
                // The logo, or the title when there's no logo.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = if (compact) 18.dp else 28.dp)
                        .padding(bottom = if (compact) 22.dp else 36.dp)
                        .graphicsLayer { translationY = (1f - enter.value) * 16.dp.toPx() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (art.logo != null && !resting) {
                        AsyncImage(
                            model = Uri.parse(art.logo),
                            contentDescription = displayTitle(game.title),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .heightIn(max = if (compact) 80.dp else 130.dp),
                        )
                    } else {
                        Text(
                            displayTitle(game.title),
                            color = Color.White,
                            fontFamily = SerifDisplay,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = if (compact) 22.sp else 30.sp,
                            lineHeight = if (compact) 26.sp else 36.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 12f),
                            ),
                        )
                    }
                }
            }

            // ---- right half: the guide ----
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = if (compact) 22.dp else 40.dp)
                    .padding(top = 52.dp, bottom = 40.dp)
                    .verticalScroll(rememberScrollState())
                    .graphicsLayer {
                        alpha = enter.value
                        translationX = (1f - enter.value) * 24.dp.toPx()
                    },
                verticalArrangement = Arrangement.Center,
            ) {
                game.player?.let {
                    Text(emulatorName(it).uppercase(), style = EyebrowStyle.copy(color = Color.White.copy(alpha = 0.7f)))
                    Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                }
                val primary = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = fill)
                val secondary = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                val outline = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                val pad = PaddingValues(horizontal = 16.dp, vertical = if (compact) 6.dp else 10.dp)
                if (guide != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GuideTile(guide, if (resting) null else guideCover, Modifier.size(if (compact) 44.dp else 60.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("YOUR GUIDE", style = EyebrowStyle.copy(color = Color.White.copy(alpha = 0.7f)))
                            Spacer(Modifier.height(2.dp))
                            Text(
                                guide.title,
                                color = Color.White,
                                fontFamily = SerifDisplay,
                                fontSize = if (compact) 16.sp else 19.sp,
                                lineHeight = if (compact) 20.sp else 24.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(if (compact) 14.dp else 22.dp))
                    Button(onClick = onOpenGuide, colors = primary, contentPadding = pad, modifier = Modifier.fillMaxWidth()) {
                        Text("Open guide", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                    if (books.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { picking = true }, colors = secondary, border = outline, contentPadding = pad, modifier = Modifier.fillMaxWidth()) {
                            Text("Other guide", maxLines = 1, softWrap = false)
                        }
                    }
                } else {
                    Text(
                        "No guide yet",
                        color = Color.White,
                        fontFamily = SerifDisplay,
                        fontSize = if (compact) 20.sp else 26.sp,
                    )
                    if (!compact) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Add one, or pick one from your shelf, and WalkThor will open it for this game from now on.",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                    }
                    Spacer(Modifier.height(if (compact) 14.dp else 22.dp))
                    Button(onClick = onAddGuide, colors = primary, contentPadding = pad, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add guide", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                    if (books.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { picking = true }, colors = secondary, border = outline, contentPadding = pad, modifier = Modifier.fillMaxWidth()) {
                            Text("From my shelf", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        // ---- over everything: back and close at the top, the status corner at the bottom ----
        Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(top = 6.dp)) {
            IconButton(onClick = onLibrary, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Library", tint = Color.White.copy(alpha = 0.85f))
            }
            Text("NOW PLAYING", style = EyebrowStyle.copy(color = Color.White.copy(alpha = 0.75f)), modifier = Modifier.align(Alignment.Center))
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.Filled.Close, contentDescription = "Close WalkThor", tint = Color.White.copy(alpha = 0.85f))
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            .fillMaxHeight()
            .aspectRatio(0.72f)
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
