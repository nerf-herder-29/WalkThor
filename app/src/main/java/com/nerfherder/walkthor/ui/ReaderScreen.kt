package com.nerfherder.walkthor.ui

import androidx.compose.foundation.layout.isImeVisible

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nerfherder.walkthor.KeyBus
import com.nerfherder.walkthor.ReaderViewModel
import com.nerfherder.walkthor.data.BookType
import com.nerfherder.walkthor.data.PageLayout
import com.nerfherder.walkthor.data.SearchHit
import kotlin.math.roundToInt

private val TXT_PAD_H = 14.dp
private val TXT_PAD_V = 10.dp
private val PDF_PAD = 4.dp
private val FOOTER = 16.dp

@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onSwapScreen: (() -> Unit)?,
    /** During a game: the game's tabs, shown in the top bar with search behind a button. */
    tabs: TabsState? = null,
) {
    // The reader's ViewModel lives exactly as long as this screen, so the PDF is closed on exit.
    val owner = remember(bookId) {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val app = LocalContext.current.applicationContext as Application
    val vm: ReaderViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = bookId,
        factory = ReaderViewModel.factory(app, bookId),
    )

    val book by vm.book.collectAsState()
    val error by vm.error.collectAsState()
    val pageCount by vm.pageCount.collectAsState()
    val vmPage by vm.currentPage.collectAsState()
    val layout by vm.layout.collectAsState()
    val pagination by vm.pagination.collectAsState()
    val paginating by vm.paginating.collectAsState()
    val pdf by vm.pdf.collectAsState()
    val query by vm.query.collectAsState()
    val applied by vm.appliedQuery.collectAsState()
    val hits by vm.hits.collectAsState()

    val bookState = rememberBookState(vmPage)
    LaunchedEffect(vmPage) {
        if (!bookState.isTurning && bookState.current != vmPage) bookState.current = vmPage
    }

    val spread = layout == PageLayout.SPREAD
    val step = if (spread) 2 else 1
    val isTxt = book?.type == BookType.TXT
    var showResults by remember { mutableStateOf(false) }
    /** With tabs: the search field is open in place of them. */
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(searchOpen) { if (searchOpen) runCatching { searchFocus.requestFocus() } }
    /** Page shown in the reading lens, or null when the lens is closed. */
    var lensPage by rememberSaveable { mutableStateOf<Int?>(null) }
    var lensZoom by rememberSaveable { mutableFloatStateOf(1f) }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current

    // Read through State so callbacks captured by long-lived effects always see the latest value.
    val pageCountNow by rememberUpdatedState(pageCount)

    fun openLens(page: Int) {
        lensPage = page.coerceIn(0, (pageCountNow - 1).coerceAtLeast(0))
    }

    fun lensTo(page: Int) {
        if (pageCountNow == 0) return
        val p = page.coerceIn(0, pageCountNow - 1)
        lensPage = p
        vm.onPageChanged(vm.align(p))
    }

    fun goTo(page: Int) {
        showResults = false
        focusManager.clearFocus()
        if (lensPage != null) lensTo(page) else bookState.turnTo(vm.align(page))
    }

    // Controller shoulder buttons / D-pad turn pages (or move the lens).
    DisposableEffect(Unit) {
        KeyBus.readerActive = true
        onDispose {
            KeyBus.readerActive = false
            KeyBus.textFieldFocused = false
            com.nerfherder.walkthor.GameFocus.wantFocus.value = false
        }
    }
    val lensPageNow by rememberUpdatedState(lensPage)
    LaunchedEffect(step) {
        KeyBus.commands.collect { cmd ->
            val forward = cmd == KeyBus.Command.NEXT
            val lp = lensPageNow
            if (lp != null) lensTo(lp + if (forward) 1 else -1)
            else bookState.turnTo(bookState.current + if (forward) step else -step)
        }
    }

    BackHandler {
        when {
            showResults -> showResults = false
            lensPage != null -> lensPage = null
            else -> onBack()
        }
    }

    // One page's content, used by both the book and the lens.
    @Composable
    fun PageBody(index: Int, renderScale: Float) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (isTxt) TXT_PAD_H else PDF_PAD,
                    vertical = if (isTxt) TXT_PAD_V else PDF_PAD,
                )
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val p = pagination
                val src = pdf
                if (isTxt && p != null && index < p.pageCount) TxtPage(p, index, applied)
                else if (!isTxt && src != null) PdfPage(src, index, applied, renderScale = renderScale)
            }
            Text(
                "— ${index + 1} —",
                modifier = Modifier.fillMaxWidth().height(FOOTER),
                textAlign = TextAlign.Center,
                fontSize = 10.sp,
                fontFamily = DisplayFont,
                color = LocalPageColors.current.inkFaint,
            )
        }
    }

    Column(Modifier.fillMaxSize().background(backdropOr(BookColors.Ink))) {
        // ---- top bar ----------------------------------------------------------------------
        val lensOn = lensPage != null
        // With tabs, search hides behind a button until you use it, and the tabs give way to it.
        val showSearch = tabs == null || searchOpen || query.isNotEmpty() || applied.isNotEmpty()

        @Composable
        fun RowScope.SearchWithHits() {
            SearchField(
                value = query,
                onValueChange = { vm.query.value = it; showResults = it.isNotBlank() },
                onSearch = { showResults = true },
                onFocusChanged = { focused ->
                    KeyBus.textFieldFocused = focused
                    if (focused && query.isNotBlank()) showResults = true
                    if (!focused && query.isEmpty()) searchOpen = false
                },
                focusRequester = if (tabs != null) searchFocus else null,
                modifier = Modifier.weight(1f),
            )
            if (applied.isNotEmpty()) {
                val shownFrom = lensPage ?: bookState.current
                val pos = vm.hitPosition(hits, shownFrom, if (lensPage != null) PageLayout.SINGLE else layout)
                Text(
                    if (hits.isEmpty()) "0" else "${if (pos == 0) "–" else pos} / ${hits.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = BookColors.TextMuted,
                    modifier = Modifier.padding(start = 8.dp, end = 2.dp),
                )
                IconButton(onClick = { vm.prevHitPage()?.let(::goTo) }, enabled = hits.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
                }
                IconButton(onClick = { vm.nextHitPage()?.let(::goTo) }, enabled = hits.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
                }
            }
        }

        // The reading lens — the big readability control, so it gets its own button.
        @Composable
        fun LensButton(modifier: Modifier = Modifier) {
            IconButton(
                modifier = modifier.then(
                    if (lensOn) Modifier.border(1.dp, BookColors.ButtonEdge, androidx.compose.foundation.shape.CircleShape) else Modifier
                ),
                onClick = { if (lensOn) lensPage = null else openLens(bookState.current) },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (lensOn) BookColors.ButtonFill else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = BookColors.Text,
                ),
            ) {
                Icon(AppIcons.ZoomIn, contentDescription = if (lensOn) "Close reading lens" else "Reading lens")
            }
        }

        if (tabs != null) {
            // During a game: the game's tab bar, centred, with back on the left and ⋮ on the right.
            // Search and the lens are in the bottom bar; the page-layout switch is in ⋮.
            TabBarFrame(
                overlay = false,
                start = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = BookColors.TextMuted)
                    }
                },
                middle = {
                    if (showSearch) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { SearchWithHits() }
                    } else {
                        GameTabsRow(tabs.selected, tabs.onSelect, Modifier.weight(1f))
                    }
                },
                end = {
                    ReaderMenu(
                        isTxt = isTxt,
                        onSmaller = { vm.changeFontSize(bigger = false) },
                        onBigger = { vm.changeFontSize(bigger = true) },
                        onSwapScreen = onSwapScreen,
                        onClose = onClose,
                        onToggleLayout = if (!lensOn) ({ vm.toggleLayout() }) else null,
                        spread = spread,
                    )
                },
            )
        } else Row(
            Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = BookColors.TextMuted)
            }
            SearchWithHits()
            LensButton()
            if (!lensOn) {
                IconButton(onClick = vm::toggleLayout) {
                    Icon(
                        if (spread) AppIcons.Page else AppIcons.Spread,
                        contentDescription = if (spread) "Single page" else "Two-page spread",
                        tint = BookColors.TextMuted,
                    )
                }
            }
            ReaderMenu(
                isTxt = isTxt,
                onSmaller = { vm.changeFontSize(bigger = false) },
                onBigger = { vm.changeFontSize(bigger = true) },
                onSwapScreen = onSwapScreen,
                onClose = onClose,
            )
        }

        // ---- the book, or the lens ------------------------------------------------------------
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
                BookView(
                    state = bookState,
                    pageCount = pageCount,
                    spread = spread,
                    onPageChanged = vm::onPageChanged,
                    onPageTapped = { openLens(it) },
                    onPageSize = { size ->
                        with(density) {
                            val w = size.width - (TXT_PAD_H * 2).roundToPx()
                            val h = size.height - (TXT_PAD_V * 2).roundToPx() - FOOTER.roundToPx()
                            vm.onPageGeometry(w, h)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { index -> PageBody(index, 1f) }
            }

            val lp = lensPage
            if (lp != null && pageCount > 0) {
                LensView(
                    page = lp.coerceIn(0, pageCount - 1),
                    pageCount = pageCount,
                    zoom = lensZoom,
                    onZoomChange = { lensZoom = it },
                    onPageChange = { lensTo(it) },
                    onClose = { lensPage = null },
                    naturalSize = { index, viewportW ->
                        val p = pagination
                        val src = pdf
                        with(density) {
                            if (isTxt && p != null) {
                                IntSize(
                                    p.widthPx + (TXT_PAD_H * 2).roundToPx(),
                                    p.heightPx + (TXT_PAD_V * 2).roundToPx() + FOOTER.roundToPx(),
                                )
                            } else if (src != null && index in src.pageSizes.indices) {
                                val (pw, ph) = src.pageSizes[index]
                                IntSize(viewportW, (viewportW * ph.toFloat() / pw).roundToInt() + FOOTER.roundToPx())
                            } else IntSize(viewportW, viewportW)
                        }
                    },
                ) { index, scale ->
                    // Render PDFs with more detail as you zoom in.
                    PageBody(index, renderScale = if (scale <= 1.2f) 1.2f else if (scale <= 2.2f) 2.2f else 3.2f)
                }
            }

            if (error != null) {
                Text(
                    error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            } else if ((isTxt && (pagination == null || paginating)) || (!isTxt && pdf == null)) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = BookColors.Brass, strokeWidth = 2.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(if (isTxt) "Setting the pages…" else "Opening…", color = BookColors.PageInkFaint)
                }
            }

            if (showResults && applied.isNotEmpty()) {
                SearchResults(
                    hits = hits,
                    onPick = { goTo(it.page) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }

        // ---- page position, time and battery ------------------------------------------------
        Row(Modifier.fillMaxWidth().height(if (tabs != null) 40.dp else 34.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tabs != null) {
                // During a game, search and the reading lens live down here, keeping the tab bar even.
                Spacer(Modifier.width(4.dp))
                IconButton(modifier = Modifier.size(40.dp), onClick = {
                    // Typing needs WalkThor's window to take focus from the game for a moment.
                    com.nerfherder.walkthor.GameFocus.wantFocus.value = true
                    searchOpen = true
                }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search this guide", tint = BookColors.TextMuted)
                }
                LensButton(Modifier.size(40.dp))
            }
            Box(Modifier.weight(1f)) {
                if (lensPage == null) {
                    PageScrubber(
                        current = bookState.current,
                        pageCount = pageCount,
                        spread = spread,
                        onScrubbed = { goTo(it) },
                    )
                }
            }
            StatusClock(Modifier.padding(start = 4.dp, end = 16.dp))
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    // While a game has focus, WalkThor's window can't take it; tapping the search field asks for
    // focus just while typing, then hands it back to the game.
    val keyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, focused) { if (windowFocused && focused) keyboard?.show() }
    // Keyboard put away (Back, or its hide key) while the field still has focus: finish typing,
    // so the game gets its priority back.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeWasVisible = true
        else if (imeWasVisible && focused) { imeWasVisible = false; focusManager.clearFocus() }
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = AppFont.family, color = BookColors.Text, fontSize = 15.sp),
        cursorBrush = SolidColor(BookColors.Brass),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(); focusManager.clearFocus() }),
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    com.nerfherder.walkthor.GameFocus.wantFocus.value = true
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) com.nerfherder.walkthor.GameFocus.wantFocus.value = false
                onFocusChanged(it.isFocused)
            },
        decorationBox = { inner ->
            Row(
                Modifier
                    .height(38.dp)
                    .background(BookColors.Surface, RoundedCornerShape(19.dp))
                    .border(1.dp, if (focused) BookColors.Brass.copy(alpha = 0.7f) else BookColors.Hairline, RoundedCornerShape(19.dp))
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = if (focused) BookColors.Brass else BookColors.TextMuted,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            "Search this guide",
                            color = BookColors.TextMuted.copy(alpha = 0.8f),
                            fontSize = 15.sp,
                            fontFamily = DisplayFont,
                        )
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
    )
}

