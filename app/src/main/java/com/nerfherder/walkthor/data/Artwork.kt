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
                        if (!name.startsWith(".")) dirs += id to name
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
        val want = Matcher.tokens(title)
        if (want.isEmpty()) return@withContext emptyList()
        _images.value.asSequence()
            .map { ArtMatch(it, Matcher.score(want, it)) }
            .filter { it.score > 0.34f }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    /** The match to use automatically, only if it's convincing. */
    suspend fun autoMatch(title: String): ArtImage? =
        suggestions(title, 1).firstOrNull()?.takeIf { it.score >= AUTO_THRESHOLD }?.image

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
        const val AUTO_THRESHOLD = 0.72f
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

        @Volatile private var instance: ArtworkRepository? = null
        fun get(context: Context): ArtworkRepository =
            instance ?: synchronized(this) {
                instance ?: ArtworkRepository(context.applicationContext).also { instance = it }
            }
    }
}

/** Fuzzy title matching between guide names and artwork file names. */
object Matcher {
    private val STOP = setOf(
        "the", "a", "an", "of", "and", "faq", "faqs", "walkthrough", "walkthru", "guide", "guides",
        "by", "version", "ver", "v", "txt", "pdf", "game", "complete", "official", "strategy",
        "full", "final", "edition", "rev", "usa", "us", "eu", "europe", "jp", "japan", "en",
    )
    // "final" is common in titles (Final Fantasy) — only drop it when it's "final version" style noise.
    private val KEEP_IF_ONLY = setOf("final")

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

    fun tokens(raw: String): Set<String> {
        val cleaned = raw.lowercase()
            .replace(Regex("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]"), " ")   // (USA), [!], {hack}
            .replace(Regex("['’]"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
        val words = cleaned.split(' ').filter { it.isNotBlank() }.map { ROMAN[it] ?: it }
        val kept = words.filter { it !in STOP || it in KEEP_IF_ONLY }
        // Drop trailing author handles like "by somebody" (already removed "by"); keep the rest.
        return kept.toSet()
    }

    fun imageTokens(img: ArtImage): Set<String> {
        val have = tokens(img.name)
        // Layouts like ".../Chrono Trigger/boxart.png": the game is the folder name.
        return if (have.isEmpty() || have.all { it in GENERIC }) tokens(img.folder.substringAfterLast('/')) else have
    }

    fun score(want: Set<String>, img: ArtImage): Float {
        val have = img.titleTokens
        if (have.isEmpty()) return 0f
        val inter = have.count { it in want }
        if (inter == 0) return 0f
        // Mostly about how much of the artwork name the guide covers (guide names carry extra
        // words like author names), with a little weight on covering the guide name too.
        var s = 0.75f * inter / have.size + 0.25f * inter / want.size
        val where = (img.folder + "/" + img.name).lowercase()
        s += when {
            "theme" in where -> -0.4f
            "box" in where || "cover" in where || "front" in where || "grid" in where -> 0.08f
            "hero" in where || "fanart" in where -> 0.04f
            "logo" in where || "wheel" in where || "marquee" in where -> -0.06f
            else -> 0f
        }
        return s
    }
}
