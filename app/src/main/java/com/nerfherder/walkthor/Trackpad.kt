package com.nerfherder.walkthor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Trackpad mode: the whole bottom screen becomes a trackpad for the top one. WalkThor draws a
 * pointer (an arrow in your stick-light colour) on the top screen; sliding a finger moves it, a tap
 * taps there, holding and then sliding drags, and two fingers scroll. Back, Keyboard and Done sit
 * along the bottom.
 *
 * Android lets WalkThor's accessibility service make touches on a screen, not move a real mouse,
 * so it works for anything you'd tap: menus, frontends, browsers, settings. Nothing is recorded.
 */
object Trackpad {
    private val main = Handler(Looper.getMainLooper())
    private var pad: Pair<WindowManager, PadView>? = null
    private var pointer: Pair<WindowManager, PointerView>? = null
    private var pointerParams: WindowManager.LayoutParams? = null
    private var topId = -1
    private var topW = 1
    private var topH = 1
    /** Where the pointer is on the top screen, in its pixels. */
    private var px = 0f
    private var py = 0f

    val on get() = pad != null

    /** The shortcut: trackpad on, or off if it's on. */
    fun toggle(service: SwipeGestureService) = if (on) stop() else start(service)

    fun start(service: SwipeGestureService) {
        if (on) return
        if (Build.VERSION.SDK_INT < 30) { ScreenMessage.show("The trackpad needs Android 11 or newer"); return }
        val dm = service.getSystemService(DisplayManager::class.java) ?: return
        val bottom = DisplayHelper.bottomDisplay(service)
        val top = dm.displays.filter { (it.flags and Display.FLAG_PRIVATE) == 0 && it.displayId != bottom?.displayId }
            .maxByOrNull { it.mode.physicalWidth.toLong() * it.mode.physicalHeight }
        if (bottom == null || top == null) { ScreenMessage.show("The trackpad needs both screens on"); return }
        runCatching { Drawer.hide() }
        val size = android.graphics.Point()
        @Suppress("DEPRECATION") top.getRealSize(size)
        topId = top.displayId; topW = size.x; topH = size.y
        px = topW / 2f; py = topH / 2f
        runCatching {
            val ctx = service.createDisplayContext(top).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = ctx.getSystemService(WindowManager::class.java)!!
            val v = PointerView(ctx)
            val p = WindowManager.LayoutParams(
                v.sizePx, v.sizePx, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setTitle("WalkThor pointer")
            }
            wm.addView(v, p)
            pointer = wm to v
            pointerParams = p
            movePointer()
        }
        runCatching {
            val ctx = service.createDisplayContext(bottom).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = ctx.getSystemService(WindowManager::class.java)!!
            val v = PadView(ctx, service)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Not focusable: touching the pad doesn't take the controller from the top screen.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            ).apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setTitle("WalkThor trackpad")
            }
            wm.addView(v, p)
            pad = wm to v
        }.onFailure { stop(); ScreenMessage.show("Couldn't open the trackpad"); return }
        ScreenMessage.show("Trackpad on", 1_500)
    }

    fun stop() {
        pad?.let { (wm, v) -> runCatching { wm.removeViewImmediate(v) } }
        pointer?.let { (wm, v) -> runCatching { wm.removeViewImmediate(v) } }
        pad = null; pointer = null; pointerParams = null
    }

    private fun movePointer() {
        val (wm, v) = pointer ?: return
        val p = pointerParams ?: return
        // The arrow's tip is the point; the view is drawn with the tip a little in from its corner.
        p.x = (px - v.tip).roundToInt(); p.y = (py - v.tip).roundToInt()
        runCatching { wm.updateViewLayout(v, p) }
    }

    // ---- touches on the top screen ----

    private fun tap(service: AccessibilityService) {
        val path = Path().apply { moveTo(px, py) }
        dispatch(service, GestureDescription.StrokeDescription(path, 0, 50))
    }

    /** Two-finger scroll: a quick swipe from the pointer by ([dx], [dy]). */
    private fun swipe(service: AccessibilityService, dx: Float, dy: Float) {
        val x2 = (px + dx).coerceIn(1f, topW - 2f)
        val y2 = (py + dy).coerceIn(1f, topH - 2f)
        if (hypot(x2 - px, y2 - py) < 8f) return
        val path = Path().apply { moveTo(px, py); lineTo(x2, y2) }
        dispatch(service, GestureDescription.StrokeDescription(path, 0, 180))
    }

    private fun dispatch(service: AccessibilityService, stroke: GestureDescription.StrokeDescription, done: (() -> Unit)? = null) {
        val g = GestureDescription.Builder().addStroke(stroke).setDisplayId(topId).build()
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done?.invoke() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done?.invoke() }
        }
        if (!runCatching { service.dispatchGesture(g, cb, main) }.getOrDefault(false)) done?.invoke()
    }

    // A drag: one long touch on the top screen, carried on piece by piece as the finger moves.
    private var drag: GestureDescription.StrokeDescription? = null
    private var dragFrom = 0f to 0f
    private var dragBusy = false
    private var dragEnding = false

    private fun dragStart(service: AccessibilityService) {
        dragFrom = px to py
        dragEnding = false
        val s = GestureDescription.StrokeDescription(Path().apply { moveTo(px, py) }, 0, 40, true)
        drag = s
        dragBusy = true
        dispatch(service, s) { dragBusy = false; dragNext(service) }
    }

    /** Sends the next piece of the drag, if the pointer's moved (or it's time to let go). */
    private fun dragNext(service: AccessibilityService) {
        val s = drag ?: return
        if (dragBusy) return
        val (fx, fy) = dragFrom
        val moved = hypot(px - fx, py - fy) >= 1f
        if (!moved && !dragEnding) return
        val path = Path().apply { moveTo(fx, fy); lineTo(if (moved) px else fx + 0.5f, if (moved) py else fy) }
        val next = runCatching { s.continueStroke(path, 0, 30, !dragEnding) }.getOrNull() ?: run { drag = null; return }
        dragFrom = px to py
        if (dragEnding) drag = null else drag = next
        dragBusy = true
        dispatch(service, next) { dragBusy = false; dragNext(service) }
    }

    private fun dragEnd(service: AccessibilityService) {
        if (drag == null) return
        dragEnding = true
        dragNext(service)
    }

    /** The arrow on the top screen. */
    private class PointerView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        val sizePx = (34 * d).roundToInt()
        val tip = 3 * d
        private val accent = runCatching {
            StickLights.color(context).let { if (it == 0) Color.rgb(237, 230, 218) else StickLights.swatch(it) or 0xFF000000.toInt() }
        }.getOrDefault(Color.rgb(237, 230, 218))
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = Color.BLACK; strokeJoin = Paint.Join.ROUND
        }
        private val arrow = Path().apply {
            val s = d * 1.35f
            fun p(x: Float, y: Float) = tip + x * s to tip + y * s
            val pts = listOf(p(0f, 0f), p(0f, 16f), p(4.5f, 12f), p(7.5f, 19.5f), p(10.5f, 18.2f), p(7.6f, 11f), p(13.5f, 11f))
            moveTo(pts[0].first, pts[0].second)
            pts.drop(1).forEach { lineTo(it.first, it.second) }
            close()
        }
        override fun getAccessibilityClassName(): CharSequence = "android.view.View"
        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, edge)
        }
    }

    /** The bottom screen: the pad itself, with Back, Keyboard and Done along the bottom. */
    private class PadView(context: Context, private val service: SwipeGestureService) : View(context) {
        private val ink = Color.rgb(237, 230, 218)
        private val accent = runCatching {
            StickLights.color(context).let { if (it == 0) ink else StickLights.swatch(it) or 0xFF000000.toInt() }
        }.getOrDefault(ink)
        private fun paint(weight: Int, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = WalkThorFont.get(context, weight); this.color = color }
        private val title = paint(600, accent).apply { letterSpacing = 0.25f }
        private val hint = paint(400, Color.rgb(163, 154, 142))
        private val chipText = paint(600, ink).apply { textAlign = Paint.Align.CENTER }
        private val chipEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val chipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(26, 255, 255, 255) }
        private val touch = Paint(Paint.ANTI_ALIAS_FLAG)
        private val chips = listOf("Back", "Keyboard", "Done")
        private val chipBoxes = List(chips.size) { RectF() }

        private var u = 1f
        private var fingerX = -1f
        private var fingerY = -1f

        override fun getAccessibilityClassName(): CharSequence = "android.view.View"

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            u = minOf(w, h) / 100f
            title.textSize = u * 2.0f
            hint.textSize = u * 2.2f
            chipText.textSize = u * 2.4f
            chipEdge.strokeWidth = u * 0.14f
            val ch = u * 5.8f
            val gap = u * 1.9f
            val widths = chips.map { chipText.measureText(it) + u * 4.8f }
            var x = (w - widths.sum() - gap * (chips.size - 1)) / 2f
            val bottomY = h - u * 4f
            chips.indices.forEach { i -> chipBoxes[i].set(x, bottomY - ch, x + widths[i], bottomY); x += widths[i] + gap }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val step = u * 4.1f
            var y = step / 2
            while (y < height) { var x = step / 2; while (x < width) { canvas.drawCircle(x, y, u * 0.15f, dot); x += step }; y += step }
            canvas.drawText("TRACKPAD", u * 6f, u * 7.6f, title)
            canvas.drawText("Slide to move · Tap to tap", u * 6f, u * 11.6f, hint)
            canvas.drawText("Hold, then slide to drag · Two fingers to scroll", u * 6f, u * 14.6f, hint)
            if (fingerX >= 0) {
                touch.style = Paint.Style.FILL; touch.color = (accent and 0x00FFFFFF) or 0x38000000
                canvas.drawCircle(fingerX, fingerY, u * 4f, touch)
                touch.style = Paint.Style.STROKE; touch.strokeWidth = u * 0.2f; touch.color = (accent and 0x00FFFFFF) or 0x99000000.toInt()
                canvas.drawCircle(fingerX, fingerY, u * 4f, touch)
            }
            chips.forEachIndexed { i, label ->
                val b = chipBoxes[i]
                val r = b.height() / 2
                canvas.drawRoundRect(b, r, r, chipFill)
                chipEdge.color = if (label == "Done") accent else Color.argb(140, 255, 255, 255)
                canvas.drawRoundRect(b, r, r, chipEdge)
                chipText.color = if (label == "Done") accent else ink
                canvas.drawText(label, b.centerX(), b.centerY() - (chipText.descent() + chipText.ascent()) / 2, chipText)
            }
        }

        // What the finger's doing.
        private var downAt = 0L
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var lastT = 0L
        private var moved = false
        private var dragging = false
        private var twoFinger = false
        private var scrollX = 0f
        private var scrollY = 0f
        private var chip = -1
        private val slop get() = u * 1.2f
        private val holdForDrag = Runnable {
            if (!moved && !twoFinger && chip < 0) { dragging = true; dragStart(service); performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis(); downX = e.x; downY = e.y
                    lastX = e.x; lastY = e.y; lastT = downAt
                    moved = false; dragging = false; twoFinger = false
                    chip = chipBoxes.indexOfFirst { it.contains(e.x, e.y) }
                    if (chip < 0) { fingerX = e.x; fingerY = e.y; postDelayed(holdForDrag, 350) }
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (chip < 0 && !dragging) {
                        twoFinger = true; removeCallbacks(holdForDrag)
                        scrollX = 0f; scrollY = 0f
                        lastX = avgX(e); lastY = avgY(e)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (chip >= 0) return true
                    if (twoFinger) {
                        val x = avgX(e); val y = avgY(e)
                        scrollX += x - lastX; scrollY += y - lastY
                        lastX = x; lastY = y
                        // In steps, so a long scroll keeps going while the fingers move.
                        if (hypot(scrollX, scrollY) > u * 6f) { swipe(service, scrollX * 2.4f, scrollY * 2.4f); scrollX = 0f; scrollY = 0f }
                        return true
                    }
                    val dx = e.x - lastX; val dy = e.y - lastY
                    val t = SystemClock.uptimeMillis()
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) { moved = true; if (!dragging) removeCallbacks(holdForDrag) }
                    if (moved || dragging) {
                        // Faster flicks go further, so the whole top screen's in reach with one swipe.
                        val speed = hypot(dx, dy) / maxOf(1L, t - lastT) / u
                        val gain = (topW.toFloat() / width) * (1f + (speed * 0.9f).coerceAtMost(2.2f))
                        px = (px + dx * gain).coerceIn(0f, topW - 1f)
                        py = (py + dy * gain).coerceIn(0f, topH - 1f)
                        movePointer()
                        if (dragging) dragNext(service)
                    }
                    lastX = e.x; lastY = e.y; lastT = t
                    fingerX = e.x; fingerY = e.y
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (twoFinger) { if (hypot(scrollX, scrollY) > u) swipe(service, scrollX * 2.4f, scrollY * 2.4f); scrollX = 0f; scrollY = 0f }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(holdForDrag)
                    val up = e.actionMasked == MotionEvent.ACTION_UP
                    when {
                        chip >= 0 -> if (up && chipBoxes[chip].contains(e.x, e.y)) press(chips[chip])
                        dragging -> dragEnd(service)
                        up && !moved && !twoFinger && SystemClock.uptimeMillis() - downAt < 350 -> tap(service)
                    }
                    chip = -1; fingerX = -1f; dragging = false
                    invalidate()
                }
            }
            return true
        }

        private fun avgX(e: MotionEvent) = (0 until e.pointerCount).sumOf { e.getX(it).toDouble() }.toFloat() / e.pointerCount
        private fun avgY(e: MotionEvent) = (0 until e.pointerCount).sumOf { e.getY(it).toDouble() }.toFloat() / e.pointerCount

        private fun press(label: String) {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            when (label) {
                "Back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "Keyboard" -> ScreenMessage.show(
                    when (com.nerfherder.walkthor.keyboard.WalkThorKeyboard.toggle(service.applicationContext)) {
                        com.nerfherder.walkthor.keyboard.WalkThorKeyboard.Companion.Toggle.SHOWN -> "Keyboard up"
                        com.nerfherder.walkthor.keyboard.WalkThorKeyboard.Companion.Toggle.HIDDEN -> "Keyboard put away"
                        com.nerfherder.walkthor.keyboard.WalkThorKeyboard.Companion.Toggle.NO_TEXT_BOX -> "Tap a text box first, then press Keyboard"
                        com.nerfherder.walkthor.keyboard.WalkThorKeyboard.Companion.Toggle.NOT_IN_USE -> "Choose the WalkThor keyboard first (Settings → General)"
                    }, 2_000,
                )
                "Done" -> { stop(); ScreenMessage.show("Trackpad off", 1_500) }
            }
        }
    }
}
