package com.nerfherder.walkthor

import android.content.Context
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the controller on one screen. Android sends button presses to whichever screen was
 * touched last, so tapping the bottom screen mid-game takes the controller away from the game
 * (and tapping the top one takes it from the bottom app). Locked to a screen, WalkThor keeps the
 * controller there: a small program run by AYN's system helper (Force SELinux off) checks several
 * times a second which screen has it and moves it straight back. Touches still work on both.
 *
 * Typing in WalkThor's own search pauses a lock on the top screen until you're done.
 */
object ControllerLock {
    enum class Screen(val label: String) { TOP("top screen"), BOTTOM("bottom screen") }

    /** The screen the controller's kept on, or null when it's free to follow your touches. */
    val locked = MutableStateFlow<Screen?>(null)

    /** What happened last, for Diagnostics. */
    @Volatile var lastNote: String? = null
        private set

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor controller lock").apply { isDaemon = true } }
    private var watchingTyping = false

    private fun prefs(c: Context) = SwipeLauncher.prefs(c)

    /** After WalkThor restarts: the lock you had. */
    fun restore(service: SwipeGestureService) {
        val saved = prefs(service).getString("controllerLock", null)?.let { n -> Screen.entries.firstOrNull { it.name == n } }
        locked.value = saved
        service.applyEventTypes()
        watchTyping(service.applicationContext)
        apply(service.applicationContext, announce = false)
    }

    /** The shortcut: lock to [screen], or unlock if it's already locked there. Says which in a message. */
    fun toggle(service: SwipeGestureService, screen: Screen) {
        val next = if (locked.value == screen) null else screen
        locked.value = next
        prefs(service).edit().putString("controllerLock", next?.name).apply()
        service.applyEventTypes()
        watchTyping(service.applicationContext)
        apply(service.applicationContext, announce = true)
    }

    /** Kept for the service's window events; the helper's watcher does the work now. */
    fun onWindowsChanged(service: SwipeGestureService) = Unit

    /** Starts or stops the helper's watcher to match [locked] (and typing in WalkThor's search). */
    private fun apply(app: Context, announce: Boolean) {
        val screen = locked.value
        worker.execute {
            if (screen == null) {
                AynSystem.focusUnwatch()
                lastNote = "unlocked"
                if (announce) ScreenMessage.show("Controller unlocked", 2_000)
                return@execute
            }
            val want = wantedDisplay(app, screen)
            if (want == null) {
                lastNote = "couldn't find the ${screen.label}"
                if (announce) ScreenMessage.show("Couldn't find the ${screen.label}")
                return@execute
            }
            if (!AynSystem.available()) {
                lastNote = "needs Force SELinux off (AYN's system helper)"
                if (announce) ScreenMessage.show("Controller lock needs Force SELinux off", 3_000)
                return@execute
            }
            // Typing in WalkThor's search needs the keyboard on the bottom screen for a moment.
            if (screen == Screen.TOP && GameFocus.wantFocus.value) {
                AynSystem.focusUnwatch()
                lastNote = "paused while you type"
                return@execute
            }
            val started = AynSystem.focusWatch(app, want)
            lastNote = if (started) "keeping the controller on the ${screen.label} (display $want)"
            else "couldn't start the helper's watcher: ${AynSystem.focusWatchNote}"
            if (announce) ScreenMessage.show(if (started) "Controller locked to the ${screen.label}" else "Couldn't lock the controller (see Diagnostics)", 2_500)
        }
    }

    /** Pauses a top-screen lock while you type in WalkThor's search, and resumes it after. */
    private fun watchTyping(app: Context) {
        if (watchingTyping) return
        watchingTyping = true
        MainScope().launch {
            var was = GameFocus.wantFocus.value
            GameFocus.wantFocus.collect { typing ->
                if (typing != was && locked.value == Screen.TOP) apply(app, announce = false)
                was = typing
            }
        }
    }

    /** The helper's watcher's latest notes, for Diagnostics. Not on the main thread. */
    fun watcherLog(): List<String> = runCatching { AynSystem.focusWatchLog() }.getOrDefault(emptyList())

    private fun wantedDisplay(c: Context, screen: Screen): Int? {
        val bottom = DisplayHelper.bottomDisplay(c)?.displayId ?: return null
        if (screen == Screen.BOTTOM) return bottom
        val dm = c.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return null
        return dm.displays.firstOrNull {
            it.displayId != bottom && it.state != android.view.Display.STATE_OFF && (it.flags and android.view.Display.FLAG_PRIVATE) == 0
        }?.displayId
    }
}
