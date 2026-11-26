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

/**
 * Your games' artwork. Two places:
 *  - a folder you already have (ES-DE's, Cocoon's, or any other), read however it's laid out:
 *    pictures are told apart by the folders they're in (a system folder somewhere above, a
 *    "covers" or "screenshots" folder) or by their own name ("cover.png" in a folder named
 *    after the game). With ES-DE's folder, the descriptions in its gamelists come along too.
 *  - pictures WalkThor downloaded for you ([ArtScraper]), kept in WalkThor's own storage.
 * Your folder's pictures win when both have one. WalkThor only reads your folder.
 */
class GameArt private constructor(private val context: Context) {

    enum class Kind(val words: List<String>) {
        COVER(listOf("covers", "cover", "icons", "icon", "tiles", "tile", "box2dfront", "boxart", "box-2d", "boxfront", "2dbox", "box", "boxes", "front", "grid", "capsule", "poster", "3dboxes", "box3d", "3dbox", "miximages", "mixrbv2", "mix")),
        /** The big picture behind the top screen. */
        HERO(listOf("fanart", "hero", "heroes", "background", "backgrounds", "screenshots", "screenshot", "screen", "snap", "snaps", "titlescreens", "titlescreen", "titles", "title")),
        LOGO(listOf("marquees", "marquee", "wheel", "logos", "logo", "clearlogo")),
        ;
        fun rank(word: String) = words.indexOf(word)
    }

    private class Pic(val ref: String, val rank: Int)

    private val prefs = context.getSharedPreferences("art", Context.MODE_PRIVATE)
    private val indexFile = File(context.filesDir, "art2.tsv")
    private val scrapedFile = File(context.filesDir, "art-scraped.tsv")
    /** Pictures you chose yourself for a game: these win over everything. */
    private val customFile = File(context.filesDir, "art-custom.tsv")
    private val descFile = File(context.filesDir, "art-desc.tsv")

    private val _folder = MutableStateFlow(prefs.getString(KEY_TREE, null)?.let(Uri::parse))
    val folder: StateFlow<Uri?> = _folder.asStateFlow()
    private val _scanning = MutableStateFlow<String?>(null)
    val scanning: StateFlow<String?> = _scanning.asStateFlow()
    private val _version = MutableStateFlow(0)
    /** Bumped whenever pictures are added or removed, so they're looked up again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** What the last scan of your folder found, for Settings (so a folder WalkThor can't read is easy to spot). */
    val lastScan: String? get() = prefs.getString(KEY_SUMMARY, null)

    /** system → kind → lowercase name → best picture. */
    @Volatile private var index: Map<String, Map<Kind, Map<String, Pic>>> = emptyMap()
    /** The same, keyed by the tidied title, for files named a little differently. */
    @Volatile private var loose: Map<String, Map<Kind, Map<String, Pic>>> = emptyMap()
    /** system → lowercase file name → description. */
    @Volatile private var descs: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var folderRows: List<List<String>> = emptyList()
    @Volatile private var scrapedRows: List<List<String>> = emptyList()
    @Volatile private var customRows: List<List<String>> = emptyList()
    @Volatile private var loaded = false

    private fun readRows(f: File) = runCatching { f.readLines() }.getOrDefault(emptyList())
        .mapNotNull { line -> line.split('\t').takeIf { it.size == 5 } }

    suspend fun load() = withContext(Background.dispatcher) {
        if (loaded) return@withContext
        loaded = true
        folderRows = readRows(indexFile)
        scrapedRows = readRows(scrapedFile)
        customRows = readRows(customFile)
        descs = runCatching { descFile.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            line.split('\t').takeIf { it.size == 3 }?.let { Triple(it[0], it[1], it[2].replace("\\n", "\n")) }
        }.groupBy({ it.first }, { it.second to it.third }).mapValues { it.value.toMap() }
        build()
        // WalkThor got better at reading art folders since this one was read: read it again.
        if (_folder.value != null && prefs.getInt(KEY_FORMAT, 0) < FORMAT) scan()
    }

