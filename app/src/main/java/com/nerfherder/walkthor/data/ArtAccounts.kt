package com.nerfherder.walkthor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The art sites that need an account, for icons, heroes and logos the free collections don't
 * have (Switch games, PC games, hero pictures and logos for everything):
 *  - SteamGridDB, with your API key: square icons, heroes and logos, by the game's name (or its
 *    Steam id for PC games);
 *  - ScreenScraper, with WalkThor's developer login (or yours) and your own account if you have
 *    one: box art, fan art and wheel logos, by system and the game's file name.
 * Your keys stay on this device. (These are the same settings WalkThor used for scraping before,
 * so keys you entered then still work.)
 */
object ArtAccounts {
    private fun prefs(c: Context) = c.getSharedPreferences("scraper", Context.MODE_PRIVATE)

    fun sgdbKey(c: Context) = prefs(c).getString("sgdbKey", "").orEmpty()
    fun ssUser(c: Context) = prefs(c).getString("ssUser", "").orEmpty()
    fun ssPassword(c: Context) = prefs(c).getString("ssPassword", "").orEmpty()
    fun ssDevIdOwn(c: Context) = prefs(c).getString("ssDevId", "").orEmpty()
    fun ssDevPasswordOwn(c: Context) = prefs(c).getString("ssDevPassword", "").orEmpty()
    private fun ssDevId(c: Context) = ssDevIdOwn(c).ifEmpty { com.nerfherder.walkthor.BuildConfig.SCREENSCRAPER_DEV_ID }
    private fun ssDevPassword(c: Context) = ssDevPasswordOwn(c).ifEmpty { com.nerfherder.walkthor.BuildConfig.SCREENSCRAPER_DEV_PASSWORD }

    fun hasSteamGridDb(c: Context) = sgdbKey(c).isNotBlank()
    fun hasScreenScraper(c: Context) = ssDevId(c).isNotBlank() && ssDevPassword(c).isNotBlank()
    /** Whether this build came with a ScreenScraper developer login (so only your own account is needed). */
    val builtInScreenScraper get() = com.nerfherder.walkthor.BuildConfig.SCREENSCRAPER_DEV_ID.isNotBlank()

    fun save(c: Context, sgdbKey: String, ssUser: String, ssPassword: String, ssDevId: String, ssDevPassword: String) {
        prefs(c).edit()
            .putString("sgdbKey", sgdbKey.trim())
            .putString("ssUser", ssUser.trim())
            .putString("ssPassword", ssPassword)
            .putString("ssDevId", ssDevId.trim())
            .putString("ssDevPassword", ssDevPassword)
            .apply()
    }

    /** A site refused the account (wrong key, daily limit): stop asking it for this run. */
    class Stop(message: String) : Exception(message)

