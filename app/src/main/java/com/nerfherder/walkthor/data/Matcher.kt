package com.nerfherder.walkthor.data

/**
 * Fuzzy title matching between guide names and game names.
 *
 * Built for the usual ROM names ("Legend of Zelda, The - A Link to the Past (USA)"):
 * accents are folded, bracketed tags dropped, Roman numerals read as numbers, and words match
 * loosely (prefixes, one-letter typos). A character-bigram similarity of the squashed titles
 * catches split/joined words ("Spiderman" vs "Spider-Man").
 */
object Matcher {
    private val STOP = setOf(
        "the", "a", "an", "of", "and", "for", "by", "faq", "faqs", "walkthrough", "walkthru", "guide",
        "guides", "version", "ver", "v", "txt", "pdf", "game", "complete", "official", "strategy", "full",
        "edition", "rev", "usa", "us", "eu", "europe", "jp", "japan", "en", "world", "unl", "beta",
        "proto", "demo", "prima", "bradygames", "gamefaqs",
    )

    private val ROMAN = mapOf(
        "ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6", "vii" to "7",
        "viii" to "8", "ix" to "9", "xi" to "11", "xii" to "12", "xiii" to "13",
    )

    private val BRACKETS = Regex("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]")
    private val APOSTROPHES = Regex("['’`]")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val MARKS = Regex("\\p{Mn}+")

    /**
     * Significant words of a title. For guide titles ([guide] = true) everything from "by" on is
     * dropped, since GameFAQs-style names end with the author ("… FAQ by SomeGuy").
     */
    fun words(raw: String, guide: Boolean = false): List<String> {
        val folded = MARKS.replace(java.text.Normalizer.normalize(raw.lowercase(), java.text.Normalizer.Form.NFD), "")
        val cleaned = NON_ALNUM.replace(APOSTROPHES.replace(BRACKETS.replace(folded, " ").replace("&", " and "), ""), " ")
        var w = cleaned.split(' ').filter { it.isNotEmpty() }.map { ROMAN[it] ?: it }
        if (guide) {
            val by = w.indexOf("by")
            if (by >= 1) w = w.subList(0, by)
        }
        return w.filter { it !in STOP }
    }

    fun tokens(raw: String): Set<String> = words(raw).toSet()

    private fun oneEditApart(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < minOf(a.length, b.length) && a[i] == b[i]) i++
        return a.drop(i + 1) == b.drop(i + 1) || a.drop(i + 1) == b.drop(i) || a.drop(i) == b.drop(i + 1)
    }

    private fun wordsMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val m = minOf(a.length, b.length)
        if (m >= 4 && (a.startsWith(b) || b.startsWith(a))) return true
        return m >= 5 && oneEditApart(a, b)
    }

    private fun dice(a: String, b: String): Float {
        if (a.length < 2 || b.length < 2) return 0f
        val counts = HashMap<String, Int>()
        for (i in 0 until a.length - 1) { val g = a.substring(i, i + 2); counts[g] = (counts[g] ?: 0) + 1 }
        var inter = 0
        for (i in 0 until b.length - 1) {
            val g = b.substring(i, i + 2)
            val c = counts[g] ?: 0
            if (c > 0) { inter++; counts[g] = c - 1 }
        }
        return 2f * inter / ((a.length - 1) + (b.length - 1))
    }

    /** Title similarity of two word sets, roughly 0..1, weighted toward covering [have]. */
    fun similarity(want: Set<String>, have: Set<String>): Float {
        if (want.isEmpty() || have.isEmpty()) return 0f
        val matchedHave = have.count { h -> want.any { wordsMatch(h, it) } }
        val matchedWant = want.count { w -> have.any { wordsMatch(w, it) } }
        val wordScore = 0.7f * matchedHave / have.size + 0.3f * matchedWant / want.size
        val squashed = dice(want.sorted().joinToString(""), have.sorted().joinToString(""))
            .coerceAtLeast(dice(want.joinToString(""), have.joinToString("")))
        return maxOf(wordScore, squashed)
    }

    /**
     * Two-way closeness, 0..1: both names must be mostly covered by the other, so "Zelda" alone
     * doesn't count as art for "Legend of Zelda, The - The Wind Waker".
     */
    fun closeness(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val inB = a.count { x -> b.any { wordsMatch(x, it) } }.toFloat() / a.size
        val inA = b.count { y -> a.any { wordsMatch(y, it) } }.toFloat() / b.size
        return minOf(inA, inB) * 0.6f + maxOf(inA, inB) * 0.4f
    }

    /** Guide-title words that say nothing about which game it is ("100% walkthrough"). */
    private val GUIDE_NOISE = setOf("100", "percent", "completion", "completionist", "trophy", "trophies", "achievements", "achievement", "faqwalkthrough")

    /**
     * How well a game's name fits a guide title, 0..1. Guides usually name only part of the game
     * ("Wind Waker Walkthrough" for "Legend of Zelda, The - The Wind Waker"), so what counts is
     * how fully the shorter of the two is contained in the other. At least two words must match,
     * so a one-word guide like "Zelda guide" never claims every Zelda game.
     */
    fun gameScore(guideTitle: String, gameName: String): Float {
        val guide = words(guideTitle, guide = true).filterNot { it in GUIDE_NOISE }.toSet()
        val game = words(gameName).toSet()
        if (game.isEmpty() || guide.isEmpty()) return 0f
        val matchedGuide = guide.count { g -> game.any { wordsMatch(g, it) } }
        val matchedGame = game.count { g -> guide.any { wordsMatch(g, it) } }
        if (minOf(matchedGuide, matchedGame) < 2 && !(guide.size == 1 && game.size == 1 && matchedGuide == 1)) return 0f
        val a = matchedGuide.toFloat() / guide.size
        val b = matchedGame.toFloat() / game.size
        return maxOf(a, b) * 0.7f + minOf(a, b) * 0.3f
    }
}
