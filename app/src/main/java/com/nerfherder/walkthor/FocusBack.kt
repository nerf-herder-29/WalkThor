package com.nerfherder.walkthor

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Keeps the game as Android's app in front while a home app comes up on the bottom screen.
 *
 * Android gives the controller, and its "app in front" rank for the memory killer, to whichever
 * screen an app last started on. When your stand-in home app starts on the bottom screen during a
 * game (WalkThor pausing your home app, or Android restarting it after the memory killer closed
 * it), the home app becomes the app in front (rank 0) and the game drops to "visible" (100 or so),
 * and the memory killer then closes the heavy game before almost anything else. A moment later,
 * WalkThor taps the very top edge of the game's screen (as the controller lock and the hand-back
 * after opening do), and the game is in front again. Only for home apps, so an app you open on the
 * bottom screen yourself keeps the controller.
 */
object FocusBack {
    private val main = Handler(Looper.getMainLooper())
    private const val AFTER_MS = 700L
    @Volatile private var notedFor = 0L

    fun onAppFront(service: SwipeGestureService, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || android.os.Build.VERSION.SDK_INT < 30) return
        if (!EmulatorWatch.gameShowing.value) return
        // The controller lock already takes it back to the screen it's locked to.
        if (ControllerLock.locked.value != null) return
        val bottom = DisplayHelper.bottomDisplay(service)?.displayId ?: return
        if (event.displayId != bottom) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == service.packageName || !isHomeApp(service, pkg)) return
        main.removeCallbacks(back)
        backFor = service to pkg
        main.postDelayed(back, AFTER_MS)
    }

    private var backFor: Pair<SwipeGestureService, String>? = null
    private val back = Runnable {
        val (service, pkg) = backFor ?: return@Runnable
        if (!EmulatorWatch.gameShowing.value || GameFocus.wantFocus.value) return@Runnable
        val bottom = DisplayHelper.bottomDisplay(service)?.displayId ?: return@Runnable
        val game = DisplayHelper.usableDisplays(service).filter { it.displayId != bottom }
            .maxByOrNull { d -> d.mode.physicalWidth.toLong() * d.mode.physicalHeight }?.displayId ?: return@Runnable
        val how = service.tapToFocus(game)
        val since = MemoryWatch.gameStartedAt()
        if (notedFor != since) {
            notedFor = since
            MemoryWatch.noteFrom(service, "The game back in front (${pkg.substringAfterLast('.')} had come up on the bottom screen and taken Android's top spot): $how")
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
