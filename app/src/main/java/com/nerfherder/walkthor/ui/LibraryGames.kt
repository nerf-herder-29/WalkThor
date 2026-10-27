package com.nerfherder.walkthor.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
    const val HIDDEN = "hidden"

    private fun prefs(c: android.content.Context) = c.getSharedPreferences("library", android.content.Context.MODE_PRIVATE)
    fun load(c: android.content.Context): String = prefs(c).getString("section", ALL) ?: ALL
    fun save(c: android.content.Context, s: String) = prefs(c).edit().putString("section", s).apply()
}

/** Games you've hidden from your library (by ROM), kept on the device. */
object HiddenGames {
    private const val KEY = "hidden"
    private fun prefs(c: android.content.Context) = c.getSharedPreferences("library", android.content.Context.MODE_PRIVATE)
    val ids = kotlinx.coroutines.flow.MutableStateFlow<Set<String>?>(null)

    fun load(c: android.content.Context): Set<String> =
        ids.value ?: (prefs(c).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()).also { ids.value = it }

    fun set(c: android.content.Context, romId: String, hidden: Boolean) {
        val now = load(c).let { if (hidden) it + romId else it - romId }
        prefs(c).edit().putStringSet(KEY, now).apply()
        ids.value = now
    }
}

/** The game a ROM is, as the rest of WalkThor knows games. */
internal fun Rom.asGame() = LaunchedGame(title, file, RomLibrary.system(system)?.name, 0L)

/** The list down the library's left: All games, each system folder, then Guides. */
@Composable
fun LibraryRail(roms: List<Rom>, guides: Int, hidden: Int, section: String, onSection: (String) -> Unit) {
    val narrow = LocalConfiguration.current.screenWidthDp < 520
    val systems = remember(roms) {
        roms.groupingBy { it.system }.eachCount().entries
            .map { (key, n) -> Triple(key, railName(key), n) }
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
        if (hidden > 0 || section == LibrarySection.HIDDEN) {
            RailItem("Hidden", hidden, section == LibrarySection.HIDDEN, narrow) { onSection(LibrarySection.HIDDEN) }
        }
        // Reads your ROMs folder again, for games you've added or removed.
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val lib = remember { RomLibrary.get(context) }
        val reading by lib.scanning.collectAsState()
        Box(Modifier.padding(horizontal = 6.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(BookColors.Hairline))
        Text(
            if (reading != null) "Refreshing…" else "Refresh list",
            fontSize = 11.sp,
            color = BookColors.TextMuted.copy(alpha = 0.8f),
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = reading == null) {
                    scope.launch {
                        lib.scan()
                        com.nerfherder.walkthor.data.ArtworkRepository.get(context).forgetGameArt()
                    }
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** A system's name short enough for the list: "GameCube", "PlayStation 2", else its folder ("GBA"). */
private fun railName(key: String): String =
    RomLibrary.system(key)?.let { shortSystem(it.name) }?.takeIf { it.length <= 13 } ?: key.uppercase()

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
fun GamesGrid(roms: List<Rom>, books: List<Book>, coverFile: (Book) -> File?, actions: LibraryActions, hiddenView: Boolean = false) {
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
    var menuFor by remember { mutableStateOf<Rom?>(null) }
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
            Box {
                GameTile(
                    rom = rom,
                    hasGuide = guide != null,
                    // Hidden games: a tap offers to show it again.
                    onClick = { if (hiddenView) menuFor = rom else if (guide != null) actions.onOpen(guide) else sheetFor = rom },
                    onLongClick = { menuFor = rom },
                )
                androidx.compose.material3.DropdownMenu(expanded = menuFor == rom, onDismissRequest = { menuFor = null }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Choose art") },
                        onClick = { menuFor = null; artFor = rom },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (hiddenView) "Show in library" else "Hide game") },
                        onClick = { menuFor = null; HiddenGames.set(context, rom.id, !hiddenView) },
                    )
                }
            }
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
            onPickFile = { kind -> artFor = null; actions.onPickGameCover(rom.title, kind) },
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
        // The name is under the card already; here just the system, quietly.
        Text(
            railName(rom.system).uppercase(),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 9.dp, end = 9.dp, bottom = 10.dp),
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
        dismissButton = { TextButton(onClick = onChooseCover) { Text("Choose art", color = BookColors.TextMuted) } },
    )
}

