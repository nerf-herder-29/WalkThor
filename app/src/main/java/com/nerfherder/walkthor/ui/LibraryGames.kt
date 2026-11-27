package com.nerfherder.walkthor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerfherder.walkthor.StickLights
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.BookType
import com.nerfherder.walkthor.data.GameGuides
import com.nerfherder.walkthor.data.LaunchedGame
import com.nerfherder.walkthor.data.Rom
import com.nerfherder.walkthor.data.RomLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.nerfherder.walkthor.data.GamePlays
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.withContext
import java.io.File

/** What the library shows: all games, one system's games, or your guides. */
object LibrarySection {
    const val ALL = "all"
    const val GUIDES = "guides"
    const val UPDATES = "updates"

    private fun prefs(c: android.content.Context) = c.getSharedPreferences("library", android.content.Context.MODE_PRIVATE)
    fun load(c: android.content.Context): String = prefs(c).getString("section", ALL) ?: ALL
    fun save(c: android.content.Context, s: String) = prefs(c).edit().putString("section", s).apply()
}

/** The game a ROM is, as the rest of WalkThor knows games. */
internal fun Rom.asGame() = LaunchedGame(title, file, RomLibrary.system(system)?.name, 0L)

/** The list down the library's left: All games, each system folder, then Guides and Updates. */
@Composable
fun LibraryRail(roms: List<Rom>, guides: Int, section: String, onSection: (String) -> Unit) {
    val narrow = LocalConfiguration.current.screenWidthDp < 520
    val systems = remember(roms) {
        roms.groupingBy { it.system }.eachCount().entries
            .map { (key, n) -> Triple(key, railName(key), n) }
            .sortedBy { it.second.lowercase() }
    }
    Column(
        Modifier
            .width(if (narrow) 132.dp else 190.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (narrow) 6.dp else 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (roms.isNotEmpty()) {
            RailItem("All games", roms.size, section == LibrarySection.ALL, narrow) { onSection(LibrarySection.ALL) }
            systems.forEach { (key, name, n) -> RailItem(name, n, section == key, narrow) { onSection(key) } }
            Box(Modifier.padding(horizontal = 6.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(BookColors.Hairline))
        }
        RailItem("Guides", guides, section == LibrarySection.GUIDES, narrow) { onSection(LibrarySection.GUIDES) }
        // Updates for WalkThor and your emulators, with how many are waiting.
        val updateContext = LocalContext.current
        androidx.compose.runtime.LaunchedEffect(Unit) { com.nerfherder.walkthor.data.Updates.load(updateContext) }
        val updates by com.nerfherder.walkthor.data.Updates.entries.collectAsState()
        RailItem("Updates", com.nerfherder.walkthor.data.Updates.waiting(updates), section == LibrarySection.UPDATES, narrow, badge = true) {
            onSection(LibrarySection.UPDATES)
        }
        if (roms.isEmpty()) return@Column
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
                .clickable(enabled = reading == null) { scope.launch { lib.scan() } }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** A system's name short enough for the list: "GameCube", "PlayStation 2", else its folder ("GBA"). */
private fun railName(key: String): String =
    RomLibrary.system(key)?.let { shortSystem(it.name) }?.takeIf { it.length <= 18 } ?: key.uppercase()

/** "Nintendo GameCube" → "GameCube", "Sony PlayStation 2" → "PlayStation 2". */
private fun shortSystem(name: String) =
    name.removePrefix("Nintendo ").removePrefix("Sony ").removePrefix("Sega ").removePrefix("Microsoft ")

@Composable
private fun RailItem(label: String, count: Int, on: Boolean, narrow: Boolean, badge: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) BookColors.SurfaceHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = if (narrow) 7.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = if (narrow) 12.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (on) BookColors.Text else BookColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badge) {
            // How many are waiting, in your stick lights' colour; nothing when there are none.
            if (count > 0) Text(
                "$count", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(guideAccent().takeIf { it != BookColors.Text } ?: Color(0xFF2BE0D8)).padding(horizontal = 7.dp, vertical = 1.dp),
            )
        } else Text("$count", fontSize = 12.sp, color = BookColors.TextMuted.copy(alpha = 0.7f), maxLines = 1)
    }
}

private val LIBRARY_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_BUTTON_A,
    android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_BUTTON_Y,
)

/** The colour for a game's guide: your stick lights' colour, or the usual light text when they're off. */
@Composable
private fun guideAccent(): Color {
    val context = LocalContext.current
    val bump by StickLights.changed.collectAsState()
    val accent = remember(bump) { StickLights.accent(context) }
    return accent?.let { Color(it) } ?: BookColors.Text
}

