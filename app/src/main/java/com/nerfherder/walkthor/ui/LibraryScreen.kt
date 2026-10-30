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
import com.nerfherder.walkthor.data.ArtMatch
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
    val onPickCoverFile: (Book) -> Unit,
    val onSetCover: (Book, Uri) -> Unit,
    val onClearCover: (Book) -> Unit,
    val suggestions: suspend (String) -> List<ArtMatch>,
    val onLinkArtFolder: () -> Unit,
    val onLinkCocoon: () -> Unit,
    val onToggleGameGuide: (Boolean) -> Unit,
    val onUnlinkCocoon: () -> Unit,
    val onRescanArt: () -> Unit,
    val onUnlinkArt: () -> Unit,
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
    /** Pick ES-DE's folder (the one with logs/es_log.txt). */
    val onLinkEsde: () -> Unit = {},
    val onUnlinkEsde: () -> Unit = {},
    /** Pick an image file as a game's cover, background or marquee. */
    val onPickGameCover: (String, com.nerfherder.walkthor.data.GameCovers.Kind) -> Unit = { _, _ -> },
    /** Add a guide (file picker) and link it to a game. */
    val onAddGuideFor: (com.nerfherder.walkthor.data.LaunchedGame) -> Unit = {},
    /** Link one of your guides to a game, and open it. */
    val onLinkGuide: (com.nerfherder.walkthor.data.LaunchedGame, String) -> Unit = { _, _ -> },
)

