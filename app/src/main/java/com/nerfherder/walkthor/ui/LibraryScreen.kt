package com.nerfherder.walkthor.ui

import androidx.compose.foundation.layout.BoxWithConstraints

import androidx.compose.ui.platform.LocalConfiguration

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults

import kotlin.math.roundToInt
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.RadioButton
import android.net.Uri
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nerfherder.walkthor.ImportState
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.BookType
import kotlinx.coroutines.delay
import java.io.File

internal val coverInks = listOf(
    Color(0xFF5B2328), Color(0xFF1F3550), Color(0xFF214234), Color(0xFF4A3420),
    Color(0xFF3D2547), Color(0xFF1E4146), Color(0xFF51401A), Color(0xFF34302E),
)

/** Everything the library needs from the outside world. */
class LibraryActions(
    val onOpen: (Book) -> Unit,
    val onImport: () -> Unit,
    val onDelete: (Book) -> Unit,
    val onRename: (Book, String) -> Unit,
    val coverFile: (Book) -> File?,
    val onToggleAlwaysBottom: (Boolean) -> Unit,
    val onToggleSwipeToOpen: (Boolean) -> Unit,
    val onClose: () -> Unit,
    val onGrantSwipePermission: () -> Unit,
    val hasSwipeConsent: () -> Boolean,
    val onSwipeConsent: (Boolean) -> Unit,
    val isSwipeServiceOn: () -> Boolean,
    val onOpenAppInfo: () -> Unit,
    val onSwapScreen: (() -> Unit)?,
    /** Pick your ROMs folder (one folder per system). */
    val onLinkRoms: () -> Unit = {},
    /** Add a guide (file picker) and link it to a game. */
    val onAddGuideFor: (com.nerfherder.walkthor.data.LaunchedGame) -> Unit = {},
    /** Reached from the drawer during a game: back to its tabs. */
    val onBackToGame: (() -> Unit)? = null,
)

/**
 * WalkThor's own screen: your games by system (from your ROMs folder), each with its guide beside
 * it, and your guides as their own section. [previewRoms] and [previewSection] are for screenshots.
 */
