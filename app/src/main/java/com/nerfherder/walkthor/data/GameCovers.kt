package com.nerfherder.walkthor.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Art you chose for each game: its cover (library), background (Now playing, behind the tabs, the
 * splash) and marquee (its logo). What you chose wins; otherwise the Cocoon artwork folder's;
 * otherwise what WalkThor scraped. Your choices are copied into WalkThor's own storage, keyed by
 * the game's title.
 */
object GameCovers {
    /** The kinds of art you can choose, with the size they're kept at. */
    enum class Kind(val label: String, val prefix: String, val maxSize: Int) {
        COVER("Cover", "", 720),
        HERO("Background", "hero:", 1600),
        LOGO("Marquee", "logo:", 900),
    }

    private fun prefs(c: Context) = c.getSharedPreferences("gameCovers", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "gamecovers").apply { mkdirs() }
    private fun key(title: String, kind: Kind) = kind.prefix + ScrapedArt.key(title)

    /** Bumped whenever art may have changed, so the library redraws it. */
    val version = MutableStateFlow(0)
    private val cache = ConcurrentHashMap<String, String>()
    private const val NONE = ""

    fun invalidate() {
        cache.clear()
        version.value++
    }

    /** The art of [kind] you chose for [title], if any (a file URI). */
    fun user(c: Context, title: String, kind: Kind): String? =
        prefs(c).getString(key(title, kind), null)?.let { File(dir(c), it) }?.takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }

    fun userCover(c: Context, title: String): String? = user(c, title, Kind.COVER)

    /** The same for a running game, by its name or else its ROM file's (Cocoon's names can differ a little). */
    fun user(c: Context, title: String, file: String?, kind: Kind): String? =
        user(c, title, kind) ?: file?.let { user(c, RomLibrary.tidy(it.substringAfterLast('/')), kind) }

    /** Makes [source] (any image) the [kind] art of [title]. */
    suspend fun setUser(c: Context, title: String, source: Uri, kind: Kind = Kind.COVER): Boolean {
        val base = ScrapedArt.key(title).ifEmpty { return false }
        val k = key(title, kind)
        val name = ArtworkRepository.get(c).saveCover(
            source, dir(c), (kind.prefix.trimEnd(':') + "_" + base.replace(' ', '_')).trimStart('_').take(70), kind.maxSize,
        ) ?: return false
        prefs(c).getString(k, null)?.let { File(dir(c), it).delete() }
        prefs(c).edit().putString(k, name).apply()
        ArtworkRepository.get(c).forgetGameArt()
        return true
    }

    /** Back to the automatic art of [kind]. */
    fun clearUser(c: Context, title: String, kind: Kind = Kind.COVER) {
        val k = key(title, kind)
        prefs(c).getString(k, null)?.let { File(dir(c), it).delete() }
        prefs(c).edit().remove(k).apply()
        ArtworkRepository.get(c).forgetGameArt()
    }

    /** The art there is to choose from, for one kind. */
    data class Options(val user: String?, val cocoon: String?, val scraped: String?)

    /** The choices for every kind at once. */
    suspend fun allOptions(c: Context, title: String, file: String?): Map<Kind, Options> = withContext(Background.dispatcher) {
        val folder = ArtworkRepository.get(c).folderArt(title, file)
        val scraped = ScrapedArt.lookup(c, title, file)
        mapOf(
            Kind.COVER to Options(user(c, title, Kind.COVER), folder.cover, scraped?.cover),
            Kind.HERO to Options(user(c, title, Kind.HERO), folder.hero, scraped?.hero),
            Kind.LOGO to Options(user(c, title, Kind.LOGO), folder.logo, scraped?.logo),
        )
    }

    /** The cover to show for a game: yours, else Cocoon's, else scraped; null if there's none. */
    suspend fun coverFor(c: Context, title: String, file: String?): String? {
        val k = ScrapedArt.key(title)
        cache[k]?.let { return it.ifEmpty { null } }
        val found = userCover(c, title)
            ?: ArtworkRepository.get(c).folderCover(title, file)
            ?: withContext(Background.dispatcher) { ScrapedArt.lookup(c, title, file)?.cover }
        cache[k] = found ?: NONE
        return found
    }
}
