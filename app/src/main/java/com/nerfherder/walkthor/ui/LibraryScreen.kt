package com.nerfherder.walkthor.ui

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nerfherder.walkthor.ImportState
import com.nerfherder.walkthor.data.ArtMatch
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.BookType
import kotlinx.coroutines.delay
import java.io.File

private val coverInks = listOf(
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
    val isSwipeServiceOn: () -> Boolean,
    val onOpenAppInfo: () -> Unit,
    val onSwapScreen: (() -> Unit)?,
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
) {
    var coverFor by remember { mutableStateOf<Book?>(null) }
    var explainSwipe by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(BookColors.Ink)) {
        // ---- header -----------------------------------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (books.isEmpty()) "YOUR SHELF" else "YOUR SHELF · ${books.size} GUIDE${if (books.size == 1) "" else "S"}",
                    style = EyebrowStyle,
                )
                Text(
                    "WalkThor",
                    fontFamily = SerifDisplay,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 26.sp,
                    color = BookColors.Text,
                )
            }
            Button(
                onClick = actions.onImport,
                colors = ButtonDefaults.buttonColors(containerColor = BookColors.Brass, contentColor = Color(0xFF1E1709)),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add guide", fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = actions.onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close WalkThor", tint = BookColors.TextMuted)
            }
            SettingsMenu(
                artLinked, artCount, alwaysBottom, swipeToOpen, gameGuide, cocoonLinked, actions,
                onToggleSwipe = { on ->
                    actions.onToggleSwipeToOpen(on)
                    if (on && !actions.isSwipeServiceOn()) explainSwipe = true
                },
            )
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
                    "Swipe-to-open: switch on “WalkThor swipe to open” in Accessibility.",
                    color = BookColors.Text,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { explainSwipe = true }) { Text("Set up") }
            }
        }
        if (artScanning != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = BookColors.Brass, trackColor = BookColors.Hairline)
                Text(artScanning, fontSize = 11.sp, color = BookColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        HorizontalDivider(color = BookColors.Hairline, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

        if (books.isEmpty()) {
            EmptyShelf(actions.onImport, artLinked, actions.onLinkArtFolder)
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

    if (explainSwipe) {
        AlertDialog(
            onDismissRequest = { explainSwipe = false },
            containerColor = BookColors.Surface,
            title = { Text("Swipe down to open", fontFamily = SerifDisplay) },
            text = {
                Column {
                    Text(
                        "Android only lets an app pop up over other apps through an accessibility service, " +
                            "so the swipe is handled by a tiny one. It only watches a thin strip at the top " +
                            "of the bottom screen and doesn't read anything on your screen.",
                        color = BookColors.TextMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "1. Tap Open settings.\n2. Find “WalkThor swipe to open” (it may be under Downloaded apps) and switch it on.",
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
private fun SettingsMenu(
    artLinked: Boolean,
    artCount: Int,
    alwaysBottom: Boolean,
    swipeToOpen: Boolean,
    gameGuide: Boolean,
    cocoonLinked: Boolean,
    actions: LibraryActions,
    onToggleSwipe: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Settings", tint = BookColors.TextMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Always open on bottom screen") },
                trailingIcon = { if (alwaysBottom) Icon(Icons.Filled.Check, contentDescription = "On", tint = BookColors.Brass) },
                onClick = { actions.onToggleAlwaysBottom(!alwaysBottom) },
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Swipe down to open")
                        Text("From the top of the bottom screen", fontSize = 11.sp, color = BookColors.TextMuted)
                    }
                },
                trailingIcon = { if (swipeToOpen) Icon(Icons.Filled.Check, contentDescription = "On", tint = BookColors.Brass) },
                onClick = { open = false; onToggleSwipe(!swipeToOpen) },
            )
            if (swipeToOpen) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Open the playing game's guide")
                            Text(
                                if (cocoonLinked) "Uses Cocoon's launch log" else "Link your Cocoon folder to turn on",
                                fontSize = 11.sp,
                                color = BookColors.TextMuted,
                            )
                        }
                    },
                    trailingIcon = { if (gameGuide) Icon(Icons.Filled.Check, contentDescription = "On", tint = BookColors.Brass) },
                    onClick = { open = false; actions.onToggleGameGuide(!gameGuide) },
                )
            }
            actions.onSwapScreen?.let { swap ->
                DropdownMenuItem(text = { Text("Move to other screen") }, onClick = { open = false; swap() })
            }
            DropdownMenuItem(text = { Text("Close WalkThor") }, onClick = { open = false; actions.onClose() })
            HorizontalDivider(color = BookColors.Hairline)
            if (cocoonLinked) {
                DropdownMenuItem(text = { Text("Change Cocoon folder…") }, onClick = { open = false; actions.onLinkCocoon() })
                DropdownMenuItem(text = { Text("Unlink Cocoon folder") }, onClick = { open = false; actions.onUnlinkCocoon() })
            } else {
                DropdownMenuItem(
                    text = { Text("Link Cocoon folder…") },
                    leadingIcon = { Icon(AppIcons.Folder, contentDescription = null) },
                    onClick = { open = false; actions.onLinkCocoon() },
                )
            }
            if (!artLinked) {
                DropdownMenuItem(
                    text = { Text("Link Cocoon artwork folder…") },
                    leadingIcon = { Icon(AppIcons.Folder, contentDescription = null) },
                    onClick = { open = false; actions.onLinkArtFolder() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Artwork: $artCount images", color = BookColors.TextMuted) },
                    leadingIcon = { Icon(AppIcons.Image, contentDescription = null, tint = BookColors.TextMuted) },
                    onClick = {},
                    enabled = false,
                )
                DropdownMenuItem(text = { Text("Rescan artwork") }, onClick = { open = false; actions.onRescanArt() })
                DropdownMenuItem(text = { Text("Change artwork folder…") }, onClick = { open = false; actions.onLinkArtFolder() })
                DropdownMenuItem(text = { Text("Unlink artwork folder") }, onClick = { open = false; actions.onUnlinkArt() })
            }
        }
    }
}

