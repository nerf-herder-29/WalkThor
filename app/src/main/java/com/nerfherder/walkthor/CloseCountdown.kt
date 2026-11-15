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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Before "close all apps": the top screen goes black (the bottom one dims) and both ask "Close all
 * apps?", with a warning that the game closes too. Tap Close all or press Start to go ahead; tap
 * Cancel or press Back or B to stop. Nobody answering for a while counts as Cancel. Once it goes
 * ahead, both screens fade to black while the apps close, then clear.
 */
object CloseCountdown {
    /** How long the question waits for an answer before it counts as Cancel. */
    private const val ASK_MS = 12_000L
    private val main = Handler(Looper.getMainLooper())
    private val views = mutableListOf<Pair<WindowManager, Face>>()
    private var onGo: (() -> Unit)? = null

    /** The question is up (Start or a tap on Close all goes ahead; Back, B or Cancel stops). */
    @Volatile var active = false
        private set

    /**
     * Asks first, then runs [go] if you say yes. With [ask] false (you've already said yes
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
                // The game's screen goes fully black, so the question can't be missed.
                val face = Face(ctx, top = display.displayId != bottom || displays.size == 1)
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
            onGo = go
            if (!ask) { proceed(service); return@post }
            active = true
            keyWatch(service)
            // Fade in: the top screen to black, the bottom one most of the way.
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                addUpdateListener { a -> views.forEach { (_, v) -> v.fade = a.animatedValue as Float; v.invalidate() } }
            }.start()
            main.postDelayed(timeout, ASK_MS)
        }
    }

    private val timeout = Runnable { cancel() }

    /** Yes: close everything. True if there was a question to answer. */
    fun confirm(): Boolean {
        if (!active) return false
        val service = SwipeGestureService.instance?.get() ?: run { cancel(); return false }
        main.post { proceed(service) }
        return true
    }

    private fun proceed(service: AccessibilityService) {
        main.removeCallbacks(timeout)
        active = false
        keyWatch(service)
        views.forEach { (_, v) -> v.closing = true; v.fade = 1f; v.invalidate() }
        val go = onGo
        onGo = null
        go?.invoke()
        // Never left up if the closing doesn't report back.
        main.postDelayed(giveUp, 20_000)
    }

    /** No: nothing is closed. True if there was a question to answer. */
    fun cancel(): Boolean {
        if (!active) return false
        active = false
        main.post {
            main.removeCallbacks(timeout)
            onGo = null
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

    /** Button presses are heard while the question is up, whatever else is switched on. */
    private fun keyWatch(service: AccessibilityService) {
        (service as? SwipeGestureService)?.let { s -> runCatching { s.setKeyWatch(com.nerfherder.walkthor.controls.ControllerInput.watching.value) } }
    }

    /** One screen's cover: black on the top screen, a deep dim on the bottom, with the question. */
    private class Face(context: Context, val top: Boolean) : View(context) {
        var fade = 0f
        var closing = false
        private fun dp(x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, resources.displayMetrics)
        private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(28f); isFakeBoldText = true
        }
        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = dp(15f) }
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = dp(13f) }
        private val warn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(224, 97, 74); style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND }
        private val warnFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(224, 97, 74) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = dp(16f); isFakeBoldText = true }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f); color = Color.argb(150, 255, 255, 255) }
        private val cancelBox = RectF()
        private val goBox = RectF()
        private var pressed: RectF? = null

        // Reported as a plain view, so WalkThor doesn't take its own cover for an app coming to the front.
        override fun getAccessibilityClassName(): CharSequence = "android.view.View"

        override fun onDraw(canvas: Canvas) {
            val shade = if (closing || top) 1f else 0.88f
            canvas.drawColor(Color.argb((fade.coerceIn(0f, 1f) * shade * 255).toInt(), 0, 0, 0))
            val cx = width / 2f
            val a = (fade * 255).toInt().coerceIn(0, 255)
            if (closing) {
                body.alpha = a
                canvas.drawText("Closing…", cx, height / 2f, body)
                return
            }
            var y = height * 0.30f
            // A warning triangle over the question.
            val s = dp(26f)
            warn.alpha = a; warnFill.alpha = a
            val tri = android.graphics.Path().apply {
                moveTo(cx, y - s); lineTo(cx + s * 1.1f, y + s * 0.85f); lineTo(cx - s * 1.1f, y + s * 0.85f); close()
            }
            canvas.drawPath(tri, warn)
            canvas.drawLine(cx, y - s * 0.3f, cx, y + s * 0.28f, warn)
            canvas.drawCircle(cx, y + s * 0.58f, dp(2f), warnFill)
            y += s + dp(52f)
            title.alpha = a
            canvas.drawText("Close all apps?", cx, y, title)
            y += dp(34f)
            body.alpha = (a * 0.8f).toInt()
            canvas.drawText("Everything you have open will close, including your game.", cx, y, body)
            y += dp(22f)
            canvas.drawText("Anything you haven't saved will be lost.", cx, y, body)
            // The two buttons.
            y += dp(36f)
            val w = dp(150f); val h = dp(48f); val gap = dp(16f)
            cancelBox.set(cx - gap / 2 - w, y, cx - gap / 2, y + h)
            goBox.set(cx + gap / 2, y, cx + gap / 2 + w, y + h)
            val r = h / 2
            pill.color = if (pressed === cancelBox) Color.argb(60, 255, 255, 255) else Color.argb(0, 0, 0, 0)
            canvas.drawRoundRect(cancelBox, r, r, pill)
            edge.alpha = (a * 0.6f).toInt()
            canvas.drawRoundRect(cancelBox, r, r, edge)
            pill.color = if (pressed === goBox) Color.rgb(196, 80, 60) else Color.rgb(224, 97, 74)
            pill.alpha = a
            canvas.drawRoundRect(goBox, r, r, pill)
            val ty = y + h / 2 + label.textSize * 0.35f
            label.color = Color.WHITE; label.alpha = a
            canvas.drawText("Cancel", cancelBox.centerX(), ty, label)
            canvas.drawText("Close all", goBox.centerX(), ty, label)
            y += h + dp(30f)
            hint.alpha = (a * 0.55f).toInt()
            canvas.drawText("Start: close all  ·  Back or B: cancel", cx, y, hint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (closing || !active) return true
            val hit = when {
                cancelBox.contains(e.x, e.y) -> cancelBox
                goBox.contains(e.x, e.y) -> goBox
                else -> null
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressed = hit; invalidate() }
                MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
                MotionEvent.ACTION_UP -> {
                    val was = pressed
                    pressed = null
                    invalidate()
                    if (was != null && was === hit) {
                        if (hit === goBox) confirm() else cancel()
                    }
                }
            }
            // Touches anywhere else do nothing (the game underneath never gets them).
            return true
        }
    }
}
