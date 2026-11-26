package com.nerfherder.walkthor.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.togetherWith
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.nerfherder.walkthor.DisplayHelper
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.GameArt
import com.nerfherder.walkthor.data.GameGuides
import com.nerfherder.walkthor.data.GameLaunch
import com.nerfherder.walkthor.data.GamePlays
import com.nerfherder.walkthor.data.Rom
import com.nerfherder.walkthor.data.RomLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The highlight colour (the ring around the chosen cover): your stick lights' colour, or white when they're off. */
@Composable
fun frontAccent(): Color {
    val context = LocalContext.current
    val bump by com.nerfherder.walkthor.StickLights.changed.collectAsState()
    return remember(bump) {
        val c = runCatching { com.nerfherder.walkthor.StickLights.color(context) }.getOrDefault(0)
        val dark = runCatching { com.nerfherder.walkthor.StickLights.level(context) <= 0.01f }.getOrDefault(false) ||
            // Switched off on AYN's side.
            runCatching {
                android.provider.Settings.System.getString(context.contentResolver, "joystick_light_enabled")
                    ?.split(",")?.all { it.trim() == "0" } == true
            }.getOrDefault(false)
        // Lights off (or turned all the way down): plain white.
        if (c == 0 || dark) Color(0xFFF5F5FA) else Color(com.nerfherder.walkthor.StickLights.swatch(c) or 0xFF000000.toInt())
    }
}

/** What the top screen shows: the game chosen on the bottom screen. */
data class Spotlight(
    val rom: Rom,
    val system: String,
    val hero: Uri?,
    val cover: Uri?,
    /** The game's logo, shown in the middle of the top screen. */
    val logo: Uri? = null,
    val description: String?,
    val lastPlayed: String?,
    val playTime: String?,
    val guide: String?,
    val favorite: Boolean,
)

object Frontend {
    /** The game chosen on the bottom screen, for the top screen. */
    val spotlight = MutableStateFlow<Spotlight?>(null)

    private fun prefs(c: Context) = c.getSharedPreferences("frontend", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow<Boolean?>(null)

    /** WalkThor opens on your games (rather than the library) when no game is running. */
    fun enabled(c: Context): kotlinx.coroutines.flow.StateFlow<Boolean?> {
        if (_enabled.value == null) _enabled.value = prefs(c).getBoolean("on", true)
        return _enabled
    }
    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("on", on).apply()
        _enabled.value = on
        if (!on) hideTop()
    }
    fun topOn(c: Context): Boolean = prefs(c).getBoolean("top", true)
    fun setTopOn(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("top", on).apply()
        if (!on) hideTop()
    }

    /** The open top-screen spotlight, so it can be closed. */
    @Volatile var top: java.lang.ref.WeakReference<Activity>? = null

    /** The games screen's buttons, so the top screen can pass on presses it gets. */
    @Volatile var keyHandler: ((KeyEvent) -> Boolean)? = null

    /**
     * Puts the spotlight on the top screen (if it isn't there already). The covers belong on the
     * bottom screen: if WalkThor is on the top one, it moves down first.
     */
    /** The bottom screen's WalkThor, which gets the buttons pressed while the top screen has them. */
    @Volatile var bottom: java.lang.ref.WeakReference<Activity>? = null

    fun showTop(activity: Activity, moveDown: Boolean = true) {
        val onBottom = runCatching { DisplayHelper.isOnBottom(activity) }.getOrDefault(true)
        if (!onBottom) {
            if (moveDown) runCatching { DisplayHelper.moveToBottom(activity) }
            return
        }
        bottom = java.lang.ref.WeakReference(activity)
        if (!topOn(activity)) return
        if (top?.get()?.isFinishing == false) return
        val display = runCatching { DisplayHelper.topDisplay(activity) }.getOrNull() ?: return
        val intent = Intent(activity, com.nerfherder.walkthor.FrontendTopActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId).toBundle()
        runCatching { activity.startActivity(intent, options) }
    }