    private const val SGDB = "https://www.steamgriddb.com/api/v2"
    private const val SS = "https://api.screenscraper.fr/api2"
    private val REGIONS = listOf("us", "wor", "ss", "eu", "uk", "jp", "")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, bearer: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("User-Agent", "WalkThor")
            bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            val code = conn.responseCode
            val body = runCatching { (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } }
                .getOrNull().orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    /**
     * SteamGridDB's pictures for [rom]: kind → link. Square icons (falling back to any grid
     * picture), heroes and logos, still pictures only.
     */
    fun steamGridDb(c: Context, rom: Rom, steamId: String?, kinds: Set<GameArt.Kind>): Map<GameArt.Kind, String> {
        val key = sgdbKey(c).takeIf { it.isNotBlank() } ?: return emptyMap()
        val id: Int = run {
            if (steamId != null) {
                val (code, body) = get("$SGDB/games/steam/$steamId", key)
                if (code == 401 || code == 403) throw Stop("SteamGridDB didn't accept your API key")
                if (code == 200) runCatching { JSONObject(body).getJSONObject("data").getInt("id") }.getOrNull()?.let { return@run it }
            }
            val (code, body) = get("$SGDB/search/autocomplete/${enc(rom.title).replace("+", "%20")}", key)
            if (code == 401 || code == 403) throw Stop("SteamGridDB didn't accept your API key")
            if (code != 200) return emptyMap()
            val games = runCatching { JSONObject(body).getJSONArray("data") }.getOrNull() ?: return emptyMap()
            val want = Matcher.words(rom.title).toSet()
            // Only a close match: the first result for an unusual name can be another game.
            val best = (0 until games.length()).mapNotNull { games.optJSONObject(it) }
                .maxByOrNull { Matcher.closeness(want, Matcher.words(it.optString("name")).toSet()) } ?: return emptyMap()
            if (Matcher.closeness(want, Matcher.words(best.optString("name")).toSet()) < 0.7f) return emptyMap()
            best.optInt("id")
        }
        fun first(path: String): String? {
            val (code, body) = get("$SGDB/$path", key)
            if (code == 401 || code == 403) throw Stop("SteamGridDB didn't accept your API key")
            if (code != 200) return null
            val data: JSONArray = runCatching { JSONObject(body).getJSONArray("data") }.getOrNull() ?: return null
            return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("url") }.firstOrNull { it.startsWith("http") }
        }
        val out = HashMap<GameArt.Kind, String>()
        if (GameArt.Kind.COVER in kinds) {
            (first("grids/game/$id?dimensions=512x512,1024x1024&types=static") ?: first("icons/game/$id?types=static"))
                ?.let { out[GameArt.Kind.COVER] = it }
        }
        if (GameArt.Kind.HERO in kinds) first("heroes/game/$id?types=static")?.let { out[GameArt.Kind.HERO] = it }
        if (GameArt.Kind.LOGO in kinds) first("logos/game/$id?types=static")?.let { out[GameArt.Kind.LOGO] = it }
        return out
    }

    private fun ssAuth(c: Context) = buildString {
        append("output=json&softname=WalkThor")
        append("&devid=").append(enc(ssDevId(c)))
        append("&devpassword=").append(enc(ssDevPassword(c)))
        if (ssUser(c).isNotEmpty()) {
            append("&ssid=").append(enc(ssUser(c)))
            append("&sspassword=").append(enc(ssPassword(c)))
        }
    }

    /** ScreenScraper's pictures for [rom], by its system and file name: kind → link. */
    fun screenScraper(c: Context, rom: Rom, kinds: Set<GameArt.Kind>): Map<GameArt.Kind, String> {
        if (!hasScreenScraper(c)) return emptyMap()
        val system = RomLibrary.system(rom.system)?.takeIf { it.screenScraperId > 0 } ?: return emptyMap()
        val (code, body) = get("$SS/jeuInfos.php?${ssAuth(c)}&systemeid=${system.screenScraperId}&romtype=rom&romnom=${enc(rom.file)}")
        when (code) {
            200 -> {}
            400, 401, 403 -> throw Stop("ScreenScraper didn't accept the login")
            423 -> throw Stop("ScreenScraper's site is closed right now")
            429, 430, 431 -> throw Stop("ScreenScraper's daily limit was reached (an account of your own raises it)")
            else -> return emptyMap()
        }
        val medias = runCatching { JSONObject(body).getJSONObject("response").getJSONObject("jeu").getJSONArray("medias") }.getOrNull()
            ?: return emptyMap()
        val byType = HashMap<String, MutableList<JSONObject>>()
        for (i in 0 until medias.length()) {
            val m = medias.optJSONObject(i) ?: continue
            byType.getOrPut(m.optString("type")) { mutableListOf() }.add(m)
        }
        fun best(type: String): String? = byType[type]
            ?.sortedBy { m -> REGIONS.indexOf(m.optString("region")).let { if (it < 0) REGIONS.size else it } }
            ?.firstNotNullOfOrNull { it.optString("url").takeIf { u -> u.startsWith("http") } }
        val out = HashMap<GameArt.Kind, String>()
        if (GameArt.Kind.COVER in kinds) best("box-2D")?.let { out[GameArt.Kind.COVER] = it }
        if (GameArt.Kind.HERO in kinds) (best("fanart") ?: best("ss"))?.let { out[GameArt.Kind.HERO] = it }
        if (GameArt.Kind.LOGO in kinds) (best("wheel-hd") ?: best("wheel"))?.let { out[GameArt.Kind.LOGO] = it }
        return out
    }
}
