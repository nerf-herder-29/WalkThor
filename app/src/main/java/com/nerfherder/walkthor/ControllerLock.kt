package com.nerfherder.walkthor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Keeps the controller on one screen. Android sends button presses to whichever screen was
 * touched last, so tapping the bottom screen mid-game takes the controller away from the game
 * (and tapping the top one takes it from the bottom app). Locked to a screen, whenever Android
 * moves its focus to the other one, WalkThor moves it straight back: through AYN's system helper
 * when it's usable (Force SELinux off; no touch at all), otherwise with one short tap on the very
 * top edge of the locked screen, as the game hand-back does.
 *
 * While locked, the accessibility service watches which window has focus on each screen (only
 * which screen it's on; nothing in the windows is read). Typing in WalkThor's own search pauses
 * the lock until you're done.
 */
object ControllerLock {
    enum class Screen(val label: String) { TOP("top screen"), BOTTOM("bottom screen") }

    /** The screen the controller's kept on, or null when it's free to follow your touches. */
    val locked = MutableStateFlow<Screen?>(null)

    /** What happened last, for Diagnostics. */
    @Volatile var lastNote: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor controller lock").apply { isDaemon = true } }
    private var lastMove = 0L
    @Volatile private var moving = false

    private fun prefs(c: Context) = SwipeLauncher.prefs(c)

    /** After WalkThor restarts: the lock you had. */
    fun restore(service: SwipeGestureService) {
        val saved = prefs(service).getString("controllerLock", null)?.let { n -> Screen.entries.firstOrNull { it.name == n } }
        locked.value = saved
        service.applyEventTypes()
        if (saved != null) main.postDelayed({ check(service) }, 500)
    }

    /** The shortcut: lock to [screen], or unlock if it's already locked there. Says which in a message. */
    fun toggle(service: SwipeGestureService, screen: Screen) {
        val next = if (locked.value == screen) null else screen
        locked.value = next
        prefs(service).edit().putString("controllerLock", next?.name).apply()
        service.applyEventTypes()
        if (next != null) {
            ScreenMessage.show("Controller locked to the ${next.label}", 2_000)
            lastMove = 0L
            check(service)
        } else {
            ScreenMessage.show("Controller unlocked", 2_000)
        }
    }

    /** Which windows have focus changed (only heard while locked). */
    fun onWindowsChanged(service: SwipeGestureService) {
        if (locked.value == null) return
        main.removeCallbacks(recheck)
        main.postDelayed(recheck, 60)
    }

    private val recheck = Runnable { SwipeGestureService.instance?.get()?.let { check(it) } }

    /** The display that has the controller now, or null if it can't be told. */
    private fun focusedDisplay(service: AccessibilityService): Int? {
        if (Build.VERSION.SDK_INT < 30) return null
        val all = runCatching { service.windowsOnAllDisplays }.getOrNull() ?: return null
        var found: Int? = null
        for (i in 0 until all.size()) {
            val id = all.keyAt(i)
            val windows = all.valueAt(i) ?: continue
            if (windows.any { it.isFocused && it.isActive }) return id
            if (found == null && windows.any { it.isFocused }) found = id
        }
        return found
    }

    private fun wantedDisplay(c: Context, screen: Screen): Int? {
        val bottom = DisplayHelper.bottomDisplay(c)?.displayId ?: return null
        if (screen == Screen.BOTTOM) return bottom
        val dm = c.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return null
        return dm.displays.firstOrNull {
            it.displayId != bottom && it.state != android.view.Display.STATE_OFF && (it.flags and android.view.Display.FLAG_PRIVATE) == 0
        }?.displayId
    }

    /** If the controller's on the wrong screen, moves it back (no more than a few times a second). */
    private fun check(service: SwipeGestureService) {
        val screen = locked.value ?: return
        // Typing in WalkThor's search needs the keyboard on the bottom screen for a moment.
        if (screen == Screen.TOP && GameFocus.wantFocus.value) return
        val want = wantedDisplay(service, screen) ?: return
        val now = focusedDisplay(service)
        if (now == null || now == want || moving) return
        val t = SystemClock.uptimeMillis()
        if (t - lastMove < 350) { main.postDelayed(recheck, 380 - (t - lastMove)); return }
        lastMove = t
        moving = true
        val app = service.applicationContext
        worker.execute {
            val how = runCatching {
                if (AynSystem.knownAvailable() == true) AynSystem.focusDisplay(app, want)?.takeIf { it.startsWith("OK") }?.let { "helper" } else null
            }.getOrNull()
            main.post {
                moving = false
                val outcome = how ?: service.tapToFocus(want)
                lastNote = "moved the controller back to the ${screen.label} ($outcome)"
            }
        }
    }
}
