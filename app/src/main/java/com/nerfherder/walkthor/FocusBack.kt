package com.nerfherder.walkthor

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Keeps the game as Android's app in front while it plays.
 *
 * Android gives its "app in front" spot to whichever screen an app last started on, and that spot
 * decides who the memory killer closes last. When WalkThor pauses your home app, Android starts
 * its own launcher on the bottom screen as the game loads (and again whenever the memory killer
 * closes it), and the launcher takes the spot: the game drops behind the bottom screen's apps and
 * is the first thing closed. With the pause off, nothing started there and the game outlasted
 * everything else.
 *
 * So during a game WalkThor asks Android which app it has in front (right after pausing the home
 * app, then every few seconds), and if it's a home app on the bottom screen, taps the very top
 * edge of the game's screen (as the controller lock and the hand-back after opening do), which
 * puts the game back in front. Only home apps: an app you open on the bottom screen yourself, or
 * WalkThor's own screens, are left alone.
 */
object FocusBack {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var notedFor = 0L
    @Volatile private var lastCheck = 0L
    @Volatile private var fixes = 0

    /** Right after the home app was paused: a look once the launcher has had time to come up. */
    fun afterPause(c: android.content.Context) {
        val app = c.applicationContext
        for (delay in longArrayOf(1_500L, 4_000L, 8_000L)) {
            main.postDelayed({ Thread({ runCatching { check(app, force = true) } }, "WalkThor focus").start() }, delay)
        }
    }

    /** From an app coming up anywhere: a home app on the bottom screen gets a look shortly after. */
    fun onAppFront(service: SwipeGestureService, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!EmulatorWatch.gameShowing.value) return
        val pkg = event.packageName?.toString() ?: return
        if (!isHomeApp(service, pkg)) return
        val app = service.applicationContext
        main.postDelayed({ Thread({ runCatching { check(app, force = true) } }, "WalkThor focus").start() }, 800)
    }

    /**
     * Whether Android has the game in front; if a home app has the spot instead, the game's
     * screen is tapped. Off the main thread (it asks through AYN's system helper). [force]:
     * look now, whatever the last look was.
     */
    @Synchronized fun check(c: android.content.Context, force: Boolean = false) {
        if (!EmulatorWatch.gameShowing.value || GameFocus.wantFocus.value) return
        // Controller locked to the bottom screen: you want it there.
        if (ControllerLock.locked.value == ControllerLock.Screen.BOTTOM) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 5_000L) return
        lastCheck = now
        if (AynSystem.knownAvailable() != true) return
        val game = MemoryWatch.gamePkg ?: return
        val top = OomRanks.topApp() ?: return
        if (top == game || !isHomeApp(c, top)) return
        val service = SwipeGestureService.instance?.get() ?: return
        main.post {
            val bottom = DisplayHelper.bottomDisplay(service)?.displayId ?: return@post
            val gameDisplay = DisplayHelper.usableDisplays(service).filter { it.displayId != bottom }
                .maxByOrNull { d -> d.mode.physicalWidth.toLong() * d.mode.physicalHeight }?.displayId ?: return@post
            val how = service.tapToFocus(gameDisplay)
            fixes++
            val since = MemoryWatch.gameStartedAt()
            if (notedFor != since) {
                notedFor = since
                fixes = 1
                MemoryWatch.noteFrom(service, "The game back in front: ${MemoryWatch.labelOf(service, top)} had taken Android's top spot " +
                    "(the game would have been closed first for memory). Tapped the game's screen: $how")
            }
        }
    }

    private val homeCache = HashMap<String, Boolean>()
    @Synchronized private fun isHomeApp(c: android.content.Context, pkg: String): Boolean = homeCache.getOrPut(pkg) {
        runCatching {
            c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .any { it.activityInfo.packageName == pkg }
        }.getOrDefault(false)
    }
}
