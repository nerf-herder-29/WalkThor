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
    /** [from]: the app on the game screen just before (your frontend), if any. */
    data class Session(val pkg: String, val name: String, val at: Long, val picked: List<String> = emptyList(), val from: String? = null)

    /** The last thing you highlighted or selected in another app (your frontend), in memory only. */
    data class Heard(val pkg: String, val app: String, val text: String, val at: Long)
    val heard = MutableStateFlow<Heard?>(null)
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
    private val labelCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val appCache = HashMap<String, Boolean>()
    private var homePackage: String? = null
    /** The last app seen coming to the front, so going back to the same emulator counts as a new launch. */
    private var lastFront: String? = null
    /** Apps WalkThor opened on its own screen (Apps tab), and when: they don't end or restart a game. */
    private val openedHere = HashMap<String, Long>()
    /** The app last seen on the bottom screen (WalkThor's screen): WalkThor itself, or another. */
    @Volatile private var bottomApp: String? = null

    /**
     * Your frontend still has its own screen on the bottom display while the game runs (a dual-screen
     * frontend like iiSU). Jumping in front of it there makes some close the game, so WalkThor waits
     * for your swipe instead.
     */
    fun frontendOnBottom(context: Context, session: Session): Boolean {
        val b = bottomApp ?: return false
        // Cocoon hands its bottom screen to WalkThor, so opening over it is fine.
        return b != context.packageName && b == session.from && "cocoon" !in b.lowercase()
    }

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
        if (!on) { heard.value = null; scan.value = null; synchronized(appeared) { appeared.clear(); onScreen = emptyList() }; focusedTexts = emptyList() }
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
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && onBottom(context, event)) {
            // WalkThor's screen: what shows there doesn't start or end a game (a dual-screen
            // frontend keeps its second screen there while the game runs). Just note who it is.
            if (pkg == context.packageName || isApp(context, pkg)) bottomApp = pkg
            return null
        }
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
                        val picked = pickedBefore(now)
                        val from = previous?.takeIf { it != pkg && !isEmulator(context, it) }
                        Session(pkg, label(context, pkg), now, picked, from).also { session.value = it }
                    } else null
                    remember(event.text.map { it.toString() } + listOfNotNull(event.contentDescription?.toString()), pkg, context)
                    return started
                }
                // Another app came to the front (your frontend, the home screen): the game is over.
                if (session.value != null) end()
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // "Recognise the game you pick": read your frontend's screen (only frontends and the
                // home-screen app; never emulators or other apps), kept in memory only.
                if (!pickActive.value || isEmulator(context, pkg) || isOpenedHere(pkg) || !isFrontend(context, pkg)) return null
                readFrontend(context, event, pkg)
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

    /** The event happened on the bottom screen (WalkThor's), on a two-screen device. */
    private fun onBottom(context: Context, event: AccessibilityEvent): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        val bottom = runCatching { DisplayHelper.bottomDisplay(context)?.displayId }.getOrNull() ?: return false
        return event.displayId == bottom
    }

    /** What WalkThor last read from your frontend's screen (for the readout). */
    data class Scan(val app: String, val items: Int, val texts: List<String>, val at: Long, val windows: Int = 1)
    val scan = MutableStateFlow<Scan?>(null)
    /** Each text on the frontend's screen and when it last appeared there (newest last). */
    private val appeared = LinkedHashMap<String, Long>()
    @Volatile private var onScreen: List<String> = emptyList()
    @Volatile private var focusedTexts: List<String> = emptyList()
    @Volatile private var focusedAt = 0L
    private var lastScanAt = 0L

    /**
     * Reads the text on the frontend's screen. Text that just appeared (a details panel showing the
     * game you moved to) is noted with the time, so the game you launch is the newest one that
     * matches your games; the highlighted item's own name counts first.
     */
    /** Screen reading runs here, off the accessibility service's main thread. */
    private val readHandler by lazy {
        android.os.Handler(android.os.HandlerThread("walkthor-read").apply { start() }.looper)
    }
    @Volatile private var readQueued = false

    private fun readFrontend(context: Context, event: AccessibilityEvent, pkg: String) {
        val now = System.currentTimeMillis()
        val focus = event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
        // Animated frontends send a flood of content changes: read at most twice a second, and
        // never pile reads up. Highlight changes always count.
        if (!focus && (readQueued || now - lastScanAt < 500)) return
        lastScanAt = now
        readQueued = true
        // Only the event's own fields here; walking the screen asks the frontend for every item,
        // which is slow, so it happens on the reading thread (or Android may stop the service).
        val own = listOfNotNull(event.contentDescription?.toString()) + event.text.map { it.toString() }
        val source = runCatching { event.source }.getOrNull()
        readHandler.post {
            try {
                readScreen(context, pkg, focus, own, source, now)
            } catch (_: Throwable) {
            } finally {
                readQueued = false
            }
        }
    }

    /**
     * Reads the text on the frontend's screen. Text that just appeared (a details panel showing the
     * game you moved to) is noted with the time, so the game you launch is the newest one that
     * matches your games; the highlighted item's own name counts first.
     */
    private fun readScreen(
        context: Context, pkg: String, focus: Boolean, own: List<String>,
        source: android.view.accessibility.AccessibilityNodeInfo?, now: Long,
    ) {
        val name = label(context, pkg)
        if (focus) {
            val t = (own + itemTexts(source))
                .map { it.trim() }.filter { it.length in 2..150 && !it.equals(name, true) }.distinct()
            if (t.isNotEmpty()) synchronized(appeared) { focusedTexts = t; focusedAt = now }
        }
        // Every window the frontend has, on both screens: a dual-screen frontend (iiSU) shows its
        // menu on one screen and the games on the other, and either can be the one that changed.
        val roots = frontendRoots(context, pkg).ifEmpty {
            listOfNotNull(
                runCatching { source?.window?.root }.getOrNull()
                    ?: runCatching { (context as? android.accessibilityservice.AccessibilityService)?.rootInActiveWindow }.getOrNull()
            )
        }
        var count = 0
        val texts = ArrayList<String>()
        for (r in roots) {
            val (t, c) = screenTexts(r, 500 - count)
            texts += t
            count += c
            if (count >= 500) break
        }
        val clean = texts.map { it.trim() }.filter { it.length in 2..150 && !it.equals(name, true) }.distinct()
        val newest = synchronized(appeared) {
            val before = onScreen.toSet()
            clean.filter { it !in before }.forEach { appeared.remove(it); appeared[it] = now }
            while (appeared.size > 300) appeared.remove(appeared.keys.first())
            onScreen = clean
            appeared.entries.lastOrNull { it.key in clean }?.key
        }
        // Turned off while reading: keep nothing.
        if (!pickActive.value) return
        scan.value = Scan(name, count, clean.take(10), now, roots.size)
        (synchronized(appeared) { focusedTexts.firstOrNull() } ?: newest)?.let { heard.value = Heard(pkg, name, it, now) }
    }

    /** Names to try for the game just launched, most likely first. */
    private fun pickedBefore(now: Long): List<String> = synchronized(appeared) {
        val focused = if (now - focusedAt < PICK_MS) focusedTexts else emptyList()
        val recent = appeared.entries.filter { now - it.value < PICK_MS }.map { it.key }.asReversed()
        (focused + recent + onScreen).distinct().take(80)
    }

    /** All the text on a screen (a few hundred items at most). */
    /** The roots of all of [pkg]'s windows, on every screen (Android 11 and later). */
    private fun frontendRoots(context: Context, pkg: String): List<android.view.accessibility.AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        val service = context as? android.accessibilityservice.AccessibilityService ?: return emptyList()
        val all = runCatching { service.windowsOnAllDisplays }.getOrNull() ?: return emptyList()
        val out = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
        for (i in 0 until all.size()) {
            all.valueAt(i).orEmpty().forEach { w ->
                runCatching { w.root }.getOrNull()?.takeIf { it.packageName?.toString() == pkg }?.let(out::add)
            }
        }
        return out
    }

    private fun screenTexts(root: android.view.accessibility.AccessibilityNodeInfo, budget: Int = 300): Pair<List<String>, Int> {
        val out = ArrayList<String>()
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < budget) {
            val n = queue.removeFirst()
            count++
            n.text?.toString()?.let(out::add)
            n.contentDescription?.toString()?.let(out::add)
            for (i in 0 until n.childCount.coerceAtMost(40)) runCatching { n.getChild(i) }.getOrNull()?.let(queue::add)
        }
        return out to count
    }

    /** Your frontend: the home-screen app, or a known frontend (Cocoon, Daijisho, ES-DE…). */
    private fun isFrontend(context: Context, pkg: String): Boolean {
        val home = homePackage ?: runCatching {
            context.packageManager.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()?.also { homePackage = it }
        if (pkg == home) return true
        val p = pkg.lowercase()
        val l = label(context, pkg).lowercase()
        return FRONTENDS.any { it in p || it in l }
    }

    /** The highlighted item's name, and the labels just inside it (a few levels, a dozen at most). */
    private fun itemTexts(source: android.view.accessibility.AccessibilityNodeInfo?): List<String> {
        val root = source ?: return emptyList()
        val out = ArrayList<String>()
        val queue = ArrayDeque<Pair<android.view.accessibility.AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var seen = 0
        while (queue.isNotEmpty() && seen < 12) {
            val (node, depth) = queue.removeFirst()
            seen++
            node.contentDescription?.toString()?.let(out::add)
            node.text?.toString()?.let(out::add)
            if (depth < 3) {
                for (i in 0 until node.childCount.coerceAtMost(8)) {
                    runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return out
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

    /** Apps you marked as emulators (or not), overriding the automatic guess. */
    fun chosen(context: Context): Map<String, Boolean> =
        prefs(context).getStringSet("emuYes", emptySet())!!.associateWith { true } +
            prefs(context).getStringSet("emuNo", emptySet())!!.associateWith { false }

    fun setChosen(context: Context, pkg: String, isEmu: Boolean) {
        val p = prefs(context)
        val yes = p.getStringSet("emuYes", emptySet())!!.toMutableSet()
        val no = p.getStringSet("emuNo", emptySet())!!.toMutableSet()
        yes.remove(pkg); no.remove(pkg)
        if (isEmu != autoEmulator(context, pkg)) (if (isEmu) yes else no).add(pkg)
        p.edit().putStringSet("emuYes", yes).putStringSet("emuNo", no).apply()
        synchronized(emulatorCache) { emulatorCache.clear() }
        appCache.clear()
    }

    /** Whether [pkg] is an emulator: your choice if you made one, else a guess from its names. */
    fun isEmulator(context: Context, pkg: String): Boolean = synchronized(emulatorCache) {
        emulatorCache.getOrPut(pkg) { chosen(context)[pkg] ?: autoEmulator(context, pkg) }
    }

    /** The automatic guess, from its package and app name. */
    fun autoEmulator(context: Context, pkg: String): Boolean {
        val p = pkg.lowercase()
        val l = label(context, pkg).lowercase()
        return KNOWN.any { it in p || it in l } ||
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
        "xendroid", "xenon", "rpcs3", "lemuroid", "emubox", "nostalgia", "kenji", "uzuy", "eggns", "egg ns",
    )
    private const val OPENED_HERE_MS = 6 * 60 * 60 * 1000L
    private val FRONTENDS = listOf(
        "cocoon", "es-de", "es_de", "esde", "daijish", "beacon", "console launcher", "consolelauncher", "iisu",
        "pegasus", "odin", "launcher", "frontend", "retro hub", "gamehub launcher",
    )
}