@Composable
fun LibraryScreen(
    books: List<Book>,
    alwaysBottom: Boolean,
    swipeToOpen: Boolean,
    swipeNeedsPermission: Boolean,
    actions: LibraryActions,
    previewRoms: List<com.nerfherder.walkthor.data.Rom>? = null,
    previewSection: String? = null,
    startInSettings: SettingsSection? = null,
) {
    var explainSwipe by remember { mutableStateOf(false) }
    var showDisclosure by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(startInSettings != null) }
    // Your ROMs folder's games, by system, with your guides as their own section.
    val libContext = LocalContext.current
    val romLibrary = remember { com.nerfherder.walkthor.data.RomLibrary.get(libContext) }
    val loadedRoms by romLibrary.roms.collectAsState()
    val roms = previewRoms ?: loadedRoms
    LaunchedEffect(Unit) { if (previewRoms == null) romLibrary.load() }
    var chosenSection by remember { mutableStateOf(previewSection ?: LibrarySection.load(libContext)) }
    // A system folder that's gone (relinked, rescanned): back to all games.
    val section = chosenSection.takeIf { s ->
        s == LibrarySection.ALL || s == LibrarySection.GUIDES || roms.any { it.system == s }
    } ?: LibrarySection.ALL
    val showGames = roms.isNotEmpty() && section != LibrarySection.GUIDES
    // Turning accessibility access on always goes through the disclosure first.
    fun startSwipeSetup() {
        if (!actions.hasSwipeConsent()) { showDisclosure = true; return }
        actions.onToggleSwipeToOpen(true)
        if (!actions.isSwipeServiceOn()) explainSwipe = true
    }
    val context = LocalContext.current
    var showEmulatorDisclosure by remember { mutableStateOf(false) }
    /** Detection needs the accessibility service: set that up too if it isn't yet. */
    fun turnOnEmulators() {
        com.nerfherder.walkthor.EmulatorWatch.setOn(context, true)
        if (!swipeToOpen || swipeNeedsPermission) startSwipeSetup()
    }

    val narrowHeader = LocalConfiguration.current.screenWidthDp < 560
    // Very narrow: the time and battery move to a bottom bar (as in the reader) to give the title room.
    val statusAtBottom = LocalConfiguration.current.screenWidthDp < 480
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(BookColors.Ink)) {
        // ---- header -----------------------------------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(start = if (actions.onBackToGame != null) 6.dp else 20.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.onBackToGame?.let { back ->
                IconButton(onClick = back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the drawer", tint = BookColors.TextMuted)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        narrowHeader -> "WalkThor"
                        showGames -> "${roms.size} game${if (roms.size == 1) "" else "s"} · ${books.size} guide${if (books.size == 1) "" else "s"}"
                        else -> "${books.size} guide${if (books.size == 1) "" else "s"}"
                    },
                    style = EyebrowStyle,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    if (showGames) "Games" else "Guides",
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 26.sp,
                    color = BookColors.Text,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (!statusAtBottom) StatusClock(Modifier.padding(end = if (narrowHeader) 6.dp else 14.dp))
            Button(
                onClick = actions.onImport,
                colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
                contentPadding = if (narrowHeader) PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                else PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // On narrow screens the button is just "+", so the header never crowds out the list.
                Icon(Icons.Filled.Add, contentDescription = if (narrowHeader) "Add guide" else null, modifier = Modifier.size(18.dp))
                if (!narrowHeader) {
                    Spacer(Modifier.width(6.dp))
                    Text("Add guide", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            }
            IconButton(onClick = actions.onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close WalkThor", tint = BookColors.TextMuted)
            }
            IconButton(onClick = { settingsOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Settings", tint = BookColors.TextMuted)
            }
        }
        if (swipeToOpen && swipeNeedsPermission) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .background(BookColors.Surface, RoundedCornerShape(10.dp))
                    .border(1.dp, BookColors.Brass.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (actions.hasSwipeConsent()) "Accessibility access: switch on “WalkThor” in Accessibility settings."
                    else "Accessibility access needs your OK before it can run.",
                    color = BookColors.Text,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { startSwipeSetup() }) { Text("Set up") }
            }
        }
        HorizontalDivider(color = BookColors.Hairline, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

        Row(Modifier.weight(1f)) {
          if (roms.isNotEmpty()) {
            LibraryRail(roms, books.size, section) { chosenSection = it; LibrarySection.save(libContext, it) }
            Box(Modifier.width(1.dp).fillMaxHeight().background(BookColors.Hairline))
          }
          Box(Modifier.weight(1f)) {
            if (showGames) {
                GamesList(
                    roms = if (section == LibrarySection.ALL) roms else roms.filter { it.system == section },
                    books = books,
                    showSystem = section == LibrarySection.ALL,
                    actions = actions,
                )
            } else if (books.isEmpty()) {
                EmptyShelf(actions.onImport, roms.isNotEmpty(), actions.onLinkRoms, onSetupGuide = { showSetup = true })
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 118.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(books, key = { it.id }) { book ->
                        ShelfItem(book, actions)
                    }
                }
            }
          }
        }
        if (statusAtBottom) {
            Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                StatusClock(Modifier.padding(start = 4.dp, end = 16.dp))
            }
        }
    }
    if (settingsOpen) {
        SettingsScreen(
            alwaysBottom, swipeToOpen, actions,
            startSection = startInSettings ?: SettingsSection.GENERAL,
            onToggleSwipe = { on -> if (on) startSwipeSetup() else actions.onToggleSwipeToOpen(false) },
            onAbout = { showAbout = true },
            onSetupGuide = { showSetup = true },
            onToggleEmulators = { on ->
                if (!on) com.nerfherder.walkthor.EmulatorWatch.setOn(context, false)
                else if (!com.nerfherder.walkthor.EmulatorWatch.hasConsent(context)) showEmulatorDisclosure = true
                else turnOnEmulators()
            },
            onDismiss = { settingsOpen = false },
        )
    }
    }

    if (showDisclosure) {
        SwipeDisclosureDialog(
            onAgree = {
                showDisclosure = false
                actions.onSwipeConsent(true)
                actions.onToggleSwipeToOpen(true)
                if (!actions.isSwipeServiceOn()) explainSwipe = true
            },
            onDecline = { showDisclosure = false; actions.onSwipeConsent(false) },
            onDismiss = { showDisclosure = false },
        )
    }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showEmulatorDisclosure) {
        EmulatorDisclosureDialog(
            onAgree = { showEmulatorDisclosure = false; turnOnEmulators() },
            onDismiss = { showEmulatorDisclosure = false },
        )
    }
    if (showSetup) {
        SetupGuideDialog(
            state = SetupState(
                hasGuides = books.isNotEmpty(),
                romsLinked = roms.isNotEmpty(),
                swipeReady = swipeToOpen && !swipeNeedsPermission,
            ),
            onAddGuide = actions.onImport,
            onLinkRoms = { showSetup = false; actions.onLinkRoms() },
            onSetUpSwipe = { showSetup = false; startSwipeSetup() },
            onDismiss = { showSetup = false },
        )
    }

    if (explainSwipe) {
        AlertDialog(
            onDismissRequest = { explainSwipe = false },
            containerColor = BookColors.Surface,
            title = { Text("Allow accessibility access", fontFamily = DisplayFont) },
            text = {
                Column {
                    Text(
                        "Android only lets an app open over other apps, respond to buttons while a game is in front, or hand " +
                            "a game back its speed, through an accessibility service, so WalkThor has a small one. It " +
                            "doesn't read anything on your screen.",
                        color = BookColors.TextMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "1. Tap Open settings.\n2. Find “WalkThor” (it may be under Downloaded apps) and switch it on.",
                        color = BookColors.Text,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "If Android says it's a restricted setting: tap App info, then ⋮ → Allow restricted settings, and try again.",
                        color = BookColors.TextMuted,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { explainSwipe = false; actions.onGrantSwipePermission() }) { Text("Open settings") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { actions.onOpenAppInfo() }) { Text("App info") }
                    TextButton(onClick = { explainSwipe = false }) { Text("Later") }
                }
            },
        )
    }
}

