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
 * The top screen's frame rate, small in its upper right corner on a light glass pill, while a
 * game is in front there (Settings → General → Screen). Read once a second from the display
 * driver, as the Stats tab does; nothing is captured, and it never takes a touch.
 */
object TopFps {
    private const val KEY = "topFps"
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor top FPS").apply { isDaemon = true } }
    private var view: TextView? = null
    private var wm: WindowManager? = null
    private var shownOn: Int? = null
    private var service: AccessibilityService? = null
    /** The last reading, and how many in a row have failed (a blip shouldn't hide it). */
    private var last: Int? = null
    private var misses = 0
    /** Couldn't put the pill up: wait before trying again, rather than every second. */
    private var failedAt = 0L
    /** One window context per screen, kept: making a new one every second leaks them. */
    private var windowCtx: Pair<Int, Context>? = null

    fun enabled(c: Context) = SwipeLauncher.prefs(c).getBoolean(KEY, false)

    /** Which corner of the top screen it sits in. */
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
                val top = topDisplay(s)
                if (top == null || !EmulatorWatch.gameShowing.value) {
                    hide()
                } else {
                    worker.execute {
                        val fps = runCatching { ScreenControls.topFps() }.getOrNull()?.takeIf { it.isFinite() && it >= 0f }
                        main.post {
                            runCatching {
                                if (fps != null) { last = fps.roundToInt(); misses = 0 } else misses++
                                val show = last
                                if (show != null && misses < 4 && enabled(s) && EmulatorWatch.gameShowing.value) show(s, top, show) else hide()
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

    private fun show(s: AccessibilityService, display: Display, fps: Int) {
        if (shownOn != display.displayId) hide()
        val v = view ?: run {
            if (android.os.SystemClock.uptimeMillis() - failedAt < 15_000) return
            val ctx = windowCtx?.takeIf { it.first == display.displayId }?.second
                ?: s.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                    .also { windowCtx = display.displayId to it }
            val w = ctx.getSystemService(WindowManager::class.java) ?: return
            val dp = { x: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, ctx.resources.displayMetrics) }
            val tv = TextView(ctx).apply {
                // Light glass: see-through, but enough of it that the dark number stands out over
                // a dark scene as well as a bright one; a bright edge.
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(125, 255, 255, 255), Color.argb(100, 240, 242, 246)),
                ).apply {
                    cornerRadius = dp(10f)
                    setStroke(dp(1f).roundToInt().coerceAtLeast(1), Color.argb(150, 255, 255, 255))
                }
                setTextColor(Color.rgb(18, 18, 22))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                includeFontPadding = false
                setPadding(dp(8f).roundToInt(), dp(4f).roundToInt(), dp(8f).roundToInt(), dp(4f).roundToInt())
            }
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
                title = "WalkThor FPS"
            }
            if (runCatching { w.addView(tv, params) }.isFailure) { failedAt = android.os.SystemClock.uptimeMillis(); return }
            wm = w
            view = tv
            shownOn = display.displayId
            tv
        }
        val text = "$fps FPS"
        if (v.text?.toString() == text) return
        v.text = SpannableString(text).apply {
            val at = text.indexOf(" FPS")
            setSpan(RelativeSizeSpan(0.8f), at, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.rgb(60, 60, 68)), at, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun hide() {
        view?.let { v -> runCatching { wm?.removeViewImmediate(v) } }
        view = null
        wm = null
        shownOn = null
    }
}
