package com.nerfherder.walkthor.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nerfherder.walkthor.EmulatorWatch
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.LaunchedGame
import com.nerfherder.walkthor.data.Rom
import com.nerfherder.walkthor.data.RomLibrary
import com.nerfherder.walkthor.data.ScrapedArt
import java.io.File

/**
 * "What are you playing?" for a game noticed from its emulator: your recent picks in this
 * emulator, the games in your ROMs folder for its systems, and your guides, with a search box.
 */
@Composable
fun GamePickerDialog(
    game: LaunchedGame,
    books: List<Book>,
    roms: List<Rom>,
    coverFile: (Book) -> File?,
    onPickGuide: (Book) -> Unit,
    onPickRom: (Rom) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val systems = remember(game.player) { RomLibrary.systemsFor(game.player) }
    // The emulator's own systems; all games if it plays anything (RetroArch) or none match.
    val forEmulator = remember(roms, systems) {
        roms.filter { systems.isEmpty() || it.system in systems }.ifEmpty { roms }
    }
    val recent = remember(game.emulatorPkg, roms, books) {
        game.emulatorPkg?.let { EmulatorWatch.recentEntries(context, it) }.orEmpty().mapNotNull { e ->
            if (e.startsWith("r:")) roms.firstOrNull { it.id == e.drop(2) }?.let { Choice.Game(it) }
            else books.firstOrNull { it.id == e.drop(2) }?.let { Choice.Guide(it) }
        }
    }
    val q = query.trim()
    val games = if (q.isEmpty()) forEmulator else roms.filter { it.title.contains(q, ignoreCase = true) }
    val guides = if (q.isEmpty()) books else books.filter { it.title.contains(q, ignoreCase = true) }
    val systemNames = remember(systems) {
        systems.mapNotNull { RomLibrary.system(it)?.name?.removePrefix("Nintendo ")?.removePrefix("Sony ") }.joinToString(" & ")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BookColors.Surface,
        title = {
            Column {
                Text(if (game.unknown) "What are you playing?" else "Which game is this?", fontFamily = DisplayFont)
                game.player?.let { Text("In ${emulatorName(it)}", style = EyebrowStyle, modifier = Modifier.padding(top = 2.dp)) }
            }
        },
        text = {
            Column {
                if (roms.size + books.size > 8) {
                    SearchBox(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp), hint = "Search games and guides")
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (q.isEmpty() && recent.isNotEmpty()) {
                        item { Section("Recent") }
                        items(recent, key = { "recent:" + it.key }) { c -> ChoiceRow(c, coverFile, onPickGuide, onPickRom) }
                    }
                    if (games.isNotEmpty()) {
                        item {
                            Section(
                                when {
                                    q.isNotEmpty() -> "Games"
                                    systemNames.isNotEmpty() && forEmulator !== roms -> "Your $systemNames games"
                                    else -> "Your games"
                                }
                            )
                        }
                        items(games, key = { "rom:" + it.id }) { r -> ChoiceRow(Choice.Game(r), coverFile, onPickGuide, onPickRom) }
                    }
                    if (guides.isNotEmpty()) {
                        item { Section("Guides") }
                        items(guides, key = { "guide:" + it.id }) { b -> ChoiceRow(Choice.Guide(b), coverFile, onPickGuide, onPickRom) }
                    }
                    if (games.isEmpty() && guides.isEmpty()) {
                        item {
                            Text(
                                if (q.isNotEmpty()) "Nothing matches “$q”." else
                                    "Link your ROMs folder (⋮ → Link ROMs folder…) or add a guide to choose from.",
                                color = BookColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private sealed class Choice {
    abstract val key: String
    data class Game(val rom: Rom) : Choice() { override val key get() = rom.id }
    data class Guide(val book: Book) : Choice() { override val key get() = book.id }
}

@Composable
private fun Section(text: String) {
    Text(text, style = EyebrowStyle, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

@Composable
private fun ChoiceRow(c: Choice, coverFile: (Book) -> File?, onPickGuide: (Book) -> Unit, onPickRom: (Rom) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { when (c) { is Choice.Game -> onPickRom(c.rom); is Choice.Guide -> onPickGuide(c.book) } }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (c) {
            is Choice.Guide -> GuideTile(c.book, remember(c.book.id, c.book.cover) { coverFile(c.book) }, Modifier.size(44.dp))
            is Choice.Game -> {
                val cover = remember(c.rom.id) { ScrapedArt.lookup(context, c.rom.title, c.rom.file)?.cover }
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(BookColors.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cover != null) {
                        AsyncImage(Uri.parse(cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp))
                    } else {
                        Text(c.rom.system.uppercase().take(4), color = BookColors.TextMuted, fontSize = 10.sp)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                when (c) { is Choice.Game -> displayTitle(c.rom.title); is Choice.Guide -> c.book.title },
                color = BookColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (c) {
                    is Choice.Game -> RomLibrary.system(c.rom.system)?.name ?: c.rom.system
                    is Choice.Guide -> "Guide"
                },
                color = BookColors.TextMuted, fontSize = 11.sp,
            )
        }
    }
}