@Composable
private fun SettingsScreen(
    alwaysBottom: Boolean,
    swipeToOpen: Boolean,
    actions: LibraryActions,
    startSection: SettingsSection,
    onToggleSwipe: (Boolean) -> Unit,
    onAbout: () -> Unit,
    onSetupGuide: () -> Unit,
    onToggleEmulators: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickDim by remember { mutableStateOf(false) }
    var showEmulators by remember { mutableStateOf(false) }
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(startSection) }
    val menuScope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val dimDelay by AutoDim.delayMs.collectAsState()
    val dimLevel by AutoDim.level.collectAsState()
    val detecting by com.nerfherder.walkthor.EmulatorWatch.on.collectAsState()
    // Your ROMs folder: the games in your library.
    val romLib = remember { com.nerfherder.walkthor.data.RomLibrary.get(context) }
    val romFolder by romLib.folder.collectAsState()
    val roms by romLib.roms.collectAsState()
    LaunchedEffect(Unit) { romLib.load() }
    androidx.activity.compose.BackHandler(onBack = onDismiss)

    SettingsFrame(
        section = section,
        onSection = { section = it },
        version = com.nerfherder.walkthor.BuildConfig.VERSION_NAME,
        onBack = onDismiss,
        onClose = { onDismiss(); actions.onClose() },
    ) {
        when (section) {
            SettingsSection.GENERAL -> {
                SettingsGroup("Access") {
                    SettingSwitch(
                        "Accessibility access",
                        "For the drawer, the button shortcuts, the FPS counter and the keyboard, and it keeps games at full speed",
                        checked = swipeToOpen,
                        divider = false,
                    ) { onToggleSwipe(it) }
                    var ownKeyboard by remember { mutableStateOf(com.nerfherder.walkthor.keyboard.WalkThorKeyboard.isDefault(context)) }
                    SettingSwitch(
                        "WalkThor keyboard",
                        "In place of AYN's, everywhere: numbers and letters filling the bottom screen, with the action key in your stick-light colour",
                        checked = ownKeyboard,
                    ) { want -> com.nerfherder.walkthor.keyboard.WalkThorKeyboard.use(context, want) { ownKeyboard = it } }
                    val hasLights = remember { com.nerfherder.walkthor.StickLights.available() }
                    var lightsOn by remember { mutableStateOf(com.nerfherder.walkthor.StickLights.isOn(context)) }
                    SettingSwitch(
                        "Stick lights",
                        if (hasLights) "The lights around the sticks. Their colour and brightness are in Light & Sound"
                        else "This device's stick lights can't be reached",
                        checked = lightsOn && hasLights,
                    ) { if (hasLights) { lightsOn = it; com.nerfherder.walkthor.StickLights.setOn(context, it) } }
                }
                SettingsGroup("Notifications") {
                    var bottomNotes by remember { mutableStateOf(com.nerfherder.walkthor.BottomNotifications.enabled(context)) }
                    // Notification access is given in Android's settings: checked again on coming back.
                    var noteAccess by remember { mutableStateOf(com.nerfherder.walkthor.BottomNotifications.hasAccess(context)) }
                    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
                    androidx.compose.runtime.DisposableEffect(lifecycle) {
                        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                noteAccess = com.nerfherder.walkthor.BottomNotifications.hasAccess(context)
                                com.nerfherder.walkthor.BottomNotifications.apply(context)
                            }
                        }
                        lifecycle.lifecycle.addObserver(obs)
                        onDispose { lifecycle.lifecycle.removeObserver(obs) }
                    }
                    SettingSwitch(
                        "Notifications on the bottom screen",
                        when {
                            bottomNotes && !noteAccess -> "Needs notification access for WalkThor: tap to allow it"
                            else -> "Android's pop-ups drop down on the bottom screen instead of over your game. Needs Force SELinux off. Switch it off before uninstalling WalkThor"
                        },
                        checked = bottomNotes,
                        divider = false,
                    ) { on ->
                        if (on && bottomNotes && !noteAccess) com.nerfherder.walkthor.BottomNotifications.openAccess(context)
                        bottomNotes = on
                        com.nerfherder.walkthor.BottomNotifications.setEnabled(context, on)
                    }
                }
                SettingsGroup("If Android closes WalkThor") {
                    var reopen by remember { mutableStateOf(com.nerfherder.walkthor.Revive.enabled(context)) }
                    SettingSwitch(
                        "Bring WalkThor back",
                        "If it was up when Android closed it, it comes back within a few seconds",
                        checked = reopen,
                        divider = false,
                    ) { reopen = it; com.nerfherder.walkthor.Revive.setEnabled(context, it) }
                }
            }
            SettingsSection.SHORTCUTS -> {
                var pickFor by remember { mutableStateOf<com.nerfherder.walkthor.Shortcuts.Trigger?>(null) }
                // Bumped on each change so the rows re-read what's set.
                var version by remember { mutableStateOf(0) }
                if (!swipeToOpen) SettingsNote("Shortcuts need accessibility access switched on (Settings → General).")
                SettingsGroup("Buttons") {
                    com.nerfherder.walkthor.Shortcuts.Trigger.entries.forEachIndexed { i, t ->
                        val action = remember(version, t) { com.nerfherder.walkthor.Shortcuts.get(context, t) }
                        val usable = remember(version, t) { com.nerfherder.walkthor.Shortcuts.usable(context, t) }
                        SettingLink(
                            t.label,
                            if (usable) t.detail else "Goes to AYN while the AYN button's press is left to AYN's own drawer",
                            value = if (usable) action.label else "AYN's",
                            divider = i > 0,
                        ) { if (swipeToOpen && usable) pickFor = t }
                    }
                }
                SettingsGroup("Feedback") {
                    var rumble by remember { mutableStateOf(com.nerfherder.walkthor.Shortcuts.rumbleOn(context)) }
                    SettingSwitch(
                        "Rumble when a shortcut is used",
                        "A short buzz, so you know the press was taken",
                        checked = rumble,
                        divider = false,
                    ) { rumble = it; com.nerfherder.walkthor.Shortcuts.setRumble(context, it); if (it) com.nerfherder.walkthor.Shortcuts.rumble(context) }
                }
                SettingsNote(
                    "Close all apps closes every app you have open, the game included (anything unsaved is lost). " +
                        "Next performance mode and Next fan mode need Force SELinux switched off in AYN's settings. " +
                        "Save the last seconds needs Keep the last switched on in Shots. " +
                        "While a combination with Back is set, Back is sent to the app when you let go of it, and while Back is held the game gets no controls at all, so a combination only does its shortcut. " +
                        "Show / hide the keyboard works with the WalkThor keyboard, once a text box is picked. " +
                        "Brightness up and down change the screen Home's brightness control is set to (top, bottom or both)."
                )
                pickFor?.let { t ->
                    val current = com.nerfherder.walkthor.Shortcuts.get(context, t)
                    AlertDialog(
                        onDismissRequest = { pickFor = null },
                        containerColor = BookColors.Surface,
                        title = { Text(t.label, fontFamily = DisplayFont) },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                com.nerfherder.walkthor.Shortcuts.actionsFor(t).forEach { a ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                com.nerfherder.walkthor.Shortcuts.set(context, t, a)
                                                version++
                                                pickFor = null
                                            }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = a == current,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(selectedColor = BookColors.Text, unselectedColor = BookColors.TextMuted),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        )
                                        Text(a.label, color = BookColors.Text, fontSize = 15.sp)
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { pickFor = null }) { Text("Cancel") } },
                    )
                }
            }
            SettingsSection.SCREEN -> {
                SettingsGroup("Bottom screen") {
                    SettingSwitch("Always open on bottom screen", checked = alwaysBottom, divider = false) { actions.onToggleAlwaysBottom(it) }
                    SettingLink("Auto-dim", "Home dims after this long while you play", value = AutoDim.label(dimDelay)) { pickDim = true }
                    var gameOff by remember { mutableStateOf(com.nerfherder.walkthor.BottomOff.gameEnabled(context)) }
                    SettingSwitch(
                        "Bottom screen off during games",
                        if (swipeToOpen) "Turns off when a game starts, and back on when it closes. Open the drawer to use WalkThor meanwhile"
                        else "Needs accessibility access switched on",
                        checked = gameOff && swipeToOpen,
                    ) { if (swipeToOpen) { gameOff = it; com.nerfherder.walkthor.BottomOff.setGameEnabled(context, it) } }
                    actions.onSwapScreen?.let { swap ->
                        SettingLink("Move to the other screen") { onDismiss(); swap() }
                    }
                }
                SettingsGroup("FPS counter") {
                    var topFps by remember { mutableStateOf(com.nerfherder.walkthor.TopFps.enabled(context)) }
                    SettingSwitch(
                        "FPS counter",
                        if (swipeToOpen) "The top screen's frame rate, small, in a corner, while a game is in front" else "Needs accessibility access switched on",
                        checked = topFps && swipeToOpen,
                        divider = false,
                    ) { if (swipeToOpen) { topFps = it; com.nerfherder.walkthor.TopFps.setEnabled(context, it) } }
                    if (topFps && swipeToOpen) {
                        var fpsScreen by remember { mutableStateOf(com.nerfherder.walkthor.TopFps.screen(context)) }
                        SettingRow(
                            "Shows on",
                            null,
                            buttons = {
                                com.nerfherder.walkthor.TopFps.Screen.entries.forEach { sc ->
                                    PillButton(sc.label, quiet = sc != fpsScreen) { fpsScreen = sc; com.nerfherder.walkthor.TopFps.setScreen(context, sc) }
                                }
                            },
                        )
                        var corner by remember { mutableStateOf(com.nerfherder.walkthor.TopFps.corner(context)) }
                        SettingRow(
                            "Corner",
                            null,
                            buttons = {
                                com.nerfherder.walkthor.TopFps.Corner.entries.forEach { c ->
                                    PillButton(c.label, quiet = c != corner) { corner = c; com.nerfherder.walkthor.TopFps.setCorner(context, c) }
                                }
                            },
                        )
                    }
                }
            }
            SettingsSection.EMULATORS -> {
                SettingsGroup("Emulators") {
                    SettingSwitch(
                        "Open when an emulator starts",
                        "The drawer comes up by itself when a game starts, from any frontend",
                        checked = detecting,
                        divider = false,
                    ) { onToggleEmulators(it) }
                    // Always here: which apps are emulators also decides when the drawer has its tabs.
                    SettingLink("Choose emulators", "The apps that count as games. Tick any WalkThor doesn't know") { showEmulators = true }
                }
                SettingsNote("While one of these is in front, the drawer has its tabs along the top and play time counts.")
            }
            SettingsSection.GUIDES -> {
                SettingsGroup("ROMs folder") {
                    if (romFolder != null) {
                        val systems = roms.map { it.system }.distinct().size
                        SettingRow(
                            "ROMs folder",
                            "${roms.size} games" + if (systems > 1) " in $systems systems" else "",
                            divider = false,
                            buttons = {
                                PillButton("Rescan") { menuScope.launch { romLib.scan() } }
                                PillButton("Change") { actions.onLinkRoms() }
                                PillButton("Unlink", quiet = true) { romLib.unlink() }
                            },
                        )
                    } else {
                        SettingRow(
                            "ROMs folder",
                            "The folder with one folder per system (3ds, gba, switch…): your games, listed by system",
                            divider = false,
                            buttons = { PillButton("Link…") { actions.onLinkRoms() } },
                        )
                    }
                }
                SettingsNote("WalkThor only reads the names in that folder, never the games themselves. Add a guide to a game from your library.")
            }
            SettingsSection.ABOUT -> {
                SettingsGroup(null) {
                    SettingLink("How to set up WalkThor", divider = false) { onSetupGuide() }
                    SettingLink("About & licences") { onAbout() }
                }
            }
        }
    }
    if (showEmulators) EmulatorsDialog(onDismiss = { showEmulators = false })
    if (pickDim) {
        AlertDialog(
            onDismissRequest = { pickDim = false; AutoDim.previewing.value = false },
            containerColor = BookColors.Surface,
            title = { Text("Auto-dim", fontFamily = DisplayFont) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Fade the screen nearly to black after this long without a touch or button press. Any touch wakes it.",
                        color = BookColors.TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text("Dim after", style = EyebrowStyle, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                    // One compact row of choices, so the whole dialog fits the short bottom screen.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutoDim.OPTIONS.forEach { ms ->
                            FilterChip(
                                selected = dimDelay == ms,
                                onClick = { AutoDim.setDelay(context, ms) },
                                label = { Text(AutoDim.shortLabel(ms)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = BookColors.SurfaceHigh,
                                    labelColor = BookColors.Text,
                                    selectedContainerColor = BookColors.ButtonFill,
                                    selectedLabelColor = BookColors.Text,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = dimDelay == ms,
                                    borderColor = Color.Transparent,
                                    selectedBorderColor = BookColors.ButtonEdge,
                                    selectedBorderWidth = 1.dp,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("How dark", style = EyebrowStyle)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = dimLevel,
                            onValueChange = { AutoDim.previewing.value = true; AutoDim.setLevel(context, it) },
                            onValueChangeFinished = { AutoDim.previewing.value = false },
                            valueRange = AutoDim.MIN_LEVEL..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = BookColors.Text,
                                activeTrackColor = BookColors.Text,
                                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${(dimLevel * 100).roundToInt()}%",
                            color = BookColors.Text,
                            fontSize = 13.sp,
                            modifier = Modifier.width(44.dp).padding(start = 8.dp),
                        )
                    }
                    Text(
                        if (dimLevel >= 0.995f) "Fully black: only the tabs and the time stay lit."
                        else "The tabs and the time always stay at full brightness.",
                        color = BookColors.TextMuted,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { pickDim = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun EmptyShelf(onImport: () -> Unit, romsLinked: Boolean, onLinkRoms: () -> Unit, onSetupGuide: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (romsLinked) "No guides yet" else "Nothing here yet", fontFamily = DisplayFont, fontSize = 22.sp, color = BookColors.Text)
        Spacer(Modifier.height(6.dp))
        Text(
            if (romsLinked) "Add a PDF or TXT walkthrough and it becomes a book you can flip through and search."
            else "Link your ROMs folder to list your games by system, and add a PDF or TXT walkthrough for any of them.",
            textAlign = TextAlign.Center,
            color = BookColors.TextMuted,
            modifier = Modifier.width(360.dp),
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!romsLinked) {
                Button(
                    onClick = onLinkRoms,
                    colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
                ) { Text("Link ROMs folder", fontWeight = FontWeight.SemiBold) }
                OutlinedButton(onClick = onImport) { Text("Add guide", color = BookColors.Text) }
            } else {
                Button(
                    onClick = onImport,
                    colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
                ) { Text("Add guide", fontWeight = FontWeight.SemiBold) }
            }
        }
        TextButton(onClick = onSetupGuide, modifier = Modifier.padding(top = 6.dp)) { Text("How to set up WalkThor") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfItem(book: Book, actions: LibraryActions) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val cover = remember(book.cover) { actions.coverFile(book) }

    Column {
        GuideTile(
            book = book,
            cover = cover,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { actions.onOpen(book) }, onLongClick = { menu = true }),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    color = BookColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp,
                )
                run {
                    val kind = if (book.type == BookType.PDF) "PDF" else "TXT"
                    Text(
                        // The type is on the card's tag; here, where you are in it.
                        if (book.pageCount > 0) "p. ${book.lastPage + 1} of ${book.pageCount}" else kind,
                        color = BookColors.TextMuted,
                        fontSize = 11.sp,
                        fontFamily = DisplayFont,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = BookColors.TextMuted, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Remove guide") }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(book.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename guide") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { renaming = false; actions.onRename(book, text) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove “${book.title}”?") },
            text = { Text("WalkThor's copy of this guide will be deleted. Your original file isn't touched.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; actions.onDelete(book) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * A guide on the shelf: a rounded card filled edge to edge with its artwork (or, without art, a
 * soft colour gradient with the guide's title), a small TXT/PDF tag in the corner and a thin
 * reading-progress line along the bottom. Small tiles (e.g. beside a guide's name) drop the tag
 * and title and just show the art or colour.
 */
@Composable
internal fun GuideTile(book: Book, cover: File?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val k = (maxWidth / 130.dp).coerceIn(0.35f, 1f)
        val small = maxWidth < 80.dp
        val shape = RoundedCornerShape(if (small) 6.dp else 10.dp)
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(BookColors.Surface)
        ) {
            val resting by AutoDim.resting.collectAsState()
            if (cover != null && !resting) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                GeneratedCover(book, k, showTitle = !small)
            }
            if (!small) {
                Text(
                    if (book.type == BookType.PDF) "PDF" else "TXT",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = DisplayFont,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            // Reading progress along the bottom edge.
            if (book.pageCount > 0 && !small) {
                val frac = ((book.lastPage + 1).toFloat() / book.pageCount).coerceIn(0f, 1f)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.5f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(frac).height(3.dp).background(Color(0xFFF5F5FA)))
            }
            // A faint edge so dark art still reads as a card on the black shelf.
            Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.08f), shape))
        }
    }
}

/** A cloth-bound cover with a brass double rule, for guides without artwork. */
@Composable
private fun GeneratedCover(book: Book, k: Float, showTitle: Boolean) {
    val ink = coverInks[(book.title.hashCode() and Int.MAX_VALUE) % coverInks.size]
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(ink, Color.White, 0.14f), ink, Color(0xFF0D0D10)))),
    ) {
        if (showTitle) {
            Text(
                book.title,
                color = BookColors.Text,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp * k,
                lineHeight = 17.sp * k,
                letterSpacing = (-0.2).sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp * k, end = 10.dp * k, bottom = 12.dp * k),
            )
        }
    }
}


@Composable
fun ImportDialogs(
    state: ImportState?,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    workingTitle: String = "Adding guide",
    failedTitle: String = "Couldn't add guide",
) {
    when (state) {
        is ImportState.Working -> AlertDialog(
            onDismissRequest = {},
            containerColor = BookColors.Surface,
            title = { Text(workingTitle, fontFamily = DisplayFont) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = BookColors.Brass, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(state.message, color = BookColors.TextMuted)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = onDismissError,
            containerColor = BookColors.Surface,
            title = { Text(failedTitle, fontFamily = DisplayFont) },
            text = { Text(state.message, color = BookColors.TextMuted) },
            confirmButton = { TextButton(onClick = onDismissError) { Text("OK") } },
        )
        null -> Unit
    }
}
