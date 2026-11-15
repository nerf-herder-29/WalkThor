package com.nerfherder.walkthor.data

import android.content.Context

/** A game, as WalkThor's screens know one: from your ROMs folder, or the app in front while you play. */
data class LaunchedGame(
    val title: String,
    /** ROM file name, e.g. "Legend of Zelda, The - The Wind Waker (USA).rvz". */
    val file: String?,
    /** The system or emulator's name, e.g. "Dolphin Emulator". */
    val player: String?,
    /** When it came to the front, epoch millis; 0 if unknown. */
    val at: Long,
    /** The package of the app that's playing it, when it's the app in front. */
    val emulatorPkg: String? = null,
)

/**
 * Which guide goes with which game: links you made in your library, then fuzzy title matching.
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

    /** Takes [game]'s guide off it. A blank link stays, so a look-alike title isn't matched straight back. */
    fun unlink(context: Context, game: LaunchedGame) {
        val k = key(game).ifEmpty { return }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(k, "").apply()
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
        linkedTo(context, game)?.let { id ->
            if (id.isEmpty()) return null // unlinked on purpose
            books.firstOrNull { it.id == id }?.let { return it }
        }
        if (books.isEmpty()) return null
        val names = listOfNotNull(game.title, game.file?.substringBeforeLast('.'))
        val ranked = books.map { b -> b to names.maxOf { Matcher.gameScore(b.title, it) } }.sortedByDescending { it.second }
        val (top, score) = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.second ?: 0f
        return top.takeIf { score >= MIN_SCORE && score - runnerUp >= MIN_LEAD }
    }
}
