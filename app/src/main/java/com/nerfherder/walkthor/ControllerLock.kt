package com.nerfherder.walkthor

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Keeps the controller on one screen. Android gives the controller to whichever screen was touched
 * last, so tapping the bottom screen mid-game takes it away from the game (and tapping the top one
 * takes it from the bottom app). Locked to a screen, WalkThor puts a one-pixel invisible window on
 * the other screen, which Android tells whenever that screen is touched (not where, and the touch
 * still goes where it was meant to). Each time, a moment later, WalkThor taps the very top edge of
 * the locked screen, as the game hand-back does, and the controller is back there.
 *
 * Needs nothing beyond accessibility access. Typing in WalkThor's own search pauses a lock on the
 * top screen until you're done.
 */
object ControllerLock {
    enum class Screen(val label: String) { TOP("top screen"), BOTTOM("bottom screen") }

    /** The screen the controller's kept on, or null when it's free to follow your touches. */
    val locked = MutableStateFlow<Screen?>(null)

    /** What happened last, for Diagnostics. */
    @Volatile var lastNote: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val watchers = mutableListOf<Pair<WindowManager, View>>()
    private var movedBack = 0

    /** How long after a touch on the other screen the controller's taken back (the touch has landed by then). */
    private const val BACK_AFTER_MS = 250L

    private fun prefs(c: Context) = SwipeLauncher.prefs(c)

    /** After WalkThor restarts: the lock you had. */
    fun restore(service: SwipeGestureService) {
        val saved = prefs(service).getString("controllerLock", null)?.let { n -> Screen.entries.firstOrNull { it.name == n } }
        locked.value = saved
        main.post { apply(service, announce = false) }
    }

    /** The shortcut: lock to [screen], or unlock if it's already locked there. Says which in a message. */
    fun toggle(service: SwipeGestureService, screen: Screen) {
        val next = if (locked.value == screen) null else screen
        locked.value = next
        prefs(service).edit().putString("controllerLock", next?.name).apply()
        main.post { apply(service, announce = true) }
    }

    /** The display the controller's kept on, while locked. */
    @Volatile private var wantDisplay: Int? = null

