package com.nerfherder.walkthor.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.GameCovers
import com.nerfherder.walkthor.data.GameGuides
import com.nerfherder.walkthor.data.LaunchedGame
import com.nerfherder.walkthor.data.Rom
import com.nerfherder.walkthor.data.RomLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the library shows: all games, one system's games, or your guides. */
object LibrarySection {
    const val ALL = "all"
    const val GUIDES = "guides"

    private fun prefs(c: android.content.Context) = c.getSharedPreferences("library", android.content.Context.MODE_PRIVATE)
    fun load(c: android.content.Context): String = prefs(c).getString("section", ALL) ?: ALL
    fun save(c: android.content.Context, s: String) = prefs(c).edit().putString("section", s).apply()
}

/** The game a ROM is, as the rest of WalkThor knows games. */
internal fun Rom.asGame() = LaunchedGame(title, file, RomLibrary.system(system)?.name, 0L)

/** The list down the library's left: All games, each system folder, then Guides. */
@Composable
fun LibraryRail(roms: List<Rom>, guides: Int, section: String, onSection: (String) -> Unit) {
    val narrow = LocalConfiguration.current.screenWidthDp < 520
    val systems = remember(roms) {
        roms.groupingBy { it.system }.eachCount().entries
            .map { (key, n) -> Triple(key, RomLibrary.system(key)?.let { shortSystem(it.name) } ?: key.uppercase(), n) }
            .sortedBy { it.second.lowercase() }
    }
    Column(
        Modifier
            .width(if (narrow) 118.dp else 150.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (narrow) 6.dp else 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RailItem("All games", roms.size, section == LibrarySection.ALL, narrow) { onSection(LibrarySection.ALL) }
        systems.forEach { (key, name, n) -> RailItem(name, n, section == key, narrow) { onSection(key) } }
        Box(Modifier.padding(horizontal = 6.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(BookColors.Hairline))
        RailItem("Guides", guides, section == LibrarySection.GUIDES, narrow) { onSection(LibrarySection.GUIDES) }
    }
}

/** "Nintendo GameCube" → "GameCube", "Sony PlayStation 2" → "PlayStation 2". */
private fun shortSystem(name: String) =
    name.removePrefix("Nintendo ").removePrefix("Sony ").removePrefix("Sega ").removePrefix("Microsoft ")

@Composable
private fun RailItem(label: String, count: Int, on: Boolean, narrow: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) BookColors.SurfaceHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = if (narrow) 7.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = if (narrow) 12.sp else 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (on) BookColors.Text else BookColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$count", fontSize = 11.sp, color = BookColors.TextMuted.copy(alpha = 0.7f), maxLines = 1)
    }
}

/**
 * Your games as covers. Tap one with a guide to open the guide; tap one without to add or pick
 * a guide; long-press any to choose its cover.
 */
@Composable
fun GamesGrid(roms: List<Rom>, books: List<Book>, coverFile: (Book) -> File?, actions: LibraryActions) {
    val context = LocalContext.current
    // Which of your guides goes with each game (worked out once, off the main thread).
    val guideFor by produceState<Map<String, Book>>(emptyMap(), roms, books) {
        value = withContext(Dispatchers.Default) {
            if (books.isEmpty()) emptyMap()
            else roms.mapNotNull { r -> GameGuides.pick(context, books, r.asGame())?.let { r.id to it } }.toMap()
        }
    }
    var sheetFor by remember { mutableStateOf<Rom?>(null) }
    var artFor by remember { mutableStateOf<Rom?>(null) }
    val sorted = remember(roms) { roms.sortedBy { it.title.lowercase() } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(sorted, key = { it.id }) { rom ->
            val guide = guideFor[rom.id]
            GameTile(
                rom = rom,
                hasGuide = guide != null,
                onClick = { if (guide != null) actions.onOpen(guide) else sheetFor = rom },
                onLongClick = { artFor = rom },
            )
        }
    }
    sheetFor?.let { rom ->
        GameSheet(
            rom = rom,
            books = books,
            coverFile = coverFile,
            onAddGuide = { sheetFor = null; actions.onAddGuideFor(rom.asGame()) },
            onLinkGuide = { b -> sheetFor = null; actions.onLinkGuide(rom.asGame(), b.id) },
            onChooseCover = { sheetFor = null; artFor = rom },
            onDismiss = { sheetFor = null },
        )
    }
    artFor?.let { rom ->
        GameArtworkDialog(
            title = displayTitle(rom.title),
            subtitle = RomLibrary.system(rom.system)?.name ?: rom.system,
            gameTitle = rom.title,
            file = rom.file,
            onPickFile = { artFor = null; actions.onPickGameCover(rom.title) },
            onDismiss = { artFor = null },
        )
    }
}

