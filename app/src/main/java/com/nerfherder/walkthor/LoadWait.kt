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
 * bottom screen is just black (a plain window, next to no memory). At 10 seconds WalkThor checks
 * memory: if free memory has stopped falling (under 150 MB in the last 5 seconds) and at least
 * 2.5 GB is free, WalkThor comes up. If not, it checks again every 5 seconds. At most 90 seconds.
 * A tap on the black brings WalkThor up straight away.
 */
object LoadWait {
    private const val FIRST_CHECK_MS = 10_000L
    private const val CHECK_EVERY_MS = 5_000L
    private const val MAX_WAIT_MS = 90_000L
    /** Free memory may fall this much between checks and still count as loaded. */
    private const val STEADY_DROP_MB = 150L

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var view: View? = null
    private var tick: Runnable? = null
    private var openNow: (() -> Unit)? = null
    private var log: LogView? = null

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
                while (readings.isNotEmpty() && now - readings.first().first > CHECK_EVERY_MS) readings.removeFirst()
                val waited = now - started
                // Free memory 5 seconds ago (the oldest reading kept).
                val before = readings.first().second
                // Loaded: free memory stopped falling, and enough of it left.
                val calmNow = before - free < STEADY_DROP_MB && free >= MemoryWatch.REAL_CRITICAL_MB
                // Checked at 10 seconds, then every 5.
                val checkNow = waited >= FIRST_CHECK_MS && (waited - FIRST_CHECK_MS) % CHECK_EVERY_MS < 1_000L
                val steady = checkNow && calmNow && readings.size >= 4
                // On screen: how long it's been, free memory now, and when the next check is.
                log?.let { v ->
                    v.elapsed = waited
                    v.free = free
                    v.status = when {
                        steady -> "loaded"
                        waited < FIRST_CHECK_MS -> "first check at 0:10"
                        else -> {
                            val left = CHECK_EVERY_MS - (waited - FIRST_CHECK_MS) % CHECK_EVERY_MS
                            "still loading · next check in ${(left + 999) / 1000} s"
                        }
                    }
                    v.blink = !v.blink
                    v.invalidate()
                }
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

    /**
     * The loading screen: how long the game has been loading, free memory now, and when WalkThor
     * next checks. Drawn straight on the window (no WalkThor screens loaded).
     */
    private class LogView(ctx: android.content.Context, private val accent: Int) : View(ctx) {
        var elapsed = 0L
        var free = -1L
        var status = "first check at 0:10"
        var blink = false
        private val mono = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE }
        private val hint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }

        override fun onDraw(canvas: android.graphics.Canvas) {
            canvas.drawColor(Color.BLACK)
            val w = width.toFloat(); val h = height.toFloat()
            val small = w * 0.03f
            val left = w * 0.14f
            var y = h * 0.28f
            mono.textSize = small
            mono.color = accent; mono.alpha = 205
            canvas.drawText("› game loading" + if (blink) " _" else "", left, y, mono)
            // The timer, big.
            y += small * 4.2f
            mono.textSize = w * 0.09f
            mono.color = 0xFFBDB6AB.toInt()
            canvas.drawText("%d:%02d".format(elapsed / 60_000, (elapsed / 1000) % 60), left, y, mono)
            // Free memory now.
            y += small * 2.6f
            mono.textSize = small * 1.25f
            mono.color = 0xFF8E877C.toInt()
            if (free >= 0) canvas.drawText("%.1f GB free".format(java.util.Locale.US, free / 1024f), left, y, mono)
            // When WalkThor next looks.
            y += small * 2f
            mono.textSize = small
            mono.color = 0xFF5D5850.toInt()
            canvas.drawText(status, left, y, mono)
            hint.textSize = w * 0.024f
            hint.color = 0xFF4A4540.toInt()
            canvas.drawText("Tap for WalkThor", w / 2f, h - w * 0.06f, hint)
        }
    }

    private fun cover(service: AccessibilityService, displayId: Int) {
        runCatching {
            val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return
            val ctx = service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val w = ctx.getSystemService(WindowManager::class.java) ?: return
            val accent = runCatching {
                StickLights.color(service).let { if (it == 0) 0xFFEDE6DA.toInt() else StickLights.swatch(it) or 0xFF000000.toInt() }
            }.getOrDefault(0xFF2BE0D8.toInt())
            val v = LogView(ctx, accent).apply {
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
            log = v
        }
    }

    private fun uncover() {
        val v = view ?: return
        runCatching { wm?.removeView(v) }
        view = null
        log = null
        wm = null
    }
}