    /**
     * An app came to the front on the other screen. Android gives the controller to the screen an
     * app starts on, with no touch at all: Android starting your stand-in home app on the bottom
     * screen while a game loads (when WalkThor pauses your home app, or the memory killer closed
     * it and Android started it again) took the controller from the game. Taken back a moment
     * later, once the app is up.
     */
    fun onAppFront(service: SwipeGestureService, event: android.view.accessibility.AccessibilityEvent) {
        if (locked.value == null || android.os.Build.VERSION.SDK_INT < 30) return
        if (event.eventType != android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val want = wantDisplay ?: return
        if (event.displayId == want || event.displayId < 0) return
        val pkg = event.packageName?.toString() ?: return
        // WalkThor's own little windows (the drawer, the loading cover, messages) never take focus.
        if (pkg == service.packageName && event.className?.toString()?.let { it.startsWith("android.widget.") || it == "android.view.View" } == true) return
        if (pkg != service.packageName && !EmulatorWatch.isApp(service, pkg)) return // keyboards, system overlays
        takeBack(service, want, APP_FRONT_AFTER_MS)
        lastNote = "keeping the controller on the ${locked.value?.label} (display $want); ${pkg.substringAfterLast('.')} came up on the other screen"
    }

    /** How long after an app comes up on the other screen the controller's taken back (it's resumed by then). */
    private const val APP_FRONT_AFTER_MS = 600L

    /** Kept for the service's window events (no longer needed). */
    fun onWindowsChanged(service: SwipeGestureService) = Unit

    /** The screens changed (one turned on or off): watch the right ones. */
    fun displaysChanged(service: SwipeGestureService) {
        if (locked.value == null) return
        main.post { if (screensOn(service) != watchedScreens) apply(service, announce = false) }
    }

    /** The screens that were on when the watchers went up. */
    private var watchedScreens: Set<Int> = emptySet()

    private fun screensOn(c: Context): Set<Int> = c.getSystemService(DisplayManager::class.java)?.displays.orEmpty()
        .filter { it.state != Display.STATE_OFF }.map { it.displayId }.toSet()

    /** Puts the watchers up (or takes them down) to match [locked]. Main thread. */
    private fun apply(service: SwipeGestureService, announce: Boolean) {
        clear()
        val screen = locked.value
        wantDisplay = null
        if (screen == null) {
            lastNote = "unlocked"
            if (announce) ScreenMessage.show("Controller unlocked", 2_000)
            return
        }
        val want = wantedDisplay(service, screen)
        if (want == null) {
            // (lastNote says what Android reported.)
            if (announce) ScreenMessage.show("Couldn't find the ${screen.label} (see Diagnostics)")
            return
        }
        val dm = service.getSystemService(DisplayManager::class.java)
        for (display in dm?.displays.orEmpty()) {
            if (display.displayId == want || display.state == Display.STATE_OFF) continue
            runCatching { watch(service, display, want) }
                .onFailure { lastNote = "couldn't watch display ${display.displayId} (${it.javaClass.simpleName})" }
        }
        watchedScreens = screensOn(service)
        wantDisplay = want
        movedBack = 0
        // The controller to the locked screen now.
        val how = service.tapToFocus(want)
        lastNote = "keeping the controller on the ${screen.label} (display $want); watching ${watchers.size} other screen(s); first move: $how"
        if (announce) ScreenMessage.show("Controller locked to the ${screen.label}", 2_000)
    }

    /** A one-pixel window on [display] that hears about every touch there. */
    private fun watch(service: SwipeGestureService, display: Display, want: Int) {
        val ctx = service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val v = object : View(ctx) {
            // Reported as a plain view, so WalkThor doesn't take it for an app coming to the front.
            override fun getAccessibilityClassName(): CharSequence = "android.view.View"

            override fun onTouchEvent(event: MotionEvent): Boolean {
                // A touch anywhere else on this screen: the controller has just moved here.
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_DOWN) takeBack(service, want)
                return false
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setTitle("WalkThor controller lock")
        }
        wm.addView(v, params)
        watchers += wm to v
    }

    private fun takeBack(service: SwipeGestureService, want: Int, after: Long = BACK_AFTER_MS) {
        main.removeCallbacks(back)
        backTo = service to want
        main.postDelayed(back, after)
    }

    private var backTo: Pair<SwipeGestureService, Int>? = null
    private val back = Runnable {
        val (service, want) = backTo ?: return@Runnable
        val screen = locked.value ?: return@Runnable
        // Typing in WalkThor's search needs the keyboard on the bottom screen for a moment.
        if (screen == Screen.TOP && GameFocus.wantFocus.value) return@Runnable
        val how = service.tapToFocus(want)
        movedBack++
        lastNote = "keeping the controller on the ${screen.label} (display $want); moved it back $movedBack time(s), last: $how"
    }

    private fun clear() {
        main.removeCallbacks(back)
        watchers.forEach { (wm, v) -> runCatching { wm.removeViewImmediate(v) } }
        watchers.clear()
    }

    /** The two screens as last found (top, bottom), so a screen briefly reported as off doesn't lose them. */
    private var known: Pair<Int, Int>? = null

    /**
     * The display for [screen]: of the public displays, the bottom one is the smaller and the top
     * one the bigger. Android's on/off state isn't relied on here: it can briefly report a screen
     * as off while it's on.
     */
    private fun wantedDisplay(c: Context, screen: Screen): Int? {
        val all = c.getSystemService(DisplayManager::class.java)?.displays.orEmpty()
            // Private displays (a recording's, say) aren't screens. (The bottom screen counts as a
            // "presentation" display, so that flag isn't a reason to leave one out.)
            .filter { (it.flags and Display.FLAG_PRIVATE) == 0 }
        fun size(d: Display) = d.mode.physicalWidth.toLong() * d.mode.physicalHeight
        // (A recording's screen, like the clip buffer's, is private, so it's left out above.)
        val builtIn = all
        if (builtIn.size >= 2) {
            val bottom = builtIn.minByOrNull(::size)!!
            val top = builtIn.filter { it.displayId != bottom.displayId }.maxByOrNull(::size)!!
            known = top.displayId to bottom.displayId
        }
        val (top, bottom) = known ?: run {
            lastNote = "couldn't tell the screens apart; Android reported: " + all.joinToString { d ->
                "display ${d.displayId} ${d.mode.physicalWidth}x${d.mode.physicalHeight} " +
                    (if (d.state == Display.STATE_OFF) "off" else "on")
            }.ifEmpty { "no displays" }
            return null
        }
        return if (screen == Screen.TOP) top else bottom
    }
}