    /**
     * Brings the top screen back if one of WalkThor's screens wants it and it's gone (closed by
     * something else, such as closing apps from the drawer), with no game running.
     */
    fun ensureTop(activity: Activity) {
        val wanted = keyHandler != null || SettingsTop.section.value != null || LibraryTop.active.value || AppsTop.active.value
        if (!wanted || com.nerfherder.walkthor.GameFocus.gameRunning.value) return
        if (top?.get()?.let { !it.isFinishing && !it.isDestroyed } == true) return
        showTop(activity, moveDown = false)
    }

    /** Closes the top screen shortly, unless the games or Settings still want it by then. */
    fun hideTopSoon() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (keyHandler == null && SettingsTop.section.value == null && !LibraryTop.active.value && !AppsTop.active.value) hideTop()
        }, 500)
    }

    fun hideTop() {
        top?.get()?.let { runCatching { it.finish() } }
        top = null
    }

    /** "G" for Golden Sun, "#" for numbers and symbols. */
    fun letter(title: String): Char {
        val t = title.trim().removePrefix("The ").removePrefix("the ")
        val c = t.firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c else '#'
    }
}

/** A made-up cover for a game with no picture: colours and shapes from its name, and the name. */
@Composable
fun PlaceholderArt(title: String, modifier: Modifier = Modifier, showTitle: Boolean = true, big: Boolean = false) {
    val seed = remember(title) { title.lowercase().hashCode() }
    val hue = ((seed ushr 3) % 360).toFloat()
    val glow = remember(hue) { Color.hsv(hue, 0.7f, 1f) }
    val deep = remember(hue) { Color.hsv((hue + 320f) % 360f, 0.75f, 0.22f) }
    val turn = ((seed ushr 9) % 90).toFloat()
    BoxWithConstraints(
        modifier
            .background(deep)
            .drawBehind {
                val w = size.width
                val h = size.height
                val r = (if (big) w * 0.4f else w * 0.45f)
                val cx = if (big) w * 0.72f else w * (0.3f + ((seed ushr 5) % 3) * 0.12f)
                val cy = if (big) h * 0.2f else h * 0.4f
                drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), Offset(cx, cy), r * 1.1f), r * 1.1f, Offset(cx, cy))
                val s = if (big) w * 0.18f else w * 0.5f
                rotate(turn, Offset(if (big) w * 0.7f else w * 0.6f, if (big) h * 0.45f else h * 0.6f)) {
                    drawRect(
                        glow.copy(alpha = 0.5f),
                        topLeft = Offset((if (big) w * 0.7f else w * 0.6f) - s / 2, (if (big) h * 0.45f else h * 0.6f) - s / 2),
                        size = Size(s, s),
                        style = Stroke(width = (w / 60f).coerceAtLeast(2f)),
                    )
                }
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, deep), startY = h * 0.55f, endY = h))
            },
    ) {
        if (showTitle) {
            Text(
                title,
                color = Color.White,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Bold,
                fontSize = (maxWidth.value * 0.12f).coerceIn(10f, 22f).sp,
                lineHeight = (maxWidth.value * 0.13f).coerceIn(11f, 24f).sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = maxWidth * 0.08f, vertical = maxWidth * 0.08f),
            )
        }
    }
}

/** A game's cover: its picture from your art folder, or a made-up one. */
@Composable
private fun Cover(rom: Rom, picture: Uri?, selected: Boolean, modifier: Modifier) {
    // Rounded squares, like the icons in your art folder.
    val shape = RoundedCornerShape(24.dp)
    // A quick fade of the ring and a slight lift as the choice moves from game to game.
    val ring by androidx.compose.animation.animateColorAsState(
        if (selected) frontAccent() else Color.Transparent, androidx.compose.animation.core.tween(160), label = "ring",
    )
    val lift by androidx.compose.animation.core.animateFloatAsState(
        if (selected) 1f else 0.95f, androidx.compose.animation.core.tween(160), label = "lift",
    )
    Box(
        modifier
            .aspectRatio(1f, matchHeightConstraintsFirst = true)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .border(3.dp, ring, shape).padding(6.dp)
            .shadow(6.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp)),
    ) {
        if (picture != null) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(LocalContext.current).data(picture).crossfade(180).build(),
                contentDescription = rom.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(BookColors.SurfaceHigh),
            )
        } else {
            PlaceholderArt(rom.title, Modifier.fillMaxSize())
        }
    }
}

