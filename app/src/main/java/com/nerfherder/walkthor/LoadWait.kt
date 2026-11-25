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
 * memory has stopped dropping: at least 10 seconds in, no big fall in the last 8 seconds, and no critical warning for 8.
 * Memory pressure low too (looser after 30 seconds: some games keep swapping a little all the
 * way through, and waited a minute or more for nothing). At most 90 seconds. A tap on the black brings WalkThor up straight away.
 */
object LoadWait {
    private const val MIN_WAIT_MS = 10_000L
    private const val MAX_WAIT_MS = 90_000L
    /** Memory pressure (avg10, %) that counts as calm: strict at first, looser once the game's had a while. */
    private const val CALM_PRESSURE = 0.5f
    private const val CALM_PRESSURE_LATE = 1.5f
    private const val LATE_MS = 30_000L
    private const val WINDOW_MS = 8_000L
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
        var lastLine = -10_000L
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
                // And Android's memory pressure low (how much time things spend waiting on memory):
                // coming up while it's high was what tipped games over.
                val pressure = runCatching {
                    java.io.File("/proc/pressure/memory").readLines().firstOrNull()?.substringAfter("avg10=")?.substringBefore(' ')?.toFloat()
                }.getOrNull() ?: 0f
                val calmNow = peak - free < STEADY_DROP_MB && MemoryWatch.msSinceCritical() >= 8_000L && pressure < (if (now - started >= LATE_MS) CALM_PRESSURE_LATE else CALM_PRESSURE)
                val steady = waited >= MIN_WAIT_MS && calmNow
                // The log on screen: a line every 6 seconds, the newest one kept up to date.
                log?.let { v ->
                    if (waited - lastLine >= 6_000L) { v.lines.add(waited to free); lastLine = waited }
                    else if (v.lines.isNotEmpty()) v.lines[v.lines.size - 1] = v.lines.last().first to free
                    v.steadying = calmNow && readings.size >= 4
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
     * The loading log: a dim line of free memory every few seconds, so the drop shows as it
     * happens, the newest line brighter. Drawn straight on the window (no WalkThor screens loaded).
     */
    private class LogView(ctx: android.content.Context, private val accent: Int) : View(ctx) {
        val lines = mutableListOf<Pair<Long, Long>>() // (ms since the start, free MB)
        var steadying = false
        var blink = false
        private val mono = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE }
        private val hint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }

        override fun onDraw(canvas: android.graphics.Canvas) {
            canvas.drawColor(Color.BLACK)
            val w = width.toFloat(); val h = height.toFloat()
            val size = w * 0.03f
            mono.textSize = size
            val step = size * 1.75f
            val left = w * 0.14f
            var y = h * 0.24f
            mono.color = accent; mono.alpha = 205
            canvas.drawText("› game loading", left, y, mono)
            y += step * 1.3f
            val shown = lines.takeLast(12)
            shown.forEachIndexed { i, (at, free) ->
                val newest = i == shown.size - 1
                val time = "%02d:%02d".format(at / 60_000, (at / 1000) % 60)
                mono.color = 0xFF3A3632.toInt()
                canvas.drawText(time, left, y, mono)
                mono.color = if (newest) 0xFFBDB6AB.toInt() else 0xFF5D5850.toInt()
                val gb = "%.1f GB".format(java.util.Locale.US, free / 1024f) +
                    (if (i == 0) " free" else "") + (if (newest && steadying) "  steadying" else "")
                canvas.drawText(gb, left + mono.measureText("00:00") + size * 0.9f, y, mono)
                y += step
            }
            if (blink) { mono.color = accent; canvas.drawText("_", left, y, mono) }
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