@Composable
private fun SearchResults(hits: List<SearchHit>, onPick: (SearchHit) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(max = 300.dp)
            .border(1.dp, BookColors.Hairline, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = BookColors.Surface,
        shadowElevation = 12.dp,
    ) {
        if (hits.isEmpty()) {
            Text(
                "No matches",
                modifier = Modifier.padding(16.dp),
                color = BookColors.TextMuted,
                fontFamily = DisplayFont,
            )
        } else LazyColumn {
            item {
                val total = hits.sumOf { it.count }
                Text(
                    "$total MATCH${if (total == 1) "" else "ES"} · ${hits.size} PAGE${if (hits.size == 1) "" else "S"}",
                    style = EyebrowStyle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            items(hits, key = { it.page }) { hit ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(hit) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "p. ${hit.page + 1}",
                        color = BookColors.Brass,
                        fontFamily = DisplayFont,
                        fontSize = 14.sp,
                        modifier = Modifier.width(60.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            buildAnnotatedString {
                                append(hit.snippet.substring(0, hit.matchStart))
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = BookColors.BrassSoft)) {
                                    append(hit.snippet.substring(hit.matchStart, hit.matchStart + hit.matchLength))
                                }
                                append(hit.snippet.substring(hit.matchStart + hit.matchLength))
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = BookColors.Text,
                        )
                        if (hit.count > 1) {
                            Text(
                                "${hit.count} matches on this page",
                                style = MaterialTheme.typography.labelSmall,
                                color = BookColors.TextMuted,
                            )
                        }
                    }
                }
                HorizontalDivider(color = BookColors.Hairline, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun ReaderMenu(
    isTxt: Boolean,
    onSmaller: () -> Unit,
    onBigger: () -> Unit,
    onSwapScreen: (() -> Unit)?,
    onClose: () -> Unit,
    onToggleLayout: (() -> Unit)? = null,
    spread: Boolean = false,
    onLens: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val darkPages by PageStyle.dark.collectAsState()
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = BookColors.TextMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (darkPages) "Light pages" else "Dark pages") },
                onClick = { open = false; PageStyle.setDark(context, !darkPages) },
            )
            if (onLens != null) {
                DropdownMenuItem(text = { Text("Reading lens") }, onClick = { open = false; onLens() })
            }
            if (onToggleLayout != null) {
                DropdownMenuItem(
                    text = { Text(if (spread) "Single page" else "Two-page spread") },
                    onClick = { open = false; onToggleLayout() },
                )
            }
            if (isTxt) {
                DropdownMenuItem(text = { Text("Larger text") }, onClick = { onBigger() })
                DropdownMenuItem(text = { Text("Smaller text") }, onClick = { onSmaller() })
            }
            if (onSwapScreen != null) {
                DropdownMenuItem(text = { Text("Move to other screen") }, onClick = { open = false; onSwapScreen() })
            }
            DropdownMenuItem(text = { Text("Close WalkThor") }, onClick = { open = false; onClose() })
        }
    }
}

@Composable
private fun PageScrubber(current: Int, pageCount: Int, spread: Boolean, onScrubbed: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) value.roundToInt() else current
    val label = when {
        pageCount == 0 -> ""
        spread && shown + 1 < pageCount -> "pp. ${shown + 1}–${shown + 2} of $pageCount"
        else -> "p. ${shown + 1} of $pageCount"
    }
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            fontFamily = DisplayFont,
            fontSize = 13.sp,
            color = BookColors.TextMuted,
            modifier = Modifier.width(130.dp),
        )
        if (pageCount > 1) {
            Slider(
                value = if (dragging) value else current.toFloat(),
                onValueChange = { dragging = true; value = it },
                onValueChangeFinished = { dragging = false; onScrubbed(value.roundToInt()) },
                valueRange = 0f..(pageCount - 1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = BookColors.Text,
                    activeTrackColor = BookColors.Text,
                    inactiveTrackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f),
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
