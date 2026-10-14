package com.nerfherder.walkthor.data

/** All matches of a query on one page. */
data class SearchHit(
    val page: Int,
    val count: Int,
    /** Text around the first match on the page, with [matchStart]/[matchLength] pointing at it. */
    val snippet: String,
    val matchStart: Int,
    val matchLength: Int,
)

object Search {
    private const val CONTEXT = 36

    fun search(pageCount: Int, query: String, pageText: (Int) -> String): List<SearchHit> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val hits = ArrayList<SearchHit>()
        for (page in 0 until pageCount) {
            val text = pageText(page)
            var at = text.indexOf(q, ignoreCase = true)
            if (at < 0) continue
            val first = at
            var count = 0
            while (at >= 0) {
                count++
                at = text.indexOf(q, at + q.length, ignoreCase = true)
            }
            hits += makeHit(page, count, text, first, q.length)
        }
        return hits
    }

    private fun makeHit(page: Int, count: Int, text: String, at: Int, len: Int): SearchHit {
        val start = (at - CONTEXT).coerceAtLeast(0)
        val end = (at + len + CONTEXT).coerceAtMost(text.length)
        val before = collapse(text.substring(start, at))
        val match = collapse(text.substring(at, at + len))
        val after = collapse(text.substring(at + len, end))
        val prefix = (if (start > 0) "…" else "") + before.trimStart()
        val suffix = after.trimEnd() + if (end < text.length) "…" else ""
        return SearchHit(page, count, prefix + match + suffix, prefix.length, match.length)
    }

    private fun collapse(s: String) = s.replace(Regex("\\s+"), " ")
}