@Composable
fun LibraryScreen(
    books: List<Book>,
    artLinked: Boolean,
    artCount: Int,
    artScanning: String?,
    alwaysBottom: Boolean,
    swipeToOpen: Boolean,
    gameGuide: Boolean,
    cocoonLinked: Boolean,
    swipeNeedsPermission: Boolean,
    actions: LibraryActions,
    esdeLinked: Boolean = false,
) {
    var coverFor by remember { mutableStateOf<Book?>(null) }
    var explainSwipe by remember { mutableStateOf(false) }
    var showDisclosure by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Your ROMs folder's games, by system, with your guides as their own section.
    val libContext = LocalContext.current
    val romLibrary = remember { com.nerfherder.walkthor.data.RomLibrary.get(libContext) }
    val allRoms by romLibrary.roms.collectAsState()
    LaunchedEffect(Unit) { romLibrary.load() }
    val hiddenIds = HiddenGames.ids.collectAsState().value ?: remember { HiddenGames.load(libContext) }
    val roms = remember(allRoms, hiddenIds) { allRoms.filter { it.id !in hiddenIds } }
    val hiddenRoms = remember(allRoms, hiddenIds) { allRoms.filter { it.id in hiddenIds } }
    var chosenSection by remember { mutableStateOf(LibrarySection.load(libContext)) }
    // A system folder that's gone (relinked, rescanned): back to all games.
    val section = chosenSection.takeIf { s ->
        s == LibrarySection.ALL || s == LibrarySection.GUIDES || roms.any { it.system == s } ||
            (s == LibrarySection.HIDDEN && hiddenRoms.isNotEmpty())
    } ?: LibrarySection.ALL
    val showGames = allRoms.isNotEmpty() && section != LibrarySection.GUIDES
    // Turning swipe-to-open on always goes through the disclosure first.
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
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        showGames -> if (narrowHeader) "Your library" else "Your library · ${roms.size} game${if (roms.size == 1) "" else "s"}"
                        books.isEmpty() || narrowHeader -> "Your guides"
                        else -> "Your guides · ${books.size} guide${if (books.size == 1) "" else "s"}"
                    },
                    style = EyebrowStyle,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    "WalkThor",
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
                // On narrow screens the button is just "+", so the header never crowds out the shelf.
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
        if (artScanning != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = BookColors.Brass, trackColor = BookColors.Hairline)
                Text(artScanning, fontSize = 11.sp, color = BookColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        ScrapeProgress()
        HorizontalDivider(color = BookColors.Hairline, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

        Row(Modifier.weight(1f)) {
          if (allRoms.isNotEmpty()) {
            LibraryRail(roms, books.size, hiddenRoms.size, section) { chosenSection = it; LibrarySection.save(libContext, it) }
            Box(Modifier.width(1.dp).fillMaxHeight().background(BookColors.Hairline))
          }
          Box(Modifier.weight(1f)) {
            if (showGames) {
                GamesGrid(
                    roms = when (section) {
                        LibrarySection.ALL -> roms
                        LibrarySection.HIDDEN -> hiddenRoms
                        else -> roms.filter { it.system == section }
                    },
                    books = books,
                    coverFile = actions.coverFile,
                    actions = actions,
                    hiddenView = section == LibrarySection.HIDDEN,
                )
            } else if (books.isEmpty()) {
                EmptyShelf(actions.onImport, artLinked, actions.onLinkArtFolder, onSetupGuide = { showSetup = true })
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 118.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(books, key = { it.id }) { book ->
                        ShelfItem(book, actions, onChooseCover = { coverFor = book })
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
            artLinked, artCount, alwaysBottom, swipeToOpen, cocoonLinked, esdeLinked, actions,
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
                cocoonLinked = cocoonLinked,
                artLinked = artLinked,
                swipeReady = swipeToOpen && !swipeNeedsPermission,
            ),
            onAddGuide = actions.onImport,
            onLinkCocoon = actions.onLinkCocoon,
            onLinkArt = actions.onLinkArtFolder,
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
                        "Android only lets an app open over other apps, or hand a game back its speed, through " +
                            "an accessibility service, so WalkThor has a tiny one. It only watches a thin strip at " +
                            "the top of the bottom screen and doesn't read anything on your screen.",
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

    coverFor?.let { book ->
        // Show the live copy so the picker reflects a cover that was just set.
        val live = books.firstOrNull { it.id == book.id } ?: book
        CoverPicker(
            book = live,
            artLinked = artLinked,
            artCount = artCount,
            actions = actions,
            onDismiss = { coverFor = null },
        )
    }
}

@Composable
private fun SettingsScreen(
    artLinked: Boolean,
    artCount: Int,
    alwaysBottom: Boolean,
    swipeToOpen: Boolean,
    cocoonLinked: Boolean,
    esdeLinked: Boolean,
    actions: LibraryActions,
    onToggleSwipe: (Boolean) -> Unit,
    onAbout: () -> Unit,
    onSetupGuide: () -> Unit,
    onToggleEmulators: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickDim by remember { mutableStateOf(false) }
    var showAccounts by remember { mutableStateOf(false) }
    var showEmulators by remember { mutableStateOf(false) }
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsSection.GENERAL) }
    val menuScope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val dimDelay by AutoDim.delayMs.collectAsState()
    val dimLevel by AutoDim.level.collectAsState()
    val showTimer by PlaySession.showTimer.collectAsState()
    val detecting by com.nerfherder.walkthor.EmulatorWatch.on.collectAsState()
    // Your ROMs folder: for picking which game you're playing, and for scraping artwork.
    val romLib = remember { com.nerfherder.walkthor.data.RomLibrary.get(context) }
    val romFolder by romLib.folder.collectAsState()
    val roms by romLib.roms.collectAsState()
    LaunchedEffect(Unit) { romLib.load() }
    // Re-read the scraper accounts each time the accounts dialog closes.
    val scraperCount = remember(showAccounts) {
        listOf(
            com.nerfherder.walkthor.data.ScraperPrefs.hasSteamGridDb(context),
            com.nerfherder.walkthor.data.ScraperPrefs.hasScreenScraper(context),
        ).count { it }
    }
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
                SettingsGroup("Screen") {
                    SettingSwitch("Always open on bottom screen", checked = alwaysBottom, divider = false) { actions.onToggleAlwaysBottom(it) }
                    SettingLink("Auto-dim", value = AutoDim.label(dimDelay)) { pickDim = true }
                    actions.onSwapScreen?.let { swap ->
                        SettingLink("Move to the other screen") { onDismiss(); swap() }
                    }
                }
                SettingsGroup("Access") {
                    SettingSwitch(
                        "Accessibility access",
                        "Swipe down to open, and keeps games at full speed",
                        checked = swipeToOpen,
                        divider = false,
                    ) { onToggleSwipe(it) }
                }
            }
            SettingsSection.ARTWORK -> {
                SettingsGroup("Cocoon / ES-DE artwork") {
                    if (artLinked) {
                        SettingRow(
                            "Artwork folder",
                            "$artCount images",
                            divider = false,
                            buttons = {
                                PillButton("Rescan") { actions.onRescanArt() }
                                PillButton("Change") { actions.onLinkArtFolder() }
                                PillButton("Unlink", quiet = true) { actions.onUnlinkArt() }
                            },
                        )
                    } else {
                        SettingRow(
                            "Artwork folder",
                            "Cocoon's media folder, or ES-DE's downloaded_media: box art, backgrounds and marquees",
                            divider = false,
                            buttons = { PillButton("Link…") { actions.onLinkArtFolder() } },
                        )
                    }
                }
                SettingsGroup("Scraping") {
                    SettingLink(
                        "Scraper accounts",
                        "SteamGridDB · ScreenScraper",
                        value = if (scraperCount == 0) "None" else "$scraperCount linked",
                        divider = false,
                    ) { showAccounts = true }
                    SettingLink("Scrape artwork", "Covers for your guides; screenshots and marquees for your games") {
                        if (com.nerfherder.walkthor.data.ScraperPrefs.hasAny(context)) {
                            com.nerfherder.walkthor.data.Scraper.scrapeAll(context)
                            onDismiss()
                        } else showAccounts = true
                    }
                    if (scraperCount > 0) {
                        SettingLink("Scrape everything again", "Replaces art WalkThor found before (never a cover you chose)") {
                            com.nerfherder.walkthor.data.Scraper.scrapeAll(context, refresh = true)
                            onDismiss()
                        }
                    }
                }
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
                            "Optional: one folder per system (3ds, gba, switch…), for exact matches",
                            divider = false,
                            buttons = { PillButton("Link…") { actions.onLinkRoms() } },
                        )
                    }
                }
            }
            SettingsSection.EMULATORS -> {
                SettingsGroup("Emulators") {
                    SettingSwitch(
                        "Open when an emulator starts",
                        when {
                            cocoonLinked -> "Even when Cocoon doesn't open WalkThor"
                            esdeLinked -> "Opens WalkThor when you start a game in ES-DE"
                            else -> "Works without Cocoon"
                        },
                        checked = detecting,
                        divider = false,
                    ) { onToggleEmulators(it) }
                    if (detecting) {
                        SettingLink("Choose emulators", "Tick any WalkThor doesn't know") { showEmulators = true }
                    }
                }
                if (detecting && (cocoonLinked || esdeLinked)) {
                    SettingsNote("With " + (if (cocoonLinked) "Cocoon" else "ES-DE") + " linked, WalkThor knows the game from its launch log.")
                }
            }
            SettingsSection.COCOON -> {
                SettingsGroup("Cocoon") {
                    if (cocoonLinked) {
                        SettingRow(
                            "Cocoon folder",
                            "WalkThor knows each game Cocoon launches",
                            divider = false,
                            buttons = {
                                PillButton("Change") { actions.onLinkCocoon() }
                                PillButton("Unlink", quiet = true) { actions.onUnlinkCocoon() }
                            },
                        )
                        SettingSwitch("Show play time", "Next to the clock, from Cocoon's launch log", checked = showTimer) {
                            PlaySession.setShowTimer(context, it)
                        }
                    } else {
                        SettingRow(
                            "Cocoon folder",
                            "Link it so WalkThor knows each game Cocoon launches",
                            divider = false,
                            buttons = { PillButton("Link…") { actions.onLinkCocoon() } },
                        )
                    }
                }
            }
            SettingsSection.ESDE -> {
                SettingsGroup("ES-DE") {
                    if (esdeLinked) {
                        SettingRow(
                            "ES-DE folder",
                            "WalkThor knows each game ES-DE launches, and when you're back",
                            divider = false,
                            buttons = {
                                PillButton("Change") { actions.onLinkEsde() }
                                PillButton("Unlink", quiet = true) { actions.onUnlinkEsde() }
                            },
                        )
                        SettingSwitch("Show play time", "Next to the clock, from ES-DE's log", checked = showTimer) {
                            PlaySession.setShowTimer(context, it)
                        }
                    } else {
                        SettingRow(
                            "ES-DE folder",
                            "Link the ES-DE folder (the one with logs inside) so WalkThor knows each game you start",
                            divider = false,
                            buttons = { PillButton("Link…") { actions.onLinkEsde() } },
                        )
                    }
                }
                SettingsNote(
                    "In ES-DE: Main menu → Other settings → turn on Custom Event Scripts and Debug Mode, so it logs " +
                        "each game. To have WalkThor open when a game starts, turn on Emulators → Open when an emulator starts.",
                )
            }
            SettingsSection.ABOUT -> {
                SettingsGroup(null) {
                    SettingLink("How to set up WalkThor", divider = false) { onSetupGuide() }
                    SettingLink("About & licences") { onAbout() }
                }
            }
        }
    }
    if (showAccounts) ScraperAccountsDialog(onDismiss = { showAccounts = false })
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
                        if (dimLevel >= 0.995f) "Fully black — only the time, play time and battery stay lit."
                        else "The time, play time and battery always stay at full brightness.",
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
private fun EmptyShelf(onImport: () -> Unit, artLinked: Boolean, onLinkArt: () -> Unit, onSetupGuide: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("❦", fontSize = 28.sp, color = BookColors.Brass)
        Spacer(Modifier.height(6.dp))
        Text("No guides yet", fontFamily = DisplayFont, fontSize = 22.sp, color = BookColors.Text)
        Spacer(Modifier.height(6.dp))
        Text(
            "Add a PDF or TXT walkthrough and it becomes a book you can flip through and search.",
            textAlign = TextAlign.Center,
            color = BookColors.TextMuted,
            modifier = Modifier.width(360.dp),
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onImport,
                colors = ButtonDefaults.buttonColors(containerColor = BookColors.ButtonFill, contentColor = BookColors.Text),
                border = androidx.compose.foundation.BorderStroke(1.dp, BookColors.ButtonEdge),
            ) { Text("Add guide", fontWeight = FontWeight.SemiBold) }
            if (!artLinked) {
                OutlinedButton(onClick = onLinkArt) { Text("Link artwork folder", color = BookColors.Text) }
            }
        }
        TextButton(onClick = onSetupGuide, modifier = Modifier.padding(top = 6.dp)) { Text("How to set up WalkThor") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfItem(book: Book, actions: LibraryActions, onChooseCover: () -> Unit) {
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
                    DropdownMenuItem(text = { Text("Choose cover…") }, onClick = { menu = false; onChooseCover() })
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
private fun CoverPicker(book: Book, artLinked: Boolean, artCount: Int, actions: LibraryActions, onDismiss: () -> Unit) {
    var query by remember(book.id) { mutableStateOf(book.title) }
    val matches by produceState<List<ArtMatch>?>(null, query, artLinked) {
        value = null
        delay(200) // let typing settle
        value = if (artLinked) actions.suggestions(query) else emptyList()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f)
                .background(BookColors.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, BookColors.Hairline, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text("Cover", style = EyebrowStyle)
            Text(
                book.title,
                fontFamily = DisplayFont,
                fontSize = 18.sp,
                color = BookColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            if (artLinked) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = AppFont.family, color = BookColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(BookColors.Brass),
                    decorationBox = { inner ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(BookColors.Ink, RoundedCornerShape(18.dp))
                                .border(1.dp, BookColors.Hairline, RoundedCornerShape(18.dp))
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = BookColors.TextMuted, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f)) { inner() }
                        }
                    },
                )
                Text(
                    when {
                        artCount == 0 -> "No images indexed yet — try Settings → Artwork → Rescan, or link a different folder."
                        matches.isNullOrEmpty() -> "Searched $artCount images"
                        else -> "Closest matches from $artCount images · tap one to use it"
                    },
                    fontSize = 11.sp,
                    color = BookColors.TextMuted,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val list = matches
                    when {
                        list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = BookColors.Brass, strokeWidth = 2.dp)
                        list.isEmpty() -> Text(
                            "No artwork matches “$query”. Try the game's name, or pick an image file.",
                            color = BookColors.TextMuted,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            textAlign = TextAlign.Center,
                        )
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 96.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(list, key = { it.image.uri }) { m ->
                                Column(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { actions.onSetCover(book, Uri.parse(m.image.uri)); onDismiss() }
                                ) {
                                    AsyncImage(
                                        model = Uri.parse(m.image.uri),
                                        contentDescription = m.image.name,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .background(BookColors.Ink, RoundedCornerShape(6.dp)),
                                    )
                                    Text(
                                        m.image.name,
                                        fontSize = 10.sp,
                                        color = BookColors.Text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 3.dp),
                                    )
                                    Text(
                                        m.image.folder.substringAfterLast('/').ifEmpty { "—" },
                                        fontSize = 10.sp,
                                        color = BookColors.TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Link your Cocoon or ES-DE artwork folder to pick from the box art you've already scraped.",
                        color = BookColors.TextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(360.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { onDismiss(); actions.onLinkArtFolder() }) {
                        Text("Link artwork folder", color = BookColors.Text)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDismiss(); actions.onPickCoverFile(book) }) { Text("Pick image file…") }
                if (book.cover != null) {
                    TextButton(onClick = { actions.onClearCover(book); onDismiss() }) { Text("Remove cover") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close", color = BookColors.TextMuted) }
            }
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