/** One game's cover, the way it's chosen: yours, else Cocoon's, else scraped. */
@Composable
private fun gameCover(title: String, file: String?): String? {
    val context = LocalContext.current
    val version by GameCovers.version.collectAsState()
    val cover by produceState<String?>(null, title, file, version) { value = GameCovers.coverFor(context, title, file) }
    return cover
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameTile(rom: Rom, hasGuide: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cover = gameCover(rom.title, rom.file)
    val resting by AutoDim.resting.collectAsState()
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        val shape = RoundedCornerShape(10.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(shape)
                .background(BookColors.Surface),
        ) {
            if (cover != null && !resting) {
                AsyncImage(Uri.parse(cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                PlainCover(rom)
            }
            if (hasGuide) {
                Text(
                    "Guide",
                    color = BookColors.Text,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.08f), shape))
        }
        Text(
            displayTitle(rom.title),
            color = BookColors.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A plain colour card with the game's name, for a game with no cover yet. */
@Composable
private fun PlainCover(rom: Rom) {
    val ink = coverInks[(rom.title.hashCode() and Int.MAX_VALUE) % coverInks.size]
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(ink, Color.White, 0.14f), ink, Color(0xFF0D0D10)))),
    ) {
        Text(
            (RomLibrary.system(rom.system)?.let { shortSystem(it.name) } ?: rom.system).uppercase(),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )
        Text(
            displayTitle(rom.title),
            color = BookColors.Text,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 9.dp, end = 9.dp, bottom = 11.dp),
        )
    }
}

/** A game without a guide: add one, pick one of yours, or change its cover. */
@Composable
private fun GameSheet(
    rom: Rom,
    books: List<Book>,
    coverFile: (Book) -> File?,
    onAddGuide: () -> Unit,
    onLinkGuide: (Book) -> Unit,
    onChooseCover: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val cover = gameCover(rom.title, rom.file)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BookColors.Surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 42.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)).background(BookColors.SurfaceHigh)) {
                    if (cover != null) AsyncImage(Uri.parse(cover), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(displayTitle(rom.title), fontFamily = DisplayFont, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(RomLibrary.system(rom.system)?.name ?: rom.system, style = EyebrowStyle)
                }
            }
        },
        text = {
            if (picking) {
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(books, key = { it.id }) { b ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onLinkGuide(b) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GuideTile(b, remember(b.id, b.cover) { coverFile(b) }, Modifier.size(40.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(b.title, color = BookColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                Text("No guide for this game yet.", color = BookColors.TextMuted)
            }
        },
        confirmButton = {
            Row {
                if (!picking && books.isNotEmpty()) TextButton(onClick = { picking = true }) { Text("From my guides") }
                TextButton(onClick = onAddGuide) { Text("Add guide") }
            }
        },
        dismissButton = { TextButton(onClick = onChooseCover) { Text("Choose cover", color = BookColors.TextMuted) } },
    )
}

/**
 * Choose a game's cover: yours (if you've set one), the Cocoon artwork folder's, the scraped one,
 * or any image file. "Use automatic" goes back to Cocoon's, else the scraped one.
 */
@Composable
fun GameArtworkDialog(
    title: String,
    subtitle: String,
    gameTitle: String,
    file: String?,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val options by produceState<GameCovers.Options?>(null, gameTitle, file) { value = GameCovers.options(context, gameTitle, file) }
    val o = options
    // What's in use now: yours, else the automatic one.
    val current = o?.user ?: o?.cocoon ?: o?.scraped
    var chosen by remember(current) { mutableStateOf(current) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.9f)
                .background(BookColors.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, BookColors.Hairline, RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Text(title, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = BookColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$subtitle · Choose the cover shown in your library", color = BookColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(16.dp))
            if (o == null) {
                Text("Looking for covers…", color = BookColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 40.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    o.user?.let { CoverChoice(it, "Yours", "Picked by you", chosen == it) { chosen = it } }
                    o.cocoon?.let { CoverChoice(it, "Cocoon", "Artwork folder", chosen == it) { chosen = it } }
                    o.scraped?.let { CoverChoice(it, "Scraped", "Fetched online", chosen == it) { chosen = it } }
                    Column(Modifier.width(100.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, BookColors.Hairline, RoundedCornerShape(8.dp))
                                .clickable(onClick = onPickFile),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("+\nPick an image", color = BookColors.TextMuted, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        Text("From a file", color = BookColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                        Text("Any image", color = BookColors.TextMuted, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (o?.user != null) {
                    TextButton(onClick = { GameCovers.clearUser(context, gameTitle); onDismiss() }) {
                        Text("Use automatic", color = BookColors.TextMuted)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel", color = BookColors.TextMuted) }
                TextButton(onClick = {
                    val pick = chosen
                    if (pick != null && pick != current) {
                        scope.launch {
                            // Choosing the automatic one just clears yours; anything else becomes yours.
                            if (o?.user != null && pick == (o.cocoon ?: o.scraped)) GameCovers.clearUser(context, gameTitle)
                            else GameCovers.setUser(context, gameTitle, Uri.parse(pick))
                        }
                    }
                    onDismiss()
                }) { Text("Done") }
            }
        }
    }
}

@Composable
private fun CoverChoice(uri: String, label: String, note: String, on: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(100.dp)) {
        AsyncImage(
            Uri.parse(uri),
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp))
                .border(if (on) 3.dp else 1.dp, if (on) BookColors.Text else BookColors.Hairline, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        )
        Text(label, color = BookColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Text(note, color = BookColors.TextMuted, fontSize = 10.sp)
    }
}
