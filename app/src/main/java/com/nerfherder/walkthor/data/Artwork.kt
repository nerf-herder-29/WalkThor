package com.nerfherder.walkthor.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/** One image found in the linked artwork folder. */
data class ArtImage(
    val uri: String,
    /** File name without extension. */
    val name: String,
    /** Folder path inside the linked folder, lower-case, e.g. "media/snes/boxart". */
    val folder: String,
) {
    /** Title words of the game this image is for (computed once; matching runs over thousands). */
    val titleTokens: Set<String> by lazy { Matcher.imageTokens(this) }
}

data class ArtMatch(val image: ArtImage, val score: Float)

/** Artwork for a launched game (content URIs as strings); either may be missing. */
data class GameArt(val cover: String?, val backdrop: String?)

/**
 * Reads cover artwork from a folder the user links once (e.g. their Cocoon data folder).
 *
 * Cocoon doesn't document how it lays out scraped media, so nothing here assumes a structure:
 * every image under the folder is indexed with its name and folder path, and guides are matched
 * to images by comparing normalized titles. Folder names like "boxart" or "logo" only nudge
 * which of several matching images is preferred.
 */
class ArtworkRepository private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("artwork", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "artwork-index.json")

    private val _folder = MutableStateFlow(prefs.getString(KEY_TREE, null)?.let(Uri::parse))
    val folder: StateFlow<Uri?> = _folder.asStateFlow()

    private val _images = MutableStateFlow(loadCache())
    val images: StateFlow<List<ArtImage>> = _images.asStateFlow()

    private val _scanning = MutableStateFlow<String?>(null)
    /** Non-null while scanning: a progress message. */
    val scanning: StateFlow<String?> = _scanning.asStateFlow()

    /** Remembers the folder (with persistent read access) and indexes it. */
    suspend fun linkFolder(tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putString(KEY_TREE, tree.toString()).apply()
        _folder.value = tree
        rescan()
    }

    fun unlinkFolder() {
        _folder.value?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs.edit().remove(KEY_TREE).apply()
        _folder.value = null
        _images.value = emptyList()
        cacheFile.delete()
    }

    suspend fun rescan() = withContext(Dispatchers.IO) {
        val tree = _folder.value ?: return@withContext
        _scanning.value = "Looking for artwork…"
        try {
            val out = ArrayList<ArtImage>()
            val root = DocumentsContract.getTreeDocumentId(tree)
            walk(tree, root, "", out, 0)
            _images.value = out
            saveCache(out)
        } finally {
            _scanning.value = null
        }
    }

    private suspend fun walk(tree: Uri, docId: String, path: String, out: MutableList<ArtImage>, depth: Int) {
        coroutineContext.ensureActive()
        if (depth > MAX_DEPTH || out.size >= MAX_IMAGES) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val dirs = ArrayList<Pair<String, String>>()
        runCatching {
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // Videos and manuals hold no images worth indexing; skipping them speeds up big libraries.
                        if (!name.startsWith(".") && name.lowercase() !in SKIP_DIRS) dirs += id to name
                    } else if (name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) {
                        out += ArtImage(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                            name = name.substringBeforeLast('.'),
                            folder = path.lowercase(),
                        )
                    }
                }
            }
        }
        if (out.size % 500 < 50) _scanning.value = "Indexed ${out.size} images…"
        for ((id, name) in dirs) walk(tree, id, if (path.isEmpty()) name else "$path/$name", out, depth + 1)
    }

    /** Best artwork for a title, best first. */
    suspend fun suggestions(title: String, limit: Int = 40): List<ArtMatch> = withContext(Dispatchers.Default) {
        val want = Matcher.words(title, guide = true).toSet()
        if (want.isEmpty()) return@withContext emptyList()
        // Loose cut-off: the picker should always offer the nearest candidates to choose from.
        _images.value.asSequence()
            .map { ArtMatch(it, Matcher.score(want, it)) }
            .filter { it.score > 0.2f }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    /** The closest artwork for a title (anything that shares a real resemblance with it). */
    suspend fun autoMatch(title: String): ArtImage? =
        suggestions(title, 1).firstOrNull()?.takeIf { it.score >= AUTO_THRESHOLD }?.image

    /**
     * Artwork for a game Cocoon launched, for the "no guide yet" screen: a [GameArt.cover] (box
     * art preferred) and a wide [GameArt.backdrop] (fan art / screenshot) if the folder has them.
     * Matches against both the game's title and its ROM file name (ES-DE names media after the
     * ROM file), and only accepts close matches, since the art is shown as *this* game's.
     */
    suspend fun gameArt(title: String, file: String?): GameArt = withContext(Dispatchers.Default) {
        val names = listOfNotNull(title, file?.substringBeforeLast('.'))
            .map { Matcher.words(it).toSet() }
            .filter { it.isNotEmpty() }
        if (names.isEmpty()) return@withContext GameArt(null, null)
        var cover: Pair<ArtImage, Float>? = null
        var backdrop: Pair<ArtImage, Float>? = null
        for (img in _images.value) {
            val sim = names.maxOf { Matcher.closeness(it, img.titleTokens) }
            if (sim < GAME_ART_MIN) continue
            val where = (img.folder + "/" + img.name).lowercase()
            val coverBonus = when {
                "theme" in where || "backcover" in where || "marquee" in where || "logo" in where ||
                    "wheel" in where || "manual" in where -> null
                "cover" in where || "box" in where || "front" in where -> 0.15f
                "grid" in where || "icon" in where -> 0.1f
                "miximage" in where -> 0.05f
                "screenshot" in where || "titlescreen" in where || "fanart" in where || "hero" in where -> -0.1f
                else -> 0f
            }
            val backBonus = when {
                "fanart" in where || "hero" in where -> 0.15f
                "screenshot" in where -> 0.1f
                "titlescreen" in where -> 0.05f
                else -> null
            }
            if (coverBonus != null && (cover == null || sim + coverBonus > cover.second)) cover = img to sim + coverBonus
            if (backBonus != null && (backdrop == null || sim + backBonus > backdrop.second)) backdrop = img to sim + backBonus
        }
        GameArt(cover?.first?.uri, backdrop?.first?.uri)
    }

    /**
     * Copies an image (any content/file URI) into [dir] as a reasonably sized cover.
     * Returns the new file name.
     */
    suspend fun saveCover(source: Uri, dir: File, bookId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_MAX) sample *= 2
            val bmp = cr.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val scale = COVER_MAX.toFloat() / max(bmp.width, bmp.height)
            val sized = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
            } else bmp
            val png = sized.hasAlpha()
            // A fresh name each time so image caches never show a stale cover.
            val name = "$bookId-${System.currentTimeMillis()}.${if (png) "png" else "jpg"}"
            File(dir, name).outputStream().use {
                sized.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it)
            }
            name
        }.getOrNull()
    }

    private fun loadCache(): List<ArtImage> = runCatching {
        if (!cacheFile.exists()) return emptyList()
        val arr = JSONArray(cacheFile.readText())
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            ArtImage(o.getString("u"), o.getString("n"), o.getString("f"))
        }
    }.getOrDefault(emptyList())

    private fun saveCache(list: List<ArtImage>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.uri).put("n", it.name).put("f", it.folder)) }
        cacheFile.writeText(arr.toString())
    }

    companion object {
        private const val KEY_TREE = "tree"
        private const val MAX_DEPTH = 8
        private const val MAX_IMAGES = 60_000
        private const val COVER_MAX = 720
        const val AUTO_THRESHOLD = 0.3f
        /** How close an image's title must be to be shown as a launched game's art. */
        const val GAME_ART_MIN = 0.6f
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
        private val SKIP_DIRS = setOf("videos", "manuals", "sounds", "music", "backup", "backups")

        @Volatile private var instance: ArtworkRepository? = null
        fun get(context: Context): ArtworkRepository =
            instance ?: synchronized(this) {
                instance ?: ArtworkRepository(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Fuzzy title matching between guide names and artwork file names.
 *
 * Built for ES-DE / Cocoon style media names ("Legend of Zelda, The - A Link to the Past (USA).png"):
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

    /** File names that describe the image type rather than the game. */
    private val GENERIC = setOf(
        "box", "boxart", "box2d", "box3d", "cover", "front", "back", "logo", "wheel", "marquee",
        "hero", "grid", "screenshot", "screen", "image", "art", "artwork", "banner", "icon",
        "fanart", "thumb", "thumbnail", "title", "snap", "background", "bg", "poster",
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

    fun imageTokens(img: ArtImage): Set<String> {
        val have = words(img.name)
        // Layouts like ".../Chrono Trigger/boxart.png": the game is the folder name.
        return if (have.isEmpty() || have.all { it in GENERIC }) {
            words(img.folder.substringAfterLast('/')).toSet()
        } else have.toSet()
    }

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

    /** How well an image fits a guide title, roughly 0..1 (plus a small artwork-type preference). */
    fun score(want: Set<String>, img: ArtImage): Float {
        var s = similarity(want, img.titleTokens)
        if (s <= 0f) return 0f
        val where = (img.folder + "/" + img.name).lowercase()
        s += when {
            "theme" in where -> -0.4f
            "backcover" in where -> -0.05f
            // Square icon art suits WalkThor's square guide tiles best.
            "icon" in where -> 0.12f
            "cover" in where || "box" in where || "front" in where || "grid" in where -> 0.06f
            "miximage" in where -> 0.04f
            "hero" in where || "fanart" in where -> 0.02f
            "marquee" in where || "logo" in where || "wheel" in where -> -0.05f
            else -> 0f
        }
        return s
    }
}
