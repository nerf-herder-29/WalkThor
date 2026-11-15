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
import android.view.animation.LinearInterpolator
import android.view.View
import android.view.WindowManager

/**
 * Before "close all apps": the top screen goes black with a warning that everything closes, the
 * game included, and the bottom screen dims while a ring counts down two seconds. Back, B,
 * Select or a tap on either screen cancels. If it isn't cancelled, both screens stay black while
 * the apps close, then clear.
 */
object CloseCountdown {
    private const val COUNT_MS = 2_000L
    private val main = Handler(Looper.getMainLooper())
    private val views = mutableListOf<Pair<WindowManager, Face>>()
    private var counting: ValueAnimator? = null

    /** The countdown is running (Back, B, Select or a tap cancels it). */
    @Volatile var active = false
        private set

    /**
     * Counts down, then runs [go] unless cancelled. With [ask] false (you've already said yes
     * somewhere else), it goes straight to closing.
     */
    fun start(service: AccessibilityService, ask: Boolean = true, go: () -> Unit) {
        main.post {
            if (active || views.isNotEmpty()) return@post
            val bottom = runCatching { DisplayHelper.bottomDisplay(service)?.displayId }.getOrNull()
            val displays = service.getSystemService(DisplayManager::class.java)?.displays.orEmpty().filter { it.state != Display.STATE_OFF }
            for (display in displays) runCatching {
                val ctx = service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                val wm = ctx.getSystemService(WindowManager::class.java) ?: return@runCatching
                // The ring on the bottom screen (or the only one); the warning on the game's screen.
                val face = Face(ctx, ring = display.displayId == bottom || displays.size == 1, warning = display.displayId != bottom || displays.size == 1)
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
            if (views.isEmpty()) { go(); return@post }
            if (!ask) { closing(go); return@post }
            active = true
            keyWatch(service)
            counting = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = COUNT_MS
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    // Fades in over the first moment, then the ring fills.
                    views.forEach { (_, v) -> v.progress = f; v.fade = (f * 12f).coerceAtMost(1f); v.invalidate() }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (cancelled) return
                        active = false
                        keyWatch(service)
                        closing(go)
                    }
                })
                start()
            }
        }
    }

    /** Closing now: no going back. Both screens go black while it happens. */
    private fun closing(go: () -> Unit) {
        views.forEach { (_, v) -> v.closing = true; v.fade = 1f; v.isClickable = false; v.invalidate() }
        go()
        // Never left up if the closing doesn't report back.
        main.postDelayed(giveUp, 20_000)
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

    /** Button presses are heard while it counts down, whatever else is switched on. */
    private fun keyWatch(service: AccessibilityService) {
        (service as? SwipeGestureService)?.let { s -> runCatching { s.setKeyWatch(com.nerfherder.walkthor.controls.ControllerInput.watching.value) } }
    }

    /**
     * One screen's cover. The game's screen ([warning]) goes black with the warning; the bottom
     * one ([ring]) dims, with the ring counting down the seconds.
     */
    private class Face(context: Context, val ring: Boolean, val warning: Boolean) : View(context) {
        var progress = 0f
        var fade = 0f
        var closing = false
        private fun dp(x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, resources.displayMetrics)
        private fun font(w: Int) = WalkThorFont.get(context, w)
        private val ink = Color.rgb(237, 230, 218)
        private val muted = Color.rgb(163, 154, 142)
        private val red = Color.rgb(224, 97, 74)
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(7f); color = Color.argb(40, 255, 255, 255) }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND; color = ink }
        private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textAlign = Paint.Align.CENTER; textSize = dp(34f); typeface = font(700) }
        private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textAlign = Paint.Align.CENTER; typeface = font(700) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textAlign = Paint.Align.CENTER; textSize = dp(17f); typeface = font(500) }
        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textAlign = Paint.Align.CENTER; typeface = font(400) }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textAlign = Paint.Align.CENTER; textSize = dp(13f); typeface = font(400) }
        private val warn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; style = Paint.Style.STROKE; strokeWidth = dp(2.6f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val warnFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red }
        private val box = RectF()

        // Reported as a plain view, so WalkThor doesn't take its own cover for an app coming to the front.
        override fun getAccessibilityClassName(): CharSequence = "android.view.View"

        override fun onDraw(canvas: Canvas) {
            val shade = if (closing || warning) 1f else 0.85f
            canvas.drawColor(Color.argb((fade.coerceIn(0f, 1f) * shade * 255).toInt(), 0, 0, 0))
            val a = (fade * 255).toInt().coerceIn(0, 255)
            val cx = width / 2f
            if (warning && !ring) drawWarning(canvas, cx, a)
            if (ring) drawRing(canvas, cx, a)
        }

        /** The game's screen: a warning triangle, "Closing all apps" and what that means. */
        private fun drawWarning(canvas: Canvas, cx: Float, a: Int) {
            // Sized to the screen, so it reads the same on any screen.
            val u = minOf(width, height) / 100f
            title.textSize = u * 6.2f
            body.textSize = u * 2.9f
            small.textSize = u * 2.3f
            var y = height * 0.30f
            val s = u * 3.2f
            warn.strokeWidth = u * 0.5f
            warn.alpha = a; warnFill.alpha = a
            val tri = android.graphics.Path().apply {
                moveTo(cx, y - s); lineTo(cx + s * 1.12f, y + s * 0.9f); lineTo(cx - s * 1.12f, y + s * 0.9f); close()
            }
            canvas.drawPath(tri, warn)
            canvas.drawLine(cx, y - s * 0.28f, cx, y + s * 0.3f, warn)
            canvas.drawCircle(cx, y + s * 0.6f, u * 0.38f, warnFill)
            y += s + u * 10f
            title.alpha = a
            canvas.drawText(if (closing) "Closing all apps…" else "Closing all apps", cx, y, title)
            if (closing) return
            y += u * 6f
            body.alpha = a
            canvas.drawText("Your game will close too. Anything you haven't saved will be lost.", cx, y, body)
            y += u * 6f
            small.alpha = (a * 0.7f).toInt()
            canvas.drawText("Press Back or tap the bottom screen to cancel", cx, y, small)
        }

        /** The bottom screen: the ring filling, with the seconds left in it. */
        private fun drawRing(canvas: Canvas, cx: Float, a: Int) {
            val cy = height * 0.45f
            val r = dp(54f)
            box.set(cx - r, cy - r, cx + r, cy + r)
            track.alpha = (a * 0.16f).toInt(); arc.alpha = a
            canvas.drawArc(box, 0f, 360f, false, track)
            canvas.drawArc(box, -90f, 360f * (if (closing) 1f else progress), false, arc)
            number.alpha = a
            if (!closing) {
                val left = kotlin.math.ceil((1f - progress) * COUNT_MS / 1000f).toInt().coerceAtLeast(1)
                canvas.drawText("$left", cx, cy + number.textSize * 0.36f, number)
            }
            label.alpha = a
            canvas.drawText(if (closing) "Closing…" else "Closing all apps", cx, cy + r + dp(38f), label)
            if (!closing) {
                small.textSize = dp(13f)
                small.alpha = (a * 0.85f).toInt()
                canvas.drawText("Press Back or tap to cancel", cx, cy + r + dp(64f), small)
            }
        }
    }
}
