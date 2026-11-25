package com.nerfherder.walkthor

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * "Open when an emulator starts", once the game has loaded. A game is hungriest for memory in its
 * first minute or so, while it loads, and that is when Android's critical warnings came and games
 * were closed, with WalkThor up on the bottom screen beside them. So while the game loads, the
 * bottom screen is just black (a plain window, next to no memory), and WalkThor comes up once
 * memory has stopped dropping: no big fall in the last 15 seconds, and no critical warning for 8.
 * At most 2½ minutes. A tap on the black brings WalkThor up straight away.
 */
object LoadWait {
    private const val MIN_WAIT_MS = 20_000L
    private const val MAX_WAIT_MS = 150_000L
    private const val WINDOW_MS = 15_000L
    private const val STEADY_DROP_MB = 150L

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var view: View? = null
    private var tick: Runnable? = null
    private var openNow: (() -> Unit)? = null

    /** Waiting now (for Diagnostics, and so a second game start doesn't start another wait). */
    val waiting get() = tick != null

    /** Waits for [pkg] to finish loading, black on [bottom] meanwhile, then [open]s WalkThor. */
    fun start(service: AccessibilityService, bottom: Int, pkg: String, open: () -> Unit) {
        stop()
        cover(service, bottom)
        openNow = open
        val started = SystemClock.elapsedRealtime()
        val readings = ArrayDeque<Pair<Long, Long>>() // (when, free MB)
        MemoryWatch.noteFrom(service, "Game loading: WalkThor waits on a black bottom screen until memory steadies")
        val am = service.getSystemService(ActivityManager::class.java)
        val t = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                // The game's gone, or someone opened WalkThor meanwhile: nothing to wait for.
                val gameStill = EmulatorWatch.topGame(service)?.pkg == pkg
                if (!gameStill || Drawer.shown.value) { stop(); return }
                val info = ActivityManager.MemoryInfo()
                runCatching { am?.getMemoryInfo(info) }
                val free = info.availMem / (1024 * 1024)
                readings.addLast(now to free)
                while (readings.isNotEmpty() && now - readings.first().first > WINDOW_MS) readings.removeFirst()
                val waited = now - started
                val peak = readings.maxOf { it.second }
                val steady = waited >= MIN_WAIT_MS && peak - free < STEADY_DROP_MB && MemoryWatch.msSinceCritical() >= 8_000L
                if (steady || waited >= MAX_WAIT_MS) {
                    MemoryWatch.noteFrom(service, if (steady) "Game loaded (memory steady after ${waited / 1000} s, free $free MB): WalkThor up" else "Still loading after ${waited / 1000} s: WalkThor up anyway")
                    finish()
                    return
                }
                main.postDelayed(this, 1_000)
            }
        }
        tick = t
        main.postDelayed(t, 1_000)
    }

    /** Opens WalkThor now, and takes the black away just after (the drawer comes up out of black). */
    private fun finish() {
        val open = openNow
        tick?.let { main.removeCallbacks(it) }
        tick = null
        openNow = null
        runCatching { open?.invoke() }
        main.postDelayed({ uncover() }, 700)
    }

    /** Stops waiting, and takes the black away. */
    fun stop() {
        tick?.let { main.removeCallbacks(it) }
        tick = null
        openNow = null
        uncover()
    }

    private fun cover(service: AccessibilityService, displayId: Int) {
        runCatching {
            val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return
            val ctx = service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val w = ctx.getSystemService(WindowManager::class.java) ?: return
            val v = View(ctx).apply {
                setBackgroundColor(Color.BLACK)
                // A tap: WalkThor now, without waiting.
                @Suppress("ClickableViewAccessibility")
                setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP && tick != null) finish(); true }
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
                title = "WalkThor waiting for the game to load"
            }
            w.addView(v, params)
            wm = w
            view = v
        }
    }

    private fun uncover() {
        val v = view ?: return
        runCatching { wm?.removeView(v) }
        view = null
        wm = null
    }
}
