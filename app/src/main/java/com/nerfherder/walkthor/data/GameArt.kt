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
 * Your games' artwork, from a folder you already have: ES-DE's (its downloaded_media folder, or
 * the ES-DE folder that holds it), or any folder laid out the same way, one folder per system and
 * inside it one folder per kind of picture (covers, screenshots, fanart…), each picture named
 * like the game's file. With ES-DE's folder, the descriptions in its gamelists come along too.
 * WalkThor only reads this folder and keeps the list of pictures on the device.
 */
class GameArt private constructor(private val context: Context) {

    enum class Kind(val folders: List<String>) {
        COVER(listOf("covers", "box2dfront", "boxart", "box-2d", "boxfront", "3dboxes", "box3d", "miximages", "mixrbv2")),
        /** The big picture behind the top screen. */
        HERO(listOf("fanart", "screenshots", "snap", "snaps", "titlescreens", "screenshot", "background", "miximages", "covers", "box2dfront")),
        LOGO(listOf("marquees", "wheel", "logos", "logo")),
    }

    private val prefs = context.getSharedPreferences("art", Context.MODE_PRIVATE)
    private val indexFile = File(context.filesDir, "art.tsv")
    private val descFile = File(context.filesDir, "art-desc.tsv")

    private val _folder = MutableStateFlow(prefs.getString(KEY_TREE, null)?.let(Uri::parse))
    val folder: StateFlow<Uri?> = _folder.asStateFlow()
    private val _scanning = MutableStateFlow<String?>(null)
    val scanning: StateFlow<String?> = _scanning.asStateFlow()
    /** How many games have a cover, for Settings. Bumped on each scan so pictures are looked up again. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** system → picture folder ("covers") → lowercase name → document id. */
    @Volatile private var index: Map<String, Map<String, Map<String, String>>> = emptyMap()
    /** The same, keyed by the tidied title, for files named a little differently. */
    @Volatile private var loose: Map<String, Map<String, Map<String, String>>> = emptyMap()
    /** system → lowercase file name → description. */
    @Volatile private var descs: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var loaded = false

    suspend fun load() = withContext(Background.dispatcher) {
        if (loaded) return@withContext
        loaded = true
        val rows = runCatching { indexFile.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            line.split('\t').takeIf { it.size == 4 }
        }
        val d = runCatching { descFile.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            line.split('\t').takeIf { it.size == 3 }?.let { Triple(it[0], it[1], it[2].replace("\\n", "\n")) }
        }
        build(rows, d)
    }

    private fun build(rows: List<List<String>>, d: List<Triple<String, String, String>>) {
        val exact = HashMap<String, HashMap<String, HashMap<String, String>>>()
        val tidy = HashMap<String, HashMap<String, HashMap<String, String>>>()
        for (r in rows) {
            exact.getOrPut(r[0]) { HashMap() }.getOrPut(r[1]) { HashMap() }[r[2]] = r[3]
            tidy.getOrPut(r[0]) { HashMap() }.getOrPut(r[1]) { HashMap() }.putIfAbsent(RomLibrary.tidy(r[2], true).lowercase(), r[3])
        }
        index = exact
        loose = tidy
        descs = d.groupBy({ it.first }, { it.second to it.third }).mapValues { it.value.toMap() }
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
        prefs.edit().remove(KEY_TREE).apply()
        _folder.value = null
        indexFile.delete()
        descFile.delete()
        build(emptyList(), emptyList())
    }

    /** How many of [roms] have a cover. */
    fun covered(roms: List<Rom>): Int = roms.count { picture(it, Kind.COVER) != null }

    /** Reads the folder again. */
    suspend fun scan() = withContext(Background.dispatcher) {
        val tree = _folder.value ?: return@withContext
        val rows = ArrayList<List<String>>()
        val d = ArrayList<Triple<String, String, String>>()
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            var top = children(tree, rootId)
            // The ES-DE folder: the pictures are in downloaded_media, the descriptions in gamelists.
            top.firstOrNull { it.third && it.second.equals("gamelists", true) }?.let { (gid, _, _) ->
                for ((sid, sname, dir) in children(tree, gid)) {
                    if (!dir) continue
                    val system = RomLibrary.systemForFolder(sname) ?: continue
                    _scanning.value = "Reading $sname descriptions…"
                    val xml = children(tree, sid).firstOrNull { it.second.equals("gamelist.xml", true) } ?: continue
                    readGamelist(DocumentsContract.buildDocumentUriUsingTree(tree, xml.first)).forEach { (file, desc) ->
                        d.add(Triple(system.key, file, desc))
                    }
                }
            }
            top.firstOrNull { it.third && it.second.equals("downloaded_media", true) }?.let { (mid, _, _) ->
                top = children(tree, mid)
            }
            val wanted = Kind.entries.flatMap { it.folders }.toSet()
            for ((sid, sname, dir) in top) {
                if (!dir) continue
                val system = RomLibrary.systemForFolder(sname) ?: continue
                _scanning.value = "Reading $sname pictures…"
                for ((kid, kname, kdir) in children(tree, sid)) {
                    val kind = kname.lowercase()
                    if (!kdir || kind !in wanted) continue
                    for ((fid, fname, fdir) in children(tree, kid)) {
                        if (fdir || fname.startsWith(".")) continue
                        val ext = fname.substringAfterLast('.', "").lowercase()
                        if (ext !in PICTURES) continue
                        rows.add(listOf(system.key, kind, fname.substringBeforeLast('.').lowercase(), fid))
                    }
                }
            }
            indexFile.writeText(rows.joinToString("\n") { it.joinToString("\t") })
            descFile.writeText(d.joinToString("\n") { "${it.first}\t${it.second}\t${it.third.replace("\t", " ").replace("\r", "").replace("\n", "\\n")}" })
            build(rows, d)
            loaded = true
        } catch (_: Exception) {
            // Folder moved or access lost: keep what we had.
        } finally {
            _scanning.value = null
        }
    }

    /** The game's picture of this kind, as a link to load, or null. */
    fun picture(rom: Rom, kind: Kind): Uri? {
        val tree = _folder.value ?: return null
        val bySystem = index[rom.system] ?: return null
        val name = rom.file.substringBeforeLast('.').lowercase()
        val alt = rom.file.lowercase()
        val tidy = rom.title.lowercase()
        for (folder in kind.folders) {
            val files = bySystem[folder] ?: continue
            val id = files[name] ?: files[alt] ?: loose[rom.system]?.get(folder)?.get(tidy) ?: continue
            return DocumentsContract.buildDocumentUriUsingTree(tree, id)
        }
        return null
    }

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
        private val PICTURES = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        @Volatile private var instance: GameArt? = null
        fun get(context: Context): GameArt =
            instance ?: synchronized(this) { instance ?: GameArt(context.applicationContext).also { instance = it } }
    }
}
