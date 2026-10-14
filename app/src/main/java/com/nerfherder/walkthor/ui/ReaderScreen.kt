package com.nerfherder.walkthor.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
private val FOOTER = 14.dp

@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    onSwapScreen: (() -> Unit)?,
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
    var zoomPage by remember { mutableStateOf<Int?>(null) }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current

    fun goTo(page: Int) {
        showResults = false
        focusManager.clearFocus()
        bookState.turnTo(vm.align(page))
    }

    // Controller shoulder buttons / D-pad turn pages.
    DisposableEffect(Unit) {
        KeyBus.readerActive = true
        onDispose { KeyBus.readerActive = false; KeyBus.textFieldFocused = false }
    }
    LaunchedEffect(step) {
        KeyBus.commands.collect { cmd ->
            if (zoomPage != null) return@collect
            bookState.turnTo(bookState.current + if (cmd == KeyBus.Command.NEXT) step else -step)
        }
    }

    BackHandler(enabled = zoomPage == null) {
        if (showResults) showResults = false else onBack()
    }

    Column(Modifier.fillMaxSize().background(BookColors.Leather)) {
        // ---- top bar: back, search, match navigation, menu --------------------------------
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Library")
            }
            SearchField(
                value = query,
                onValueChange = { vm.query.value = it; showResults = it.isNotBlank() },
                onSearch = { showResults = true },
                onFocusChanged = { focused ->
                    KeyBus.textFieldFocused = focused
                    if (focused && query.isNotBlank()) showResults = true
                },
                modifier = Modifier.weight(1f),
            )
            if (applied.isNotEmpty()) {
                val pos = vm.hitPosition(hits, bookState.current, layout)
                Text(
                    if (hits.isEmpty()) "0" else "${if (pos == 0) "–" else pos}/${hits.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
                IconButton(onClick = { vm.prevHitPage()?.let(::goTo) }, enabled = hits.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
                }
                IconButton(onClick = { vm.nextHitPage()?.let(::goTo) }, enabled = hits.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
                }
            }
            ReaderMenu(
                spread = spread,
                isTxt = isTxt,
                onToggleLayout = vm::toggleLayout,
                onSmaller = { vm.changeFontSize(bigger = false) },
                onBigger = { vm.changeFontSize(bigger = true) },
                onSwapScreen = onSwapScreen,
            )
        }

        // ---- the book ---------------------------------------------------------------------
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp)) {
            BookView(
                state = bookState,
                pageCount = pageCount,
                spread = spread,
                onPageChanged = vm::onPageChanged,
                onPageTapped = { zoomPage = it },
                onPageSize = { size ->
                    with(density) {
                        val w = size.width - (TXT_PAD_H * 2).roundToPx()
                        val h = size.height - (TXT_PAD_V * 2).roundToPx() - FOOTER.roundToPx()
                        vm.onPageGeometry(w, h)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { index ->
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
                        else if (!isTxt && src != null) PdfPage(src, index, applied)
                    }
                    Text(
                        "${index + 1}",
                        modifier = Modifier.fillMaxWidth().height(FOOTER),
                        textAlign = TextAlign.Center,
                        fontSize = 10.sp,
                        color = BookColors.InkFaint,
                    )
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
                    CircularProgressIndicator(color = BookColors.Gold)
                    Spacer(Modifier.height(8.dp))
                    Text(if (isTxt) "Laying out pages…" else "Opening…", color = BookColors.InkFaint)
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

        // ---- page position ----------------------------------------------------------------
        PageScrubber(
            current = bookState.current,
            pageCount = pageCount,
            spread = spread,
            onScrubbed = { goTo(it) },
        )
    }

    zoomPage?.let { index ->
        ZoomOverlay(onClose = { zoomPage = null }) {
            Box(Modifier.fillMaxSize().background(BookColors.Paper).padding(8.dp)) {
                val p = pagination
                val src = pdf
                if (isTxt && p != null && index < p.pageCount) {
                    TxtPage(p, index, applied, Modifier.align(Alignment.Center))
                } else if (!isTxt && src != null) {
                    PdfPage(src, index, applied, renderScale = 2.5f)
                }
            }
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
) {
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
        cursorBrush = SolidColor(BookColors.Gold),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(); focusManager.clearFocus() }),
        modifier = modifier.onFocusChanged { onFocusChanged(it.isFocused) },
        decorationBox = { inner ->
            Row(
                Modifier
                    .height(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
                    .padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            "Search this guide",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 15.sp,
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
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).heightIn(max = 280.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        if (hits.isEmpty()) {
            Text("No matches", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn {
            item {
                val total = hits.sumOf { it.count }
                Text(
                    "$total match${if (total == 1) "" else "es"} on ${hits.size} page${if (hits.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            items(hits, key = { it.page }) { hit ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(hit) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "p. ${hit.page + 1}",
                        color = BookColors.Gold,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(64.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            buildAnnotatedString {
                                append(hit.snippet.substring(0, hit.matchStart))
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = BookColors.Highlight)) {
                                    append(hit.snippet.substring(hit.matchStart, hit.matchStart + hit.matchLength))
                                }
                                append(hit.snippet.substring(hit.matchStart + hit.matchLength))
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (hit.count > 1) {
                            Text(
                                "${hit.count} matches on this page",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun ReaderMenu(
    spread: Boolean,
    isTxt: Boolean,
    onToggleLayout: () -> Unit,
    onSmaller: () -> Unit,
    onBigger: () -> Unit,
    onSwapScreen: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (spread) "Single page" else "Two-page spread") },
                onClick = { open = false; onToggleLayout() },
            )
            if (isTxt) {
                DropdownMenuItem(text = { Text("Text bigger") }, onClick = { onBigger() })
                DropdownMenuItem(text = { Text("Text smaller") }, onClick = { onSmaller() })
            }
            if (onSwapScreen != null) {
                DropdownMenuItem(text = { Text("Move to other screen") }, onClick = { open = false; onSwapScreen() })
            }
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
        spread && shown + 1 < pageCount -> "${shown + 1}–${shown + 2} of $pageCount"
        else -> "${shown + 1} of $pageCount"
    }
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        if (pageCount > 1) {
            Slider(
                value = if (dragging) value else current.toFloat(),
                onValueChange = { dragging = true; value = it },
                onValueChangeFinished = { dragging = false; onScrubbed(value.roundToInt()) },
                valueRange = 0f..(pageCount - 1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = BookColors.Gold,
                    activeTrackColor = BookColors.Gold,
                    inactiveTrackColor = BookColors.LeatherLight,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Full-screen view of one page with pinch-to-zoom, pan, and double-tap zoom. */
@Composable
private fun ZoomOverlay(onClose: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onClose)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        fun clamp(o: Offset, s: Float): Offset {
            val mx = (s - 1f) * w / 2f
            val my = (s - 1f) * h / 2f
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 6f)
            offset = clamp(offset + pan, scale)
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.05f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        },
                    )
                }
                .transformable(transform)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        ) {
            content()
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
        }
    }
}
