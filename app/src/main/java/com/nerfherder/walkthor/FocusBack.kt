package com.nerfherder.walkthor

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Keeps the game as Android's app in front while your home app is paused.
 *
 * Android gives its "app in front" spot to the screen an app last started on, and that spot
 * decides who the memory killer closes last. Pausing your home app makes Android start its own
 * launcher on the bottom screen as the game loads, and the launcher took the spot: in every heavy
 * Banana Native session the game was then closed first. A tap on the game's screen after it loaded
 * put it back in front, and in five sessions in a row it outlasted the squeeze that had closed it
 * before. So WalkThor makes that tap itself: on the very top edge of the game's screen (as the
 * controller lock and the hand-back after opening do), a few times while the game loads, and again
 * whenever a home app comes up on the bottom screen during the game (Android restarting it after
 * the memory killer closed it). No checks first: asking Android who's in front didn't work mid-game.
 */
object FocusBack {
    private val main = Handler(Looper.getMainLooper())
    /** After the home app's paused: while the game loads, and once it has. */
    private val AFTER_PAUSE_MS = longArrayOf(2_000L, 8_000L, 20_000L, 40_000L)
    @Volatile private var notedFor = 0L
    private val pending = mutableListOf<Runnable>()

    /** The home app was just paused for a game. */
    fun afterPause(c: android.content.Context) {
        main.post {
            pending.forEach { main.removeCallbacks(it) }
            pending.clear()
            for (delay in AFTER_PAUSE_MS) {
                val r = Runnable { tap("the game started") }
                pending += r
                main.postDelayed(r, delay)
            }
        }
    }

    /** An app came up: a home app on the bottom screen during the game gets the game tapped back in front. */
    fun onAppFront(service: SwipeGestureService, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || android.os.Build.VERSION.SDK_INT < 30) return
        if (!EmulatorWatch.gameShowing.value || !HomeSwap.pausedNow(service)) return
        val bottom = DisplayHelper.bottomDisplay(service)?.displayId ?: return
        if (event.displayId != bottom) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == service.packageName || !isHomeApp(service, pkg)) return
        main.removeCallbacks(homeBack)
        main.postDelayed(homeBack, 800)
    }

    private val homeBack = Runnable { tap("a home app came up on the bottom screen") }

    private fun tap(why: String) {
        val service = SwipeGestureService.instance?.get() ?: return
        if (!EmulatorWatch.gameShowing.value || GameFocus.wantFocus.value) return
        // Controller locked to the bottom screen: you want it there.
        if (ControllerLock.locked.value == ControllerLock.Screen.BOTTOM) return
        // A game drawn on both screens has nothing on the bottom screen to take its place.
        if (EmulatorWatch.bothScreensGameShowing(service)) return
        val bottom = DisplayHelper.bottomDisplay(service)?.displayId ?: return
        val gameDisplay = DisplayHelper.usableDisplays(service).filter { it.displayId != bottom }
            .maxByOrNull { d -> d.mode.physicalWidth.toLong() * d.mode.physicalHeight }?.displayId ?: return
        val how = service.tapToFocus(gameDisplay)
        val since = MemoryWatch.gameStartedAt()
        if (notedFor != since) {
            notedFor = since
            MemoryWatch.noteFrom(service, "Kept the game in front for the memory killer ($why): tapped the top edge of its screen ($how)")
        }
    }

    private val homeCache = HashMap<String, Boolean>()
    private fun isHomeApp(c: android.content.Context, pkg: String): Boolean = homeCache.getOrPut(pkg) {
        runCatching {
            c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .any { it.activityInfo.packageName == pkg }
        }.getOrDefault(false)
    }
}
