package com.nerfherder.walkthor.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Reads the most recent game from ES-DE's own log, `ES-DE/logs/es_log.txt`. With ES-DE's
 * "Custom Event Scripts" and "Debug Mode" on, every launch and every return is logged like this:
 *
 *     Jul 28 15:16:07 Debug:  Scripting::fireEvent(): game-start "/storage/…/ROMs/gc/Game (USA).rvz" "Game" "gc" "Nintendo GameCube"
 *     Jul 28 16:02:41 Debug:  Scripting::fireEvent(): game-end "/storage/…/ROMs/gc/Game (USA).rvz" "Game" "gc" "Nintendo GameCube"
 *
 * Only the end of the file is read. Nothing is written.
 */
object EsdeLog {
    private const val LOG_NAME = "es_log.txt"
    private const val TAIL_BYTES = 256 * 1024L
    private val EVENT = Regex("""Scripting::fireEvent\(\): (game-start|game-end) "([^"]*)" "([^"]*)" "([^"]*)" "([^"]*)"""")
    private val STAMP = Regex("""^([A-Z][a-z]{2} +\d{1,2} \d{2}:\d{2}:\d{2})""")

    private const val PREFS = "esde"
    private const val KEY_TREE = "tree"

    fun linkedFolder(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)

    fun link(context: Context, tree: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, tree.toString()).apply()
        cached = null
    }

    fun unlink(context: Context) {
        linkedFolder(context)?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TREE).apply()
        cached = null
    }

    /** True if [tree] is ES-DE's folder (or its logs folder) with the log in it. */
    suspend fun hasLog(context: Context, tree: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { findLog(context, tree) != null }.getOrDefault(false)
    }

    @Volatile private var cached: Pair<Uri, Uri>? = null
    @Volatile private var lastStamp: Pair<Long, Long>? = null
    @Volatile private var lastResult: LaunchedGame? = null

    private fun stamp(context: Context, log: Uri): Pair<Long, Long>? =
        context.contentResolver.query(
            log,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }

    /** The newest game ES-DE started (marked [LaunchedGame.ended] once you're back in ES-DE). */
    suspend fun lastLaunch(context: Context, tree: Uri): LaunchedGame? = withContext(Dispatchers.IO) {
        cached?.takeIf { it.first == tree }?.let { (_, log) ->
            val stamp = runCatching { stamp(context, log) }.getOrNull()
            if (stamp != null) {
                if (stamp == lastStamp) return@withContext lastResult
                runCatching {
                    val result = parseLast(readTail(context, log))
                    lastStamp = stamp
                    lastResult = result
                    return@withContext result
                }
            }
            cached = null
        }
        runCatching {
            val log = findLog(context, tree) ?: return@runCatching null
            cached = tree to log
            val result = parseLast(readTail(context, log))
            lastStamp = runCatching { stamp(context, log) }.getOrNull()
            lastResult = result
            result
        }.getOrNull()
    }

    /** es_log.txt in [tree], or in its logs folder. */
    private fun findLog(context: Context, tree: Uri): Uri? {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val top = children(context, tree, root)
        top.firstOrNull { !it.third && it.second.equals(LOG_NAME, true) }?.let { return DocumentsContract.buildDocumentUriUsingTree(tree, it.first) }
        val logs = top.firstOrNull { it.third && it.second.equals("logs", true) } ?: return null
        return children(context, tree, logs.first).firstOrNull { !it.third && it.second.equals(LOG_NAME, true) }
            ?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.first) }
    }

    private fun children(context: Context, tree: Uri, docId: String): List<Triple<String, String, Boolean>> {
        val out = ArrayList<Triple<String, String, Boolean>>()
        context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(Triple(c.getString(0), c.getString(1) ?: continue, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
            }
        }
        return out
    }

    private fun readTail(context: Context, uri: Uri): String {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                val size = pfd.statSize
                if (size > TAIL_BYTES) input.channel.position(size - TAIL_BYTES)
                return input.readBytes().toString(Charsets.UTF_8)
            }
        }
        return ""
    }

    /** The newest game-start or game-end in [text]. Visible for testing. */
    fun parseLast(text: String, now: Long = System.currentTimeMillis()): LaunchedGame? {
        val line = text.lines().lastOrNull { EVENT.containsMatchIn(it) } ?: return null
        val m = EVENT.find(line) ?: return null
        val (event, path, name, _, systemName) = m.destructured
        val file = path.substringAfterLast('/').ifEmpty { null }
        val title = name.ifBlank { file?.let { RomLibrary.tidy(it) } ?: return null }
        return LaunchedGame(title, file, systemName.ifBlank { null }, timeOf(line, now), ended = event == "game-end")
    }

    /** ES-DE's log times have no year: this year's, or last year's if that would be in the future. */
    private fun timeOf(line: String, now: Long): Long {
        val s = STAMP.find(line)?.groupValues?.get(1) ?: return 0L
        return runCatching {
            val parsed = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).parse(s.replace(Regex(" +"), " ")) ?: return 0L
            val cal = Calendar.getInstance().apply { time = parsed }
            val year = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
            cal.set(Calendar.YEAR, year)
            if (cal.timeInMillis > now + 60_000L) cal.set(Calendar.YEAR, year - 1)
            cal.timeInMillis
        }.getOrDefault(0L)
    }
}

/** Whichever frontend log is linked (Cocoon's, ES-DE's, or both). */
object LaunchLogs {
    fun anyLinked(context: Context) = CocoonLaunchLog.linkedFolder(context) != null || EsdeLog.linkedFolder(context) != null

    /** The newest launch across the linked frontends. */
    suspend fun lastLaunch(context: Context): LaunchedGame? {
        val cocoon = CocoonLaunchLog.linkedFolder(context)?.let { CocoonLaunchLog.lastLaunch(context, it) }
        val esde = EsdeLog.linkedFolder(context)?.let { EsdeLog.lastLaunch(context, it) }
        return listOfNotNull(cocoon, esde).maxByOrNull { it.at }
    }
}
