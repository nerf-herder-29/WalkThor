package com.nerfherder.walkthor.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads covers and screenshots for games that have none, from sources that need no account:
 *  - the libretro thumbnail collection (most cartridge and disc systems), matched by the game's
 *    file name, or failing that its title;
 *  - Steam, for PC games whose shortcut holds a Steam id.
 * Pictures are kept in WalkThor's own storage; nothing is written to your folders.
 */
object ArtScraper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _progress = MutableStateFlow<String?>(null)
    /** "Downloading 12 of 80…", or null when idle. */
    val progress: StateFlow<String?> = _progress.asStateFlow()
    private val _result = MutableStateFlow<String?>(null)
    /** What the last run did. */
    val result: StateFlow<String?> = _result.asStateFlow()

    private const val LIBRETRO = "https://thumbnails.libretro.com"

    /** WalkThor's systems → the libretro collection's names for them. */
    private val LIBRETRO_SYSTEMS = mapOf(
        "gb" to "Nintendo - Game Boy",
        "gbc" to "Nintendo - Game Boy Color",
        "gba" to "Nintendo - Game Boy Advance",
        "nds" to "Nintendo - Nintendo DS",
        "3ds" to "Nintendo - Nintendo 3DS",
        "nes" to "Nintendo - Nintendo Entertainment System",
        "snes" to "Nintendo - Super Nintendo Entertainment System",
        "n64" to "Nintendo - Nintendo 64",
        "virtualboy" to "Nintendo - Virtual Boy",
        "gc" to "Nintendo - GameCube",
        "wii" to "Nintendo - Wii",
        "wiiu" to "Nintendo - Wii U",
        "psx" to "Sony - PlayStation",
        "ps2" to "Sony - PlayStation 2",
        "psp" to "Sony - PlayStation Portable",
        "psvita" to "Sony - PlayStation Vita",
        "ps3" to "Sony - PlayStation 3",
        "dreamcast" to "Sega - Dreamcast",
        "saturn" to "Sega - Saturn",
        "genesis" to "Sega - Mega Drive - Genesis",
        "segacd" to "Sega - Mega-CD - Sega CD",
        "sega32x" to "Sega - 32X",
        "mastersystem" to "Sega - Master System - Mark III",
        "gamegear" to "Sega - Game Gear",
        "pcengine" to "NEC - PC Engine - TurboGrafx 16",
        "neogeo" to "SNK - Neo Geo",
        "ngpc" to "SNK - Neo Geo Pocket Color",
        "atari2600" to "Atari - 2600",
        "lynx" to "Atari - Lynx",
        "wonderswan" to "Bandai - WonderSwan Color",
        "xbox" to "Microsoft - Xbox",
        "arcade" to "MAME",
        "pc" to "DOS",
    )

    fun running() = job?.isActive == true

    fun stop() {
        job?.cancel()
        job = null
        _progress.value = null
    }

    /** Downloads what's missing for [roms]. Screenshots too, if [heroes]. */
    fun start(context: Context, roms: List<Rom>, heroes: Boolean) {
        if (running()) return
        val c = context.applicationContext
        job = scope.launch {
            val art = GameArt.get(c)
            art.load()
            val accounts = ArtAccounts.hasSteamGridDb(c) || ArtAccounts.hasScreenScraper(c)
            // With an account, logos too (the free collections have none).
            val kinds = buildSet {
                add(GameArt.Kind.COVER)
                if (heroes) add(GameArt.Kind.HERO)
                if (accounts) add(GameArt.Kind.LOGO)
            }
            val wanted = roms.filter { RomLibrary.isGame(it) && it.system != PinnedShortcuts.SYSTEM && kinds.any { k -> !art.has(it, k) } }
            var covers = 0
            var shots = 0
            var logos = 0
            sgdbStop = null
            ssStop = null
            var none = 0
            var done = 0
            val listings = HashMap<String, Map<String, String>?>()
            firstMiss = null
            val gate = Semaphore(4)
            try {
                wanted.chunked(16).forEach { batch ->
                    if (!isActive) return@forEach
                    batch.map { rom ->
                        async {
                            gate.withPermit {
                                // The sites with your accounts first (icons, heroes, logos), then the free ones.
                                val fromAccounts = if (accounts) runCatching { fetchAccounts(c, art, rom, kinds) }.getOrDefault(emptyMap()) else emptyMap()
                                val got = runCatching { fetch(c, art, rom, heroes, listings) }.getOrDefault(0 to 0)
                                synchronized(this@ArtScraper) {
                                    covers += got.first + (fromAccounts[GameArt.Kind.COVER] ?: 0)
                                    shots += got.second + (fromAccounts[GameArt.Kind.HERO] ?: 0)
                                    logos += fromAccounts[GameArt.Kind.LOGO] ?: 0
                                    if (got.first == 0 && !art.has(rom, GameArt.Kind.COVER)) none++
                                    done++
                                    _progress.value = "Downloading pictures: $done of ${wanted.size}…"
                                }
                            }
                        }
                    }.awaitAll()
                    art.refresh()
                }
            } finally {
                art.refresh()
                _progress.value = null
                _result.value = (when {
                    wanted.isEmpty() -> "Every game already has its pictures."
                    else -> "Downloaded $covers icons" + (if (heroes) ", $shots heroes" else "") + (if (accounts) " and $logos logos" else "") +
                        (if (none > 0) ". $none games still have no icon" + (if (!accounts) " (a SteamGridDB key finds Switch and PC games)." else ".") else ".") +
                        listOfNotNull(sgdbStop, ssStop).joinToString("") { " $it." } +
                        (if (covers + shots == 0) firstMiss?.let { " First problem: $it" } ?: "" else "")
                }).also { runCatching { com.nerfherder.walkthor.MemoryWatch.noteFrom(c, "Art download: $it") } }
            }
        }
    }

    @Volatile private var sgdbStop: String? = null
    @Volatile private var ssStop: String? = null
    /** ScreenScraper allows only so many requests at once. */
    private val ssGate = Semaphore(1)

    /** What came from SteamGridDB and ScreenScraper for [rom]: kind → how many (0 or 1). */
    private suspend fun fetchAccounts(c: Context, art: GameArt, rom: Rom, kinds: Set<GameArt.Kind>): Map<GameArt.Kind, Int> {
        val got = HashMap<GameArt.Kind, Int>()
        fun missing() = kinds.filter { !art.has(rom, it) }.toSet()
        val dir = File(art.scrapedDir(), rom.system)
        val safe = rom.file.substringBeforeLast('.').replace(Regex("""[\\/:*?"<>|]"""), "_")
        fun take(links: Map<GameArt.Kind, String>) {
            for ((kind, url) in links) {
                if (art.has(rom, kind)) continue
                val ext = url.substringAfterLast('.', "png").substringBefore('?').lowercase().takeIf { it.length in 3..4 } ?: "png"
                val f = File(dir, "${kind.name}/$safe.$ext")
                if (download(listOf(url), f)) { art.addScraped(rom, kind, f); got[kind] = 1 }
            }
            art.refresh()
        }
        if (sgdbStop == null && ArtAccounts.hasSteamGridDb(c) && missing().isNotEmpty()) {
            try {
                val steam = if (rom.system == "windows") steamId(c, rom) else null
                take(ArtAccounts.steamGridDb(c, rom, steam, missing()))
            } catch (e: ArtAccounts.Stop) { sgdbStop = e.message }
        }
        if (ssStop == null && ArtAccounts.hasScreenScraper(c) && missing().isNotEmpty()) {
            try {
                ssGate.withPermit { take(ArtAccounts.screenScraper(c, rom, missing())) }
            } catch (e: ArtAccounts.Stop) { ssStop = e.message }
        }
        return got
    }

    /** (covers, screenshots) downloaded for [rom]. */
    private suspend fun fetch(c: Context, art: GameArt, rom: Rom, heroes: Boolean, listings: HashMap<String, Map<String, String>?>): Pair<Int, Int> {
        val dir = File(art.scrapedDir(), rom.system)
        val base = rom.file.substringBeforeLast('.')
        val safe = base.replace(Regex("""[\\/:*?"<>|]"""), "_")
        var covers = 0
        var shots = 0
        if (rom.system == "windows") {
            val id = steamId(c, rom) ?: return 0 to 0
            if (!art.has(rom, GameArt.Kind.COVER)) {
                val f = File(dir, "COVER/$safe.jpg")
                if (download(listOf(
                        "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/library_600x900.jpg",
                        "https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/$id/library_600x900.jpg",
                    ), f)) { art.addScraped(rom, GameArt.Kind.COVER, f); covers++ }
            }
            if (heroes && !art.has(rom, GameArt.Kind.HERO)) {
                val f = File(dir, "HERO/$safe.jpg")
                if (download(listOf(
                        "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/library_hero.jpg",
                        "https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/$id/library_hero.jpg",
                    ), f)) { art.addScraped(rom, GameArt.Kind.HERO, f); shots++ }
            }
            return covers to shots
        }
        val system = LIBRETRO_SYSTEMS[rom.system] ?: return 0 to 0
        suspend fun one(kind: GameArt.Kind, folder: String): Boolean {
            if (art.has(rom, kind)) return false
            val f = File(dir, "${kind.name}/$safe.png")
            // Its exact name first (most ROM sets use the same names as the collection)…
            val exact = base.replace(Regex("""[&*/:`<>?\\|"]"""), "_")
            if (download(listOf("$LIBRETRO/${Uri.encode(system)}/$folder/${Uri.encode(exact)}.png"), f)) {
                art.addScraped(rom, kind, f); return true
            }
            // …then the closest title in the collection's list.
            val key = "$system/$folder"
            val list = synchronized(listings) { listings[key] } ?: listing(system, folder).also { synchronized(listings) { listings[key] = it } }
            val name = list[rom.title.lowercase()] ?: closest(rom.title, list) ?: return false
            if (download(listOf("$LIBRETRO/${Uri.encode(system)}/$folder/${Uri.encode(name)}"), f)) {
                art.addScraped(rom, kind, f); return true
            }
            return false
        }
        if (one(GameArt.Kind.COVER, "Named_Boxarts")) covers++
        if (heroes && (one(GameArt.Kind.HERO, "Named_Snaps") || one(GameArt.Kind.HERO, "Named_Titles"))) shots++
        return covers to shots
    }

    /** The collection's file whose title is clearly the game's ("Pokemon Emerald" → "Pokemon - Emerald Version"). */
    private fun closest(title: String, list: Map<String, String>): String? {
        val want = Matcher.words(title).toSet()
        if (want.isEmpty()) return null
        var best: String? = null
        var bestScore = 0f
        var next = 0f
        for ((t, file) in list) {
            val score = Matcher.closeness(want, Matcher.words(t).toSet())
            if (score > bestScore) { next = bestScore; bestScore = score; best = file }
            else if (score > next) next = score
        }
        return best.takeIf { bestScore >= 0.8f && bestScore - next >= 0.05f }
    }

    /** The collection's files for a system: tidied title → file name, preferring USA releases. */
    private fun listing(system: String, folder: String): Map<String, String> {
        val html = runCatching {
            val conn = URL("$LIBRETRO/${Uri.encode(system)}/$folder/").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", AGENT)
            conn.inputStream.use { it.readBytes().decodeToString() }
        }.onFailure { e -> if (firstMiss == null) firstMiss = "$system list → ${e.javaClass.simpleName}: ${e.message?.take(80)}" }
            .getOrNull() ?: return emptyMap()
        val out = HashMap<String, String>()
        Regex("""href="([^"]+\.png)"""").findAll(html).forEach { m ->
            val file = Uri.decode(m.groupValues[1]).substringAfterLast('/')
            val title = RomLibrary.tidy(file).lowercase()
            val had = out[title]
            if (had == null || ("(USA" in file && "(USA" !in had)) out[title] = file
        }
        return out
    }

    /** The first thing that went wrong in this run, to show if nothing came down. */
    @Volatile private var firstMiss: String? = null
    private const val AGENT = "Mozilla/5.0 (Linux; Android 13) WalkThor"

    private fun download(urls: List<String>, to: File): Boolean {
        for (url in urls) {
            val ok = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", AGENT)
                val code = conn.responseCode
                if (code != 200) {
                    if (code != 404 && firstMiss == null) firstMiss = "$url → HTTP $code"
                    conn.disconnect()
                    false
                } else {
                    to.parentFile?.mkdirs()
                    val tmp = File(to.path + ".part")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    // A picture, whatever the server calls it (PNG, JPEG, WebP or GIF).
                    val head = tmp.inputStream().use { i -> ByteArray(4).also { i.read(it) } }
                    val picture = (head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte()) ||
                        (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) ||
                        (head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte()) ||
                        (head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte())
                    if (picture && tmp.length() > 0 && tmp.renameTo(to)) true
                    else { tmp.delete(); if (firstMiss == null) firstMiss = "$url → not a picture"; false }
                }
            } catch (e: Exception) {
                if (firstMiss == null) firstMiss = "$url → ${e.javaClass.simpleName}: ${e.message?.take(80)}"
                false
            }
            if (ok) return true
        }
        return false
    }

    /** The Steam id in a PC game's shortcut file, if it has one. */
    private suspend fun steamId(c: Context, rom: Rom): String? {
        if (!rom.file.endsWith(".steam", true) && !rom.file.endsWith(".steamappid", true)) return null
        val uri = RomLibrary.get(c).document(rom) ?: return null
        val text = runCatching { c.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
        return text?.let { Regex("""\d+""").find(it)?.value }
    }
}
