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
import androidx.compose.foundation.verticalScroll
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
        // Lights off (or turned all the way down): plain white.
        com.nerfherder.walkthor.StickLights.accent(context)?.let { Color(it) } ?: Color(0xFFF5F5FA)
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
    /** 0–1 while a game is launching (the top screen sweeps and fades along), else null. */
    val launching = MutableStateFlow<Float?>(null)

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
        // Never over a game. Just after Android has closed and restarted WalkThor mid-game, what's
        // up there isn't known for a moment: wait for it (the watchdog asks again shortly).
        if (com.nerfherder.walkthor.EmulatorWatch.gameInFront(activity, display.displayId) == true) return
        if (!com.nerfherder.walkthor.EmulatorWatch.frontKnown.value &&
            android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis() < 6_000
        ) return
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
        val alive = top?.get()?.let { !it.isFinishing && !it.isDestroyed } == true
        if (!alive) { showTop(activity, moveDown = false); return }
        if (topVisible) return
        // Still open but covered: bring it back if what covers it is only a home screen or launcher
        // (left behind when an app was closed), not an app you opened up there.
        val topId = runCatching { DisplayHelper.topDisplay(activity)?.displayId }.getOrNull() ?: return
        val front = com.nerfherder.walkthor.EmulatorWatch.frontOn(topId) ?: return
        if (front == activity.packageName || front !in launchers(activity)) return
        bringTop(activity, topId)
    }

    /** Whether the top screen's WalkThor is showing (not covered, the screen on). */
    @Volatile var topVisible = false

    private var launcherCache: Set<String>? = null
    private fun launchers(c: Context): Set<String> = launcherCache ?: runCatching {
        c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet() + setOf("com.android.launcher3", "com.android.systemui")
    }.getOrDefault(emptySet()).also { launcherCache = it }

    private fun bringTop(activity: Activity, displayId: Int) {
        val intent = Intent(activity, com.nerfherder.walkthor.FrontendTopActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        runCatching { activity.startActivity(intent, options) }
    }

    /** Closes the top screen shortly, unless the games or Settings still want it by then. */
    fun hideTopSoon() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (keyHandler == null && SettingsTop.section.value == null && !LibraryTop.active.value && !AppsTop.active.value && !UpdatesTop.active.value) hideTop()
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
        if (selected) frontAccent() else Color.Transparent, androidx.compose.animation.core.tween(240), label = "ring",
    )
    val lift by androidx.compose.animation.core.animateFloatAsState(
        if (selected) 1f else 0.97f, androidx.compose.animation.core.tween(240), label = "lift",
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
    /** The launch ripple's progress (0 at rest). */
    val launchAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { com.nerfherder.walkthor.data.UiSounds.warm(context) }
    // The top screen follows the ripple.
    LaunchedEffect(launchAnim.value) { Frontend.launching.value = launchAnim.value.takeIf { it > 0f } }
    /** The game whose options are open (X, or a long press). */
    var menuFor by remember { mutableStateOf<Rom?>(null) }
    /** The game whose emulator you're choosing. */
    var emulatorFor by remember { mutableStateOf<Rom?>(null) }
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
        if (shown[n].id != shown.getOrNull(selected)?.id) com.nerfherder.walkthor.data.UiSounds.play(context, com.nerfherder.walkthor.data.UiSounds.Kind.MOVE)
        selectedId = shown[n].id
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
        // Moving quickly through games: only the one you stop on goes up top (no flicker on the way).
        if (Frontend.spotlight.value?.rom?.id != rom.id && Frontend.spotlight.value != null) kotlinx.coroutines.delay(140)
        Frontend.spotlight.value = Spotlight(
            rom = rom,
            system = RomLibrary.system(rom.system)?.name
                ?: if (rom.system == com.nerfherder.walkthor.data.PinnedShortcuts.SYSTEM) {
                    com.nerfherder.walkthor.data.PinnedShortcuts.find(context, rom)?.appLabel ?: "Shortcut"
                } else rom.system,
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
        if (launchAnim.isRunning) return
        com.nerfherder.walkthor.data.UiSounds.play(context, com.nerfherder.walkthor.data.UiSounds.Kind.LAUNCH)
        scope.launch {
            // The ripple: rings spread out, the others are pushed away, the top screen sweeps and fades.
            launchAnim.snapTo(0f)
            val ripple = launch {
                launchAnim.animateTo(1f, androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing))
            }
            kotlinx.coroutines.delay(450)
            val error = GameLaunch.start(context, rom, runCatching { DisplayHelper.topDisplay(context) }.getOrNull())
            if (error != null) {
                ripple.cancel()
                launchAnim.snapTo(0f)
                Frontend.launching.value = null
                launchError = error
            } else {
                ripple.join()
                // Back to normal shortly (the game is up by now; if it isn't, the games come back).
                kotlinx.coroutines.delay(1500)
                launchAnim.snapTo(0f)
                Frontend.launching.value = null
            }
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
    }

    /** A button press, from either screen. */
    fun onKey(k: KeyEvent): Boolean {
        if (searching || choosingFor != null || launchError != null || menuFor != null || emulatorFor != null) return false
        if (k.action != KeyEvent.ACTION_DOWN) return k.keyCode in HANDLED
        val rom = shown.getOrNull(selected)
        when (k.keyCode) {
            // Left and right go along the columns, up and down within one.
            // The D-pad moves to the neighbouring bubble that way.
            KeyEvent.KEYCODE_DPAD_LEFT -> BubbleLayout.neighbor(selected, shown.size, -1, 0)?.let(::select)
            KeyEvent.KEYCODE_DPAD_RIGHT -> BubbleLayout.neighbor(selected, shown.size, 1, 0)?.let(::select)
            KeyEvent.KEYCODE_DPAD_UP -> BubbleLayout.neighbor(selected, shown.size, 0, -1)?.let(::select)
            KeyEvent.KEYCODE_DPAD_DOWN -> BubbleLayout.neighbor(selected, shown.size, 0, 1)?.let(::select)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (k.repeatCount == 0) rom?.let(::play)
            KeyEvent.KEYCODE_BUTTON_Y -> if (k.repeatCount == 0) rom?.let(::guide)
            KeyEvent.KEYCODE_BUTTON_X -> if (k.repeatCount == 0) rom?.let {
                com.nerfherder.walkthor.data.UiSounds.play(context, com.nerfherder.walkthor.data.UiSounds.Kind.PICK)
                menuFor = it
            }
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

    // Only a press of the search button since this screen came up (coming back to Games isn't one).
    var searchesSeen by remember { mutableStateOf(searchRequests) }
    LaunchedEffect(searchRequests) {
        if (searchRequests > searchesSeen) { searchesSeen = searchRequests; searching = true }
    }
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
                    // Your games as a cloud of round bubbles: the chosen one big in the middle.
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val ring = frontAccent()
                        BubbleCloud(
                            count = shown.size,
                            selected = selected,
                            onSelect = { select(it) },
                            onActivate = { shown.getOrNull(it)?.let(::play) },
                            onLongPress = { i -> shown.getOrNull(i)?.let { menuFor = it } },
                            ring = ring,
                            launching = launchAnim.value.takeIf { it > 0f },
                            // Each game's system, as a coloured ring with its name.
                            ringColor = { i -> shown.getOrNull(i)?.let { SystemColors.color(it.system) } },
                            ringLabel = { i -> shown.getOrNull(i)?.let { SystemColors.label(it.system) } },
                        ) { i ->
                            val rom = shown[i]
                            val picture = remember(rom.id, artVersion) { art.picture(rom, GameArt.Kind.COVER) }
                            if (picture != null) {
                                AsyncImage(
                                    model = coil.request.ImageRequest.Builder(LocalContext.current).data(picture).crossfade(180).build(),
                                    contentDescription = rom.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().background(BookColors.SurfaceHigh),
                                )
                            } else {
                                PlaceholderArt(rom.title, Modifier.fillMaxSize(), showTitle = false)
                            }
                        }
                        // The chosen game's name, under the cloud.
                        shown.getOrNull(selected)?.let { rom ->
                            Text(
                                rom.title,
                                color = Color.White, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC07080B))))
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                        Toast.makeText(context, "Hidden. Show it again on the Library tab.", Toast.LENGTH_LONG).show()
                    }) { Text("Hide this game") }
                    if (rom.system == com.nerfherder.walkthor.data.PinnedShortcuts.SYSTEM) {
                        androidx.compose.material3.TextButton(onClick = {
                            menuFor = null
                            com.nerfherder.walkthor.data.PinnedShortcuts.remove(context, rom.file)
                        }) { Text("Remove this shortcut") }
                    }
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
                    if (rom.system != com.nerfherder.walkthor.data.PinnedShortcuts.SYSTEM) {
                        val now = remember(rom.id) { com.nerfherder.walkthor.data.GameLaunch.emulatorName(context, rom) }
                        androidx.compose.material3.TextButton(onClick = { menuFor = null; emulatorFor = rom }) {
                            Text("Start it in…" + (now?.let { " ($it)" } ?: ""))
                        }
                    }
                }
            },
        )
    }
    emulatorFor?.let { rom ->
        val options = remember(rom.id) { com.nerfherder.walkthor.data.GameLaunch.choices(context, rom.system) }
        val mine = remember(rom.id) { com.nerfherder.walkthor.data.GameLaunch.preferredFor(context, rom) }
        val systemName = RomLibrary.system(rom.system)?.name ?: "this system’s"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { emulatorFor = null },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { emulatorFor = null }) { Text("Cancel") } },
            title = { Text("Start ${rom.title} in") },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    if (options.isEmpty()) Text("No emulator for it is installed.", fontSize = 13.sp)
                    androidx.compose.material3.TextButton(onClick = {
                        com.nerfherder.walkthor.data.GameLaunch.setPreferredFor(context, rom, null); emulatorFor = null
                    }) { Text((if (mine == null) "✓ " else "") + "The same as other $systemName games") }
                    options.forEach { o ->
                        androidx.compose.material3.TextButton(onClick = {
                            com.nerfherder.walkthor.data.GameLaunch.setPreferredFor(context, rom, o.id); emulatorFor = null
                        }) { Text((if (mine == o.id) "✓ " else "") + o.label) }
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
fun TopSpotlight(spot: Spotlight?) = Box(Modifier.fillMaxSize()) {
    // Moving from game to game fades the top screen across, quickly.
    androidx.compose.animation.AnimatedContent(
        targetState = spot,
        // The new game fades in over the old one (which stays until it's covered), so the
        // screen never dips to black between them.
        transitionSpec = {
            (
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                ) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(1, delayMillis = 380))
            ).apply { targetContentZIndex = 1f }
        },
        contentKey = { it?.rom?.id },
        label = "spotlight",
    ) { s -> TopSpotlightBody(s) }
    // Launching: a band of light sweeps across, then the screen fades to black for the game.
    val launching by Frontend.launching.collectAsState()
    launching?.let { t ->
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val sweep = (t / 0.8f).coerceIn(0f, 1f)
            val e = sweep * sweep * (3 - 2 * sweep)
            val x = -size.width * 0.25f + e * size.width * 1.5f
            val band = size.width * 0.18f
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.22f), 1f to Color.Transparent,
                    startX = x - band, endX = x + band,
                ),
            )
            val fade = ((t - 0.6f) / 0.4f).coerceIn(0f, 1f)
            if (fade > 0f) drawRect(Color.Black.copy(alpha = fade))
        }
    }
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
            Modifier.align(Alignment.BottomEnd).padding(end = 55.dp, bottom = 56.dp)
                .clip(RoundedCornerShape(50)).background(Color(0xB3000000))
                .padding(horizontal = 16.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
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
            Modifier.size(24.dp).border(1.5.dp, Color(0xE6FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(key, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** The time and battery, kept up to date. */
@Composable
internal fun ClockLine(modifier: Modifier) {
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
    // On a dark pill so it reads over any picture.
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xB3000000)).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)),
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
        if (battery != null && battery in 0..100) {
            Text("$battery%", color = Color(0xFFE6E0D6), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
