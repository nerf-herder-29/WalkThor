package com.nerfherder.walkthor

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * A short message on both screens for a few seconds (a smoked glass pill near the bottom), drawn
 * by WalkThor's service so it shows over whatever's there. It never takes a touch.
 */
object ScreenMessage {
    private val main = Handler(Looper.getMainLooper())
    private val up = mutableListOf<Pair<WindowManager, View>>()

    fun show(text: String, forMs: Long = 4_500) {
        main.post {
            val service = SwipeGestureService.instance?.get() ?: return@post
            clear()
            val displays = service.getSystemService(DisplayManager::class.java)?.displays.orEmpty().filter { it.state != Display.STATE_OFF }
            for (display in displays) runCatching {
                val ctx = service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                val wm = ctx.getSystemService(WindowManager::class.java) ?: return@runCatching
                val dp = { x: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, ctx.resources.displayMetrics) }
                val tv = TextView(ctx).apply {
                    this.text = text
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    maxLines = 3
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(Color.argb(215, 12, 12, 16))
                        cornerRadius = dp(16f)
                        setStroke(dp(1f).roundToInt().coerceAtLeast(1), Color.argb(56, 255, 255, 255))
                    }
                    setPadding(dp(16f).roundToInt(), dp(10f).roundToInt(), dp(16f).roundToInt(), dp(10f).roundToInt())
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = dp(48f).roundToInt()
                    title = "WalkThor message"
                }
                wm.addView(tv, params)
                up += wm to tv
            }
            main.removeCallbacks(clearLater)
            main.postDelayed(clearLater, forMs)
        }
    }

    private val clearLater = Runnable { clear() }

    private fun clear() {
        up.forEach { (wm, v) -> runCatching { wm.removeViewImmediate(v) } }
        up.clear()
    }
}