    /** Rows: system, kind, lowercase name, reference ("doc:id" or "file:path"), rank (lower wins). */
    private fun build() {
        val exact = HashMap<String, HashMap<Kind, HashMap<String, Pic>>>()
        val tidy = HashMap<String, HashMap<Kind, HashMap<String, Pic>>>()
        fun put(map: HashMap<String, HashMap<Kind, HashMap<String, Pic>>>, system: String, kind: Kind, name: String, pic: Pic) {
            val m = map.getOrPut(system) { HashMap() }.getOrPut(kind) { HashMap() }
            val had = m[name]
            if (had == null || pic.rank < had.rank) m[name] = pic
        }
        for (r in folderRows + scrapedRows + customRows) {
            val kind = runCatching { Kind.valueOf(r[1]) }.getOrNull() ?: continue
            val pic = Pic(r[3], r[4].toIntOrNull() ?: 99)
            put(exact, r[0], kind, r[2], pic)
            // Named like the file ("golden sun (usa)") or with its ending ("golden sun (usa).gba").
            put(tidy, r[0], kind, RomLibrary.tidy(r[2], true).lowercase(), pic)
            put(tidy, r[0], kind, RomLibrary.tidy(r[2], false).lowercase(), pic)
        }
        index = exact
        loose = tidy
        _version.value++
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
        prefs.edit().remove(KEY_TREE).remove(KEY_SUMMARY).apply()
        _folder.value = null
        indexFile.delete()
        descFile.delete()
        folderRows = emptyList()
        descs = emptyMap()
        build()
    }

    /** How many of [roms] have a cover. */
    fun covered(roms: List<Rom>): Int = roms.count { picture(it, Kind.COVER) != null }

