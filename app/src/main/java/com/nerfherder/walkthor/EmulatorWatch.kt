package com.nerfherder.walkthor

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Knows a game is running without Cocoon: "Detect games from any emulator".
 *
 * While it's switched on (and Cocoon isn't linked), WalkThor's accessibility service tells this
 * which app comes to the front. When that app is an emulator, a game session starts: WalkThor
 * opens on the bottom screen with the game's tabs, and works out which game it is from
 *  1. any text the emulator shows that matches one of your guides (its window title or its own
 *     notifications; only the running emulator's, kept in memory for this session only), else
 *  2. the guide you used last time with this emulator, else
 *  3. asks you, with the guides you've used with this emulator first.
 * The session ends when your home screen or frontend comes back to the front.
 */
object EmulatorWatch {
    /** A game running in [name] (the emulator's app name), since [at]. */
    data class Session(val pkg: String, val name: String, val at: Long)

    val session = MutableStateFlow<Session?>(null)
    /** On, agreed to, and Cocoon isn't linked: the service should listen for apps coming to the front. */
    val active = MutableStateFlow(false)
    /** The setting itself (shown in the ⋮ menu). */
    val on = MutableStateFlow(false)
    /** Bumped whenever new text from the running emulator arrives (so a waiting screen re-checks). */
    val textTick = MutableStateFlow(0)

    private const val PREFS = "emulators"
    private const val KEY_ON = "detect"
    private const val KEY_CONSENT = "consent"
    private const val MAX_SEEN = 24
    private const val MAX_RECENT = 6

    private val seen = ArrayDeque<String>()
    private val emulatorCache = HashMap<String, Boolean>()
    private val labelCache = HashMap<String, String>()
    private var homePackage: String? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context) = prefs(context).getBoolean(KEY_ON, false)
    fun hasConsent(context: Context) = prefs(context).getBoolean(KEY_CONSENT, false)

    /** Turns detection on (after the disclosure was agreed to) or off. */
    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply { if (on) putBoolean(KEY_CONSENT, true) }.apply()
        refresh(context)
    }

    /** Re-reads whether detection should run (after a setting or the Cocoon link changes). */
    fun refresh(context: Context) {
        val now = isOn(context) && hasConsent(context) &&
            com.nerfherder.walkthor.data.CocoonLaunchLog.linkedFolder(context) == null
        on.value = isOn(context)
        active.value = now
        if (!now) end()
        SwipeGestureService.instance?.get()?.applyEventTypes()
    }

    fun end() {
        session.value = null
        synchronized(seen) { seen.clear() }
    }

    /** Text the running emulator has shown this session (window titles, its notifications). */
    fun seenText(): List<String> = synchronized(seen) { seen.toList() }

    /**
     * From the accessibility service: an app came to the front, or posted a notification.
     * Returns the new session when an emulator just started one (so the service can open WalkThor).
     */
    fun onEvent(context: Context, event: AccessibilityEvent): Session? {
        if (!active.value) return null
        val pkg = event.packageName?.toString() ?: return null
        if (pkg == context.packageName) return null
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (isEmulator(context, pkg)) {
                    val current = session.value
                    val started = if (current?.pkg != pkg) {
                        end()
                        Session(pkg, label(context, pkg), System.currentTimeMillis()).also { session.value = it }
                    } else null
                    remember(event.text.map { it.toString() } + listOfNotNull(event.contentDescription?.toString()), pkg, context)
                    return started
                }
                // Back on the home screen or in a frontend: the game is over.
                if (session.value != null && isHomeOrFrontend(context, pkg)) end()
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // Only the running emulator's own notifications; everything else is ignored outright.
                if (session.value?.pkg != pkg) return null
                val n = event.parcelableData as? Notification
                val texts = buildList {
                    event.text.forEach { add(it.toString()) }
                    n?.extras?.let { e ->
                        e.getCharSequence(Notification.EXTRA_TITLE)?.let { add(it.toString()) }
                        e.getCharSequence(Notification.EXTRA_TEXT)?.let { add(it.toString()) }
                        e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { add(it.toString()) }
                    }
                }
                remember(texts, pkg, context)
            }
        }
        return null
    }

    private fun remember(texts: List<String>, pkg: String, context: Context) {
        val name = label(context, pkg)
        val useful = texts.map { it.trim() }.filter { t ->
            t.length in 3..160 && !t.equals(name, ignoreCase = true)
        }
        if (useful.isEmpty()) return
        synchronized(seen) {
            useful.forEach { t ->
                seen.remove(t)
                seen.addLast(t)
                while (seen.size > MAX_SEEN) seen.removeFirst()
            }
        }
        textTick.value++
    }

    /** Whether [pkg] is an emulator, from its package and app name. */
    fun isEmulator(context: Context, pkg: String): Boolean = emulatorCache.getOrPut(pkg) {
        val p = pkg.lowercase()
        val l = label(context, pkg).lowercase()
        KNOWN.any { it in p || it in l } ||
            "emulator" in p || "emulator" in l ||
            p.split('.', '_').any { it.endsWith("emu") && it.length > 3 }
    }

    private fun isHomeOrFrontend(context: Context, pkg: String): Boolean {
        val home = homePackage ?: runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()?.also { homePackage = it }
        if (pkg == home) return true
        val p = pkg.lowercase()
        val l = label(context, pkg).lowercase()
        return FRONTENDS.any { it in p || it in l }
    }

    private fun label(context: Context, pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /** Guides you've picked while playing in [pkg], most recent first. */
    fun recentGuides(context: Context, pkg: String): List<String> =
        prefs(context).getString("recent/$pkg", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    fun noteGuide(context: Context, pkg: String, bookId: String) {
        val list = (listOf(bookId) + recentGuides(context, pkg).filter { it != bookId }).take(MAX_RECENT)
        prefs(context).edit().putString("recent/$pkg", list.joinToString(",")).apply()
    }

    /** Forget a deleted guide everywhere. */
    fun forgetGuide(context: Context, bookId: String) {
        val p = prefs(context)
        val e = p.edit()
        p.all.filterKeys { it.startsWith("recent/") }.forEach { (k, v) ->
            val left = (v as? String)?.split(',')?.filter { it.isNotBlank() && it != bookId } ?: return@forEach
            e.putString(k, left.joinToString(","))
        }
        e.apply()
    }

    private val KNOWN = listOf(
        "dolphin", "retroarch", "ppsspp", "aethersx2", "nethersx2", "armsx2", "pcsx2", "duckstation", "swanstation",
        "citron", "yuzu", "sudachi", "suyu", "ryujinx", "torzu", "skyline", "citra", "lime3ds", "azahar",
        "melonds", "drastic", "mupen", "m64plus", "flycast", "redream", "vita3k", "cemu", "xenia", "ax360",
        "mgba", "snes9x", "nestopia", "fceumm", "epsxe", "fpse", "mame4droid", "scummvm", "dosbox", "yabasanshiro",
        "gearboy", "my boy", "john gba", "pizza boy", "rpcsx", "shadps4", "winlator", "gamehub",
    )
    private val FRONTENDS = listOf(
        "cocoon", "es-de", "es_de", "esde", "daijish", "beacon", "console launcher", "consolelauncher", "iisu",
        "pegasus", "odin", "launcher",
    )
}