/**
 * Your games as a plain list, each with its guide beside it. Tap a guide to open it; tap
 * "Add a guide" to pick one of yours or add a file; press and hold a guide to change it or take
 * it off the game. [showSystem] adds each game's system under its name (the All games list).
 */
@Composable
fun GamesList(roms: List<Rom>, books: List<Book>, showSystem: Boolean, actions: LibraryActions) {
    val context = LocalContext.current
    // Bumped when a game's guide is changed here, so the list works them out again.
    var version by remember { mutableIntStateOf(0) }
    // Which of your guides goes with each game (worked out off the main thread).
    val guideFor by produceState<Map<String, Book>>(emptyMap(), roms, books, version) {
        value = withContext(Dispatchers.Default) {
            if (books.isEmpty()) emptyMap()
            else roms.mapNotNull { r -> GameGuides.pick(context, books, r.asGame())?.let { r.id to it } }.toMap()
        }
    }
    var query by remember { mutableStateOf("") }
    var choosingFor by remember { mutableStateOf<Rom?>(null) }
    val shown = remember(roms, query) {
        val q = query.trim()
        roms.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }.sortedBy { it.title.lowercase() }
    }
    // Typing needs WalkThor's window to take focus for a moment (it gives it back after).
    DisposableEffect(Unit) { onDispose { com.nerfherder.walkthor.GameFocus.wantFocus.value = false } }

    // ---- both screens: the game you're on, with its guide, big on the top screen ----
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val libActive by LibraryTop.active.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = shown.firstOrNull { it.id == selectedId }
        ?: shown.maxByOrNull { GamePlays.lastPlayed(context, it) }?.takeIf { GamePlays.lastPlayed(context, it) > 0 }
        ?: shown.firstOrNull { guideFor[it.id] != null }
        ?: shown.firstOrNull()
    val activity = context as? android.app.Activity
    val gameRunning by com.nerfherder.walkthor.GameFocus.gameRunning.collectAsState()
    DisposableEffect(activity, gameRunning) {
        val on = activity != null && !gameRunning
        LibraryTop.active.value = on
        if (on) Frontend.showTop(activity!!, moveDown = false)
        onDispose {
            LibraryTop.active.value = false
            Frontend.hideTopSoon()
        }
    }
    LaunchedEffect(selected?.id, guideFor[selected?.id]) {
        LibraryTop.current.value = selected?.let { r -> LibrarySpot(r, guideFor[r.id], guideFor[r.id]?.let(actions.coverFile)) }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun selectAt(i: Int) {
        val r = shown.getOrNull(i.coerceIn(0, (shown.size - 1).coerceAtLeast(0))) ?: return
        selectedId = r.id
        val n = shown.indexOf(r)
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == n } || visible.firstOrNull()?.index == n || visible.lastOrNull()?.index == n) {
            scope.launch { listState.animateScrollToItem((n - 2).coerceAtLeast(0)) }
        }
    }
    // Buttons pressed while the top screen has the controller.
    fun onKey(k: android.view.KeyEvent): Boolean {
        if (choosingFor != null) return false
        if (k.action != android.view.KeyEvent.ACTION_DOWN) return k.keyCode in LIBRARY_KEYS
        val i = shown.indexOf(selected)
        when (k.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> selectAt(i - 1)
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> selectAt(i + 1)
            android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER ->
                if (k.repeatCount == 0) selected?.let { r -> guideFor[r.id]?.let { actions.onOpen(it) } ?: run { choosingFor = r } }
            android.view.KeyEvent.KEYCODE_BUTTON_Y -> if (k.repeatCount == 0) selected?.let { choosingFor = it }
            else -> return false
        }
        return true
    }
    androidx.compose.runtime.SideEffect { if (activity != null && !gameRunning) Frontend.keyHandler = ::onKey }
    DisposableEffect(Unit) { onDispose { Frontend.keyHandler = null } }
    Column(Modifier.fillMaxSize()) {
        SearchBox(
            query, { query = it },
            Modifier
                .padding(start = 14.dp, end = 18.dp, top = 6.dp, bottom = 8.dp)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        com.nerfherder.walkthor.GameFocus.wantFocus.value = true
                    }
                }
                .onFocusChanged { if (!it.hasFocus) com.nerfherder.walkthor.GameFocus.wantFocus.value = false },
            hint = "Search games",
        )
        if (shown.isEmpty()) {
            Text(
                "No games match “${query.trim()}”.",
                color = BookColors.TextMuted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 14.dp, end = 18.dp, bottom = 20.dp),
        ) {
            items(shown, key = { it.id }) { rom ->
                GameRow(
                    rom = rom,
                    guide = guideFor[rom.id],
                    selected = libActive && rom.id == selected?.id,
                    onPress = { selectedId = rom.id },
                    showSystem = showSystem,
                    onOpen = { actions.onOpen(it) },
                    onChoose = { choosingFor = rom },
                    onRemove = { GameGuides.unlink(context, rom.asGame()); version++ },
                )
            }
        }
    }
    choosingFor?.let { rom ->
        GuideChooserDialog(
            title = rom.title,
            books = books,
            coverFile = actions.coverFile,
            onPick = { b -> choosingFor = null; GameGuides.remember(context, rom.asGame(), b.id); version++ },
            onAddFile = { choosingFor = null; actions.onAddGuideFor(rom.asGame()) },
            onDismiss = { choosingFor = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameRow(
    rom: Rom,
    guide: Book?,
    /** The game shown on the top screen. */
    selected: Boolean = false,
    onPress: () -> Unit = {},
    showSystem: Boolean,
    onOpen: (Book) -> Unit,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val ring = frontAccent()
    Column(
        Modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    onPress()
                }
            }
            .onFocusChanged { if (it.hasFocus) onPress() },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (selected) Modifier.clip(RoundedCornerShape(10.dp)).background(BookColors.SurfaceHigh.copy(alpha = 0.6f))
                        .border(2.dp, ring, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp)
                    else Modifier,
                )
                .heightIn(min = 52.dp).padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    displayTitle(rom.title),
                    color = BookColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp,
                )
                if (showSystem) {
                    Text(
                        RomLibrary.system(rom.system)?.name ?: rom.system.uppercase(),
                        color = BookColors.TextMuted, fontSize = 11.sp, maxLines = 1,
                    )
                }
            }
            Box {
                if (guide != null) {
                    Text(
                        guide.title,
                        color = guideAccent(), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .clip(RoundedCornerShape(50))
                            .background(BookColors.SurfaceHigh)
                            .combinedClickable(onClick = { onOpen(guide) }, onLongClick = { menu = true })
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                } else {
                    Text(
                        "+ Add a guide",
                        color = BookColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(BookColors.SurfaceHigh)
                            .clickable(onClick = onChoose)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Change guide…") }, onClick = { menu = false; onChoose() })
                    DropdownMenuItem(text = { Text("Take this guide off the game") }, onClick = { menu = false; onRemove() })
                }
            }
        }
        HorizontalDivider(color = BookColors.Hairline.copy(alpha = 0.6f))
    }
}

