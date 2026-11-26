package com.nerfherder.walkthor.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Every emulator ES-DE Frontend knows how to start a game in on Android, and how: its launch
 * rules (assets/launch/es-de.tsv, made from ES-DE's es_find_rules.xml and es_systems_saf.xml,
 * MIT License). WalkThor reads them the way ES-DE does: each rule names the emulator, then the
 * intent's action, category, data and extras, with the game filled in.
 */
object EsDeLaunch {

    /** One way to start [system]'s games: ES-DE's [label] ("mGBA", "DuckStation (Standalone)"). */
    class Command(val system: String, val label: String, val emulator: String, val text: String) {
        val retroArch: Boolean get() = emulator == "RETROARCH"
        /** How it shows in Settings: "RetroArch: mGBA", "DuckStation". */
        val title: String get() = if (retroArch) "RetroArch: $label" else label.removeSuffix(" (Standalone)")
    }

    private class Table(val emulators: Map<String, List<String>>, val commands: Map<String, List<Command>>)

    @Volatile private var table: Table? = null

    private fun table(c: Context): Table = table ?: synchronized(this) {
        table ?: read(c).also { table = it }
    }

    private fun read(c: Context): Table {
        val emus = HashMap<String, List<String>>()
        val cmds = LinkedHashMap<String, MutableList<Command>>()
        runCatching {
            c.assets.open("launch/es-de.tsv").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split('\t')
                    when (p.firstOrNull()) {
                        "E" -> if (p.size >= 3) emus[p[1]] = p.drop(2)
                        "C" -> if (p.size >= 4) {
                            val emu = Regex("""^%EMULATOR_([^%]+)%""").find(p[3])?.groupValues?.get(1) ?: return@forEach
                            cmds.getOrPut(p[1]) { ArrayList() } += Command(p[1], p[2], emu, p[3])
                        }
                    }
                }
            }
        }
        return Table(emus, cmds)
    }

    /** ES-DE's ways to start [system]'s games, in its order. */
    fun commands(c: Context, system: String): List<Command> = table(c).commands[system].orEmpty()

    /** Every app ES-DE starts games in (to count them as emulators). */
    fun packages(c: Context): Set<String> =
        table(c).emulators.values.flatten().map { it.substringBefore('/') }.toSet()

    /** The installed app and screen that [emulator] means on this device, or null. */
    fun resolve(c: Context, emulator: String): ComponentName? =
        table(c).emulators[emulator].orEmpty().firstNotNullOfOrNull { entry ->
            val pkg = entry.substringBefore('/')
            val cls = entry.substringAfter('/')
            ComponentName(pkg, cls).takeIf { runCatching { c.packageManager.getActivityInfo(it, 0) }.isSuccess }
        }

    // ---- Reading a rule ----

    /** What a rule asks for, before the game is filled in. */
    class Spec(
        val action: String?,
        val category: String?,
        val mime: String?,
        val data: String?,
        /** (kind, name, value): kind is "", "ARRAY", "INTEGER" or "BOOL". */
        val extras: List<Triple<String, String, String>>,
        val clearTask: Boolean,
        val clearTop: Boolean,
        val noHistory: Boolean,
    )

    private const val QUOTE = "\u0000Q\u0000"

    /** Splits a rule the way ES-DE does: by spaces, keeping "quoted values" whole. */
    fun parse(text: String): Spec {
        val t = text.replace("\\\"", QUOTE)
        val tokens = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        for (ch in t) {
            when {
                ch == '"' -> quoted = !quoted
                ch == ' ' && !quoted -> { if (cur.isNotEmpty()) tokens += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) tokens += cur.toString()
        var action: String? = null
        var category: String? = null
        var mime: String? = null
        var data: String? = null
        val extras = ArrayList<Triple<String, String, String>>()
        val extra = Regex("""^%EXTRA(ARRAY|INTEGER|BOOL)?_(.+?)%=(.*)$""")
        for (tok in tokens) {
            when {
                tok.startsWith("%ACTION%=") -> action = tok.removePrefix("%ACTION%=")
                tok.startsWith("%CATEGORY%=") -> category = tok.removePrefix("%CATEGORY%=")
                tok.startsWith("%MIMETYPE%=") -> mime = tok.removePrefix("%MIMETYPE%=")
                tok.startsWith("%DATA%=") -> data = tok.removePrefix("%DATA%=")
                else -> extra.find(tok)?.let { m ->
                    val value = m.groupValues[3].replace(QUOTE, "\\\"")
                    if (m.groupValues[2].isNotEmpty() && value.isNotEmpty()) extras += Triple(m.groupValues[1], m.groupValues[2], value)
                }
            }
        }
        return Spec(action, category, mime, data, extras, "%ACTIVITY_CLEAR_TASK%" in text, "%ACTIVITY_CLEAR_TOP%" in text, "%ACTIVITY_NO_HISTORY%" in text)
    }

    /** Where a game is, for filling in a rule. */
    class Game(
        /** The game's document (from the ROMs folder). */
        val uri: Uri,
        /** The ROMs folder you linked. */
        val tree: Uri?,
        val file: String,
    )

    /** Why a rule couldn't be filled in (a file it needs isn't there). */
    class Missing(what: String) : Exception(what)

    /**
     * The intent [command] asks for, for [game], in [component]. Throws [Missing] when the rule
     * needs something this game doesn't have (a ".ps3" file beside it, a path on the device).
     */
    fun intent(c: Context, command: Command, component: ComponentName, game: Game): Intent {
        val spec = parse(command.text)
        val user = android.os.Process.myUid() / 100000
        val romRaw by lazy { rawPath(game.uri) }
        val romsRaw by lazy { game.tree?.let { rawPath(it) } }
        val baseName = game.file.substringBeforeLast('.')

        fun paths(v: String, extra: Boolean): String {
            var s = v.replace("%ANDROIDPACKAGE%", component.packageName)
                .replace("%INTERNALDATA%", "/data/user/$user")
                .replace("%EXTERNALDATA%", "/storage/emulated/$user")
                .replace("%BASENAME%", baseName)
            if ("%GAMEDIRRAW%" in s) s = s.replace("%GAMEDIRRAW%", (romRaw ?: throw Missing("the game's folder on the device")).substringBeforeLast('/'))
            if ("%ROMPATHRAW%" in s) s = s.replace("%ROMPATHRAW%", romsRaw ?: throw Missing("the ROMs folder on the device"))
            if ("%ROMRAW%" in s) s = s.replace("%ROMRAW%", romRaw ?: throw Missing("the game's path on the device"))
            if (extra) s = s.replace(Regex("/{2,}"), "/").replace("\\/", "/")
            return s
        }

        fun game(v: String): String {
            var s = v
            // %INJECT%=x: what's written in the file x (the game itself, or a file named for it beside it).
            val at = s.indexOf("%INJECT%=")
            if (at >= 0) {
                val target = s.substring(at + "%INJECT%=".length)
                s = s.substring(0, at) + read(c, game, target).trim()
            }
            if ("%ROMSAF%" in s) s = s.replace("%ROMSAF%", game.uri.toString())
            if ("%ROMPROVIDER%" in s) s = s.replace("%ROMPROVIDER%", game.uri.toString())
            if ("%ROM%" in s) s = s.replace("%ROM%", romRaw ?: throw Missing("the game's path on the device"))
            return s
        }

        val intent = Intent().setComponent(component)
        spec.action?.let { intent.action = it }
        spec.category?.let { intent.addCategory(it) }
        spec.data?.let { d ->
            val value = game(paths(d, extra = false))
            val uri = Uri.parse(value)
            if (spec.mime != null) intent.setDataAndType(uri, spec.mime) else intent.data = uri
        }
        for ((kind, name, raw) in spec.extras) {
            val value = game(paths(raw, extra = true))
            when (kind) {
                "INTEGER" -> intent.putExtra(name, value.trim().toIntOrNull() ?: throw Missing("a number in ${game.file}"))
                "BOOL" -> intent.putExtra(name, value.trim().lowercase().let { it == "true" || it == "1" })
                "ARRAY" -> intent.putExtra(name, value.split(Regex("""(?<!\\),""")).map { it.replace("\\,", ",") }.toTypedArray())
                else -> intent.putExtra(name, value)
            }
        }
        if (spec.clearTask) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (spec.clearTop) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (spec.noHistory) intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        return intent
    }

    /** The text in [target]: "%ROM%" (the game file) or a file name beside the game. */
    private fun read(c: Context, game: Game, target: String): String {
        val uri = if (target == "%ROM%" || target == "%ROMSAF%") game.uri else sibling(game, target) ?: throw Missing(target)
        val text = runCatching { c.contentResolver.openInputStream(uri)?.use { val buf = ByteArray(4096); val n = it.read(buf); if (n > 0) String(buf, 0, n) else "" } }.getOrNull()
        return text?.takeIf { it.isNotBlank() } ?: throw Missing(target)
    }

    private fun sibling(game: Game, name: String): Uri? {
        val tree = game.tree ?: return null
        val id = runCatching { DocumentsContract.getDocumentId(game.uri) }.getOrNull() ?: return null
        if (!id.contains('/')) return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, id.substringBeforeLast('/') + "/" + name)
    }

    /** "/storage/emulated/0/ROMs/gba/Game.gba" for a document on the device's own storage or an SD card. */
    fun rawPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        val volume = id.substringBefore(':', "")
        val path = id.substringAfter(':', "")
        if (volume.isEmpty()) return null
        val base = if (volume == "primary") "/storage/emulated/${android.os.Process.myUid() / 100000}" else "/storage/$volume"
        return if (path.isEmpty()) base else "$base/$path"
    }
}
