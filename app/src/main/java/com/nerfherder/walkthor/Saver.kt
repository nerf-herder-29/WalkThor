package com.nerfherder.walkthor

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * RAM saver mode. During a game, when memory gets tight (under 3 GB free, or free memory falling
 * fast, as when a game loads or changes scene), the drawer is put away and taken down, and the
 * bottom screen shows just the time, battery and free memory, drawn straight on a plain window
 * (a few MB instead of the drawer's 50 or so). Once memory is back above 3.3 GB and steady for 5
 * seconds, the drawer comes back where you were. A tap brings it back straight away.
 *
 * Real critical memory is still handled by [BottomOff] (the black cover).
 */
object Saver {
    private const val ENTER_BELOW_MB = 3_000L
    private const val EXIT_ABOVE_MB = 3_300L
    private const val FAST_DROP_MB = 500L
    private const val STEADY_DROP_MB = 150L
    private const val WINDOW_MS = 5_000L
    private const val STEADY_FOR_MS = 5_000L
    /** After a tap brings the drawer back: no automatic saver mode for this long. */
    private const val TAPPED_GRACE_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    private var service: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var view: StatusView? = null
    private val readings = ArrayDeque<Pair<Long, Long>>() // (when, free MB)
    private var steadySince = 0L
    private var quietUntil = 0L

    /** Saver mode is showing on the bottom screen. */
    val showing get() = view != null

    /** Starts watching (from the accessibility service; looks once a second, only during a game). */
    fun start(s: AccessibilityService) {
        service = s
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1_000)
    }

    private val tick = object : Runnable {
        override fun run() {
            main.postDelayed(this, 1_000)
            val s = service ?: return
            runCatching { check(s) }
        }
    }

    private fun check(s: AccessibilityService) {
        val inGame = EmulatorWatch.gameShowing.value && !EmulatorWatch.bothScreensGameShowing(s)
        if (!inGame) {
            readings.clear()
            if (showing) leave(s, drawer = false, why = "game over")
            return
        }
        val bottom = DisplayHelper.bottomDisplay(s)?.displayId ?: return
        val drawerUp = Drawer.shown.value && AppState.drawerDisplayId == bottom
        // Nothing of WalkThor's up on the bottom screen: nothing to save.
        if (!drawerUp && !showing) { readings.clear(); return }
        val now = SystemClock.elapsedRealtime()
        val free = freeMb(s)
        readings.addLast(now to free)
        while (readings.isNotEmpty() && now - readings.first().first > WINDOW_MS) readings.removeFirst()
        val drop = readings.maxOf { it.second } - free
        // Opened some other way (the AYN button, say) while saver mode was up: you wanted it.
        if (showing && drawerUp) {
            quietUntil = now + TAPPED_GRACE_MS
            uncover()
            MemoryWatch.noteFrom(s, "RAM saver mode off (drawer opened)")
            return
        }
        if (showing) {
            view?.let { it.free = free; it.battery = battery(s); it.invalidate() }
            // The black cover's up (critical memory): it's in charge until memory recovers.
            if (BottomOff.off.value) { steadySince = 0L; return }
            val calm = free >= EXIT_ABOVE_MB && drop < STEADY_DROP_MB
            if (!calm) steadySince = 0L else if (steadySince == 0L) steadySince = now
            if (calm && now - steadySince >= STEADY_FOR_MS) leave(s, drawer = true, why = "memory steady, ${free} MB free")
            return
        }
        if (now < quietUntil || readings.size < 2) return
        val why = when {
            free < ENTER_BELOW_MB -> "$free MB free"
            drop >= FAST_DROP_MB -> "free memory fell $drop MB in ${WINDOW_MS / 1000} s"
            else -> return
        }
        enter(s, bottom, free, why)
    }

    private fun enter(s: AccessibilityService, bottom: Int, free: Long, why: String) {
        if (!cover(s, bottom, free)) return
        steadySince = 0L
        // The drawer put away: during a game it's taken down shortly after, freeing its memory.
        runCatching { Drawer.hide() }
        MemoryWatch.noteFrom(s, "RAM saver mode on ($why): drawer taken down, time and memory only")
    }

    /** Out of saver mode: the drawer back ([drawer]), the light screen gone just after (the drawer comes up over it). */
    private fun leave(s: AccessibilityService, drawer: Boolean, why: String) {
        if (drawer) {
            val bottom = DisplayHelper.bottomDisplay(s)?.displayId
            if (bottom != null) runCatching { Drawer.show(s, bottom, opened = true, fade = true) }
            main.postDelayed({ uncover() }, 700)
        } else uncover()
        readings.clear()
        MemoryWatch.noteFrom(s, "RAM saver mode off ($why)")
    }

    private fun freeMb(s: AccessibilityService): Long {
        val info = ActivityManager.MemoryInfo()
        runCatching { s.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info) }
        return info.availMem / (1024 * 1024)
    }

    private fun battery(s: AccessibilityService): Int =
        runCatching { s.getSystemService(BatteryManager::class.java)!!.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1)

    /** The light screen: the time, battery and free memory. */
    private class StatusView(ctx: android.content.Context) : View(ctx) {
        var free = -1L
        var battery = -1
        private val clock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = 0xFF9A9388.toInt()
            typeface = runCatching { ctx.resources.getFont(R.font.space_grotesk_600) }.getOrNull()
            letterSpacing = -0.03f
        }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = runCatching { ctx.resources.getFont(R.font.space_grotesk_400) }.getOrNull()
        }
        private val ticker = object : Runnable {
            override fun run() { invalidate(); postDelayed(this, 15_000) }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(ticker) }
        override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

        override fun onDraw(canvas: android.graphics.Canvas) {
            canvas.drawColor(Color.BLACK)
            val w = width.toFloat(); val h = height.toFloat()
            val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
            val time = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date())
            clock.textSize = w * 0.185f
            val clockY = h * 0.40f
            canvas.drawText(time, w / 2f, clockY, clock)
            small.textSize = w * 0.037f
            small.color = 0xFF8E877C.toInt()
            val parts = listOfNotNull(
                battery.takeIf { it >= 0 }?.let { "$it%" },
                free.takeIf { it >= 0 }?.let { "%.1f GB free".format(java.util.Locale.US, it / 1024f) },
            )
            canvas.drawText(parts.joinToString(" · "), w / 2f, clockY + w * 0.1f, small)
            small.textSize = w * 0.028f
            small.color = 0xFF5D5850.toInt()
            canvas.drawText("Saver mode · tap for WalkThor", w / 2f, h - w * 0.065f, small)
        }
    }

    private fun cover(s: AccessibilityService, displayId: Int, free: Long): Boolean {
        if (view != null) return true
        return runCatching {
            val display = s.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return false
            val ctx = s.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val w = ctx.getSystemService(WindowManager::class.java) ?: return false
            val v = StatusView(ctx).apply {
                this.free = free
                battery = battery(s)
                setBackgroundColor(Color.BLACK)
                // A tap: the full drawer now, and no automatic saver mode for a while.
                @Suppress("ClickableViewAccessibility")
                setOnTouchListener { _, e ->
                    if (e.action == MotionEvent.ACTION_UP && view != null) {
                        quietUntil = SystemClock.elapsedRealtime() + TAPPED_GRACE_MS
                        leave(s, drawer = true, why = "tapped")
                    }
                    true
                }
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
                title = "WalkThor saver mode"
            }
            w.addView(v, params)
            wm = w
            view = v
            true
        }.getOrDefault(false)
    }

    private fun uncover() {
        val v = view ?: return
        runCatching { wm?.removeView(v) }
        view = null
        wm = null
    }
}
