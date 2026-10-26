package com.nerfherder.walkthor.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Your scraper accounts (stored only on this device). ScreenScraper needs your username and
 * password and developer credentials; WalkThor builds may come with developer credentials built in,
 * otherwise enter your own. SteamGridDB needs an API key from your SteamGridDB account settings.
 */
object ScraperPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("scraper", Context.MODE_PRIVATE)

    fun ssUser(c: Context) = prefs(c).getString("ssUser", "").orEmpty()
    fun ssPassword(c: Context) = prefs(c).getString("ssPassword", "").orEmpty()
    /** Your own developer id, else the one built into this copy of WalkThor (if any). */
    fun ssDevId(c: Context) = prefs(c).getString("ssDevId", "").orEmpty().ifEmpty { com.nerfherder.walkthor.BuildConfig.SCREENSCRAPER_DEV_ID }
    fun ssDevPassword(c: Context) = prefs(c).getString("ssDevPassword", "").orEmpty().ifEmpty { com.nerfherder.walkthor.BuildConfig.SCREENSCRAPER_DEV_PASSWORD }
    fun ssDevIdOwn(c: Context) = prefs(c).getString("ssDevId", "").orEmpty()
    fun ssDevPasswordOwn(c: Context) = prefs(c).getString("ssDevPassword", "").orEmpty()
    val builtInDev: Boolean get() = com.nerfherder.walkthor.BuildConfig.SCREENSCRAPER_DEV_ID.isNotEmpty()
    fun sgdbKey(c: Context) = prefs(c).getString("sgdbKey", "").orEmpty()

    fun save(c: Context, ssUser: String, ssPassword: String, ssDevId: String, ssDevPassword: String, sgdbKey: String) {
        prefs(c).edit()
            .putString("ssUser", ssUser.trim()).putString("ssPassword", ssPassword)
            .putString("ssDevId", ssDevId.trim()).putString("ssDevPassword", ssDevPassword)
            .putString("sgdbKey", sgdbKey.trim())
            .apply()
    }

    fun hasScreenScraper(c: Context) = ssDevId(c).isNotEmpty() && ssDevPassword(c).isNotEmpty()
    fun hasSteamGridDb(c: Context) = sgdbKey(c).isNotEmpty()
    fun hasAny(c: Context) = hasScreenScraper(c) || hasSteamGridDb(c)
}

/**
 * Artwork WalkThor scraped itself, kept in its private storage: per game a cover (box art), a
 * hero (wide art) and a logo, found by the game's title.
 */
object ScrapedArt {
    private fun root(c: Context) = File(c.filesDir, "scraped")
    private fun indexFile(c: Context) = File(root(c), "index.tsv")
    @Volatile private var index: MutableMap<String, String>? = null

    fun key(title: String) = Matcher.words(title).joinToString(" ")

    private fun load(c: Context): MutableMap<String, String> = index ?: synchronized(this) {
        index ?: HashMap<String, String>().also { m ->
            runCatching { indexFile(c).readLines().forEach { l -> l.split('\t').takeIf { it.size == 2 }?.let { m[it[0]] = it[1] } } }
            index = m
        }
    }

    fun dirFor(c: Context, rom: Rom): File = dirFor(c, rom.system, rom.title)

    /** A game's folder by system (or "_" when it isn't known) and title. */
    fun dirFor(c: Context, system: String?, title: String): File =
        File(root(c), (system ?: "_") + "/" + title.replace(Regex("""[^\w\- ]"""), "_").take(80))

    fun has(c: Context, rom: Rom): Boolean = has(c, rom.title)
    fun has(c: Context, title: String): Boolean =
        load(c)[key(title)]?.let { File(root(c), it).listFiles()?.isNotEmpty() } == true

    fun record(c: Context, rom: Rom, dir: File) = record(c, rom.title, dir)

