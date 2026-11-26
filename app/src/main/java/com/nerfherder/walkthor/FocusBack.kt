package com.nerfherder.walkthor

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Keeps the game ranked ahead of the bottom screen's app while your home app is paused.
 *
 * Android ranks the visible apps for its memory killer partly by the order they came up: the most
 * recent ranks best. With your home app left alone, it was up before the game, so the game ranked
 * better (100/101) and your home app worse (102): when memory ran out the memory killer closed your
 * home app (the flashing), and the game lived. Pausing your home app starts Android's launcher on
 * the bottom screen after the game: the launcher then ranked better and the game 102, and the game
 * went first, every time. Taps on the game's screen only moved the controller, not that order.
 *
 * So after the launcher comes up, WalkThor brings the game's own window back to the front through
 * AYN's system helper (as if you'd switched back to it from recent apps, with nothing restarted):
 * the game is the most recent again. A moment after your home app is paused, and whenever a home
 * app comes up on the bottom screen during the game (Android restarting its launcher after the
 * memory killer closed it). Never while memory is critical (the command starts a small program).
 */
object FocusBack {
    private val main = Handler(Looper.getMainLooper())
    /** After your home app is paused: once the launcher is up, and again a little later. */
    private val AFTER_PAUSE_MS = longArrayOf(1_500L, 6_000L)
    private val pending = mutableListOf<Runnable>()
    @Volatile private var lastAt = 0L

    /** Your home app was just paused for a game. */
    fun afterPause(c: android.content.Context) {
        val app = c.applicationContext
        main.post {
            pending.forEach { main.removeCallbacks(it) }
            pending.clear()
            for (delay in AFTER_PAUSE_MS) {
                val r = Runnable { bringBack(app, "the launcher came up after the game") }
                pending += r
                main.postDelayed(r, delay)
            }
        }
    }

    /** An app came up: a home app on the bottom screen during the game gets the game brought back in front of it. */
    fun onAppFront(service: SwipeGestureService, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || android.os.Build.VERSION.SDK_INT < 30) return
        if (!EmulatorWatch.gameShowing.value || !HomeSwap.pausedNow(service)) return
        val bottom = DisplayHelper.bottomDisplay(service)?.displayId ?: return
        if (event.displayId != bottom) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == service.packageName || !isHomeApp(service, pkg)) return
        val app = service.applicationContext
        main.removeCallbacks(homeBack)
        homeBackFor = app
        main.postDelayed(homeBack, 1_000)
    }

    private var homeBackFor: android.content.Context? = null
    private val homeBack = Runnable { homeBackFor?.let { bringBack(it, "a home app came up on the bottom screen") } }

    private fun bringBack(c: android.content.Context, why: String) {
        if (!EmulatorWatch.gameShowing.value || GameFocus.wantFocus.value) return
        if (runCatching { EmulatorWatch.bothScreensGameShowing(c) }.getOrDefault(false)) return
        // Not twice within a few seconds (the launcher coming up and the timer can land together).
        val now = System.currentTimeMillis()
        if (now - lastAt < 3_000L) return
        lastAt = now
        val game = MemoryWatch.gamePkg ?: return
        Thread({ runCatching { bringBackNow(c, game, why) } }, "WalkThor game to front").start()
    }

    private fun bringBackNow(c: android.content.Context, game: String, why: String) {
        val free = runCatching {
            val mi = android.app.ActivityManager.MemoryInfo()
            c.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(mi)
            mi.availMem / (1024 * 1024)
        }.getOrDefault(0L)
        if (free < MemoryWatch.REAL_CRITICAL_MB) return
        if (AynSystem.knownAvailable() != true && !AynSystem.available()) return
        // The game's window, as Android lists it: "… dev.bananative.android/.SomeActivity t123}".
        val lines = AynSystem.shellLines("dumpsys activity activities | grep -E 'ResumedActivity|mFocusedApp'").orEmpty()
        val component = lines.firstNotNullOfOrNull { l ->
            Regex(" (" + Regex.escape(game) + "/[A-Za-z0-9_.$]+)").find(l)?.groupValues?.get(1)
        }
        if (component == null) {
            MemoryWatch.noteFrom(c, "Couldn't bring the game back to the front ($why): its window wasn't listed")
            return
        }
        // Brought to the front as it is: its existing window reordered, nothing new started.
        val out = AynSystem.shellLines("am start --activity-reorder-to-front -n $component").orEmpty()
        val result = out.firstOrNull { it.startsWith("Error") || it.startsWith("Warning") } ?: "done"
        MemoryWatch.noteFrom(c, "Brought the game back to the front ($why), so it outranks the bottom screen's app: $result")
    }

    private val homeCache = HashMap<String, Boolean>()
    @Synchronized private fun isHomeApp(c: android.content.Context, pkg: String): Boolean = homeCache.getOrPut(pkg) {
        runCatching {
            c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .any { it.activityInfo.packageName == pkg }
        }.getOrDefault(false)
    }
}
