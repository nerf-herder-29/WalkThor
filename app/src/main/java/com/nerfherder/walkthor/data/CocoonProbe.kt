package com.nerfherder.walkthor.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * One-off look around the Cocoon data folder to find where (and whether) Cocoon keeps its play
 * history, so "open the playing game's guide" can be built on the real format.
 *
 * Produces a plain-text report: the folder's file list (names, sizes, dates) and, for anything
 * that looks like a database or history file, its structure and newest few entries.
 * Runs only when the user asks; nothing leaves the device unless they share the report.
 */
object CocoonProbe {
    private const val MAX_FILES = 600
    private const val MAX_DEPTH = 7
    private const val MAX_REPORT = 60_000
    private val SKIP_DIRS = setOf(
        "downloaded_media", "media", "themes", "videos", "manuals", "wallpapers", "sounds", "music",
        "jingles", "fonts", "screenshots", "covers", "3dboxes", "marquees", "miximages", "fanart",
    )
    private val KEYWORDS = listOf("session", "history", "play", "recent", "launch", "log", "stat", "activity", "time", "game")
    private val TEXT_EXT = setOf("json", "xml", "txt", "csv", "lpl", "cfg", "ini", "yaml", "yml", "log")

    private class Entry(val docUri: Uri, val path: String, val size: Long, val modified: Long)

    suspend fun inspect(context: Context, tree: Uri, progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        out.appendLine("WalkThor · Cocoon data folder report")
        out.appendLine("Folder: ${tree.lastPathSegment}")
        out.appendLine("Made: ${fmt.format(Date())}")
        out.appendLine()

        val files = ArrayList<Entry>()
        val skipped = ArrayList<String>()
        progress("Listing files…")
        walk(context, tree, DocumentsContract.getTreeDocumentId(tree), "", 0, files, skipped)

        out.appendLine("== Files (${files.size}${if (files.size >= MAX_FILES) "+, truncated" else ""}), newest first ==")
        files.sortedByDescending { it.modified }.forEach {
            out.appendLine("${fmt.format(Date(it.modified))}  ${human(it.size).padStart(8)}  ${it.path}")
        }
        if (skipped.isNotEmpty()) out.appendLine("(media folders not listed: ${skipped.distinct().joinToString(", ")})")
        out.appendLine()

        val candidates = files.filter { f ->
            val name = f.path.substringAfterLast('/').lowercase()
            val ext = name.substringAfterLast('.', "")
            ext in setOf("db", "sqlite", "sqlite3", "realm", "isar", "hive") || ext.isEmpty() && f.size > 4096 ||
                (ext in TEXT_EXT && KEYWORDS.any { it in f.path.lowercase() })
        }.filterNot { it.path.endsWith("-wal") || it.path.endsWith("-shm") || it.path.endsWith("-journal") }

        out.appendLine("== Possible history files (${candidates.size}) ==")
        val cache = File(context.cacheDir, "cocoon-probe").apply { deleteRecursively(); mkdirs() }
        for ((i, f) in candidates.withIndex()) {
            coroutineContext.ensureActive()
            progress("Looking inside ${f.path.substringAfterLast('/')} (${i + 1}/${candidates.size})…")
            out.appendLine()
            out.appendLine("--- ${f.path}  (${human(f.size)}, ${fmt.format(Date(f.modified))})")
            runCatching { describe(context, f, files, cache, out) }
                .onFailure { out.appendLine("  couldn't read: ${it.javaClass.simpleName}: ${it.message}") }
            if (out.length > MAX_REPORT) {
                out.appendLine("\n(report truncated)")
                break
            }
        }
        cache.deleteRecursively()
        out.toString().take(MAX_REPORT + 200)
    }

    private suspend fun walk(
        context: Context, tree: Uri, docId: String, path: String, depth: Int,
        out: MutableList<Entry>, skipped: MutableList<String>,
    ) {
        coroutineContext.ensureActive()
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val dirs = ArrayList<Pair<String, String>>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext() && out.size < MAX_FILES) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val p = if (path.isEmpty()) name else "$path/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (name.lowercase() in SKIP_DIRS) skipped += p else dirs += id to p
                } else {
                    out += Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), p, c.getLong(3), c.getLong(4))
                }
            }
        }
        for ((id, p) in dirs) walk(context, tree, id, p, depth + 1, out, skipped)
    }

    private fun describe(context: Context, f: Entry, all: List<Entry>, cache: File, out: StringBuilder) {
        val cr = context.contentResolver
        val head = ByteArray(16)
        val n = cr.openInputStream(f.docUri)?.use { it.read(head) } ?: 0
        val isSqlite = n >= 15 && String(head, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
        if (isSqlite) {
            // Copy the database (plus its write-ahead log, where the newest rows often are).
            val name = f.path.substringAfterLast('/')
            val local = File(cache, name)
            cr.openInputStream(f.docUri)?.use { input -> local.outputStream().use { input.copyTo(it) } }
            for (suffix in listOf("-wal", "-shm")) {
                all.firstOrNull { it.path == f.path + suffix }?.let { side ->
                    cr.openInputStream(side.docUri)?.use { input -> File(cache, name + suffix).outputStream().use { input.copyTo(it) } }
                }
            }
            describeSqlite(local, out)
            return
        }
        val ext = f.path.substringAfterLast('.', "").lowercase()
        if (ext in TEXT_EXT && f.size < 4_000_000) {
            val text = cr.openInputStream(f.docUri)?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
            out.appendLine("  text, ${text.length} chars. Start:")
            out.appendLine(text.take(1500).prependIndent("  | "))
            if (text.length > 3000) {
                out.appendLine("  … end:")
                out.appendLine(text.takeLast(1200).prependIndent("  | "))
            }
            return
        }
        out.appendLine("  binary, header: ${head.take(n).joinToString(" ") { "%02x".format(it) }}")
    }

    private fun describeSqlite(file: File, out: StringBuilder) {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            val tables = ArrayList<String>()
            it.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
            out.appendLine("  SQLite database, ${tables.size} tables")
            for (t in tables) {
                if (t.startsWith("sqlite_") || t == "android_metadata") continue
                val cols = ArrayList<String>()
                it.rawQuery("PRAGMA table_info(\"${t.replace("\"", "\"\"")}\")", null).use { c ->
                    while (c.moveToNext()) cols += "${c.getString(1)}:${c.getString(2)}"
                }
                val count = runCatching {
                    it.rawQuery("SELECT COUNT(*) FROM \"${t.replace("\"", "\"\"")}\"", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
                }.getOrDefault(-1)
                out.appendLine("  • $t ($count rows): ${cols.joinToString(", ")}")
                val interesting = KEYWORDS.any { k -> k in t.lowercase() || cols.any { c -> k in c.lowercase() } }
                if (interesting && count > 0) {
                    runCatching {
                        it.rawQuery("SELECT * FROM \"${t.replace("\"", "\"\"")}\" ORDER BY rowid DESC LIMIT 5", null).use { c ->
                            while (c.moveToNext()) {
                                val row = (0 until c.columnCount).joinToString(" | ") { i ->
                                    val v = when (c.getType(i)) {
                                        android.database.Cursor.FIELD_TYPE_BLOB -> "<blob ${c.getBlob(i).size}b>"
                                        android.database.Cursor.FIELD_TYPE_NULL -> "null"
                                        else -> c.getString(i)
                                    }
                                    "${c.getColumnName(i)}=${v.take(80)}"
                                }
                                out.appendLine("      $row")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun human(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "%.1fMB".format(bytes / 1048576.0)
    }
}