    @Synchronized
    fun record(c: Context, title: String, dir: File) {
        val m = load(c)
        m[key(title)] = dir.relativeTo(root(c)).path
        indexFile(c).parentFile?.mkdirs()
        indexFile(c).writeText(m.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    }

    /** The scraped box art file for a title, if any (for a guide's cover). */
    fun coverFile(c: Context, title: String): File? {
        val rel = load(c)[key(title)] ?: return null
        return File(root(c), rel).listFiles()?.firstOrNull { it.name.startsWith("cover.") }
    }

    private fun tried(c: Context) = c.getSharedPreferences("scrapeTried", Context.MODE_PRIVATE)

    /**
     * Whether to look [title] up now, for a game you're playing: not if it was looked up in the
     * last week (found or not), so launching the same game again never waits on the network.
     */
    fun shouldTry(c: Context, title: String): Boolean {
        val at = tried(c).getLong(key(title), 0L)
        return System.currentTimeMillis() - at > RETRY_MS
    }

    fun markTried(c: Context, title: String) { tried(c).edit().putLong(key(title), System.currentTimeMillis()).apply() }

    private const val RETRY_MS = 7L * 24 * 60 * 60 * 1000

    /** Scraped art for a game, by its title (or its ROM file's tidied name); null if there's none. */
    fun lookup(c: Context, title: String, file: String?): GameArt? {
        val m = load(c)
        val rel = listOfNotNull(title, file?.let { RomLibrary.tidy(it) })
            .map { key(it) }.firstNotNullOfOrNull { m[it] } ?: return null
        val dir = File(root(c), rel)
        fun pick(name: String) = dir.listFiles()?.firstOrNull { it.name.startsWith("$name.") }?.let { Uri.fromFile(it).toString() }
        val art = GameArt(cover = pick("cover"), logo = pick("logo"), hero = pick("hero"))
        return art.takeIf { it.cover != null || it.hero != null || it.logo != null }
    }

    fun clearAll(c: Context) {
        root(c).deleteRecursively()
        tried(c).edit().clear().apply()
        index = null
    }
}

/** Progress of a scrape: [done] of [total], the game being looked up, and a message when it stops. */
data class ScrapeState(val done: Int, val total: Int, val current: String?, val found: Int, val message: String? = null, val running: Boolean = true)

/**
 * Fetches artwork, for two places:
 *  - box art for your guides' covers on the shelf, and
 *  - a screenshot and the game's marquee (its logo) for the Now playing screen while you play.
 * ScreenScraper is asked first (by system and ROM name when the game is in your ROMs folder, else by
 * name), then SteamGridDB fills the gaps (box art, wide art and logos, by name). Only the game's
 * name (or ROM file name), its system and your account details are sent, and only when you scrape,
 * or once for a game you start that has none yet.
 */
object Scraper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<ScrapeState?>(null)
    val state: StateFlow<ScrapeState?> = _state.asStateFlow()

    private const val SS_BASE = "https://api.screenscraper.fr/api2"
    private const val SGDB_BASE = "https://www.steamgriddb.com/api/v2"
    private val REGIONS = listOf("us", "wor", "eu", "ss", "uk", "jp")