/**
 * Choose a game's art, in three tabs: its cover (library), background (Now playing, behind the
 * tabs, the splash) and marquee (the logo on top). Each shows yours (if you've set one), the
 * Cocoon artwork folder's and the scraped one, or any image file. "Use automatic" goes back to
 * Cocoon's, else the scraped one.
 */
@Composable
fun GameArtworkDialog(
    title: String,
    subtitle: String,
    gameTitle: String,
    file: String?,
    onPickFile: (GameCovers.Kind) -> Unit,
    onDismiss: () -> Unit,
    startOn: GameCovers.Kind = GameCovers.Kind.COVER,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by GameCovers.version.collectAsState()
    val all by produceState<Map<GameCovers.Kind, GameCovers.Options>?>(null, gameTitle, file, version) {
        value = GameCovers.allOptions(context, gameTitle, file)
    }
    var kind by remember { mutableStateOf(startOn) }
    // Your pick per tab, applied together with Done.
    val picks = remember { androidx.compose.runtime.mutableStateMapOf<GameCovers.Kind, String>() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.9f)
                .background(BookColors.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, BookColors.Hairline, RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Text(title, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = BookColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$subtitle · " + when (kind) {
                    GameCovers.Kind.COVER -> "Shown in your library"
                    GameCovers.Kind.HERO -> "Shown behind the game on Now playing"
                    GameCovers.Kind.LOGO -> "Shown on top of the background"
                },
                color = BookColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GameCovers.Kind.entries.forEach { k ->
                    val on = k == kind
                    Text(
                        k.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (on) Color.Black else BookColors.TextMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) BookColors.Text else BookColors.SurfaceHigh)
                            .clickable { kind = k }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            val o = all?.get(kind)
            val current = o?.let { it.user ?: it.cocoon ?: it.scraped }
            val chosen = picks[kind] ?: current
            // Box art is tall, backgrounds wide, marquees wider still (and shown whole).
            val (w, ratio) = when (kind) {
                GameCovers.Kind.COVER -> 96.dp to 3f / 4f
                GameCovers.Kind.HERO -> 150.dp to 16f / 9f
                GameCovers.Kind.LOGO -> 150.dp to 2f
            }
            if (o == null) {
                Text("Looking for art…", color = BookColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 40.dp))
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    o.user?.let { ArtChoice(it, "Yours", "Picked by you", chosen == it, w, ratio, kind) { picks[kind] = it } }
                    o.cocoon?.let { ArtChoice(it, "Cocoon", "Artwork folder", chosen == it, w, ratio, kind) { picks[kind] = it } }
                    o.scraped?.let { ArtChoice(it, "Scraped", "Fetched online", chosen == it, w, ratio, kind) { picks[kind] = it } }
                    Column(Modifier.width(w)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(ratio)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, BookColors.Hairline, RoundedCornerShape(8.dp))
                                .clickable { onPickFile(kind) },
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
                    TextButton(onClick = { GameCovers.clearUser(context, gameTitle, kind); picks.remove(kind) }) {
                        Text("Use automatic", color = BookColors.TextMuted)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel", color = BookColors.TextMuted) }
                TextButton(onClick = {
                    val chosenNow = picks.toMap()
                    val options = all
                    scope.launch {
                        chosenNow.forEach { (k, pick) ->
                            val op = options?.get(k) ?: return@forEach
                            val cur = op.user ?: op.cocoon ?: op.scraped
                            if (pick == cur) return@forEach
                            // Choosing the automatic one just clears yours; anything else becomes yours.
                            if (op.user != null && pick == (op.cocoon ?: op.scraped)) GameCovers.clearUser(context, gameTitle, k)
                            else GameCovers.setUser(context, gameTitle, Uri.parse(pick), k)
                        }
                    }
                    onDismiss()
                }) { Text("Done") }
            }
        }
    }
}

@Composable
private fun ArtChoice(
    uri: String, label: String, note: String, on: Boolean,
    width: androidx.compose.ui.unit.Dp, ratio: Float, kind: GameCovers.Kind, onClick: () -> Unit,
) {
    Column(Modifier.width(width)) {
        AsyncImage(
            Uri.parse(uri),
            contentDescription = label,
            // Marquees are logos: show them whole, on a dark card.
            contentScale = if (kind == GameCovers.Kind.LOGO) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0B0B0D))
                .border(if (on) 3.dp else 1.dp, if (on) BookColors.Text else BookColors.Hairline, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(if (kind == GameCovers.Kind.LOGO) 8.dp else 0.dp),
        )
        Text(label, color = BookColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Text(note, color = BookColors.TextMuted, fontSize = 10.sp)
    }
}
