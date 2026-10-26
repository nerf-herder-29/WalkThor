package com.nerfherder.walkthor.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Each game's cover in your library. The one you chose wins; otherwise the Cocoon artwork
 * folder's box art; otherwise box art WalkThor scraped. Your choices are copied into WalkThor's
 * own storage, keyed by the game's title.
 */
object GameCovers {
    private fun prefs(c: Context) = c.getSharedPreferences("gameCovers", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "gamecovers").apply { mkdirs() }
    private fun key(title: String) = ScrapedArt.key(title)

    /** Bumped whenever covers may have changed, so the library redraws them. */
    val version = MutableStateFlow(0)
    private val cache = ConcurrentHashMap<String, String>()
    private const val NONE = ""

    fun invalidate() {
        cache.clear()
        version.value++
    }

    /** The cover you chose for [title], if any (a file URI). */
    fun userCover(c: Context, title: String): String? =
        prefs(c).getString(key(title), null)?.let { File(dir(c), it) }?.takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }

    /** Makes [source] (any image) the cover of [title]. */
    suspend fun setUser(c: Context, title: String, source: Uri): Boolean {
        val k = key(title).ifEmpty { return false }
        val name = ArtworkRepository.get(c).saveCover(source, dir(c), k.replace(' ', '_').take(60)) ?: return false
        prefs(c).getString(k, null)?.let { File(dir(c), it).delete() }
        prefs(c).edit().putString(k, name).apply()
        ArtworkRepository.get(c).forgetGameArt()
        return true
    }

    /** Back to the automatic cover. */
    fun clearUser(c: Context, title: String) {
        val k = key(title)
        prefs(c).getString(k, null)?.let { File(dir(c), it).delete() }
        prefs(c).edit().remove(k).apply()
        ArtworkRepository.get(c).forgetGameArt()
    }

    /** The covers there are to choose from. */
    data class Options(val user: String?, val cocoon: String?, val scraped: String?)

    suspend fun options(c: Context, title: String, file: String?): Options = withContext(Background.dispatcher) {
        Options(
            user = userCover(c, title),
            cocoon = ArtworkRepository.get(c).folderCover(title, file),
            scraped = ScrapedArt.lookup(c, title, file)?.cover,
        )
    }

    /** The cover to show for a game: yours, else Cocoon's, else scraped; null if there's none. */
    suspend fun coverFor(c: Context, title: String, file: String?): String? {
        val k = key(title)
        cache[k]?.let { return it.ifEmpty { null } }
        val found = userCover(c, title)
            ?: ArtworkRepository.get(c).folderCover(title, file)
            ?: withContext(Background.dispatcher) { ScrapedArt.lookup(c, title, file)?.cover }
        cache[k] = found ?: NONE
        return found
    }
}
