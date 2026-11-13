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

    fun toggle(service: AccessibilityService) { if (off.value) turnOn(service) else turnOff(service, game = false) }

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

    fun turnOn(service: Context) {
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
     * What's in front on the top screen changed: off when a game comes up, on when it goes. Waits
     * a moment for things to settle first (frontends and emulators flick past each other as a
     * game starts or closes), so the screen doesn't flash on and off.
     */
    fun gameFront(service: AccessibilityService) {
        settleFor = service
        main.removeCallbacks(settle)
        main.postDelayed(settle, 1_200)
    }
    private var settleFor: AccessibilityService? = null
    private val settle = Runnable {
        val service = settleFor ?: return@Runnable
        val showing = EmulatorWatch.gameShowing.value
        if (showing != gameWas) {
            gameWas = showing
            if (gameEnabled(service)) runCatching { if (showing) turnOff(service, game = true) else if (byGame) turnOn(service) }
        }
    }

    /** A black window over the whole bottom screen; a double tap on it turns the screen back on. */
    private fun cover(service: AccessibilityService, displayId: Int): Boolean {
        val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return false
        val ctx = service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val w = ctx.getSystemService(WindowManager::class.java) ?: return false
        val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { turnOn(service); return true }
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
