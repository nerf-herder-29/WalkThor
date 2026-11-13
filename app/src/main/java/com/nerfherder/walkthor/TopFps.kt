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

    fun enabled(c: Context) = SwipeLauncher.prefs(c).getBoolean(KEY, false)

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
            val top = topDisplay(s)
            if (top == null || !EmulatorWatch.gameShowing.value) {
                hide()
            } else {
                worker.execute {
                    val fps = runCatching { ScreenControls.topFps() }.getOrNull()
                    main.post { if (fps != null && enabled(s) && EmulatorWatch.gameShowing.value) show(s, top, fps.roundToInt()) else hide() }
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
            val ctx = s.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val w = ctx.getSystemService(WindowManager::class.java) ?: return
            val dp = { x: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, ctx.resources.displayMetrics) }
            val tv = TextView(ctx).apply {
                // Light glass: a pale, see-through pill with a brighter edge.
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(110, 236, 238, 242)),
                ).apply {
                    cornerRadius = dp(10f)
                    setStroke(dp(1f).roundToInt().coerceAtLeast(1), Color.argb(170, 255, 255, 255))
                }
                setTextColor(Color.rgb(24, 24, 28))
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
                gravity = Gravity.TOP or Gravity.END
                x = dp(12f).roundToInt()
                y = dp(10f).roundToInt()
                title = "WalkThor FPS"
            }
            if (runCatching { w.addView(tv, params) }.isFailure) return
            wm = w
            view = tv
            shownOn = display.displayId
            tv
        }
        val text = "$fps FPS"
        v.text = SpannableString(text).apply {
            val at = text.indexOf(" FPS")
            setSpan(RelativeSizeSpan(0.8f), at, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.rgb(80, 80, 88)), at, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun hide() {
        view?.let { v -> runCatching { wm?.removeViewImmediate(v) } }
        view = null
        wm = null
        shownOn = null
    }
}
