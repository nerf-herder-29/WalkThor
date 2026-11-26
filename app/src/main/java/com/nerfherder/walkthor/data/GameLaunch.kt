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
        Emulator("ARMSX2", setOf("ps2"), listOf("com.armsx2", "com.armsx2*", "*armsx2*")) { _, pkg, uri, _ ->
            Intent(Intent.ACTION_VIEW).setPackage(pkg).setData(uri)
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
        val uri = RomLibrary.get(c).document(rom) ?: return "Couldn't find ${rom.file} in your ROMs folder. Try Rescan."
        val all = installed(c)
        val tries = ArrayList<Intent>()
        for (e in KNOWN.filter { rom.system in it.systems }) {
            val pkg = e.packages.firstNotNullOfOrNull { find(c, all, it) } ?: continue
            e.intent(c, pkg, uri, rom)?.let { tries += it }
        }
        generic(c, all, rom)?.let { pkg -> tries += Intent(Intent.ACTION_VIEW).setPackage(pkg).setData(uri) }
        if (tries.isEmpty()) {
            val system = RomLibrary.system(rom.system)?.name ?: rom.system
            return "No emulator for $system found"
        }
        val options = gameDisplay?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        for (intent in tries) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // So the emulator may read the file even when the link travels in an extra.
            intent.clipData = ClipData.newRawUri(rom.title, uri)
            if (intent.component == null && c.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) continue
            val ok = runCatching { c.startActivity(intent, options) }.isSuccess
            if (ok) {
                GamePlays.started(c, rom)
                return null
            }
        }
        return "Couldn't start ${rom.title}"
    }
}
