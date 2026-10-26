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
    /** Opens one of the game's tabs (Guide, Mirror, Face Buttons). */
    onTab: (GameTab) -> Unit = {},
    /** A game noticed from its emulator: you picked it from your ROMs folder. */
    onPickRom: (com.nerfherder.walkthor.data.Rom) -> Unit = {},
) {
    val roms by com.nerfherder.walkthor.data.RomLibrary.get(androidx.compose.ui.platform.LocalContext.current).roms.collectAsState()
    // Without Cocoon there's something to choose from if you have guides or a ROMs folder.
    val choices = books.isNotEmpty() || (game.emulatorPkg != null && roms.isNotEmpty())
    var picking by remember { mutableStateOf(false) }
    // Dims after the Auto-dim setting's time, like everywhere else.
    androidx.compose.runtime.DisposableEffect(guide?.id) {
        AutoDim.wake()
        AutoDim.linkLabel.value = linkLabelFor(guide, books, game, choices)
        AutoDim.nowPlayingShown.value = true
        onDispose {
            AutoDim.nowPlayingShown.value = false
            AutoDim.linkLabel.value = null
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
        // ---- the game's wide art across the whole screen, sharp ----
        if (banner != null && !resting) {
            AsyncImage(
                model = Uri.parse(banner),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = enter.value },
            )
        }
        // Shading so white text reads on any art: darker in the middle (behind the name and
        // buttons) and at the top and bottom (the bar and the status corner).
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    0f to Color.Black.copy(alpha = 0.72f),
                    0.6f to Color.Black.copy(alpha = 0.45f),
                    1f to Color.Black.copy(alpha = 0.25f),
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.2f to Color.Transparent,
                    0.8f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.6f),
                )
            )
        )
        // ---- the game, its emulator and the buttons: the same layout as the dimmed screen ----
        val link = linkLabelFor(guide, books, game, choices)?.let { it to { picking = true } }
        NowPlayingStack(
            // Noticed from its emulator but not yet known: ask (the emulator shows underneath).
            title = if (game.unknown && choices) "What are you playing?" else game.title,
            player = game.player,
            buttons = emptyList(),
            link = link,
            linkVisible = true,
            actOnDown = false,
            modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = enter.value },
        )

        // ---- over everything: the tabs at the top, the status corner at the bottom ----
        GameTabBar(selected = null, onSelect = onTab, onBack = onLibrary, onClose = onClose, overlay = true)
        // The game's logo (marquee) in the bottom-left corner; the dimmed screen leaves it out.
        if (art.logo != null && !resting) {
            AsyncImage(
                model = Uri.parse(art.logo),
                contentDescription = displayTitle(game.title),
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 12.dp)
                    .widthIn(max = if (compact) 130.dp else 180.dp)
                    .heightIn(max = if (compact) 44.dp else 64.dp)
                    .graphicsLayer { alpha = enter.value },
            )
        }
        Row(
            Modifier.align(Alignment.BottomEnd).height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusClock(Modifier.padding(start = 4.dp, end = 16.dp))
        }
    }

    if (picking) {
        if (game.emulatorPkg != null) {
            GamePickerDialog(
                game, books, roms, coverFile,
                onPickGuide = { picking = false; onPickGuide(it) },
                onPickRom = { picking = false; onPickRom(it) },
                onDismiss = { picking = false },
            )
        } else {
            GuidePickerDialog(game, books, coverFile, onPick = { picking = false; onPickGuide(it) }, onDismiss = { picking = false })
        }
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
            fontFamily = DisplayFont,
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
    onControls: (() -> Unit)?,
    compact: Boolean,
    colors: androidx.compose.material3.ButtonColors,
    border: androidx.compose.foundation.BorderStroke,
    pad: PaddingValues,
) {
    val items = listOfNotNull(first, onMirror?.let { "Mirror" to it }, onControls?.let { "Face Buttons" to it })
    if (items.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    // Side by side only when every label fits its half without being cut off.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = androidx.compose.material3.MaterialTheme.typography.labelLarge
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val widths = items.map { (label, _) -> with(density) { measurer.measure(label, labelStyle).size.width.toDp() } + 4.dp }
    // Padding that lets every label in a row of [n] fit, or null if they don't fit at all.
    fun padFor(labels: List<androidx.compose.ui.unit.Dp>, n: Int): androidx.compose.ui.unit.Dp? {
        val cell = (maxWidth - 8.dp * (n - 1)) / n
        val widest = labels.maxOf { it }
        return when {
            cell >= widest + 16.dp * 2 -> 16.dp
            cell >= widest + 6.dp * 2 -> 6.dp
            else -> null
        }
    }
    // Fill rows left to right with as many buttons as fit side by side.
    val rows = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    items.indices.forEach { i ->
        val trial = current + i
        if (current.isEmpty() || padFor(trial.map { widths[it] }, trial.size) != null) current.add(i)
        else { rows.add(current); current = mutableListOf(i) }
    }
    if (current.isNotEmpty()) rows.add(current)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            val sidePad = padFor(row.map { widths[it] }, row.size) ?: 6.dp
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.map { items[it] }.forEach { (label, action) ->
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
}

/** The small link under the buttons: pick another guide, or one from the shelf; null if there's none to pick. */
private fun linkLabelFor(guide: Book?, books: List<Book>, game: LaunchedGame? = null, choices: Boolean = books.isNotEmpty()): String? = when {
    game?.unknown == true && choices -> "Choose game"
    game?.emulatorPkg != null && choices -> "Change game"
    guide != null && books.size > 1 -> "Other guide"
    guide == null && books.isNotEmpty() -> "From my guides"
    else -> null
}

/** Pick a guide from the shelf for [game]; closest titles first. */
@Composable
fun GuidePickerDialog(
    game: LaunchedGame,
    books: List<Book>,
    coverFile: (Book) -> File?,
    onPick: (Book) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ranked = remember(books, game) {
        // Without Cocoon: the guides you've used with this emulator first, most recent first.
        val recent = game.emulatorPkg?.let { com.nerfherder.walkthor.EmulatorWatch.recentGuides(context, it) }.orEmpty()
        if (game.unknown) {
            books.sortedWith(compareBy<Book> { b -> recent.indexOf(b.id).let { if (it < 0) Int.MAX_VALUE else it } }.thenByDescending { it.addedAt })
        } else {
            val names = listOfNotNull(game.title, game.file?.substringBeforeLast('.'))
            books.sortedByDescending { b -> names.maxOf { Matcher.gameScore(b.title, it) } }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BookColors.Surface,
        title = { Text(if (game.unknown) "What are you playing?" else "Which guide is for this game?", fontFamily = DisplayFont) },
        text = {
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(ranked, key = { it.id }) { book ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(book) }
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The Guide tab when the game has no guide yet: add one, or pick one from the shelf. */
@Composable
fun NoGuidePanel(
    game: LaunchedGame,
    books: List<Book>,
    coverFile: (Book) -> File?,
    onAddGuide: () -> Unit,
    onPickGuide: (Book) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(backdropOr(BookColors.Ink)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No guide yet", style = EyebrowStyle)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add a guide for ${displayTitle(game.title)}",
                color = BookColors.Text,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "WalkThor will open it for this game from now on.",
                color = BookColors.TextMuted,
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onAddGuide,
                    colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
                ) { Text("Add guide", fontWeight = FontWeight.SemiBold) }
                if (books.isNotEmpty()) {
                    androidx.compose.material3.OutlinedButton(onClick = { picking = true }) {
                        Text("From my guides", color = BookColors.Text, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    if (picking) {
        GuidePickerDialog(game, books, coverFile, onPick = { picking = false; onPickGuide(it) }, onDismiss = { picking = false })
    }
}