    /**
     * Scrapes your guides' covers (guides without one) and every game in your ROMs folder without
     * artwork. [refresh] looks everything up again, replacing art and covers WalkThor found itself
     * (never a cover you chose).
     */
    fun scrapeAll(context: Context, refresh: Boolean = false) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            val books = BookRepository.get(app)
            val lib = RomLibrary.get(app)
            lib.load()
            val guides = books.books.value.filter { b ->
                !b.coverManual && (books.coverFile(b) == null || (refresh && b.coverSource?.startsWith(SCRAPED) == true))
            }
            val roms = lib.roms.value.filter { refresh || !ScrapedArt.has(app, it) }
            val total = guides.size + roms.size
            var found = 0
            var done = 0
            _state.value = ScrapeState(0, total, null, 0)
            suspend fun step(name: String, work: suspend () -> Boolean): Boolean {
                if (!isActive) return false
                _state.value = ScrapeState(done, total, name, found)
                val result = runCatching { work() }
                // A login or quota problem stops the batch; anything odd about one game just skips it.
                (result.exceptionOrNull() as? ScrapeStop)?.let { e ->
                    _state.value = ScrapeState(done, total, null, found, e.message ?: "Scraping stopped", running = false)
                    ArtworkRepository.get(app).forgetGameArt()
                    return false
                }
                if (result.getOrNull() == true) found++
                done++
                delay(350) // gentle on the services' rate limits
                return true
            }
            for (b in guides) {
                val title = com.nerfherder.walkthor.ui.gameTitleOf(b.title)
                if (!step(title) { guideCover(app, b, refresh) }) return@launch
            }
            for (rom in roms) {
                if (!step(rom.title) { scrape(app, rom) }) return@launch
            }
            _state.value = ScrapeState(total, total, null, found, "Found artwork for $found of $total", running = false)
            ArtworkRepository.get(app).forgetGameArt()
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = _state.value?.copy(running = false, current = null, message = "Stopped")
    }

    fun dismiss() { if (job?.isActive != true) _state.value = null }

    /** Scrapes one game from your ROMs folder now (one you just started). True if any artwork was found. */
    suspend fun scrapeOne(context: Context, rom: Rom): Boolean = withContext(Dispatchers.IO) {
        ScrapedArt.markTried(context, rom.title)
        (runCatching { scrape(context.applicationContext, rom) }.getOrNull() ?: false)
            .also { if (it) ArtworkRepository.get(context).forgetGameArt() }
    }

    /** Scrapes a game by name (one Cocoon started that isn't in your ROMs folder). [system] if known. */
    suspend fun scrapeTitle(context: Context, title: String, system: String?): Boolean = withContext(Dispatchers.IO) {
        ScrapedArt.markTried(context, title)
        (runCatching { scrapeByName(context.applicationContext, title, system) }.getOrNull() ?: false)
            .also { if (it) ArtworkRepository.get(context).forgetGameArt() }
    }

    /** Finds box art for a guide's game and makes it the guide's cover. */
    suspend fun coverForGuide(context: Context, book: Book): Boolean = withContext(Dispatchers.IO) {
        runCatching { guideCover(context.applicationContext, book, refresh = false) }.getOrNull() ?: false
    }

    private const val SCRAPED = "scraped:"

    private suspend fun guideCover(c: Context, book: Book, refresh: Boolean): Boolean {
        val title = com.nerfherder.walkthor.ui.gameTitleOf(book.title)
        // The guide's game in your ROMs folder, if it's there: its system makes the match exact.
        val rom = RomLibrary.matchName(title, RomLibrary.get(c).roms.value)
        val have = if (rom != null) ScrapedArt.has(c, rom) else ScrapedArt.has(c, title)
        if (refresh || !have || ScrapedArt.coverFile(c, rom?.title ?: title) == null) {
            if (rom != null) scrape(c, rom) else scrapeByName(c, title, null)
        }
        val file = ScrapedArt.coverFile(c, rom?.title ?: title) ?: return false
        val repo = BookRepository.get(c)
        val fresh = repo.find(book.id) ?: return false
        if (fresh.coverManual) return false
        val name = ArtworkRepository.get(c).saveCover(Uri.fromFile(file), repo.coversDir, fresh.id) ?: return false
        repo.coverFile(fresh)?.delete()
        repo.update(fresh.copy(cover = name, coverManual = false, coverSource = SCRAPED + file.path))
        return true
    }

    /**
     * Looks one game from your ROMs folder up and saves what it finds. ScreenScraper by ROM name
     * (else by the game's name); SteamGridDB fills the gaps. Throws (with a message to show) on a
     * login or quota problem, which stops a batch.
     */
    private fun scrape(c: Context, rom: Rom): Boolean {
        val system = RomLibrary.system(rom.system)
        val ss = if (ScraperPrefs.hasScreenScraper(c)) {
            screenScraperRom(c, rom) ?: system?.let { screenScraperSearch(c, rom.title, it.screenScraperId) }
        } else null
        return save(c, rom.title, ScrapedArt.dirFor(c, rom), ss)
    }

    /** The same, by name only. */
    private fun scrapeByName(c: Context, title: String, system: String?): Boolean {
        val ss = if (ScraperPrefs.hasScreenScraper(c)) {
            screenScraperSearch(c, title, system?.let { RomLibrary.system(it)?.screenScraperId })
        } else null
        return save(c, title, ScrapedArt.dirFor(c, system, title), ss)
    }

    /**
     * Picks and downloads: box art (cover); a screenshot for the Now playing screen (hero),
     * else the title screen, wide art or fan art; and the marquee (logo).
     */
    private fun save(c: Context, title: String, dir: File, ss: Map<String, String>?): Boolean {
        val sg = if (ScraperPrefs.hasSteamGridDb(c)) steamGridDb(c, title) else null
        val cover = ss?.get("box-2D") ?: sg?.get("grid")
        val hero = ss?.get("ss") ?: ss?.get("sstitle") ?: sg?.get("hero") ?: ss?.get("fanart")
        val logo = ss?.get("wheel-hd") ?: ss?.get("wheel") ?: sg?.get("logo")
        if (cover == null && hero == null && logo == null) return false
        dir.mkdirs()
        var saved = 0
        listOf("cover" to cover, "hero" to hero, "logo" to logo).forEach { (name, url) ->
            if (url != null && download(url, dir, name)) saved++
        }
        if (saved == 0) return false
        ScrapedArt.record(c, title, dir)
        return true
    }

    private fun ssAuth(c: Context) = buildString {
        append("output=json&softname=WalkThor")
        append("&devid=").append(enc(ScraperPrefs.ssDevId(c)))
        append("&devpassword=").append(enc(ScraperPrefs.ssDevPassword(c)))
        if (ScraperPrefs.ssUser(c).isNotEmpty()) {
            append("&ssid=").append(enc(ScraperPrefs.ssUser(c)))
            append("&sspassword=").append(enc(ScraperPrefs.ssPassword(c)))
        }
    }

    /** ScreenScraper's answer, or null if the game isn't there; throws on account problems. */
    private fun ssGet(url: String): String? {
        val (code, body) = get(url)
        return when (code) {
            200 -> body
            404 -> null
            400, 401, 403 -> throw ScrapeStop("ScreenScraper didn't accept the login. Check your accounts in Settings → Artwork → Scraper accounts.")
            423 -> throw ScrapeStop("ScreenScraper's API is closed right now. Try again later.")
            426 -> throw ScrapeStop("ScreenScraper needs a newer WalkThor.")
            429, 430, 431 -> throw ScrapeStop("ScreenScraper's daily limit for your account was reached. Try again tomorrow.")
            else -> null
        }
    }

    /** ScreenScraper's media for a ROM, by system and file name: type → URL, best region first. */
    private fun screenScraperRom(c: Context, rom: Rom): Map<String, String>? {
        val system = RomLibrary.system(rom.system) ?: return null
        val body = ssGet("$SS_BASE/jeuInfos.php?${ssAuth(c)}&systemeid=${system.screenScraperId}&romtype=rom&romnom=${enc(rom.file)}") ?: return null
        val medias = runCatching { JSONObject(body).getJSONObject("response").getJSONObject("jeu").getJSONArray("medias") }.getOrNull()
            ?: return null
        return medias(medias).ifEmpty { null }
    }

    /** ScreenScraper's media for a game found by name (and system, if known), only on a close match. */
    private fun screenScraperSearch(c: Context, title: String, systemId: Int?): Map<String, String>? {
        val url = "$SS_BASE/jeuRecherche.php?${ssAuth(c)}" + (systemId?.let { "&systemeid=$it" } ?: "") + "&recherche=${enc(title)}"
        val body = ssGet(url) ?: return null
        val games = runCatching { JSONObject(body).getJSONObject("response").getJSONArray("jeux") }.getOrNull() ?: return null
        val want = Matcher.words(title).toSet()
        val best = (0 until games.length()).mapNotNull { games.optJSONObject(it) }.map { g ->
            val names = g.optJSONArray("noms")
            val score = (0 until (names?.length() ?: 0)).maxOfOrNull { i ->
                Matcher.closeness(want, Matcher.words(names!!.optJSONObject(i)?.optString("text").orEmpty()).toSet())
            } ?: 0f
            g to score
        }.maxByOrNull { it.second } ?: return null
        if (best.second < 0.75f) return null
        val medias = best.first.optJSONArray("medias") ?: return null
        return medias(medias).ifEmpty { null }
    }

    private fun medias(medias: JSONArray): Map<String, String> {
        val byType = HashMap<String, MutableList<JSONObject>>()
        for (i in 0 until medias.length()) {
            val m = medias.optJSONObject(i) ?: continue
            byType.getOrPut(m.optString("type")) { mutableListOf() }.add(m)
        }
        fun best(type: String): String? {
            val list = byType[type] ?: return null
            val ranked = list.sortedBy { m -> REGIONS.indexOf(m.optString("region")).let { if (it < 0) REGIONS.size else it } }
            return ranked.firstNotNullOfOrNull { it.optString("url").takeIf { u -> u.startsWith("http") } }
        }
        return listOf("box-2D", "ss", "sstitle", "fanart", "wheel-hd", "wheel").mapNotNull { t -> best(t)?.let { t to it } }.toMap()
    }

    /** SteamGridDB's art for the title: grid (box art), hero and logo. */
    private fun steamGridDb(c: Context, title: String): Map<String, String>? {
        val key = ScraperPrefs.sgdbKey(c)
        val (code, body) = get("$SGDB_BASE/search/autocomplete/${enc(title).replace("+", "%20")}", key)
        if (code == 401 || code == 403) throw ScrapeStop("SteamGridDB didn't accept your API key. Check it in Settings → Artwork → Scraper accounts.")
        if (code != 200) return null
        val games = runCatching { JSONObject(body).getJSONArray("data") }.getOrNull() ?: return null
        if (games.length() == 0) return null
        // Only a close match: the first result for an obscure title can be a different game.
        val top = (0 until games.length()).mapNotNull { games.optJSONObject(it) }
            .maxByOrNull { Matcher.closeness(Matcher.words(title).toSet(), Matcher.words(it.optString("name")).toSet()) }
            ?: return null
        if (Matcher.closeness(Matcher.words(title).toSet(), Matcher.words(top.optString("name")).toSet()) < 0.6f) return null
        val id = top.optInt("id")
        fun first(path: String): String? {
            val (cd, b) = get("$SGDB_BASE/$path", key)
            if (cd != 200) return null
            val data: JSONArray = runCatching { JSONObject(b).getJSONArray("data") }.getOrNull() ?: return null
            return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("url") }.firstOrNull { it.startsWith("http") }
        }
        return buildMap {
            first("grids/game/$id?dimensions=600x900&types=static")?.let { put("grid", it) }
            first("heroes/game/$id?types=static")?.let { put("hero", it) }
            first("logos/game/$id?types=static")?.let { put("logo", it) }
        }
    }

    private fun get(url: String, bearer: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "WalkThor")
            bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            val code = conn.responseCode
            val body = runCatching { (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } }
                .getOrNull().orEmpty()
            code to body
        } catch (e: java.io.IOException) {
            throw ScrapeStop("Couldn't reach the scraper (${e.javaClass.simpleName}). Check your connection.")
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, dir: File, name: String): Boolean = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "WalkThor")
            if (conn.responseCode != 200) return@runCatching false
            val type = conn.contentType.orEmpty()
            val ext = when {
                "png" in type -> "png"
                "webp" in type -> "webp"
                "jpeg" in type || "jpg" in type -> "jpg"
                else -> url.substringAfterLast('.').substringBefore('?').lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "png"
            }
            dir.listFiles()?.filter { it.name.startsWith("$name.") }?.forEach { it.delete() }
            val out = File(dir, "$name.$ext")
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 12L * 1024 * 1024) { out.delete(); return@runCatching false } // nothing art-sized is this big
                        o.write(buf, 0, n)
                    }
                }
            }
            true
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    class ScrapeStop(message: String) : Exception(message)
}
