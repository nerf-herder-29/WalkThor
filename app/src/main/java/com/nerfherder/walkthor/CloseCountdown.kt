package com.nerfherder.walkthor

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/**
 * The moment before "close all apps": both screens dim and a ring fills on the bottom one for a
 * little over a second, with "Press Back or tap to cancel". If it isn't cancelled, the screens
 * fade to black while the apps close, then clear.
 */
object CloseCountdown {
    private const val COUNT_MS = 1_300L
    private val main = Handler(Looper.getMainLooper())
    private val views = mutableListOf<Pair<WindowManager, Face>>()
    private var counting: ValueAnimator? = null

    /** The ring is filling (a Back press or a tap cancels it). */
    @Volatile var active = false
        private set

    /** Starts the countdown; [onGo] runs when it completes without being cancelled. */
    fun start(service: AccessibilityService, onGo: () -> Unit) {
        main.post {
            if (active || views.isNotEmpty()) return@post
            val bottom = runCatching { DisplayHelper.bottomDisplay(service)?.displayId }.getOrNull()
            val displays = service.getSystemService(DisplayManager::class.java)?.displays.orEmpty().filter { it.state != Display.STATE_OFF }
            for (display in displays) runCatching {
                val ctx = service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                val wm = ctx.getSystemService(WindowManager::class.java) ?: return@runCatching
                // The ring goes on the bottom screen (or the only one).
                val face = Face(ctx, ring = display.displayId == bottom || displays.size == 1)
                face.setOnClickListener { cancel() }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    title = "WalkThor closing"
                }
                wm.addView(face, params)
                views += wm to face
            }
            if (views.isEmpty()) { onGo(); return@post }
            active = true
            keyWatch(service)
            counting = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = COUNT_MS
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    views.forEach { (_, v) -> v.progress = f; v.dim = 0.5f * (f * 6f).coerceAtMost(1f); v.invalidate() }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (cancelled) return
                        active = false
                        keyWatch(service)
                        // Closing now: no going back. Fade to black while it happens.
                        views.forEach { (_, v) -> v.closing = true; v.isClickable = false }
                        ValueAnimator.ofFloat(0.5f, 1f).apply {
                            duration = 300
                            addUpdateListener { a -> views.forEach { (_, v) -> v.dim = a.animatedValue as Float; v.invalidate() } }
                        }.start()
                        onGo()
                        // Never left up if the closing doesn't report back.
                        main.postDelayed(giveUp, 20_000)
                    }
                })
                start()
            }
        }
    }

    /** Stops the countdown before anything is closed. True if there was one to stop. */
    fun cancel(): Boolean {
        if (!active) return false
        active = false
        main.post {
            counting?.cancel()
            counting = null
            clear()
            SwipeGestureService.instance?.get()?.let { keyWatch(it) }
            ScreenMessage.show("Cancelled: nothing was closed", 2_500)
        }
        return true
    }

    /** The apps are closed: the screens clear. */
    fun finish() {
        main.post {
            main.removeCallbacks(giveUp)
            if (views.isEmpty()) return@post
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 350
                addUpdateListener { a -> views.forEach { (_, v) -> v.alpha = a.animatedValue as Float } }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { clear() }
                })
            }.start()
        }
    }

    private val giveUp = Runnable { clear() }

    private fun clear() {
        views.forEach { (wm, v) -> runCatching { wm.removeViewImmediate(v) } }
        views.clear()
    }

    /** Back presses are heard while the ring fills, whatever else is switched on. */
    private fun keyWatch(service: AccessibilityService) {
        (service as? SwipeGestureService)?.let { s -> runCatching { s.setKeyWatch(com.nerfherder.walkthor.controls.ControllerInput.watching.value) } }
    }

    /** One screen's dim, with the ring and its words on the bottom one. */
    private class Face(context: Context, val ring: Boolean) : View(context) {
        var progress = 0f
        var dim = 0f
        var closing = false
        private fun dp(x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, resources.displayMetrics)
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(7f); color = Color.argb(40, 255, 255, 255) }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND; color = Color.rgb(237, 230, 218) }
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(17f) }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = dp(13f) }
        private val box = RectF()

        // Reported as a plain view, so WalkThor doesn't take its own cover for an app coming to the front.
        override fun getAccessibilityClassName(): CharSequence = "android.view.View"

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.argb((dim.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0))
            if (!ring) return
            val cx = width / 2f
            val cy = height * 0.42f
            val r = dp(46f)
            box.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(box, 0f, 360f, false, track)
            canvas.drawArc(box, -90f, 360f * (if (closing) 1f else progress), false, arc)
            canvas.drawText(if (closing) "Closing…" else "Closing", cx, cy + big.textSize * 0.35f, big)
            if (!closing) canvas.drawText("Press Back or tap to cancel", cx, cy + r + dp(30f), small)
        }
    }
}
