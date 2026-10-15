package com.nerfherder.walkthor.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Locale

/** A game Cocoon launched, read from its launch log. */
data class LaunchedGame(
    val title: String,
    /** ROM file name, e.g. "Legend of Zelda, The - The Wind Waker (USA).rvz". */
    val file: String?,
    /** Emulator name, e.g. "Dolphin Emulator". */
    val player: String?,
    /** When it was launched (or last swapped screens), epoch millis; 0 if unknown. */
    val at: Long,
)

/**
 * Reads the most recent launch from Cocoon's `launch_debug.log`, which sits in the Cocoon data
 * folder and gets a block like this every time a game starts:
 *
 *     [2026-10-14 08:59:19.145] [----] ══════ Launch: Legend of Zelda, The - The Wind Waker ══════
 *     [2026-10-14 08:59:19.146] [INFO] Game: Legend of Zelda, The - The Wind Waker | File: …(USA).rvz
 *     [2026-10-14 08:59:19.148] [INFO] Player: Dolphin Emulator (org.dolphinemu.dolphinemu/…)
 *
 * Only the tail of the file is read. Nothing is written.
 */
object CocoonLaunchLog {
    private const val LOG_NAME = "launch_debug.log"
    private const val TAIL_BYTES = 48 * 1024L
    private val HEADER = Regex("""^\[([0-9-]+ [0-9:.]+)\] \[[^\]]*\] ═+ (Launch|Swap): (.+?) ═+\s*$""")
    private val GAME = Regex("""\] Game: (.+?) \| File: (.+)$""")
    private val PLAYER = Regex("""\] Player: (.+?) \(""")

    private const val PREFS = "cocoon"
    private const val KEY_TREE = "tree"

    fun linkedFolder(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)

    fun link(context: Context, tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, tree.toString()).apply()
    }

    fun unlink(context: Context) {
        linkedFolder(context)?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TREE).apply()
    }

    private const val KEY_HANDLED = "handled"
    private fun handledKey(game: LaunchedGame) = "${game.at}|${game.title}"

    /** Whether WalkThor already acted on this launch (so the background check leaves it alone). */
    fun isHandled(context: Context, game: LaunchedGame) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HANDLED, null) == handledKey(game)

    fun markHandled(context: Context, game: LaunchedGame) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HANDLED, handledKey(game)).apply()
    }

    /** True if [tree] looks like Cocoon's data folder (has the launch log). */
    suspend fun hasLog(context: Context, tree: Uri): Boolean = withContext(Dispatchers.IO) { findLog(context, tree) != null }

    /** The log's location, remembered so the folder isn't listed again on every check. */
    @Volatile private var cached: Pair<Uri, Uri>? = null

    suspend fun lastLaunch(context: Context, tree: Uri): LaunchedGame? = withContext(Dispatchers.IO) {
        // Fast path: read the log we found last time.
        cached?.takeIf { it.first == tree }?.let { (_, log) ->
            runCatching { return@withContext parseLast(readTail(context, log)) }
            cached = null // moved or deleted: look for it again
        }
        runCatching {
            val log = findLog(context, tree) ?: return@runCatching null
            cached = tree to log
            parseLast(readTail(context, log))
        }.getOrNull()
    }

    private fun findLog(context: Context, tree: Uri): Uri? {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1).equals(LOG_NAME, ignoreCase = true)) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        return null
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

    /** The newest Launch/Swap block in [text]. Visible for testing. */
    fun parseLast(text: String): LaunchedGame? {
        val lines = text.lines()
        val start = lines.indexOfLast { HEADER.containsMatchIn(it) }
        if (start < 0) return null
        val header = HEADER.find(lines[start]) ?: return null
        val at = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse(header.groupValues[1])?.time ?: 0L
        }.getOrDefault(0L)
        var title = header.groupValues[3].trim()
        var file: String? = null
        var player: String? = null
        for (line in lines.drop(start + 1).take(20)) {
            if (HEADER.containsMatchIn(line)) break
            GAME.find(line)?.let { title = it.groupValues[1].trim(); file = it.groupValues[2].trim() }
            PLAYER.find(line)?.let { player = it.groupValues[1].trim() }
        }
        return LaunchedGame(title, file, player, at)
    }
}

/**
 * Which guide goes with which game: links the user made (by opening a guide after swiping in
 * during a game), then fuzzy title matching.
 */
object GameGuides {
    private const val PREFS = "gameGuides"
    private const val MIN_SCORE = 0.75f
    private const val MIN_LEAD = 0.1f

    private fun key(game: LaunchedGame) = Matcher.words(game.title).joinToString(" ")

    fun remember(context: Context, game: LaunchedGame, bookId: String) {
        val k = key(game).ifEmpty { return }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(k, bookId).apply()
    }

    fun forget(context: Context, bookId: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit()
        p.all.filterValues { it == bookId }.keys.forEach { e.remove(it) }
        e.apply()
    }

    fun linkedTo(context: Context, game: LaunchedGame): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(game), null)

    /** The guide for [game]: a remembered link first, otherwise a clear title match. */
    fun pick(context: Context, books: List<Book>, game: LaunchedGame): Book? {
        linkedTo(context, game)?.let { id -> books.firstOrNull { it.id == id }?.let { return it } }
        if (books.isEmpty()) return null
        val names = listOfNotNull(game.title, game.file?.substringBeforeLast('.'))
        val ranked = books.map { b -> b to names.maxOf { Matcher.gameScore(b.title, it) } }.sortedByDescending { it.second }
        val (top, score) = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.second ?: 0f
        return top.takeIf { score >= MIN_SCORE && score - runnerUp >= MIN_LEAD }
    }
}
