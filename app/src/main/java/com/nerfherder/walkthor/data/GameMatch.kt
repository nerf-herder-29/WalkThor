package com.nerfherder.walkthor.data

/** Picks the guide for the game named on screen, only when one guide is a clear winner. */
object GameMatch {
    private const val MIN_SCORE = 0.8f
    private const val MIN_LEAD = 0.1f

    /** [hints] are "weight|text" strings from the swipe gesture. */
    fun best(books: List<Book>, hints: List<String>): Book? {
        if (books.isEmpty() || hints.isEmpty()) return null
        val parsed = hints.mapNotNull { h ->
            val bar = h.indexOf('|')
            if (bar <= 0) null else h.substring(0, bar).toFloatOrNull()?.let { it to h.substring(bar + 1) }
        }
        if (parsed.isEmpty()) return null
        val ranked = books.map { book ->
            book to parsed.maxOf { (w, text) -> Matcher.gameScore(book.title, text) * w }
        }.sortedByDescending { it.second }
        val (top, score) = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.second ?: 0f
        return top.takeIf { score >= MIN_SCORE && score - runnerUp >= MIN_LEAD }
    }
}
