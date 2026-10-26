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

    fun dirFor(c: Context, rom: Rom): File =
        File(root(c), rom.system + "/" + rom.title.replace(Regex("""[^\w\- ]"""), "_").take(80))

    fun has(c: Context, rom: Rom): Boolean = load(c).containsKey(key(rom.title)) && dirFor(c, rom).listFiles()?.isNotEmpty() == true

    @Synchronized
    fun record(c: Context, rom: Rom, dir: File) {
        val m = load(c)
        m[key(rom.title)] = dir.relativeTo(root(c)).path
        indexFile(c).parentFile?.mkdirs()
        indexFile(c).writeText(m.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    }

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
        index = null
    }
}

/** Progress of a scrape: [done] of [total], the game being looked up, and a message when it stops. */
data class ScrapeState(val done: Int, val total: Int, val current: String?, val found: Int, val message: String? = null, val running: Boolean = true)

/**
 * Fetches artwork for your ROMs from ScreenScraper (box art, by system and ROM name) and
 * SteamGridDB (wide hero art and logos, by title). Only the game's ROM file name, its system and
 * your account details are sent, and only when you start a scrape (or a game you pick has none yet).
 */
object Scraper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<ScrapeState?>(null)
    val state: StateFlow<ScrapeState?> = _state.asStateFlow()

    private const val SS_BASE = "https://api.screenscraper.fr/api2"
    private const val SGDB_BASE = "https://www.steamgriddb.com/api/v2"
    private val REGIONS = listOf("us", "wor", "eu", "ss", "uk", "jp")

    /** Scrapes every game in your ROMs folder that has no artwork yet (or all of them, [refresh]). */
    fun scrapeAll(context: Context, refresh: Boolean = false) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            val lib = RomLibrary.get(app)
            lib.load()
            val todo = lib.roms.value.filter { refresh || !ScrapedArt.has(app, it) }
            var found = 0
            _state.value = ScrapeState(0, todo.size, null, 0)
            for ((i, rom) in todo.withIndex()) {
                if (!isActive) break
                _state.value = ScrapeState(i, todo.size, rom.title, found)
                val result = runCatching { scrape(app, rom) }
                // A login or quota problem stops the batch; anything odd about one game just skips it.
                (result.exceptionOrNull() as? ScrapeStop)?.let { e ->
                    _state.value = ScrapeState(i, todo.size, null, found, e.message ?: "Scraping stopped", running = false)
                    ArtworkRepository.get(app).forgetGameArt()
                    return@launch
                }
                if (result.getOrNull() == true) found++
                delay(350) // gentle on the services' rate limits
            }
            _state.value = ScrapeState(todo.size, todo.size, null, found, "Found artwork for $found of ${todo.size} games", running = false)
            ArtworkRepository.get(app).forgetGameArt()
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = _state.value?.copy(running = false, current = null, message = "Stopped")
    }

    fun dismiss() { if (job?.isActive != true) _state.value = null }

    /** Scrapes one game now (e.g. one you just picked). True if any artwork was found. */
    suspend fun scrapeOne(context: Context, rom: Rom): Boolean = withContext(Dispatchers.IO) {
        (runCatching { scrape(context.applicationContext, rom) }.getOrNull() ?: false)
            .also { if (it) ArtworkRepository.get(context).forgetGameArt() }
    }

    /**
     * Looks one game up and saves what it finds. Box art from ScreenScraper, else a SteamGridDB
     * grid; wide art from a SteamGridDB hero, else ScreenScraper fan art or a screenshot; logo from
     * SteamGridDB, else ScreenScraper's wheel. Throws (with a message to show) on a login or quota
     * problem, which stops a batch.
     */
    private fun scrape(c: Context, rom: Rom): Boolean {
        var cover: String? = null
        var hero: String? = null
        var logo: String? = null
        if (ScraperPrefs.hasScreenScraper(c)) {
            screenScraper(c, rom)?.let { m ->
                cover = m["box-2D"]
                hero = m["fanart"] ?: m["ss"]
                logo = m["wheel-hd"] ?: m["wheel"]
            }
        }
        if (ScraperPrefs.hasSteamGridDb(c)) {
            steamGridDb(c, rom.title)?.let { m ->
                if (cover == null) cover = m["grid"]
                m["hero"]?.let { hero = it }
                m["logo"]?.let { logo = it }
            }
        }
        if (cover == null && hero == null && logo == null) return false
        val dir = ScrapedArt.dirFor(c, rom)
        dir.mkdirs()
        var saved = 0
        listOf("cover" to cover, "hero" to hero, "logo" to logo).forEach { (name, url) ->
            if (url != null && download(url, dir, name)) saved++
        }
        if (saved == 0) return false
        ScrapedArt.record(c, rom, dir)
        return true
    }

    /** ScreenScraper's media for the game: type → URL, best region first. */
    private fun screenScraper(c: Context, rom: Rom): Map<String, String>? {
        val system = RomLibrary.system(rom.system) ?: return null
        val url = buildString {
            append("$SS_BASE/jeuInfos.php?output=json&softname=WalkThor")
            append("&devid=").append(enc(ScraperPrefs.ssDevId(c)))
            append("&devpassword=").append(enc(ScraperPrefs.ssDevPassword(c)))
            if (ScraperPrefs.ssUser(c).isNotEmpty()) {
                append("&ssid=").append(enc(ScraperPrefs.ssUser(c)))
                append("&sspassword=").append(enc(ScraperPrefs.ssPassword(c)))
            }
            append("&systemeid=").append(system.screenScraperId)
            append("&romtype=rom")
            append("&romnom=").append(enc(rom.file))
        }
        val (code, body) = get(url)
        when (code) {
            200 -> Unit
            404 -> return null // not in ScreenScraper
            400, 401, 403 -> throw ScrapeStop("ScreenScraper didn't accept the login. Check your accounts in Settings → Artwork → Scraper accounts.")
            423 -> throw ScrapeStop("ScreenScraper's API is closed right now. Try again later.")
            426 -> throw ScrapeStop("ScreenScraper needs a newer WalkThor.")
            429, 430, 431 -> throw ScrapeStop("ScreenScraper's daily limit for your account was reached. Try again tomorrow.")
            else -> return null
        }
        val medias = runCatching { JSONObject(body).getJSONObject("response").getJSONObject("jeu").getJSONArray("medias") }.getOrNull()
            ?: return null
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
        return listOf("box-2D", "fanart", "ss", "wheel-hd", "wheel").mapNotNull { t -> best(t)?.let { t to it } }.toMap()
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