    /** Reads your folder again. */
    suspend fun scan() = withContext(Background.dispatcher) {
        val tree = _folder.value ?: return@withContext
        val rows = ArrayList<List<String>>()
        val d = ArrayList<Triple<String, String, String>>()
        var pictures = 0
        val examples = ArrayList<String>()
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            fun walk(id: String, path: List<String>, depth: Int) {
                for ((cid, name, dir) in children(tree, id)) {
                    if (name.startsWith(".")) continue
                    if (dir) {
                        if (depth < 7 && name.lowercase() !in SKIP) {
                            if (depth <= 1) _scanning.value = "Reading ${(path + name).joinToString("/")}…"
                            walk(cid, path + name, depth + 1)
                        }
                        continue
                    }
                    val lower = name.lowercase()
                    if (lower == "gamelist.xml") {
                        val system = path.asReversed().firstNotNullOfOrNull { systemFor(it) } ?: continue
                        readGamelist(DocumentsContract.buildDocumentUriUsingTree(tree, cid)).forEach { (file, desc) ->
                            d.add(Triple(system.key, file, desc))
                        }
                        continue
                    }
                    if (lower.substringAfterLast('.', "") !in PICTURES) continue
                    pictures++
                    if (examples.size < 3) examples += (path + name).joinToString("/")
                    classify(path, cleanName(lower.substringBeforeLast('.')))?.let { (system, kind, game, rank) ->
                        rows.add(listOf(system, kind.name, game, "doc:$cid", rank.toString()))
                    }
                }
            }
            walk(rootId, emptyList(), 0)
            indexFile.writeText(rows.joinToString("\n") { it.joinToString("\t") })
            descFile.writeText(d.joinToString("\n") { "${it.first}\t${it.second}\t${it.third.replace("\t", " ").replace("\r", "").replace("\n", "\\n")}" })
            folderRows = rows
            descs = d.groupBy({ it.first }, { it.second to it.third }).mapValues { it.value.toMap() }
            build()
            loaded = true
            prefs.edit().putString(
                KEY_SUMMARY,
                "$pictures pictures found, ${rows.size} placed by system" +
                    (if (examples.isNotEmpty()) ". For example: ${examples.joinToString(", ")}" else ""),
            ).putInt(KEY_FORMAT, FORMAT).apply()
            note("Art folder read: ${prefs.getString(KEY_SUMMARY, "")}")
            runCatching { note("Art matching: " + explain(RomLibrary.get(context).roms.value)) }
        } catch (e: Exception) {
            // Folder moved or access lost: keep what we had, and say so.
            prefs.edit().putString(KEY_SUMMARY, "Couldn't read the folder (${e.javaClass.simpleName}: ${e.message?.take(80)})").apply()
            note("Art folder: ${prefs.getString(KEY_SUMMARY, "")}")
        } finally {
            _scanning.value = null
        }
    }

    /**
     * The game's name in a picture's file name, without what the app that saved it added:
     * Cocoon's "__cocoon_edit_11_a6abfb9d…" after each edit, or a long id on the end.
     */
    private fun cleanName(base: String): String = base
        .replace(Regex("""__cocoon_edit_\d+_[0-9a-f]+$"""), "")
        .replace(Regex("""[_-][0-9a-f]{24,}$"""), "")
        .trim()

    private data class Placed(val system: String, val kind: Kind, val game: String, val rank: Int)

    /** Which game, system and kind of picture a file is, from where it sits and its name. */
    private fun classify(path: List<String>, base: String): Placed? {
        val folders = path.map { it.lowercase() }
        fun kindOf(word: String): Pair<Kind, Int>? {
            val w = word.lowercase().replace(Regex("""[\s_]"""), "")
            for (k in Kind.entries) { val r = k.rank(w); if (r >= 0) return k to r }
            return null
        }
        // "Golden Sun (USA)/cover.png": the picture is named for what it is, the folder for the game.
        kindOf(base)?.let { (kind, rank) ->
            val game = folders.lastOrNull() ?: return null
            val system = folders.dropLast(1).asReversed().firstNotNullOfOrNull { systemFor(it) } ?: return null
            return Placed(system.key, kind, game, rank)
        }
        // "gba/covers/Golden Sun (USA).png" (or "covers/gba/…"): named for the game.
        val kind = folders.asReversed().firstNotNullOfOrNull { kindOf(it) }
        val system = folders.asReversed().filter { kindOf(it) == null }.firstNotNullOfOrNull { systemFor(it) } ?: return null
        val (k, rank) = kind ?: (Kind.COVER to 90)
        return Placed(system.key, k, base, rank)
    }

    /** The game's picture of this kind, as a link to load, or null. */
    fun picture(rom: Rom, kind: Kind): Uri? {
        val pic = find(rom, kind) ?: return null
        return when {
            pic.ref.startsWith("file:") -> Uri.fromFile(File(pic.ref.removePrefix("file:")))
            else -> _folder.value?.let { DocumentsContract.buildDocumentUriUsingTree(it, pic.ref.removePrefix("doc:")) }
        }
    }

    private fun find(rom: Rom, kind: Kind): Pic? {
        val name = rom.file.substringBeforeLast('.').lowercase()
        val alt = rom.file.lowercase()
        val tidy = rom.title.lowercase()
        val kinds = when (kind) {
            Kind.COVER -> listOf(Kind.COVER)
            Kind.HERO -> listOf(Kind.HERO, Kind.COVER)
            Kind.LOGO -> listOf(Kind.LOGO)
        }
        for (k in kinds) {
            val files = index[rom.system]?.get(k)
            (files?.get(name) ?: files?.get(alt) ?: loose[rom.system]?.get(k)?.get(tidy) ?: closest(rom, k))?.let { return it }
        }
        return null
    }

    /** Remembered word matches ("pokemon emerald" ↔ "Pokémon - Emerald Version"), per game and kind. */
    private val guesses = java.util.concurrent.ConcurrentHashMap<String, Pic>()
    private val misses = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Each picture's name as words, worked out once per system and kind. */
    private val wordCache = java.util.concurrent.ConcurrentHashMap<String, List<Pair<Set<String>, Pic>>>()
    private fun wordsOf(system: String, kind: Kind): List<Pair<Set<String>, Pic>>? {
        val files = loose[system]?.get(kind) ?: return null
        return wordCache.getOrPut("${_version.value}|$system|$kind") { files.map { (n, pic) -> Matcher.words(n).toSet() to pic } }
    }

    /** The picture whose name is clearly the game's, when no name matches exactly. */
    private fun closest(rom: Rom, kind: Kind): Pic? {
        val key = "${_version.value}|${rom.id}|$kind"
        guesses[key]?.let { return it }
        if (key in misses) return null
        val files = wordsOf(rom.system, kind) ?: return null
        val want = Matcher.words(rom.title).toSet()
        var best: Pic? = null
        var bestScore = 0f
        var next = 0f
        if (want.isNotEmpty()) for ((words, pic) in files) {
            val score = Matcher.closeness(want, words)
            if (score > bestScore) { if (best != pic) next = bestScore; bestScore = score; best = pic }
            else if (score > next && pic != best) next = score
        }
        val found = best.takeIf { bestScore >= 0.8f && bestScore - next >= 0.05f }
        if (found != null) guesses[key] = found else misses.add(key)
        return found
    }

    /**
     * Why games have no cover, in a line: for a few of them, the closest picture name in their
     * system (or that their system has no pictures at all), so a mismatch is easy to see.
     */
    fun explain(roms: List<Rom>): String {
        val missing = roms.filter { RomLibrary.isGame(it) && picture(it, Kind.COVER) == null }
        if (missing.isEmpty()) return "Every game has a cover"
        val systems = index.entries.joinToString(", ") { (sys, kinds) -> "$sys ${kinds.values.sumOf { it.size }}" }
        val parts = missing.distinctBy { it.system }.take(4).map { r ->
            val names = index[r.system]?.get(Kind.COVER)?.keys
            if (names.isNullOrEmpty()) "${r.title} (${r.system}): no ${r.system} icons found"
            else {
                val want = Matcher.words(r.title).toSet()
                val best = names.maxByOrNull { Matcher.closeness(want, Matcher.words(it).toSet()) }
                "${r.title} (${r.system}, file “${r.file}”): closest icon “$best”"
            }
        }
        return "${missing.size} of ${roms.size} games have no cover. Pictures by system: ${systems.ifEmpty { "none" }}. " +
            parts.joinToString("; ")
    }

    /** Whether the game already has this kind of picture (so it isn't downloaded again). */
    fun has(rom: Rom, kind: Kind): Boolean {
        val files = index[rom.system]?.get(kind) ?: return false
        return files.containsKey(rom.file.substringBeforeLast('.').lowercase()) || files.containsKey(rom.file.lowercase()) ||
            loose[rom.system]?.get(kind)?.containsKey(rom.title.lowercase()) == true || closest(rom, kind) != null
    }

    /** Where downloaded pictures go. */
    fun scrapedDir(): File = File(context.filesDir, "scraped").apply { mkdirs() }

    /** A picture was downloaded for [rom]. */
    @Synchronized fun addScraped(rom: Rom, kind: Kind, file: File) {
        val row = listOf(rom.system, kind.name, rom.file.substringBeforeLast('.').lowercase(), "file:${file.absolutePath}", "100")
        scrapedRows = scrapedRows + listOf(row)
        runCatching { scrapedFile.appendText((if (scrapedFile.length() > 0) "\n" else "") + row.joinToString("\t")) }
    }

    /**
     * Your own picture of [kind] for [rom], copied from [source] into WalkThor's storage (so it
     * stays even if the original moves). Returns false if it couldn't be read.
     */
    suspend fun setCustom(rom: Rom, kind: Kind, source: Uri): Boolean = withContext(Background.dispatcher) {
        val name = rom.file.substringBeforeLast('.').lowercase()
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val dir = File(context.filesDir, "custom/${rom.system}/${kind.name}").apply { mkdirs() }
        val to = File(dir, "$safe-${System.currentTimeMillis()}.img")
        val ok = runCatching {
            context.contentResolver.openInputStream(source)?.use { input -> to.outputStream().use { input.copyTo(it) } } != null && to.length() > 0
        }.getOrDefault(false)
        if (!ok) { to.delete(); return@withContext false }
        synchronized(this@GameArt) {
            // The one you chose before for this game and kind goes.
            customRows.filter { it[0] == rom.system && it[1] == kind.name && it[2] == name }
                .forEach { File(it[3].removePrefix("file:")).delete() }
            customRows = customRows.filterNot { it[0] == rom.system && it[1] == kind.name && it[2] == name } +
                listOf(listOf(rom.system, kind.name, name, "file:${to.absolutePath}", "-10"))
            runCatching { customFile.writeText(customRows.joinToString("\n") { it.joinToString("\t") }) }
        }
        build()
        true
    }

    /** Whether you chose any pictures for [rom]. */
    fun hasCustom(rom: Rom): Boolean {
        val name = rom.file.substringBeforeLast('.').lowercase()
        return customRows.any { it[0] == rom.system && it[2] == name }
    }

    /** Forgets the pictures you chose for [rom] (its usual ones come back). */
    fun clearCustom(rom: Rom) {
        val name = rom.file.substringBeforeLast('.').lowercase()
        synchronized(this) {
            customRows.filter { it[0] == rom.system && it[2] == name }.forEach { File(it[3].removePrefix("file:")).delete() }
            customRows = customRows.filterNot { it[0] == rom.system && it[2] == name }
            runCatching { customFile.writeText(customRows.joinToString("\n") { it.joinToString("\t") }) }
        }
        build()
    }

    /** After a round of downloads: look pictures up again. */
    fun refresh() = build()

    /** Throws away every downloaded picture. */
    fun clearScraped() {
        scrapedDir().deleteRecursively()
        scrapedFile.delete()
        scrapedRows = emptyList()
        build()
    }

    val scrapedCount: Int get() = scrapedRows.size

    /** The game's description from ES-DE's gamelist, if there is one. */
    fun description(rom: Rom): String? = descs[rom.system]?.get(rom.file.lowercase())

    /** (file name, description) for each game in a gamelist.xml that has one. */
    private fun readGamelist(uri: Uri): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val p = android.util.Xml.newPullParser()
                p.setInput(input, null)
                var path: String? = null
                var desc: String? = null
                var tag: String? = null
                var event = p.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        org.xmlpull.v1.XmlPullParser.START_TAG -> {
                            tag = p.name
                            if (tag == "game") { path = null; desc = null }
                        }
                        org.xmlpull.v1.XmlPullParser.TEXT -> when (tag) {
                            "path" -> path = (path ?: "") + p.text
                            "desc" -> desc = (desc ?: "") + p.text
                        }
                        org.xmlpull.v1.XmlPullParser.END_TAG -> {
                            if (p.name == "game") {
                                val file = path?.trim()?.substringAfterLast('/')?.lowercase()
                                val text = desc?.trim()
                                if (!file.isNullOrEmpty() && !text.isNullOrEmpty()) out.add(file to text)
                            }
                            tag = null
                        }
                    }
                    event = p.next()
                }
            }
        }
        return out
    }

    private fun children(tree: Uri, docId: String): List<Triple<String, String, Boolean>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = ArrayList<Triple<String, String, Boolean>>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(Triple(c.getString(0), c.getString(1) ?: continue, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        }
        return out
    }

    /** Into the memory log, which goes along with game reports. */
    private fun note(text: String) = runCatching { com.nerfherder.walkthor.MemoryWatch.noteFrom(context, text) }

    companion object {
        /** Bumped when reading folders changes, so linked folders are read again. */
        private const val FORMAT = 5
        private const val KEY_FORMAT = "format"
        private const val KEY_TREE = "tree"
        private const val KEY_SUMMARY = "summary"
        private val PICTURES = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        /** Folders with nothing to find in them. */
        private val SKIP = setOf("videos", "video", "manuals", "themes", "theme", "sounds", "music", "backups", "backup", "logs", "saves", "states", "bios")

        /** The system a folder is for: "gba", but also "Nintendo - Game Boy Advance" or "SonyPlayStation2". */
        fun systemFor(folder: String): GameSystem? {
            RomLibrary.systemForFolder(folder)?.let { return it }
            val n = folder.lowercase().filter { it.isLetterOrDigit() }
            if (n.length < 4) return null
            var best: GameSystem? = null
            var bestLen = 0
            for (s in RomLibrary.SYSTEMS) {
                val names = s.folders + s.name.lowercase().filter { it.isLetterOrDigit() }
                for (alias in names) {
                    val a = alias.lowercase().filter { it.isLetterOrDigit() }
                    if (a.length >= 4 && a.length > bestLen && (n == a || n.endsWith(a) || n.startsWith(a))) {
                        best = s
                        bestLen = a.length
                    }
                }
            }
            return best
        }

        @Volatile private var instance: GameArt? = null
        fun get(context: Context): GameArt =
            instance ?: synchronized(this) { instance ?: GameArt(context.applicationContext).also { instance = it } }
    }
}
