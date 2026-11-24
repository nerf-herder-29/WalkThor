package com.nerfherder.walkthor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The bottom screen switched off: WalkThor puts its drawer away, lets go of what it was holding
 * in memory, and covers the screen in black, which on its OLED panel is as good as off (black
 * pixels aren't lit). Double-tap the screen, or tap the AYN button, to turn it back on.
 *
 * It never touches the screen's brightness, so there's nothing to undo: if WalkThor stops for any
 * reason, the cover goes with it and the screen is simply on again. When WalkThor comes back, it
 * puts the cover back if the screen was off.
 *
 * Two ways in, each its own setting: holding the AYN button, and a game coming to the top screen
 * (it comes back on by itself when the game closes).
 */
object BottomOff {
    val off = MutableStateFlow(false)
    /** Turned off because a game came up (so it comes back on when the game goes). */
    @Volatile private var byGame = false
    /** Whether a game was in front last we looked (only a change counts). */
    @Volatile private var gameWas = false
    private var view: View? = null
    private var wm: WindowManager? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor bottom off").apply { isDaemon = true } }

    private fun prefs(c: Context) = SwipeLauncher.prefs(c)
    fun holdEnabled(c: Context) = prefs(c).getBoolean("bottomOffHold", false)
    fun setHoldEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("bottomOffHold", on).apply()
    fun gameEnabled(c: Context) = prefs(c).getBoolean("bottomOffGame", false)
    fun setGameEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("bottomOffGame", on).apply()

    // ---- holding the AYN button: the bottom screen dimmed all the way down, or back up ----

    /** The bottom screen is dimmed right down by a hold (kept, so a hold undoes it even after a restart). */
    fun dimmed(c: Context) = prefs(c).getBoolean("bottomHeldDim", false)

    /** A hold: dims the bottom screen to 0%; again, back up to the top screen's level. */
    fun holdToggle(service: AccessibilityService) {
        if (off.value) { turnOn(service); return }
        if (dimmed(service)) undim(service) else {
            prefs(service).edit().putBoolean("bottomHeldDim", true).apply()
            AynButton.note("held → bottom screen dimmed to 0%")
            setBottom(service, 0f)
        }
    }

    /** Back up to the top screen's brightness, if a hold had dimmed it. */
    fun undim(c: Context) {
        if (!dimmed(c)) return
        prefs(c).edit().putBoolean("bottomHeldDim", false).apply()
        AynButton.note("bottom screen back to the top screen's level")
        setBottom(c, null)
    }

    /** [level], or the top screen's level when null. */
    private fun setBottom(c: Context, level: Float?) {
        val app = c.applicationContext
        worker.execute {
            // Finds out whether AYN's helper can be used (not on the main thread), then sets it.
            runCatching { AynSystem.available() }
            main.post { runCatching { ScreenControls.setBottom(app, level ?: ScreenControls.top(app).coerceAtLeast(0.05f)) } }
        }
    }

    fun turnOff(service: AccessibilityService, game: Boolean, note: Boolean = true) {
        if (off.value) { if (!game) byGame = false; return }
        val bottom = DisplayHelper.bottomDisplay(service) ?: return
        runCatching { Drawer.hide() }
        if (!cover(service, bottom.displayId)) return
        off.value = true
        byGame = game
        runCatching { Revive.noteBottomOff(service, true, game) }
        if (note) AynButton.note(if (game) "game started → bottom screen off" else "held → bottom screen off")
        // Less for Android to want back while the game runs.
        runCatching { com.nerfherder.walkthor.data.PdfSource.trimAll() }
        runCatching { coil.Coil.imageLoader(service).memoryCache?.clear() }
    }

    /**
     * Memory's critical mid-game: WalkThor gets off the bottom screen before Android closes it (and
     * then brings it back, which can be what costs the game). The drawer or app there is put away
     * and the plain black cover takes its place: tiny, and it keeps the home app from coming up
     * underneath. Double-tap for the screen back; it's back by itself when the game closes.
     * Only WalkThor's own windows, never the system helper.
     */
    fun pauseForMemory(service: AccessibilityService) {
        if (off.value) return
        val bottom = DisplayHelper.bottomDisplay(service)?.displayId ?: return
        val drawerUp = Drawer.shown.value && AppState.drawerDisplayId == bottom
        val appUp = AppState.resumedDisplayId == bottom
        if (!drawerUp && !appUp) return
        // The game uses the bottom screen itself: WalkThor just gets out of its way, no cover.
        if (EmulatorWatch.bothScreensGameShowing(service)) {
            if (drawerUp) runCatching { Drawer.hide() }
            if (appUp) runCatching { AppState.activity?.get()?.moveTaskToBack(true) }
            MemoryWatch.noteFrom(service, "WalkThor stepped off the bottom screen for the game (it uses both screens)")
            return
        }
        if (appUp) runCatching { AppState.activity?.get()?.finishAndRemoveTask() }
        turnOff(service, game = true, note = false)
        if (off.value) {
            // What to bring back once memory's recovered (kept, in case Android closes WalkThor meanwhile).
            prefs(service).edit().putString(MEM_PAUSE, if (drawerUp) "drawer" else "app").apply()
            watchMemory(service)
            gameWas = true
            AynButton.note("memory critical → WalkThor off the bottom screen")
            MemoryWatch.noteFrom(service, "WalkThor stepped off the bottom screen for the game (black until the game closes; double-tap for it back)")
        }
    }

    private const val MEM_PAUSE = "bottomMemPause"

    /** WalkThor stepped off the bottom screen for memory (and hasn't come back yet). */
    fun pausedForMemory(c: Context) = off.value && prefs(c).getString(MEM_PAUSE, null) != null

    /**
     * Memory's recovered (or you double-tapped): the screen back on, with the drawer or app that
     * was there, if the game's still playing. Once the game's over, just the screen.
     */
    fun resumeFromMemory(service: AccessibilityService, why: String) {
        val kind = prefs(service).getString(MEM_PAUSE, null) ?: return
        if (!off.value) { prefs(service).edit().remove(MEM_PAUSE).apply(); return }
        turnOn(service)
        if (!EmulatorWatch.gameShowing.value) return
        val bottom = DisplayHelper.bottomDisplay(service)?.displayId ?: return
        if (kind == "drawer") runCatching { Drawer.show(service, bottom, opened = true, fade = true) }
        else runCatching { Revive.openApp(service) }
        MemoryWatch.noteFrom(service, "WalkThor back on the bottom screen ($why)")
    }

    private var recoveredSince = 0L

    /**
     * While stepped off for memory: looks every second (just Android's memory figures) and brings
     * WalkThor back once Android's critical warnings have stopped for 8 seconds and there's over
     * 1.8 GB free (well clear of the 1.5 GB where the game is put first). Works even if Android
     * closed WalkThor meanwhile and it started again.
     */
    private fun watchMemory(service: AccessibilityService) {
        recoveredSince = 0L
        main.removeCallbacks(memoryCheck)
        memoryFor = service
        main.postDelayed(memoryCheck, 1_000)
    }
    private var memoryFor: AccessibilityService? = null
    private val memoryCheck = object : Runnable {
        override fun run() {
            val service = memoryFor ?: return
            if (!pausedForMemory(service)) return
            if (!EmulatorWatch.gameShowing.value) return // the game closing turns the screen on
            val info = android.app.ActivityManager.MemoryInfo()
            runCatching { service.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info) }
            val ok = !info.lowMemory && info.availMem > 1_800L * 1024 * 1024 && MemoryWatch.msSinceCritical() >= 8_000L
            val now = android.os.SystemClock.elapsedRealtime()
            if (!ok) recoveredSince = 0L else if (recoveredSince == 0L) recoveredSince = now
            // A couple of good readings in a row, so a momentary dip or spike doesn't decide it.
            if (ok && now - recoveredSince >= 2_000L) { resumeFromMemory(service, "memory settled"); return }
            main.postDelayed(this, 1_000)
        }
    }

    fun turnOn(service: Context) {
        prefs(service).edit().remove(MEM_PAUSE).apply()
        if (!off.value) return
        off.value = false
        byGame = false
        view?.let { v -> runCatching { wm?.removeViewImmediate(v) } }
        view = null
        wm = null
        runCatching { Revive.noteBottomOff(service, false, false) }
        AynButton.note("bottom screen on")
    }

    /**
     * WalkThor's service has just started (after the device started, or after Android closed
     * WalkThor): the cover goes back if the screen was off, and a brightness an older version
     * turned down is put back up.
     */
    fun afterRestart(service: AccessibilityService) {
        Revive.bottomWasOff(service)?.let { game ->
            turnOff(service, game, note = false)
            // So that the game closing still turns it back on.
            if (off.value && game) gameWas = true
            // Stepped off for memory: back once there's memory for it.
            if (pausedForMemory(service)) watchMemory(service)
            AynButton.note("WalkThor restarted → bottom screen off again")
        }
        val old = prefs(service).getFloat("bottomOffLevel", -1f)
        if (old >= 0f) {
            prefs(service).edit().remove("bottomOffLevel").apply()
            val bottom = DisplayHelper.bottomDisplay(service)?.displayId ?: return
            // Only if it's still all the way down.
            worker.execute {
                runCatching {
                    if (AynSystem.available() && (AynSystem.brightness(service, bottom) ?: 1f) < 0.03f) {
                        AynSystem.setBrightness(service, bottom, old.coerceAtLeast(0.1f))
                    }
                }
            }
        }
    }

    /**
     * What's in front on the top screen changed: off when a game comes up, on when it goes.
     * Going off is almost at once: a frontend flicking past afterwards doesn't matter while the
     * screen's black. Coming back on waits for things to settle (frontends and emulators flick
     * past each other as a game starts or closes), so the screen doesn't light up mid-launch.
     */
    fun gameFront(service: AccessibilityService) {
        settleFor = service
        main.removeCallbacks(settle)
        val goingOff = EmulatorWatch.gameShowing.value && !gameWas
        main.postDelayed(settle, if (goingOff) 250L else 1_200L)
    }
    private var settleFor: AccessibilityService? = null
    private val settle = Runnable {
        val service = settleFor ?: return@Runnable
        val showing = EmulatorWatch.gameShowing.value
        if (showing != gameWas) {
            gameWas = showing
            runCatching {
                // Not over a game that draws on the bottom screen itself.
                if (showing) { if (gameEnabled(service) && !EmulatorWatch.bothScreensGameShowing(service)) turnOff(service, game = true) }
                // Off for the game (by this setting, or to free memory): back on once it's gone.
                else if (byGame) turnOn(service)
            }
        }
    }

    /** A black window over the whole bottom screen; a double tap on it turns the screen back on. */
    private fun cover(service: AccessibilityService, displayId: Int): Boolean {
        val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return false
        val ctx = service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val w = ctx.getSystemService(WindowManager::class.java) ?: return false
        val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (pausedForMemory(service)) resumeFromMemory(service, "double-tapped") else turnOn(service)
                return true
            }
        })
        val v = View(ctx).apply {
            setBackgroundColor(Color.BLACK)
            @Suppress("ClickableViewAccessibility")
            setOnTouchListener { _, e -> taps.onTouchEvent(e); true }
            keepScreenOn = false
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "WalkThor bottom screen off"
        }
        if (runCatching { w.addView(v, params) }.isFailure) return false
        wm = w
        view = v
        return true
    }
}
