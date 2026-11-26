package com.nerfherder.walkthor.data

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Shortcuts other apps add to your home screen (PokeDaisy's "add to home screen" for a game, for
 * example), for when WalkThor is your home app. Each shows up with your games, with its icon as
 * its cover, and starts like any other game.
 */
object PinnedShortcuts {
    /** The pretend system shortcuts are filed under. */
    const val SYSTEM = "shortcut"
    private const val PREFS = "pinned_shortcuts"

    data class Pinned(val key: String, val pkg: String, val id: String, val label: String, val appLabel: String)

    private val _all = MutableStateFlow<List<Pinned>>(emptyList())
    val all: StateFlow<List<Pinned>> = _all.asStateFlow()
    private var loaded = false

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        _all.value = prefs(c).getStringSet("items", emptySet()).orEmpty().mapNotNull { line ->
            line.split('\t').takeIf { it.size == 5 }?.let { Pinned(it[0], it[1], it[2], it[3], it[4]) }
        }.sortedBy { it.label.lowercase() }
    }

    private fun save(c: Context, list: List<Pinned>) {
        prefs(c).edit().putStringSet("items", list.map { listOf(it.key, it.pkg, it.id, it.label, it.appLabel).joinToString("\t") }.toSet()).apply()
        _all.value = list.sortedBy { it.label.lowercase() }
    }

    /** A name for the shortcut without dots (it's used like a file name). */
    private fun keyOf(pkg: String, id: String) = "${pkg}_$id".replace(Regex("""[^A-Za-z0-9_-]"""), "_")

    /** The icon's file, its cover. */
    fun iconFile(c: Context, key: String) = File(c.filesDir, "shortcuts/$key.png")

    /** An app asked to add [info] to the home screen: keep it, with its icon. */
    fun add(c: Context, info: ShortcutInfo): Pinned {
        load(c)
        val label = (info.longLabel ?: info.shortLabel)?.toString()?.takeIf { it.isNotBlank() } ?: info.id
        val appLabel = runCatching {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(info.`package`, 0)).toString()
        }.getOrDefault(info.`package`)
        val p = Pinned(keyOf(info.`package`, info.id), info.`package`, info.id, label.replace('\t', ' '), appLabel.replace('\t', ' '))
        runCatching {
            val la = c.getSystemService(LauncherApps::class.java)
            val d = la?.getShortcutIconDrawable(info, c.resources.displayMetrics.densityDpi)
            if (d != null) {
                val size = 256
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, size, size)
                d.draw(Canvas(bmp))
                val f = iconFile(c, p.key)
                f.parentFile?.mkdirs()
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        save(c, _all.value.filterNot { it.key == p.key } + p)
        return p
    }

    fun remove(c: Context, key: String) {
        load(c)
        val p = _all.value.firstOrNull { it.key == key } ?: return
        runCatching {
            // Tell Android this home screen no longer shows it.
            val la = c.getSystemService(LauncherApps::class.java)
            val still = _all.value.filter { it.pkg == p.pkg && it.key != key }.map { it.id }
            la?.pinShortcuts(p.pkg, still, Process.myUserHandle())
        }
        iconFile(c, key).delete()
        save(c, _all.value.filterNot { it.key == key })
    }

    /** Your shortcuts as games, for the games screen. */
    fun asRoms(list: List<Pinned>): List<Rom> = list.map { Rom(SYSTEM, it.key, it.label) }

    fun find(c: Context, rom: Rom): Pinned? {
        load(c)
        return _all.value.firstOrNull { it.key == rom.file }
    }

    /** Starts the shortcut; returns null if it started, or what went wrong. */
    fun start(c: Context, rom: Rom, options: Bundle?): String? {
        val p = find(c, rom) ?: return "That shortcut is gone"
        val la = c.getSystemService(LauncherApps::class.java) ?: return "Android's launcher service isn't there"
        if (!la.hasShortcutHostPermission()) return "WalkThor needs to be your home app to open shortcuts"
        return runCatching { la.startShortcut(p.pkg, p.id, null, options, Process.myUserHandle()); null }
            .getOrElse { e -> "${p.appLabel} couldn't open it (${e.javaClass.simpleName}: ${e.message?.take(80)})" }
    }
}