/** One of your guides in a list: its small tile, its name, and where you are in it. */
@Composable
private fun GuideRow(book: Book, cover: File?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GuideTile(book, cover, Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, color = BookColors.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (book.pageCount > 0) "p. ${book.lastPage + 1} of ${book.pageCount}" else if (book.type == BookType.PDF) "PDF" else "TXT",
                color = BookColors.TextMuted, fontSize = 11.sp,
            )
        }
    }
}

/** Pick one of your guides for the game called [title], or add a file for it. */
@Composable
fun GuideChooserDialog(
    title: String,
    books: List<Book>,
    coverFile: (Book) -> File?,
    onPick: (Book) -> Unit,
    onAddFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Closest titles first.
    val ranked = remember(books, title) {
        books.sortedByDescending { b -> com.nerfherder.walkthor.data.Matcher.gameScore(b.title, title) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BookColors.Surface,
        title = { Text("A guide for ${displayTitle(title)}", fontFamily = DisplayFont, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            if (ranked.isEmpty()) {
                Text("You haven't added any guides yet. Add a PDF or TXT file for this game.", color = BookColors.TextMuted)
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(ranked, key = { it.id }) { book ->
                        GuideRow(book, remember(book.id, book.cover) { coverFile(book) }) { onPick(book) }
                    }
                }
            }
        },
        confirmButton = { FocusTextButton(onClick = onAddFile) { Text("Add a file…") } },
        dismissButton = { FocusTextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The Guide tab while you play: your guides, newest first, to pick the one you want. It asks
 * each time you come to the tab.
 */
@Composable
fun GuidePickPanel(
    books: List<Book>,
    coverFile: (Book) -> File?,
    onAddGuide: () -> Unit,
    onPick: (Book) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(books) { books.sortedByDescending { it.addedAt } }
    Box(modifier.fillMaxSize().background(backdropOr(BookColors.Ink))) {
        if (sorted.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No guides yet", style = EyebrowStyle)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add a PDF or TXT walkthrough to read while you play.",
                    color = BookColors.TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                AddGuideButton(onAddGuide)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Which guide?", color = BookColors.Text, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    AddGuideButton(onAddGuide)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(sorted, key = { it.id }) { book ->
                        GuideRow(book, remember(book.id, book.cover) { coverFile(book) }) { onPick(book) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddGuideButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
        border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) { Text("Add guide", fontWeight = FontWeight.SemiBold, maxLines = 1) }
}
