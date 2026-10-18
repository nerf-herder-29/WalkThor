package com.nerfherder.walkthor.ui

import androidx.compose.ui.draw.drawWithContent

import androidx.compose.ui.draw.blur

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
    onMirror: (() -> Unit)? = null,
) {
    var picking by remember { mutableStateOf(false) }
    // Nothing to read here, so dim after a few seconds (whatever the Auto-dim setting is);
    // with a guide, the dimmed screen offers to open it.
    val openGuide by androidx.compose.runtime.rememberUpdatedState(onOpenGuide)
    val mirror by androidx.compose.runtime.rememberUpdatedState(onMirror)
    androidx.compose.runtime.DisposableEffect(guide?.id) {
        AutoDim.wake()
        AutoDim.screenDelayMs.value = AutoDim.NO_GUIDE_DELAY_MS
        AutoDim.openGuide.value = if (guide != null) ({ openGuide() }) else null
        AutoDim.openMirror.value = if (onMirror != null) ({ mirror?.invoke() }) else null
        onDispose {
            AutoDim.screenDelayMs.value = null
            AutoDim.openGuide.value = null
            AutoDim.openMirror.value = null
        }
    }
    // The Thor's bottom screen is short; at denser display settings tighten everything up.
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val enter = remember { Animatable(0f) }
    LaunchedEffect(game.title, game.at) { enter.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    val resting by AutoDim.resting.collectAsState()
    val guideCover = remember(guide?.id, guide?.cover) { guide?.let(coverFile) }
    // Only wide scene art makes a background; box art and icons (with their logos baked in) don't.
    val banner = art.hero
    // Button text colour on the white primary button.
    val fill = BookColors.Ink

    Box(Modifier.fillMaxSize().background(BookColors.Ink)) {
        // ---- the game's wide art across the whole screen ----
        if (banner != null && !resting) {
            AsyncImage(
                model = Uri.parse(banner),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = enter.value },
            )
        }
        // Gentle shading at the top and bottom, for the bar icons, the logo and the status corner.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.22f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.7f),
                )
            )
        )
        // ---- frosted glass that eases in toward the right, for the guide ----
        // A blurred, darkened copy of the art, faded in with a soft mask across the middle, so
        // the art goes from sharp on the left to frosted on the right with no hard edge.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = enter.value
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.36f to Color.Transparent,
                            0.62f to Color.Black,
                            1f to Color.Black,
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
        ) {
            if (banner != null && !resting) {
                // The same art, gently blurred. (Android 12+; older versions just get the tint.)
                AsyncImage(
                    model = Uri.parse(banner),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(14.dp) else Modifier),
                )
            }
            // Smoky tint so white text reads on any art.
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0B10).copy(alpha = 0.62f)))
        }
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
                    SecondaryButtons(
                        first = if (books.size > 1) ("Other guide" to { picking = true }) else null,
                        onMirror = onMirror, compact = compact, colors = secondary, border = outline, pad = pad,
                    )
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
                    SecondaryButtons(
                        first = if (books.isNotEmpty()) ("From my shelf" to { picking = true }) else null,
                        onMirror = onMirror, compact = compact, colors = secondary, border = outline, pad = pad,
                    )
                }
            }
        }

        // ---- over everything: back and close at the top, the status corner at the bottom ----
        Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(top = 6.dp)) {
            IconButton(onClick = onLibrary, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Library", tint = Color.White.copy(alpha = 0.85f))
            }
            // Beside the back arrow, clear of the glass edge in the middle.
            Text("NOW PLAYING", style = EyebrowStyle.copy(color = Color.White.copy(alpha = 0.75f)), modifier = Modifier.align(Alignment.CenterStart).padding(start = 52.dp))
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

/**
 * The buttons under the main one (Other guide / From my shelf, and Mirror), side by side so they
 * read as one group; stacked on dense screens where two don't fit across.
 */
@Composable
private fun SecondaryButtons(
    first: Pair<String, () -> Unit>?,
    onMirror: (() -> Unit)?,
    compact: Boolean,
    colors: androidx.compose.material3.ButtonColors,
    border: androidx.compose.foundation.BorderStroke,
    pad: PaddingValues,
) {
    val items = listOfNotNull(first, onMirror?.let { "Mirror" to it })
    if (items.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    // Side by side only when every label fits its half without being cut off.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = androidx.compose.material3.MaterialTheme.typography.labelLarge
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val widest = items.maxOf { (label, _) -> measurer.measure(label, labelStyle).size.width }
    val half = (maxWidth - 8.dp) / 2
    val textWidth = with(density) { widest.toDp() } + 4.dp
    // Normal padding if it fits, else tighter padding, else stack them.
    val sidePad = when {
        half >= textWidth + 16.dp * 2 -> 16.dp
        half >= textWidth + 6.dp * 2 -> 6.dp
        else -> null
    }
    if (compact || items.size == 1 || sidePad == null) {
        Column {
        items.forEachIndexed { i, (label, action) ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = action, colors = colors, border = border, contentPadding = pad, modifier = Modifier.fillMaxWidth()) {
                Text(label, maxLines = 1, softWrap = false)
            }
        }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { (label, action) ->
                OutlinedButton(
                    onClick = action, colors = colors, border = border,
                    contentPadding = PaddingValues(horizontal = sidePad, vertical = pad.calculateTopPadding()),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(label, maxLines = 1, softWrap = false)
                }
            }
        }
    }
    }
}
