package com.nerfherder.walkthor.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** A game system: its usual ROM folder names, its ScreenScraper id, and the emulators that play it. */
data class GameSystem(
    val key: String,
    val name: String,
    /** ScreenScraper's system id (systemeid). */
    val screenScraperId: Int,
    val folders: Set<String>,
    /** Words in an emulator's app name that mean it plays this system. */
    val emulators: Set<String> = emptySet(),
)

/** A game in your ROMs folder. [title] is the file name tidied up ("Name (USA).iso" → "Name"). */
data class Rom(val system: String, val file: String, val title: String) {
    /** Stable id, e.g. "gba/Golden Sun (USA).gba". */
    val id: String get() = "$system/$file"
}

/**
 * Your ROMs folder, for picking which game you're playing (without Cocoon) and for scraping its
 * artwork. Link the folder that holds one folder per system, named the usual way (3ds, nds, gba,
 * switch, psp, ps2, gc, windows, pc…, as ES-DE and most frontends do). Inside each, every file is
 * a game, and so is every folder (a PC game's folder, a game kept with its updates…), except
 * folders that are plainly media or saves. WalkThor only reads the names, never the games
 * themselves, and keeps the list on the device.
 */
class RomLibrary private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("roms", Context.MODE_PRIVATE)
    private val indexFile = File(context.filesDir, "roms.tsv")

    private val _folder = MutableStateFlow(prefs.getString(KEY_TREE, null)?.let(Uri::parse))
    val folder: StateFlow<Uri?> = _folder.asStateFlow()
    private val _roms = MutableStateFlow<List<Rom>>(emptyList())
    val roms: StateFlow<List<Rom>> = _roms.asStateFlow()
    private val _scanning = MutableStateFlow<String?>(null)
    /** "Reading gba…" while the folder is being read. */
    val scanning: StateFlow<String?> = _scanning.asStateFlow()
    private var loaded = false

    /** The list from the last scan (read from disk the first time). */
    suspend fun load() = withContext(Background.dispatcher) {
        if (loaded) return@withContext
        loaded = true
        _roms.value = runCatching {
            indexFile.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size == 3) Rom(p[0], p[1], p[2]) else null
            }
        }.getOrDefault(emptyList())
    }

    suspend fun link(tree: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        _folder.value?.takeIf { it != tree }?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs.edit().putString(KEY_TREE, tree.toString()).apply()
        _folder.value = tree
        scan()
    }

    fun unlink() {
        _folder.value?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs.edit().remove(KEY_TREE).apply()
        _folder.value = null
        _roms.value = emptyList()
        indexFile.delete()
    }

    /** Reads the folder again: one folder per system, and the games inside each. */
    suspend fun scan() = withContext(Background.dispatcher) {
        val tree = _folder.value ?: return@withContext
        val found = ArrayList<Rom>()
        try {
            val root = DocumentsContract.getTreeDocumentId(tree)
            for ((id, name, isDir) in children(tree, root)) {
                if (!isDir) continue
                val system = systemForFolder(name) ?: continue
                _scanning.value = "Reading $name…"
                val entries = children(tree, id)
                // Windows games: each .pcgame or .steam shortcut is a game (in the folder, or one folder down).
                // Only when there are none do the folders inside count as games, as before.
                if (system.key == "windows") {
                    val shortcuts = pcGames(tree, entries)
                    if (shortcuts.isNotEmpty()) {
                        val seenPc = HashSet<String>()
                        shortcuts.forEach { f ->
                            val title = tidy(f)
                            if (title.isNotEmpty() && seenPc.add(title.lowercase())) found.add(Rom(system.key, f, title))
                        }
                        continue
                    }
                }
                val names = entries.filter { !it.third }.map { it.second }.toSet()
                val seen = HashSet<String>()
                for ((_, file, dir) in entries) {
                    if (file.startsWith(".")) continue
                    if (dir) {
                        // A game kept in its own folder (some systems do this); skip media folders.
                        if (file.lowercase() in NOT_GAMES) continue
                    } else {
                        val ext = file.substringAfterLast('.', "").lowercase()
                        if (ext in NOT_ROMS) continue
                        // Disc images: the .cue stands for its .bin tracks.
                        if (ext == "bin" && names.any { it.equals(file.substringBeforeLast('.') + ".cue", true) }) continue
                    }
                    val title = tidy(file, dir)
                    if (title.isEmpty() || !seen.add(title.lowercase())) continue
                    found.add(Rom(system.key, file, title))
                }
            }
            found.sortWith(compareBy({ it.system }, { it.title.lowercase() }))
            indexFile.writeText(found.joinToString("\n") { "${it.system}\t${it.file}\t${it.title}" })
            _roms.value = found
            loaded = true
        } catch (_: Exception) {
            // Folder moved or access lost: keep what we had.
        } finally {
            _scanning.value = null
        }
    }

    /** The .pcgame and .steam shortcuts in a Windows folder and the folders just inside it. */
    private fun pcGames(tree: Uri, entries: List<Triple<String, String, Boolean>>): List<String> {
        fun isPc(name: String) = !name.startsWith(".") &&
            PC_SHORTCUTS.any { name.endsWith(it, ignoreCase = true) }
        val out = entries.filter { !it.third && isPc(it.second) }.map { it.second }.toMutableList()
        entries.filter { it.third && !it.second.startsWith(".") && it.second.lowercase() !in NOT_GAMES }.forEach { (id, _, _) ->
            runCatching { children(tree, id) }.getOrNull()?.filter { !it.third && isPc(it.second) }?.forEach { out.add(it.second) }
        }
        return out
    }

    private fun children(tree: Uri, docId: String): List<Triple<String, String, Boolean>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = ArrayList<Triple<String, String, Boolean>>()
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(Triple(c.getString(0), c.getString(1) ?: continue, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
            }
        }
        return out
    }

    companion object {
        private const val KEY_TREE = "tree"
        @Volatile private var instance: RomLibrary? = null
        fun get(context: Context): RomLibrary =
            instance ?: synchronized(this) { instance ?: RomLibrary(context.applicationContext).also { instance = it } }

        /** Shortcut files that stand for one Windows game each (GameNative, Winlator and the like). */
        private val PC_SHORTCUTS = listOf(".pcgame", ".steam")

        private val NOT_ROMS = setOf(
            "txt", "nfo", "xml", "json", "dat", "db", "ini", "cfg", "log", "md", "pdf", "html",
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "mp4", "mkv", "avi", "webm", "mp3", "ogg", "wav",
            "sav", "srm", "state", "sta", "ss0", "ss1", "rtc", "eep", "fla", "mcr", "mcd", "dsv",
        )
        private val NOT_GAMES = setOf(
            "media", "images", "image", "videos", "video", "manuals", "manual", "downloaded_media", "covers",
            "screenshots", "boxart", "box2dfront", "snap", "snaps", "titles", "marquees", "wheel", "saves", "states",
        )

        /** "Golden Sun (USA, Europe) [!].gba" → "Golden Sun". */
        fun tidy(file: String, isDir: Boolean = false): String {
            val base = if (isDir) file else file.substringBeforeLast('.')
            return base
                .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
                .replace('_', ' ')
                .replace(Regex("""\s+"""), " ")
                .trim()
                .trimEnd('-', ' ')
        }

        val SYSTEMS = listOf(
            GameSystem("switch", "Nintendo Switch", 225, setOf("switch", "nsw", "ns"), setOf("eden", "citron", "yuzu", "sudachi", "suyu", "ryujinx", "torzu", "skyline", "strato")),
            GameSystem("3ds", "Nintendo 3DS", 17, setOf("3ds", "n3ds", "nintendo3ds"), setOf("azahar", "citra", "lime3ds", "borked3ds", "mandarine")),
            GameSystem("nds", "Nintendo DS", 15, setOf("nds", "ds", "nintendods"), setOf("melonds", "drastic", "desmume", "nooDS".lowercase())),
            GameSystem("wiiu", "Nintendo Wii U", 18, setOf("wiiu"), setOf("cemu")),
            GameSystem("wii", "Nintendo Wii", 16, setOf("wii"), setOf("dolphin")),
            GameSystem("gc", "Nintendo GameCube", 13, setOf("gc", "gamecube", "ngc"), setOf("dolphin")),
            GameSystem("n64", "Nintendo 64", 14, setOf("n64", "nintendo64"), setOf("mupen", "m64plus", "rosalie")),
            GameSystem("gba", "Game Boy Advance", 12, setOf("gba", "gameboyadvance"), setOf("mgba", "my boy", "john gba", "pizza boy gba")),
            GameSystem("gbc", "Game Boy Color", 10, setOf("gbc", "gameboycolor"), setOf("mgba", "gearboy", "pizza boy", "my oldboy", "john gbc")),
            GameSystem("gb", "Game Boy", 9, setOf("gb", "gameboy"), setOf("mgba", "gearboy", "pizza boy", "my oldboy", "john gbc")),
            GameSystem("snes", "Super Nintendo", 4, setOf("snes", "sfc", "superfamicom", "supernintendo"), setOf("snes9x", "bsnes")),
            GameSystem("nes", "Nintendo (NES)", 3, setOf("nes", "famicom", "fc"), setOf("nestopia", "fceumm", "nes.emu")),
            GameSystem("virtualboy", "Virtual Boy", 11, setOf("virtualboy", "vb")),
            GameSystem("ps2", "PlayStation 2", 58, setOf("ps2", "playstation2"), setOf("aethersx2", "nethersx2", "pcsx2", "armsx2")),
            GameSystem("psx", "PlayStation", 57, setOf("psx", "ps1", "playstation", "psone"), setOf("duckstation", "swanstation", "epsxe", "fpse", "pcsx")),
            GameSystem("psp", "PlayStation Portable", 61, setOf("psp"), setOf("ppsspp")),
            GameSystem("psvita", "PlayStation Vita", 62, setOf("psvita", "vita"), setOf("vita3k")),
            GameSystem("ps3", "PlayStation 3", 59, setOf("ps3"), setOf("rpcsx", "aps3e")),
            GameSystem("dreamcast", "Dreamcast", 23, setOf("dreamcast", "dc"), setOf("flycast", "redream", "reicast")),
            GameSystem("saturn", "Sega Saturn", 22, setOf("saturn"), setOf("yabasanshiro", "yaba")),
            GameSystem("genesis", "Genesis / Mega Drive", 1, setOf("genesis", "megadrive", "md"), setOf("md.emu", "picodrive")),
            GameSystem("segacd", "Sega CD", 20, setOf("segacd", "megacd")),
            GameSystem("sega32x", "Sega 32X", 19, setOf("sega32x", "32x")),
            GameSystem("mastersystem", "Master System", 2, setOf("mastersystem", "sms")),
            GameSystem("gamegear", "Game Gear", 21, setOf("gamegear", "gg")),
            GameSystem("pcengine", "PC Engine / TurboGrafx-16", 31, setOf("pcengine", "tg16", "turbografx16", "pce")),
            GameSystem("neogeo", "Neo Geo", 142, setOf("neogeo")),
            GameSystem("ngpc", "Neo Geo Pocket Color", 82, setOf("ngpc", "ngp")),
            GameSystem("arcade", "Arcade", 75, setOf("arcade", "mame", "fbneo", "fba"), setOf("mame4droid")),
            GameSystem("atari2600", "Atari 2600", 26, setOf("atari2600")),
            GameSystem("lynx", "Atari Lynx", 28, setOf("lynx", "atarilynx")),
            GameSystem("xbox", "Xbox", 32, setOf("xbox"), setOf("cxbx", "xemu")),
            GameSystem("xbox360", "Xbox 360", 33, setOf("xbox360"), setOf("ax360", "xenia")),
            GameSystem("wonderswan", "WonderSwan Color", 46, setOf("wonderswan", "wonderswancolor", "wsc")),
            // PC games: usually one folder per game (or a shortcut file), run through Winlator and the like.
            GameSystem(
                "windows", "Windows", 138, setOf("windows", "win", "win32", "win64", "pcgames", "steam", "winlator", "gamehub"),
                setOf("winlator", "gamehub", "mobox", "gamenative", "pluvia", "box64", "exagear", "termux"),
            ),
            GameSystem("pc", "PC (DOS)", 135, setOf("pc", "dos", "msdos", "pcdos"), setOf("dosbox", "magic dosbox")),
        )
        private val BY_FOLDER: Map<String, GameSystem> = SYSTEMS.flatMap { s -> s.folders.map { it to s } }.toMap()

        fun systemForFolder(name: String): GameSystem? =
            BY_FOLDER[name.lowercase().replace(Regex("""[\s_\-]"""), "")]

        fun system(key: String): GameSystem? = SYSTEMS.firstOrNull { it.key == key }

        /** The game in [roms] whose name [text] clearly is (a close match, well ahead of the next best). */
        fun matchName(text: String, roms: List<Rom>): Rom? {
            val want = Matcher.words(text).toSet()
            if (want.isEmpty() || roms.isEmpty()) return null
            val ranked = roms.map { it to Matcher.closeness(want, Matcher.words(it.title).toSet()) }.sortedByDescending { it.second }
            val (top, score) = ranked.first()
            val next = ranked.getOrNull(1)?.second ?: 0f
            return top.takeIf { score >= 0.85f && score - next >= 0.1f }
        }

        /**
         * The game a frontend's label names: a close match, or a game all of whose words appear in
         * the label (labels often add the system: "Golden Sun · Game Boy Advance"), the most specific
         * one if several fit.
         */
        fun matchLabel(label: String, roms: List<Rom>): Rom? {
            matchName(label, roms)?.let { return it }
            val words = Matcher.words(label).toSet()
            if (words.isEmpty()) return null
            val fits = roms.mapNotNull { r ->
                val w = Matcher.words(r.title).toSet()
                if (w.isNotEmpty() && words.containsAll(w)) r to w.size else null
            }
            val best = fits.maxOfOrNull { it.second } ?: return null
            return fits.filter { it.second == best }.map { it.first }.distinctBy { it.title.lowercase() }.singleOrNull()
        }


        /** The systems an emulator plays, from its app name; empty if it plays anything (RetroArch) or isn't known. */
        fun systemsFor(emulatorName: String?): Set<String> {
            val n = emulatorName?.lowercase() ?: return emptySet()
            if ("retroarch" in n) return emptySet()
            return SYSTEMS.filter { s -> s.emulators.any { it in n } }.map { it.key }.toSet()
        }
    }
}
