package com.nerfherder.walkthor.data

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.Display

/**
 * Starts a game from your ROMs folder in its emulator, on the game screen. The emulators below
 * are opened straight into the game, the way ES-DE opens them; for other systems WalkThor hands
 * the game's file to an emulator you have for that system and lets it open it.
 */
object GameLaunch {

    /** An emulator WalkThor knows how to start a game in. */
    private class Emulator(
        val name: String,
        val systems: Set<String>,
        /** Its package names, in the order to try; a name ending in '*' matches any package that starts so. */
        val packages: List<String>,
        val intent: (Context, String, Uri, Rom) -> Intent?,
    )

    private val KNOWN = listOf(
        Emulator(
            "Eden", setOf("switch"),
            listOf("dev.eden.eden_emulator.nightly", "dev.eden.eden_emulator", "dev.eden.*"),
        ) { _, pkg, uri, _ ->
            Intent("android.nfc.action.TECH_DISCOVERED")
                .setComponent(ComponentName(pkg, "org.yuzu.yuzu_emu.activities.EmulationActivity"))
                .setData(uri)
        },
        // ARMSX2's package is come.nanodata.armsx2; its game screen moved from the old PCSX2 port's
        // classes to com.armsx2.* in newer builds. Opening its splash screen with a game does nothing.
        Emulator("ARMSX2", setOf("ps2"), listOf("come.nanodata.armsx2", "com.armsx2", "*armsx2*")) { c, pkg, uri, _ ->
            val activity = listOf("com.armsx2.Main", "com.armsx2.MainActivity", "kr.co.iefriends.pcsx2.MainActivity")
                .firstOrNull { exists(c, pkg, it) } ?: return@Emulator null
            Intent(Intent.ACTION_VIEW).setClassName(pkg, activity).setData(uri)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        Emulator("Dolphin", setOf("gc", "wii"), listOf("org.dolphinemu.dolphinemu", "org.dolphinemu.*", "*dolphin*")) { c, pkg, uri, _ ->
            val start = Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER").setPackage(pkg)
            val activity = c.packageManager.queryIntentActivities(start, 0).firstOrNull()?.activityInfo
                ?: return@Emulator null
            start.setClassName(activity.packageName, activity.name).putExtra("AutoStartFile", uri.toString())
        },
        Emulator("GameNative", setOf("windows"), listOf("app.gamenative")) { c, pkg, uri, rom ->
            // The shortcut file holds the game's id.
            val text = runCatching { c.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            val id = text?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: return@Emulator null
            Intent("app.gamenative.LAUNCH_GAME").setPackage(pkg).putExtra("app_id", id).apply {
                if (!rom.file.endsWith(".steam", true) && !rom.file.endsWith(".steamappid", true)) putExtra("game_source", "CUSTOM_GAME")
            }
        },
        Emulator("PokeDaisy", setOf("gb", "gbc", "gba", "nds"), listOf("com.pokedaisy.app")) { _, pkg, uri, _ ->
            Intent(Intent.ACTION_VIEW).setComponent(ComponentName(pkg, "com.pokedaisy.app.LaunchActivity")).setData(uri)
        },
    )

    private fun exists(c: Context, pkg: String, cls: String): Boolean =
        runCatching { c.packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess

    private fun installed(c: Context): List<String> =
        c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct()

    private fun find(c: Context, all: List<String>, pattern: String): String? = when {
        pattern.startsWith("*") && pattern.endsWith("*") -> all.firstOrNull { pattern.trim('*') in it.lowercase() }
        pattern.endsWith("*") -> all.firstOrNull { it.startsWith(pattern.dropLast(1)) }
        else -> pattern.takeIf { runCatching { c.packageManager.getPackageInfo(it, 0) }.isSuccess }
    }

    /** The emulator WalkThor would start [rom] in ("Eden"), or null if there's none. */
    fun emulatorName(c: Context, rom: Rom): String? {
        val all = installed(c)
        KNOWN.filter { rom.system in it.systems }.forEach { e ->
            if (e.packages.any { find(c, all, it) != null }) return e.name
        }
        return generic(c, all, rom)?.let { label(c, it) }
    }

    private fun label(c: Context, pkg: String): String =
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /** An installed app whose name says it plays the game's system. */
    /** Every installed app whose name says it plays [system]. */
    private fun generics(c: Context, all: List<String>, system: String): List<String> {
        val words = RomLibrary.system(system)?.emulators.orEmpty()
        if (words.isEmpty()) return emptyList()
        return all.filter { pkg -> val l = label(c, pkg).lowercase(); words.any { it in l } }
    }

    /** An emulator you can pick for a system: [id] is "known:Eden" or "pkg:<package>". */
    data class Choice(val id: String, val label: String)

    /** The emulators WalkThor can start [system]'s games in, on this device. */
    fun choices(c: Context, system: String): List<Choice> {
        val all = installed(c)
        val out = ArrayList<Choice>()
        val knownPkgs = HashSet<String>()
        KNOWN.filter { system in it.systems }.forEach { e ->
            e.packages.firstNotNullOfOrNull { find(c, all, it) }?.let { pkg -> knownPkgs += pkg; out += Choice("known:${e.name}", e.name) }
        }
        generics(c, all, system).filter { it !in knownPkgs }.forEach { out += Choice("pkg:$it", label(c, it)) }
        return out
    }

    private fun prefs(c: Context) = c.getSharedPreferences("launch", Context.MODE_PRIVATE)
    /** Your emulator for [system], or null to let WalkThor pick. */
    fun preferred(c: Context, system: String): String? = prefs(c).getString(system, null)
    fun setPreferred(c: Context, system: String, id: String?) {
        prefs(c).edit().apply { if (id == null) remove(system) else putString(system, id) }.apply()
    }

    private fun generic(c: Context, all: List<String>, rom: Rom): String? {
        val words = RomLibrary.system(rom.system)?.emulators.orEmpty()
        if (words.isEmpty()) return null
        return all.firstOrNull { pkg -> val l = label(c, pkg).lowercase(); words.any { it in l } }
    }

    /**
     * Starts [rom]; returns null if it started, or what went wrong. [gameDisplay] is the screen to
     * start it on (the top screen).
     */
    suspend fun start(c: Context, rom: Rom, gameDisplay: Display?): String? {
        val system = RomLibrary.system(rom.system)?.name ?: rom.system
        val uri = RomLibrary.get(c).document(rom)
        if (uri == null) {
            log(c, "${rom.id}: not found in the ROMs folder")
            return "Couldn't find ${rom.file} in your ROMs folder. Try Rescan in Settings → Guides."
        }
        val all = installed(c)
        // (which emulator, what it is, the intent) in the order to try.
        val tagged = ArrayList<Triple<String, String, Intent>>()
        val why = ArrayList<String>()
        val knownPkgs = HashSet<String>()
        for (e in KNOWN.filter { rom.system in it.systems }) {
            val pkg = e.packages.firstNotNullOfOrNull { find(c, all, it) }
            if (pkg == null) { why += "${e.name} isn't installed"; continue }
            knownPkgs += pkg
            val made = runCatching { e.intent(c, pkg, uri, rom) }
            made.getOrNull()?.let { tagged += Triple("known:${e.name}", e.name, it) }
                ?: why.add("${e.name}: couldn't set up the game (${made.exceptionOrNull()?.javaClass?.simpleName ?: "nothing to start"})")
            // Second try: hand it the file the ordinary way.
            tagged += Triple("known:${e.name}", "${e.name} (open file)", Intent(Intent.ACTION_VIEW).setPackage(pkg).setData(uri))
        }
        generics(c, all, rom.system).filter { it !in knownPkgs }.forEach { pkg ->
            tagged += Triple("pkg:$pkg", label(c, pkg), Intent(Intent.ACTION_VIEW).setPackage(pkg).setData(uri))
        }
        // The emulator you chose for this system (Settings → Emulators) goes first.
        val chosen = preferred(c, rom.system)
        val tries = tagged.sortedBy { if (it.first == chosen) 0 else 1 }.map { it.second to it.third }
        if (tries.isEmpty()) {
            log(c, "${rom.id}: no emulator. ${why.joinToString("; ")}")
            return "No emulator for $system found" + (why.firstOrNull()?.let { " ($it)" } ?: "")
        }
        val options = gameDisplay?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        for ((name, intent) in tries) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // So the emulator may read the file even when the link travels in an extra.
            intent.clipData = ClipData.newRawUri(rom.title, uri)
            if (intent.component == null && c.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                why += "$name: doesn't open files this way"
                continue
            }
            // The whole ROMs folder too, for games in several files (.cue and .bin, other discs).
            val target = intent.component?.packageName ?: intent.`package`
            RomLibrary.get(c).folder.value?.let { tree ->
                if (target != null) runCatching { c.grantUriPermission(target, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) }
            }
            val result = runCatching { c.startActivity(intent, options) }
            if (result.isSuccess) {
                log(c, "${rom.id}: started with $name (${intent.action}, ${intent.component?.flattenToShortString() ?: intent.`package`})")
                GamePlays.started(c, rom)
                return null
            }
            val e = result.exceptionOrNull()
            why += "$name: ${e?.javaClass?.simpleName}: ${e?.message?.take(120)}"
        }
        log(c, "${rom.id}: failed. ${why.joinToString("; ")}")
        return "Couldn't start ${rom.title}. " + why.joinToString("\n")
    }

    /** Into the memory log, which goes along with game reports. */
    private fun log(c: Context, text: String) {
        runCatching { com.nerfherder.walkthor.MemoryWatch.noteFrom(c, "Launch: $text") }
    }
}
