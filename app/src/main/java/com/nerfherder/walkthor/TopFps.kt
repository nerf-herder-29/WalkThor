package com.nerfherder.walkthor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The performance overlay (Settings → Screen), in place of the old FPS counter: a small glass
 * panel in a corner while a game is in front, with the top screen's frame rate in large type, a
 * line graph of it over the last 30 seconds (dips show stutters), and underneath one more stat
 * that changes every 5 seconds: CPU speed and heat, GPU load and heat, battery draw and time
 * left, RAM. Read once a second, as the Stats tab does; a stat that can't be read on this device
 * is skipped. Nothing is captured, and it never takes a touch.
 */
object TopFps {
    private const val KEY = "topFps"
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor top FPS").apply { isDaemon = true } }
    private var view: PerfPanel? = null
    private var wm: WindowManager? = null
    private var shownOn: Int? = null
    private var service: AccessibilityService? = null
    /** The last reading, and how many in a row have failed (a blip shouldn't hide it). */
    private var last: Int? = null
    private var misses = 0
    /** Couldn't put the pill up: wait before trying again, rather than every second. */
    private var failedAt = 0L
    /** The frame rate over the last 30 readings, for the graph. */
    private val history = ArrayDeque<Int>()
    private const val HISTORY = 30
    /** How long each stat stays before the next. */
    private const val PAGE_MS = 5_000L
    private var shownSince = 0L
    /** One window context per screen, kept: making a new one every second leaks them. */
    private var windowCtx: Pair<Int, Context>? = null

    fun enabled(c: Context) = SwipeLauncher.prefs(c).getBoolean(KEY, false)

    /** Which screen it's drawn on (it's always the top screen's frame rate). */
    enum class Screen(val label: String) { BOTTOM("Bottom screen"), TOP("Top screen") }

    fun screen(c: Context): Screen =
        SwipeLauncher.prefs(c).getString("topFpsScreen", null)?.let { n -> Screen.entries.firstOrNull { it.name == n } } ?: Screen.BOTTOM

    fun setScreen(c: Context, screen: Screen) {
        SwipeLauncher.prefs(c).edit().putString("topFpsScreen", screen.name).apply()
        main.post { hide() }
    }

    /** Which corner of that screen it sits in. */
    enum class Corner(val label: String, val gravity: Int) {
        TOP_RIGHT("Top right", Gravity.TOP or Gravity.END),
        TOP_LEFT("Top left", Gravity.TOP or Gravity.START),
        BOTTOM_LEFT("Bottom left", Gravity.BOTTOM or Gravity.START),
        BOTTOM_RIGHT("Bottom right", Gravity.BOTTOM or Gravity.END),
    }

    fun corner(c: Context): Corner =
        SwipeLauncher.prefs(c).getString("topFpsCorner", null)?.let { n -> Corner.entries.firstOrNull { it.name == n } } ?: Corner.TOP_RIGHT

    fun setCorner(c: Context, corner: Corner) {
        SwipeLauncher.prefs(c).edit().putString("topFpsCorner", corner.name).apply()
        // Put up again in the new corner on the next reading.
        main.post { hide() }
    }

    fun setEnabled(c: Context, on: Boolean) {
        SwipeLauncher.prefs(c).edit().putBoolean(KEY, on).apply()
        main.post { if (on) service?.let { start(it) } else hide() }
    }

    /** From the accessibility service once it's running. */
    fun start(s: AccessibilityService) {
        service = s
        main.removeCallbacks(tick)
        if (enabled(s)) main.post(tick)
    }

