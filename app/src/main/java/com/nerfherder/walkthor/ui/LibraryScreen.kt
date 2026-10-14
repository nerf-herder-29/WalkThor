package com.nerfherder.walkthor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerfherder.walkthor.ImportState
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.BookType

private val coverColors = listOf(
    Color(0xFF7A2E2E), Color(0xFF2E4A7A), Color(0xFF2E6A4A), Color(0xFF6A4A2E),
    Color(0xFF5A2E6A), Color(0xFF2E5E6A), Color(0xFF7A5A1E), Color(0xFF4A4A4A),
)

@Composable
fun LibraryScreen(
    books: List<Book>,
    onOpen: (Book) -> Unit,
    onImport: () -> Unit,
    onDelete: (Book) -> Unit,
    onRename: (Book, String) -> Unit,
    onSwapScreen: (() -> Unit)?,
) {
    Column(Modifier.fillMaxSize().background(BookColors.Leather)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "WalkThor",
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = BookColors.Gold,
            )
            Spacer(Modifier.weight(1f))
            if (onSwapScreen != null) {
                TextButton(onClick = onSwapScreen) { Text("Other screen") }
            }
            Button(onClick = onImport) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Import guide")
            }
        }

        if (books.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Your shelf is empty",
                    fontFamily = FontFamily.Serif,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Import a PDF or TXT walkthrough and it becomes a searchable book.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onImport) { Text("Import guide") }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 128.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(books, key = { it.id }) { book ->
                    BookCover(book, onOpen = { onOpen(book) }, onDelete = { onDelete(book) }, onRename = { onRename(book, it) })
                }
            }
        }
    }
}

@Composable
private fun BookCover(book: Book, onOpen: () -> Unit, onDelete: () -> Unit, onRename: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val color = coverColors[(book.title.hashCode() and Int.MAX_VALUE) % coverColors.size]

    Box(
        Modifier
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp, topStart = 3.dp, bottomStart = 3.dp))
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.75f), color, color.copy(alpha = 0.9f))))
            .clickable(onClick = onOpen)
    ) {
        // spine
        Box(Modifier.fillMaxHeight().width(10.dp).background(Color.Black.copy(alpha = 0.25f)))
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)) {
            Text(
                book.title,
                color = Color(0xFFF6E7C1),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 18.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                buildString {
                    append(if (book.type == BookType.PDF) "PDF" else "TXT")
                    if (book.pageCount > 0) append(" · p. ${book.lastPage + 1}/${book.pageCount}")
                },
                color = Color(0xFFF6E7C1).copy(alpha = 0.75f),
                fontSize = 11.sp,
            )
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = Color(0xFFF6E7C1))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(text = { Text("Remove from shelf") }, onClick = { menu = false; confirmDelete = true })
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(book.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename guide") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { renaming = false; onRename(text) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove “${book.title}”?") },
            text = { Text("WalkThor's copy of this guide will be deleted. Your original file isn't touched.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ImportDialogs(state: ImportState?, onCancel: () -> Unit, onDismissError: () -> Unit) {
    when (state) {
        is ImportState.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Adding guide") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = BookColors.Gold)
                    Spacer(Modifier.width(16.dp))
                    Text(state.message)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("Couldn't add guide") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismissError) { Text("OK") } },
        )
        null -> Unit
    }
}
