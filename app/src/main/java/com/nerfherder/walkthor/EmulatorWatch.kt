package com.nerfherder.walkthor

import android.app.Notification
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * "Open when an emulator starts": opens WalkThor whenever an emulator comes to the front.
 *
 * While it's switched on, WalkThor's accessibility service tells this which app comes to the
 * front. Each time an emulator does (after any other app was in front), a game session starts
 * and WalkThor opens on the bottom screen with the game's tabs. With Cocoon linked, Cocoon's
 * launch log names the game as usual. Without it, WalkThor works out which game it is from
 *  1. any text the emulator shows that matches one of your games or guides (its window title or
 *     its own notifications; only the running emulator's, kept in memory for this session only),
 *  2. else the game or guide you picked last time in this emulator,
 *  3. else it asks, with your picks for this emulator first.
 * The session ends when another app (your frontend, the home screen) comes to the front; apps
 * you open from WalkThor's Apps tab don't count.
 */
object EmulatorWatch {
    /**
     * A game running in [name] (the emulator's app name), since [at]. [picked] is the name you
     * last highlighted in your frontend just before (with "Recognise the game you pick").
     */
    data class Session(val pkg: String, val name: String, val at: Long, val picked: List<String> = emptyList())

    /** The last thing you highlighted or selected in another app (your frontend), in memory only. */
    data class Heard(val pkg: String, val app: String, val text: String, val at: Long)
    val heard = MutableStateFlow<Heard?>(null)
    /** The last few names heard (newest last), so a "Play" button pressed after the game doesn't hide it. */
    private val recentHeard = ArrayDeque<Heard>()
    /** "Recognise the game you pick" (the setting), and whether it's running (it needs detection on too). */
    val pickOn = MutableStateFlow(false)
    val pickActive = MutableStateFlow(false)

    val session = MutableStateFlow<Session?>(null)
    /** On and agreed to: the service should listen for apps coming to the front. */
    val active = MutableStateFlow(false)
    /** The setting itself (shown in the ⋮ menu). */
    val on = MutableStateFlow(false)
    /** Bumped whenever new text from the running emulator arrives (so a waiting screen re-checks). */
    val textTick = MutableStateFlow(0)

    private const val PREFS = "emulators"
    private const val KEY_ON = "detect"
    private const val KEY_CONSENT = "consent"
    private const val KEY_PICK = "pick"
    private const val KEY_PICK_CONSENT = "pickConsent"
    /** How long a highlighted name counts as "what you just launched". */
    private const val PICK_MS = 45_000L
    private const val MAX_SEEN = 24
    private const val MAX_RECENT = 8

    private val seen = ArrayDeque<String>()
    private val emulatorCache = HashMap<String, Boolean>()
    private val labelCache = HashMap<String, String>()
    private val appCache = HashMap<String, Boolean>()
    /** The last app seen coming to the front, so going back to the same emulator counts as a new launch. */
    private var lastFront: String? = null
    /** Apps WalkThor opened on its own screen (Apps tab), and when: they don't end or restart a game. */
    private val openedHere = HashMap<String, Long>()

    /** WalkThor's Apps tab just opened [pkg] on WalkThor's screen. */
    fun openedByWalkThor(pkg: String) { synchronized(openedHere) { openedHere[pkg] = System.currentTimeMillis() } }
    private fun isOpenedHere(pkg: String) = synchronized(openedHere) {
        openedHere[pkg]?.let { System.currentTimeMillis() - it < OPENED_HERE_MS } == true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context) = prefs(context).getBoolean(KEY_ON, false)
    fun isPickOn(context: Context) = prefs(context).getBoolean(KEY_PICK, false)
    fun hasPickConsent(context: Context) = prefs(context).getBoolean(KEY_PICK_CONSENT, false)

    /** Turns "Recognise the game you pick" on (after its explanation was agreed to) or off. */
    fun setPickOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_PICK, on).apply { if (on) putBoolean(KEY_PICK_CONSENT, true) }.apply()
        if (!on) { heard.value = null; synchronized(recentHeard) { recentHeard.clear() } }
        refresh(context)
    }
    fun hasConsent(context: Context) = prefs(context).getBoolean(KEY_CONSENT, false)

    /** Turns detection on (after the disclosure was agreed to) or off. */
    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply { if (on) putBoolean(KEY_CONSENT, true) }.apply()
        refresh(context)
    }

    /** Re-reads whether detection should run (after a setting or the Cocoon link changes). */
    fun refresh(context: Context) {
        val now = isOn(context) && hasConsent(context)
        on.value = isOn(context)
        active.value = now
        pickOn.value = isPickOn(context)
        pickActive.value = now && isPickOn(context) && hasPickConsent(context)
        if (!pickActive.value) heard.value = null
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
                // System UI, keyboards and overlays aren't apps you switch to; and apps you opened
                // from WalkThor's Apps tab sit on WalkThor's screen, beside the game.
                if (!isApp(context, pkg) || isOpenedHere(pkg)) return null
                val previous = lastFront
                lastFront = pkg
                if (isEmulator(context, pkg)) {
                    val current = session.value
                    // A new launch: a different emulator, or this one again after another app.
                    val started = if (current?.pkg != pkg || previous != pkg) {
                        end()
                        val now = System.currentTimeMillis()
                        // What you highlighted in your frontend just before is most likely the game.
                        val picked = synchronized(recentHeard) {
                            recentHeard.filter { now - it.at < PICK_MS && it.pkg != pkg }.map { it.text }.asReversed().distinct()
                        }
                        Session(pkg, label(context, pkg), now, picked).also { session.value = it }
                    } else null
                    remember(event.text.map { it.toString() } + listOfNotNull(event.contentDescription?.toString()), pkg, context)
                    return started
                }
                // Another app came to the front (your frontend, the home screen): the game is over.
                if (session.value != null) end()
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                // "Recognise the game you pick": only the latest name highlighted in an app (your
                // frontend), never from emulators, keyboards or system UI, and kept in memory only.
                if (!pickActive.value || isEmulator(context, pkg) || !isApp(context, pkg) || isOpenedHere(pkg)) return null
                val name = label(context, pkg)
                val text = (listOfNotNull(event.contentDescription?.toString()) + event.text.map { it.toString() })
                    .map { it.trim() }
                    .firstOrNull { it.length in 2..150 && !it.equals(name, ignoreCase = true) }
                    ?: return null
                val h = Heard(pkg, name, text, System.currentTimeMillis())
                heard.value = h
                synchronized(recentHeard) {
                    recentHeard.removeAll { it.text == text }
                    recentHeard.addLast(h)
                    while (recentHeard.size > 6) recentHeard.removeFirst()
                }
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

    /** An app you can switch to (it has a home-screen icon), or an emulator. */
    private fun isApp(context: Context, pkg: String): Boolean = appCache.getOrPut(pkg) {
        isEmulator(context, pkg) || runCatching { context.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
    }

    private fun label(context: Context, pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /**
     * What you've picked while playing in [pkg], most recent first: "r:<rom id>" for a game from your
     * ROMs folder, "g:<guide id>" for a guide (older entries without a prefix are guides).
     */
    fun recentEntries(context: Context, pkg: String): List<String> =
        prefs(context).getString("recent/$pkg", null)
            // Older lists held only guide ids, separated by commas.
            ?.let { raw -> if ('\u0001' in raw || raw.startsWith("r:") || raw.startsWith("g:")) raw.split('\u0001') else raw.split(',') }
            ?.filter { it.isNotBlank() }
            ?.map { if (it.startsWith("r:") || it.startsWith("g:")) it else "g:$it" } ?: emptyList()

    /** Guide ids you've picked in [pkg], most recent first. */
    fun recentGuides(context: Context, pkg: String): List<String> =
        recentEntries(context, pkg).filter { it.startsWith("g:") }.map { it.drop(2) }

    fun noteGuide(context: Context, pkg: String, bookId: String) = note(context, pkg, "g:$bookId")
    fun noteRom(context: Context, pkg: String, romId: String) = note(context, pkg, "r:$romId")

    private fun note(context: Context, pkg: String, entry: String) {
        val list = (listOf(entry) + recentEntries(context, pkg).filter { it != entry }).take(MAX_RECENT)
        // ROM file names can contain commas, so entries are separated by an unprintable character.
        prefs(context).edit().putString("recent/$pkg", list.joinToString("\u0001")).apply()
    }

    /** Forget a deleted guide everywhere. */
    fun forgetGuide(context: Context, bookId: String) {
        val p = prefs(context)
        val e = p.edit()
        p.all.keys.filter { it.startsWith("recent/") }.forEach { k ->
            val pkg = k.removePrefix("recent/")
            e.putString(k, recentEntries(context, pkg).filter { it != "g:$bookId" }.joinToString("\u0001"))
        }
        e.apply()
    }

    private val KNOWN = listOf(
        "dolphin", "retroarch", "ppsspp", "aethersx2", "nethersx2", "armsx2", "pcsx2", "duckstation", "swanstation",
        "citron", "yuzu", "sudachi", "suyu", "ryujinx", "torzu", "skyline", "citra", "lime3ds", "azahar",
        "melonds", "drastic", "mupen", "m64plus", "flycast", "redream", "vita3k", "cemu", "xenia", "ax360",
        "mgba", "snes9x", "nestopia", "fceumm", "epsxe", "fpse", "mame4droid", "scummvm", "dosbox", "yabasanshiro",
        "gearboy", "my boy", "john gba", "pizza boy", "rpcsx", "shadps4", "winlator", "gamehub", "mobox", "gamenative", "pluvia", "box64",
    )
    private const val OPENED_HERE_MS = 6 * 60 * 60 * 1000L
}