/** Which games the grid shows. */
private sealed class Shelf(val label: String) {
    data object All : Shelf("All games")
    data object Recent : Shelf("Recent")
    data object Favorites : Shelf("Favorites")
    class System(val key: String, name: String) : Shelf(name)
}

/**
 * The bottom screen with no game running: your games as covers, four across, with the chosen one
 * ringed (and shown big on the top screen). D-pad or a tap to choose, A or a second tap to play,
 * Y for its guide, X to make it a favorite, L1 and R1 to go through All, Recent, Favorites and
 * each system, Select to search.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FrontendScreen(
    roms: List<Rom>,
    books: List<Book>,
    coverFile: (Book) -> java.io.File?,
    onOpenGuide: (Book) -> Unit,
    onAddGuideFor: (com.nerfherder.walkthor.data.LaunchedGame) -> Unit,
    onOpenLibrary: () -> Unit,
    /** Your other apps (for when WalkThor is the home app). */
    onOpenApps: () -> Unit = {},
    /** No ROMs folder yet: link one. */
    onLinkRoms: () -> Unit = {},
    /** Bumped by the search button on the tabs. */
    searchRequests: Int = 0,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val art = remember { GameArt.get(context) }
    LaunchedEffect(Unit) { art.load() }
    val artVersion by art.version.collectAsState()
    val plays by GamePlays.changed.collectAsState()

    // The top screen's spotlight, whenever you're here.
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        GamePlays.ended(context)
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) activity?.let { Frontend.showTop(it) }
        }
        lifecycle.lifecycle.addObserver(obs)
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) activity?.let { Frontend.showTop(it) }
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }

    // All your games on one screen.
    val shelves = remember { listOf<Shelf>(Shelf.All) }
    var shelfIndex by remember { mutableStateOf(0) }
    val shelf = shelves[shelfIndex.coerceIn(0, shelves.lastIndex)]
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = remember(roms, shelf, query, plays) {
        val q = query.trim()
        val hidden = GamePlays.hidden(context)
        val visible = roms.filter { it.id !in hidden && RomLibrary.isGame(it) }
        val base = when (shelf) {
            Shelf.All -> visible.sortedWith(compareBy({ Frontend.letter(it.title) }, { it.title.lowercase().removePrefix("the ") }))
            Shelf.Recent -> roms.filter { GamePlays.lastPlayed(context, it) > 0 }.sortedByDescending { GamePlays.lastPlayed(context, it) }
            Shelf.Favorites -> GamePlays.favorites(context).let { f -> roms.filter { it.id in f }.sortedBy { it.title.lowercase() } }
            is Shelf.System -> roms.filter { it.system == shelf.key }.sortedBy { it.title.lowercase() }
        }
        // Each game once (the same title can be in two folders of a system).
        base.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = shown.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
    val grid = rememberLazyGridState()
    /** Rows of icons; the games run left to right in columns of this many. */
    val rows = 3
    var choosingFor by remember { mutableStateOf<Rom?>(null) }
    var launchError by remember { mutableStateOf<String?>(null) }
    /** The game whose options are open (X, or a long press). */
    var menuFor by remember { mutableStateOf<Rom?>(null) }
    // Choosing your own icon, hero picture or logo for a game.
    var picking by remember { mutableStateOf<Pair<Rom, GameArt.Kind>?>(null) }
    val pickPicture = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        val (rom, kind) = picking ?: return@rememberLauncherForActivityResult
        picking = null
        if (uri != null) scope.launch {
            val ok = art.setCustom(rom, kind, uri)
            if (!ok) Toast.makeText(context, "Couldn't read that picture", Toast.LENGTH_SHORT).show()
        }
    }

    fun select(i: Int) {
        if (shown.isEmpty()) return
        val n = i.coerceIn(0, shown.lastIndex)
        selectedId = shown[n].id
        scope.launch {
            val info = grid.layoutInfo
            val visible = info.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: 0
            val last = visible.lastOrNull()?.index ?: 0
            if (n < first + rows || n > last - rows) grid.animateScrollToItem(((n / rows) - 1).coerceAtLeast(0) * rows)
        }
    }

    // The guide that goes with each game is worked out off the main thread.
    val guideFor by produceState<Map<String, Book>>(emptyMap(), roms, books) {
        value = withContext(Dispatchers.Default) {
            if (books.isEmpty()) emptyMap()
            else roms.mapNotNull { r -> GameGuides.pick(context, books, r.asGame())?.let { r.id to it } }.toMap()
        }
    }

    // Tell the top screen.
    val current = shown.getOrNull(selected)
    LaunchedEffect(current?.id, artVersion, plays, guideFor) {
        val rom = current ?: run { Frontend.spotlight.value = null; return@LaunchedEffect }
        Frontend.spotlight.value = Spotlight(
            rom = rom,
            system = RomLibrary.system(rom.system)?.name ?: rom.system,
            hero = art.picture(rom, GameArt.Kind.HERO),
            cover = art.picture(rom, GameArt.Kind.COVER),
            logo = art.picture(rom, GameArt.Kind.LOGO),
            description = art.description(rom),
            lastPlayed = GamePlays.whenText(GamePlays.lastPlayed(context, rom)),
            playTime = GamePlays.timeText(GamePlays.playTime(context, rom)),
            guide = guideFor[rom.id]?.title,
            favorite = GamePlays.isFavorite(context, rom),
        )
    }

    fun play(rom: Rom) {
        scope.launch {
            Toast.makeText(context, "Starting ${rom.title}…", Toast.LENGTH_SHORT).show()
            val error = GameLaunch.start(context, rom, runCatching { DisplayHelper.topDisplay(context) }.getOrNull())
            if (error != null) launchError = error
        }
    }
    fun guide(rom: Rom) {
        val book = guideFor[rom.id]
        if (book != null) onOpenGuide(book) else choosingFor = rom
    }
    fun favorite(rom: Rom) {
        val on = GamePlays.toggleFavorite(context, rom)
        Toast.makeText(context, if (on) "Added to Favorites" else "Taken off Favorites", Toast.LENGTH_SHORT).show()
    }
    fun moveShelf(by: Int) {
        shelfIndex = (shelfIndex + by + shelves.size) % shelves.size
        selectedId = null
        scope.launch { grid.scrollToItem(0) }
    }

    /** A button press, from either screen. */
    fun onKey(k: KeyEvent): Boolean {
        if (searching || choosingFor != null || launchError != null || menuFor != null) return false
        if (k.action != KeyEvent.ACTION_DOWN) return k.keyCode in HANDLED
        val rom = shown.getOrNull(selected)
        when (k.keyCode) {
            // Left and right go along the columns, up and down within one.
            KeyEvent.KEYCODE_DPAD_LEFT -> select(selected - rows)
            KeyEvent.KEYCODE_DPAD_RIGHT -> select(selected + rows)
            KeyEvent.KEYCODE_DPAD_UP -> if (selected % rows > 0) select(selected - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (selected % rows < rows - 1) select(selected + 1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (k.repeatCount == 0) rom?.let(::play)
            KeyEvent.KEYCODE_BUTTON_Y -> if (k.repeatCount == 0) rom?.let(::guide)
            KeyEvent.KEYCODE_BUTTON_X -> if (k.repeatCount == 0) rom?.let { menuFor = it }
            // L2 / R2: the previous or next letter of your games.
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> {
                val at = rom?.let { Frontend.letter(it.title) }
                val i = if (k.keyCode == KeyEvent.KEYCODE_BUTTON_R2) shown.indexOfFirst { Frontend.letter(it.title) > (at ?: ' ') }
                else shown.indexOfLast { Frontend.letter(it.title) < (at ?: '~') }.let { j ->
                    if (j < 0) -1 else shown.indexOfFirst { Frontend.letter(it.title) == Frontend.letter(shown[j].title) }
                }
                if (i >= 0) select(i)
            }
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (k.repeatCount == 0) searching = true
            KeyEvent.KEYCODE_BUTTON_START -> if (k.repeatCount == 0) onOpenLibrary()
            else -> return false
        }
        return true
    }
    // The top screen hands its presses over here.
    androidx.compose.runtime.SideEffect { Frontend.keyHandler = ::onKey }
    DisposableEffect(Unit) { onDispose { Frontend.keyHandler = null } }

    LaunchedEffect(searchRequests) { if (searchRequests > 0) searching = true }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (!searching) runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(BookColors.Ink)
            .focusRequester(focus)
            .onPreviewKeyEvent { e -> onKey(e.nativeKeyEvent) }
            .focusable(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- search (from the search button on the tabs, or Select) ----
            if (searching || query.isNotBlank()) Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searching) {
                    DisposableEffect(Unit) { onDispose { com.nerfherder.walkthor.GameFocus.wantFocus.value = false } }
                    val field = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
                    SearchBox(
                        query, { query = it; selectedId = null },
                        Modifier.weight(1f).focusRequester(field),
                        hint = "Search ${shelf.label.lowercase()}",
                    )
                    Spacer(Modifier.width(10.dp))
                    Pill("Done") { searching = false }
                } else {
                    Text(
                        "${shown.size} games matching",
                        color = Color(0xFF7D766D), fontSize = 14.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Pill("“${query.trim()}” ×") { query = "" }
                    Spacer(Modifier.weight(1f))
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (shown.isEmpty()) {
                    Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            query.isNotBlank() -> "No games match “${query.trim()}”."
                            roms.isEmpty() -> "Link your ROMs folder (one folder per system) to see your games here."
                            shelf == Shelf.Favorites -> "No favorites yet. Press X on a game to add it."
                            shelf == Shelf.Recent -> "Games you start here show up here."
                            else -> "No games here."
                        },
                        color = BookColors.TextMuted, fontSize = 13.sp,
                        modifier = Modifier.padding(18.dp),
                    )
                    if (roms.isEmpty()) Box(Modifier.padding(start = 16.dp)) { Pill("Link ROMs folder…") { onLinkRoms() } }
                    }
                } else {
                    LazyHorizontalGrid(
                        rows = GridCells.Fixed(rows),
                        state = grid,
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        itemsIndexed(shown, key = { _, r -> r.id }) { i, rom ->
                            val picture = remember(rom.id, artVersion) { art.picture(rom, GameArt.Kind.COVER) }
                            Cover(
                                rom, picture, selected = i == selected,
                                modifier = Modifier.combinedClickable(
                                    onClick = { if (i == selected) play(rom) else select(i) },
                                    onLongClick = { select(i); menuFor = rom },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { rom ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { menuFor = null },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { menuFor = null }) { Text("Cancel") } },
            title = { Text(rom.title) },
            text = {
                Column {
                    androidx.compose.material3.TextButton(onClick = {
                        menuFor = null
                        GamePlays.setHidden(context, rom, true)
                        Toast.makeText(context, "Hidden. Show it again in Settings → Guides → Games screen.", Toast.LENGTH_LONG).show()
                    }) { Text("Hide this game") }
                    listOf(
                        GameArt.Kind.COVER to "Choose its icon…",
                        GameArt.Kind.HERO to "Choose its hero picture…",
                        GameArt.Kind.LOGO to "Choose its logo…",
                    ).forEach { (kind, label) ->
                        androidx.compose.material3.TextButton(onClick = {
                            menuFor = null
                            picking = rom to kind
                            runCatching { pickPicture.launch("image/*") }
                        }) { Text(label) }
                    }
                    if (art.hasCustom(rom)) {
                        androidx.compose.material3.TextButton(onClick = { menuFor = null; art.clearCustom(rom) }) {
                            Text("Go back to its usual pictures")
                        }
                    }
                    androidx.compose.material3.TextButton(onClick = { menuFor = null; choosingFor = rom }) {
                        Text(if (guideFor[rom.id] != null) "Change its guide" else "Add a guide")
                    }
                }
            },
        )
    }
    launchError?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { launchError = null },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { launchError = null }) { Text("OK") } },
            title = { Text("Couldn’t start the game") },
            text = { Text(text, fontSize = 13.sp) },
        )
    }
    choosingFor?.let { rom ->
        GuideChooserDialog(
            title = rom.title,
            books = books,
            coverFile = coverFile,
            onPick = { b -> choosingFor = null; GameGuides.remember(context, rom.asGame(), b.id); onOpenGuide(b) },
            onAddFile = { choosingFor = null; onAddGuideFor(rom.asGame()) },
            onDismiss = { choosingFor = null },
        )
    }
}