    fun stop() {
        main.removeCallbacks(tick)
        hide()
        service = null
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val s = service ?: return
            if (!enabled(s)) { hide(); return }
            runCatching {
                // On the bottom screen it stays out of the game's way (and away while that screen's off).
                val onBottom = screen(s) == Screen.BOTTOM
                val top = if (onBottom) runCatching { DisplayHelper.bottomDisplay(s) }.getOrNull() else topDisplay(s)
                if (top == null || !EmulatorWatch.gameShowing.value || (onBottom && BottomOff.off.value)) {
                    hide()
                } else {
                    val app = s.applicationContext
                    worker.execute {
                        val fps = runCatching { ScreenControls.topFps() }.getOrNull()?.takeIf { it.isFinite() && it >= 0f }
                        val lines = runCatching { statLines(app) }.getOrDefault(emptyList())
                        main.post {
                            runCatching {
                                if (fps != null) { last = fps.roundToInt(); misses = 0 } else misses++
                                val show = last
                                if (show != null && misses < 4 && enabled(s) && EmulatorWatch.gameShowing.value) {
                                    if (fps != null) { history.addLast(show); while (history.size > HISTORY) history.removeFirst() }
                                    show(s, top, show, lines)
                                } else hide()
                            }
                        }
                    }
                }
            }
            main.postDelayed(this, 1_000)
        }
    }

    private fun topDisplay(c: Context): Display? {
        val bottom = runCatching { DisplayHelper.bottomDisplay(c)?.displayId }.getOrNull()
        return c.getSystemService(DisplayManager::class.java)?.displays
            ?.filter { it.displayId != bottom && it.state != Display.STATE_OFF }
            ?.maxByOrNull { it.mode.physicalWidth.toLong() * it.mode.physicalHeight }
    }

    private fun show(s: AccessibilityService, display: Display, fps: Int, lines: List<Pair<String, String>>) {
        if (shownOn != display.displayId) hide()
        val v = view ?: run {
            if (android.os.SystemClock.uptimeMillis() - failedAt < 15_000) return
            val ctx = windowCtx?.takeIf { it.first == display.displayId }?.second
                ?: s.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                    .also { windowCtx = display.displayId to it }
            val w = ctx.getSystemService(WindowManager::class.java) ?: return
            val dp = { x: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, ctx.resources.displayMetrics) }
            val panel = PerfPanel(ctx)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = corner(s).gravity
                x = dp(12f).roundToInt()
                y = dp(10f).roundToInt()
                title = "WalkThor performance"
            }
            if (runCatching { w.addView(panel, params) }.isFailure) { failedAt = android.os.SystemClock.uptimeMillis(); return }
            wm = w
            view = panel
            shownOn = display.displayId
            shownSince = android.os.SystemClock.uptimeMillis()
            panel
        }
        val page = if (lines.isEmpty()) 0 else (((android.os.SystemClock.uptimeMillis() - shownSince) / PAGE_MS) % lines.size).toInt()
        v.update(fps, history.toList(), lines.getOrNull(page), page, lines.size)
    }

    /** The stats under the frame rate, one shown at a time: (label, value). Those that can't be read are left out. Worker thread. */
    private fun statLines(c: Context): List<Pair<String, String>> {
        val st = com.nerfherder.walkthor.ui.StatsReader.read(c)
        fun deg(t: Float?) = t?.let { "${it.roundToInt()}°" }
        return buildList {
            listOfNotNull(st.cpuGhz?.let { "%.1f GHz".format(it) }, deg(st.cpuTempC)).takeIf { it.isNotEmpty() }
                ?.let { add("CPU" to it.joinToString(" · ")) }
            listOfNotNull(gpuBusy()?.let { "$it%" }, deg(st.gpuTempC)).takeIf { it.isNotEmpty() }
                ?.let { add("GPU" to it.joinToString(" · ")) }
            val battery = when {
                st.charging -> listOfNotNull("Charging", st.watts?.let { "%.1f W".format(it) })
                else -> listOfNotNull(st.watts?.let { "%.1f W".format(it) }, st.minutesLeft?.let { "%d:%02d left".format(it / 60, it % 60) })
            }
            if (battery.isNotEmpty()) add("BATTERY" to battery.joinToString(" · "))
            if (st.memTotal > 0) {
                val gb = 1024f * 1024f * 1024f
                add("RAM" to "%.1f / %.0f GB".format((st.memTotal - st.memFree) / gb, st.memTotal / gb))
            }
        }
    }

    /** How busy the GPU is, %, where the driver lets an app read it (Adreno's "kgsl"). */
    private fun gpuBusy(): Int? {
        runCatching {
            java.io.File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").readText().trim().removeSuffix("%").trim().toInt()
        }.getOrNull()?.takeIf { it in 0..100 }?.let { return it }
        return runCatching {
            val (busy, total) = java.io.File("/sys/class/kgsl/kgsl-3d0/gpubusy").readText().trim().split(Regex("\\s+")).map { it.toLong() }
            if (total > 0) (busy * 100 / total).toInt() else null
        }.getOrNull()?.takeIf { it in 0..100 }
    }

    /**
     * The panel: a dark see-through glass card with a faint light edge. Big frame rate and its
     * graph on top; one stat underneath, with dots for which one it is.
     */
    private class PerfPanel(context: Context) : android.view.View(context) {
        private companion object { val INK = Color.rgb(237, 230, 218) }
        private var fps = 0
        private var history: List<Int> = emptyList()
        private var line: Pair<String, String>? = null
        private var page = 0
        private var pages = 0
        private var widest = 0f

        private fun dp(x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, resources.displayMetrics)
        /** Your stick lights' colour, as the keyboard's action key shows it (cream while they're off); looked up again each second. */
        private var accent = stickColour()
        private fun stickColour() = runCatching {
            StickLights.color(context).let { if (it == 0) INK else StickLights.swatch(it) or 0xFF000000.toInt() }
        }.getOrDefault(INK)
        private val muted = Color.rgb(200, 200, 208)
        private fun paint(size: Float, weight: Int, color: Int) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(size); typeface = WalkThorFont.get(context, weight); this.color = color
        }
        private val big = paint(22f, 600, Color.WHITE)
        private val bigLabel = paint(9.5f, 500, muted).apply { letterSpacing = 0.06f }
        private val value = paint(11.5f, 600, Color.WHITE)
        private val label = paint(8.5f, 500, muted).apply { letterSpacing = 0.06f }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(158, 12, 12, 16) }
        private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(1f).coerceAtLeast(1f); color = Color.argb(56, 255, 255, 255)
        }
        private val graph = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(1.6f); color = accent
            strokeJoin = android.graphics.Paint.Join.ROUND; strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val box = android.graphics.RectF()
        private val path = android.graphics.Path()

        private val padH get() = dp(11f)
        private val padV get() = dp(8f)
        private val graphW get() = dp(62f)
        private val graphH get() = dp(15f)
        private val dotR get() = dp(2.2f)
        private val dotGap get() = dp(3.2f)

        // Reported as a plain view, so WalkThor doesn't take it for an app coming to the front.
        override fun getAccessibilityClassName(): CharSequence = "android.view.View"

        fun update(fps: Int, history: List<Int>, line: Pair<String, String>?, page: Int, pages: Int) {
            this.fps = fps; this.history = history; this.line = line; this.page = page; this.pages = pages
            // Follows the stick lights if they change while it's up.
            accent = stickColour()
            graph.color = accent
            val w = contentWidth()
            // Only ever grows while shown, so it doesn't jump about as the stats change.
            if (w > widest) { widest = w; requestLayout() }
            invalidate()
        }

        private fun dotsWidth() = if (pages > 1) pages * dotR * 2 + (pages - 1) * dotGap else 0f

        private fun contentWidth(): Float {
            val top = big.measureText(fps.toString()) + dp(3f) + bigLabel.measureText("FPS") + dp(10f) + graphW
            val l = line
            val bottom = if (l == null) 0f else label.measureText(l.first) + dp(4f) + value.measureText(l.second) + dp(12f) + dotsWidth()
            return maxOf(top, bottom)
        }

        private val topH get() = big.fontMetrics.let { it.descent - it.ascent } * 0.82f
        private val lineH get() = value.fontMetrics.let { it.descent - it.ascent }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = padH * 2 + maxOf(widest, contentWidth())
            val h = padV * 2 + topH + (if (line != null) dp(4f) + lineH else 0f)
            setMeasuredDimension(Math.ceil(w.toDouble()).toInt(), Math.ceil(h.toDouble()).toInt())
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val r = dp(12f)
            box.set(edge.strokeWidth / 2, edge.strokeWidth / 2, width - edge.strokeWidth / 2, height - edge.strokeWidth / 2)
            canvas.drawRoundRect(box, r, r, fill)
            canvas.drawRoundRect(box, r, r, edge)
            // Top row: the frame rate, big, and its graph.
            val baseline = padV - big.fontMetrics.ascent * 0.9f
            val n = fps.toString()
            var x = padH
            canvas.drawText(n, x, baseline, big)
            x += big.measureText(n) + dp(3f)
            canvas.drawText("FPS", x, baseline, bigLabel)
            val gRight = width - padH
            val gLeft = gRight - graphW
            val gTop = baseline - graphH * 0.95f
            if (history.size >= 2) {
                val topFps = maxOf(60, history.maxOrNull() ?: 60).toFloat()
                path.reset()
                history.forEachIndexed { i, f ->
                    val px = gLeft + graphW * i / (HISTORY - 1).toFloat() + graphW * (HISTORY - history.size) / (HISTORY - 1).toFloat()
                    val py = gTop + graphH * (1f - (f / topFps).coerceIn(0f, 1f))
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                canvas.drawPath(path, graph)
            }
            // Bottom row: this stat, and which one of how many.
            val l = line ?: return
            val b2 = padV + topH + dp(4f) - value.fontMetrics.ascent
            canvas.drawText(l.first, padH, b2, label)
            canvas.drawText(l.second, padH + label.measureText(l.first) + dp(4f), b2, value)
            if (pages > 1) {
                val cy = b2 + (value.fontMetrics.ascent / 2.6f)
                var dx = width - padH - dotsWidth() + dotR
                for (i in 0 until pages) {
                    dot.color = if (i == page) accent else Color.argb(72, 255, 255, 255)
                    canvas.drawCircle(dx, cy, dotR, dot)
                    dx += dotR * 2 + dotGap
                }
            }
        }
    }

    private fun hide() {
        history.clear()
        view?.let { v -> runCatching { wm?.removeViewImmediate(v) } }
        view = null
        wm = null
        shownOn = null
    }
}