@Composable
private fun EmptyShelf(onImport: () -> Unit, artLinked: Boolean, onLinkArt: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("❦", fontSize = 28.sp, color = BookColors.Brass)
        Spacer(Modifier.height(6.dp))
        Text("An empty shelf", fontFamily = SerifDisplay, fontSize = 22.sp, color = BookColors.Text)
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
                colors = ButtonDefaults.buttonColors(containerColor = BookColors.Brass, contentColor = Color(0xFF1E1709)),
            ) { Text("Add guide", fontWeight = FontWeight.SemiBold) }
            if (!artLinked) {
                OutlinedButton(onClick = onLinkArt) { Text("Link Cocoon artwork", color = BookColors.Text) }
            }
        }
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
                        if (book.pageCount > 0) "$kind · p. ${book.lastPage + 1} of ${book.pageCount}" else kind,
                        color = BookColors.TextMuted,
                        fontSize = 11.sp,
                        fontFamily = SerifDisplay,
                        fontStyle = FontStyle.Italic,
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
                    DropdownMenuItem(text = { Text("Remove from shelf") }, onClick = { menu = false; confirmDelete = true })
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

private val GuideRed = Color(0xFFB3261F)
private val GuideRedDeep = Color(0xFF7A1410)
private val BINDING = 16.dp
private val BANNER = 22.dp

/**
 * A guide on the shelf: square artwork, a red "GUIDE" banner across the top, and a leather
 * book binding down the left edge.
 */
@Composable
internal fun GuideTile(book: Book, cover: File?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 8.dp, bottomEnd = 8.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .shadow(10.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(BookColors.Surface)
    ) {
        // ---- artwork, right of the binding ----
        Box(Modifier.fillMaxSize().padding(start = BINDING)) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                GeneratedCover(book)
            }
            // Where the cover meets the binding it dips into a hinge.
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(10.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
            )
            // Red banner.
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(BANNER)
                    .background(Brush.verticalGradient(listOf(GuideRed, GuideRedDeep))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "GUIDE",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    fontFamily = SerifDisplay,
                )
            }
            Box(
                Modifier
                    .padding(top = BANNER)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))
            )
            // Reading progress along the bottom edge.
            if (book.pageCount > 0) {
                val frac = ((book.lastPage + 1).toFloat() / book.pageCount).coerceIn(0f, 1f)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.5f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(frac).height(3.dp).background(BookColors.Brass))
            }
        }
        // ---- the binding ----
        Canvas(Modifier.fillMaxHeight().width(BINDING)) {
            drawRect(
                Brush.horizontalGradient(
                    0f to Color(0xFF160807),
                    0.35f to Color(0xFF52201A),
                    0.6f to Color(0xFF3A1511),
                    1f to Color(0xFF120605),
                )
            )
            // Raised bands, like the ridges on an old leather spine.
            val band = 2.dp.toPx()
            for (y in floatArrayOf(0.14f, 0.20f, 0.80f, 0.86f)) {
                val top = size.height * y
                drawRect(Color.Black.copy(alpha = 0.45f), Offset(0f, top + band / 2), Size(size.width, band))
                drawRect(BookColors.Brass.copy(alpha = 0.6f), Offset(0f, top - band / 2), Size(size.width, band / 2))
            }
            // Stitching down the middle.
            val dash = 3.dp.toPx()
            var y = size.height * 0.26f
            while (y < size.height * 0.74f) {
                drawRect(BookColors.Brass.copy(alpha = 0.35f), Offset(size.width * 0.45f, y), Size(1.dp.toPx(), dash))
                y += dash * 2
            }
        }
    }
}

/** A cloth-bound cover with a brass double rule, for guides without artwork. */
@Composable
private fun GeneratedCover(book: Book) {
    val ink = coverInks[(book.title.hashCode() and Int.MAX_VALUE) % coverInks.size]
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(ink.copy(alpha = 0.85f), ink, Color.Black.copy(alpha = 0.6f).compositeOn(ink))))
            .padding(start = 8.dp, end = 8.dp, top = 30.dp, bottom = 8.dp)
            .border(1.dp, BookColors.Brass.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
            .padding(3.dp)
            .border(0.5.dp, BookColors.Brass.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            book.title,
            color = BookColors.BrassSoft,
            fontFamily = SerifDisplay,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

private fun Color.compositeOn(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a))
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
            Text("COVER", style = EyebrowStyle)
            Text(
                book.title,
                fontFamily = SerifDisplay,
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
                    textStyle = TextStyle(color = BookColors.Text, fontSize = 14.sp),
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
                        artCount == 0 -> "No images indexed yet — try ⋮ → Rescan artwork, or link a different folder."
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
                        "Link your Cocoon artwork folder to pick from the box art you've already scraped.",
                        color = BookColors.TextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(360.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { onDismiss(); actions.onLinkArtFolder() }) {
                        Text("Link Cocoon artwork folder", color = BookColors.Text)
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
            title = { Text(workingTitle, fontFamily = SerifDisplay) },
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
            title = { Text(failedTitle, fontFamily = SerifDisplay) },
            text = { Text(state.message, color = BookColors.TextMuted) },
            confirmButton = { TextButton(onClick = onDismissError) { Text("OK") } },
        )
        null -> Unit
    }
}