private val HANDLED = setOf(
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_Y,
    KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_SELECT,
    KeyEvent.KEYCODE_BUTTON_START,
)

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = BookColors.TextMuted, fontSize = 12.sp, maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF1D1B20))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * The top screen with no game running: the game chosen on the bottom screen, big. Its picture
 * fills the screen behind its system, when you last played, its title, description, play time
 * and guide, with the buttons that act on it.
 */
@Composable
fun TopSpotlight(spot: Spotlight?) {
    // Moving from game to game fades the top screen across, quickly.
    androidx.compose.animation.AnimatedContent(
        targetState = spot,
        transitionSpec = {
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220))
        },
        contentKey = { it?.rom?.id },
        label = "spotlight",
    ) { s -> TopSpotlightBody(s) }
}

@Composable
private fun TopSpotlightBody(spot: Spotlight?) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0C0B0E))) {
        if (spot == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("WalkThor", color = Color.White, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 40.sp)
                Text(
                    "Your games are on the bottom screen",
                    color = BookColors.TextMuted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@Box
        }
        val picture = spot.hero ?: spot.cover
        if (picture != null) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(LocalContext.current).data(picture).crossfade(180).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PlaceholderArt(spot.rom.title, Modifier.fillMaxSize(), showTitle = false, big = true)
        }
        val logo = spot.logo
        if (logo == null) {
            // Dark to the left and along the bottom, so the words read over any picture.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Color(0xEB08080A), 0.45f to Color(0x8C08080A), 0.75f to Color.Transparent),
                ),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xE608080A))))
        // The game's logo, in the middle of its picture.
        if (logo != null) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(LocalContext.current).data(logo).crossfade(180).build(),
                contentDescription = spot.rom.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.5f).fillMaxHeight(0.38f),
            )
        }
        ClockLine(Modifier.align(Alignment.TopStart).padding(start = 55.dp, top = 40.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 55.dp, bottom = 56.dp).fillMaxWidth(0.55f)) {
            val eyebrow = listOfNotNull(spot.system, spot.lastPlayed?.let { "last played $it" }).joinToString(" · ").uppercase()
            Text(eyebrow, color = frontAccent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            // With a logo in the middle, the title isn't written out again.
            if (logo == null) Text(
                spot.rom.title,
                color = Color.White, fontFamily = DisplayFont, fontWeight = FontWeight.Bold,
                fontSize = if (spot.rom.title.length > 22) 44.sp else 64.sp,
                lineHeight = if (spot.rom.title.length > 22) 46.sp else 62.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            val facts = listOfNotNull(spot.playTime?.let { "$it played" }, spot.guide?.let { "Guide: $it" }).joinToString(" · ")
            if (logo != null) Spacer(Modifier.height(8.dp))
            spot.description?.takeIf { logo == null }?.let {
                Text(it, color = Color(0xFFCFC8BC), fontSize = 15.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (facts.isNotEmpty()) {
                Text(facts, color = Color(0xFFCFC8BC), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 55.dp, bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Hint("A", "Play")
            Hint("Y", if (spot.guide != null) "Guide" else "Add a guide")
            Hint("X", "Options")
        }
    }
}

@Composable
private fun Hint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).border(1.5.dp, Color(0xFF4A4540), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(key, color = BookColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(7.dp))
        Text(label, color = BookColors.TextMuted, fontSize = 13.sp)
    }
}

/** The time and battery, kept up to date. */
@Composable
private fun ClockLine(modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { now = System.currentTimeMillis() }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, android.content.IntentFilter(Intent.ACTION_TIME_TICK), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val battery = remember(now / 60_000L) {
        context.getSystemService(android.os.BatteryManager::class.java)?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)),
            color = BookColors.TextMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        )
        if (battery != null && battery in 0..100) {
            Text("$battery%", color = BookColors.TextMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
