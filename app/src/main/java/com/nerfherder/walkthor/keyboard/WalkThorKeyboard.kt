package com.nerfherder.walkthor.keyboard

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.nerfherder.walkthor.AynSystem
import com.nerfherder.walkthor.DisplayHelper
import com.nerfherder.walkthor.StickLights
import com.nerfherder.walkthor.SwipeLauncher

/**
 * WalkThor's own keyboard, to use in place of AYN's: numbers and letters on glass keys filling the
 * whole bottom screen, with the action key in the stick lights' colour. It's an ordinary Android
 * keyboard (you choose it once, in Settings → General → Access or Android's keyboard settings).
 * What you type goes only to the app you're typing in; nothing is kept or sent anywhere.
 */
class WalkThorKeyboard : InputMethodService() {
    private var keys: KeysView? = null

    override fun onCreateInputView(): View = KeysView(this).also { keys = it }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keys?.start(info)
    }

    /** Never Android's "extract" mode (a text box of its own above the keys). */
    override fun onEvaluateFullscreenMode() = false

    internal fun type(text: String) { currentInputConnection?.commitText(text, 1) }

    internal fun backspace() = sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)

    /** The action key: whatever the text box asks for (Go, Search, Next…), or a new line. */
    internal fun action() {
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val plainEnter = info == null || (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        if (plainEnter || currentInputConnection?.performEditorAction(action) != true) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    internal fun pickKeyboard() {
        runCatching { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
    }

    companion object {
        private fun id(c: Context) = "${c.packageName}/${WalkThorKeyboard::class.java.name}"
        private val ID = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")

        /** WalkThor's keyboard is the one Android uses. */
        fun isDefault(c: Context): Boolean =
            Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(c.packageName + "/") == true

        /**
         * Makes WalkThor's keyboard the one Android uses ([on]), or goes back to the one before.
         * Through AYN's system helper where that's reachable; otherwise opens Android's own
         * keyboard settings, where it's two taps. [done] is told whether it's now WalkThor's.
         */
        fun use(c: Context, on: Boolean, done: (Boolean) -> Unit) {
            val app = c.applicationContext
            val main = Handler(Looper.getMainLooper())
            Thread {
                val current = Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                val prefs = SwipeLauncher.prefs(app)
                val target = if (on) id(app) else prefs.getString("keyboardBefore", null)
                var changed = false
                if (target != null && ID.matches(target) && runCatching { AynSystem.available() }.getOrDefault(false)) {
                    if (on && current != null && !current.startsWith(app.packageName + "/")) prefs.edit().putString("keyboardBefore", current).apply()
                    changed = runCatching { AynSystem.setKeyboard(target) }.getOrDefault(false)
                    Thread.sleep(300)
                }
                main.post {
                    if (!changed) runCatching {
                        if (on) app.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else app.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                    }
                    done(isDefault(app))
                }
            }.start()
        }
    }
}

private enum class Kind { CHAR, SHIFT, BACK, PAGE, SPACE, ACTION }

private class Key(val label: String, val kind: Kind, val weight: Float = 1f) {
    val box = RectF()
}

/** The keys themselves, drawn and handled by hand: light, and quick to come up. */
private class KeysView(private val service: WalkThorKeyboard) : View(service) {
    private val main = Handler(Looper.getMainLooper())
    private fun dp(x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, resources.displayMetrics)

    private var symbols = false
    /** 0 off, 1 the next letter only, 2 locked. */
    private var shift = 0
    private var shiftAt = 0L
    private var actionLabel = "↵"
    private var accent = INK
    private var rows: List<List<Key>> = emptyList()
    private val pressed = HashMap<Int, Key>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.argb(34, 255, 255, 255) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    init { build() }

    fun start(info: EditorInfo?) {
        symbols = false
        shift = 0
        val o = info?.imeOptions ?: 0
        actionLabel = if ((o and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) "↵" else when (o and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            else -> "↵"
        }
        // The action key takes the stick lights' colour (cream when they're off).
        accent = runCatching { StickLights.color(context).let { if (it == 0) INK else StickLights.swatch(it) or 0xFF000000.toInt() } }.getOrDefault(INK)
        build()
        requestLayout()
        invalidate()
    }

    private fun chars(s: String) = s.map { Key(it.toString(), Kind.CHAR) }

    private fun build() {
        val top = chars("1234567890")
        val middle = if (!symbols) listOf(
            chars("qwertyuiop"),
            chars("asdfghjkl"),
            listOf(Key("shift", Kind.SHIFT, 1.5f)) + chars("zxcvbnm") + Key("back", Kind.BACK, 1.5f),
        ) else listOf(
            chars("@#$%&-+()/"),
            chars("*\"':;!?_="),
            listOf(Key("~", Kind.CHAR, 1.5f)) + chars("\\|<>[]^") + Key("back", Kind.BACK, 1.5f),
        )
        val bottom = listOf(
            Key(if (symbols) "abc" else "#+=", Kind.PAGE, 1.4f), Key(",", Kind.CHAR), Key("space", Kind.SPACE, 5f),
            Key(".", Kind.CHAR), Key(actionLabel, Kind.ACTION, 1.6f),
        )
        rows = listOf(top) + middle + listOf(bottom)
        pressed.clear()
        place()
    }

    /** Fills the bottom screen; on any other screen, a little under half of it. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val screen = resources.displayMetrics.heightPixels
        @Suppress("DEPRECATION")
        val here = runCatching { context.getSystemService(WindowManager::class.java)?.defaultDisplay?.displayId }.getOrNull()
        val bottom = runCatching { DisplayHelper.bottomDisplay(context)?.displayId }.getOrNull()
        val h = if (here != null && here == bottom) screen else (screen * 0.46f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = place()

    private fun place() {
        if (width == 0 || height == 0) return
        val pad = dp(8f)
        val gap = dp(5f)
        val rowH = (height - 2 * pad - gap * (rows.size - 1)) / rows.size
        rows.forEachIndexed { r, keys ->
            // The middle letter row sits in a little, as on any keyboard.
            val inset = if (r == 2 && keys.size == 9) width * 0.05f else 0f
            val total = keys.sumOf { it.weight.toDouble() }.toFloat()
            val unit = (width - 2 * pad - 2 * inset - gap * (keys.size - 1)) / total
            var x = pad + inset
            val y = pad + r * (rowH + gap)
            keys.forEach { k ->
                k.box.set(x, y, x + unit * k.weight, y + rowH)
                x += unit * k.weight + gap
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val round = dp(9f)
        rows.forEach { keys ->
            keys.forEach { k ->
                val down = pressed.containsValue(k)
                val on = (k.kind == Kind.SHIFT && shift > 0)
                if (k.kind == Kind.ACTION) {
                    fill.shader = null
                    fill.color = accent
                    fill.alpha = if (down) 190 else 255
                } else {
                    val a = if (down) 70 else if (on) 48 else 20
                    fill.shader = LinearGradient(0f, k.box.top, 0f, k.box.bottom, Color.argb(a, 255, 255, 255), Color.argb(a / 2 + 2, 255, 255, 255), Shader.TileMode.CLAMP)
                }
                canvas.drawRoundRect(k.box, round, round, fill)
                if (k.kind != Kind.ACTION) canvas.drawRoundRect(k.box, round, round, edge)
                val cx = k.box.centerX()
                val cy = k.box.centerY()
                when (k.kind) {
                    Kind.SHIFT -> arrow(canvas, cx, cy, if (shift == 2) INK else if (shift == 1) INK else MUTED, shift == 2)
                    Kind.BACK -> delete(canvas, cx, cy)
                    else -> {
                        val quiet = k.kind == Kind.PAGE || k.kind == Kind.SPACE || (k.kind == Kind.CHAR && (k.label == "," || k.label == "."))
                        text.color = if (k.kind == Kind.ACTION) Color.rgb(0, 28, 26) else if (quiet) MUTED else INK
                        text.isFakeBoldText = k.kind == Kind.ACTION
                        text.textSize = when (k.kind) {
                            Kind.SPACE -> dp(12f)
                            Kind.PAGE, Kind.ACTION -> dp(15f)
                            else -> minOf(dp(23f), k.box.height() * 0.42f)
                        }
                        val label = if (k.kind == Kind.CHAR && shift > 0 && !symbols) k.label.uppercase() else k.label
                        canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
                    }
                }
            }
        }
    }

    private fun arrow(canvas: Canvas, cx: Float, cy: Float, color: Int, locked: Boolean) {
        val s = dp(9f)
        line.color = color
        line.strokeWidth = dp(1.8f)
        val p = Path().apply {
            moveTo(cx, cy - s); lineTo(cx + s, cy); lineTo(cx + s * 0.45f, cy); lineTo(cx + s * 0.45f, cy + s * 0.8f)
            lineTo(cx - s * 0.45f, cy + s * 0.8f); lineTo(cx - s * 0.45f, cy); lineTo(cx - s, cy); close()
        }
        canvas.drawPath(p, line)
        if (locked) canvas.drawLine(cx - s * 0.6f, cy + s * 1.3f, cx + s * 0.6f, cy + s * 1.3f, line)
    }

    private fun delete(canvas: Canvas, cx: Float, cy: Float) {
        val s = dp(8f)
        line.color = MUTED
        line.strokeWidth = dp(1.8f)
        val p = Path().apply {
            moveTo(cx - s * 1.5f, cy); lineTo(cx - s * 0.6f, cy - s); lineTo(cx + s * 1.4f, cy - s)
            lineTo(cx + s * 1.4f, cy + s); lineTo(cx - s * 0.6f, cy + s); close()
        }
        canvas.drawPath(p, line)
        val c = cx + s * 0.35f
        canvas.drawLine(c - s * 0.4f, cy - s * 0.4f, c + s * 0.4f, cy + s * 0.4f, line)
        canvas.drawLine(c + s * 0.4f, cy - s * 0.4f, c - s * 0.4f, cy + s * 0.4f, line)
    }

    /** The key at a point, or the nearest one (a touch between two keys still counts). */
    private fun keyAt(x: Float, y: Float): Key? {
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        rows.forEach { keys ->
            keys.forEach { k ->
                val dx = maxOf(k.box.left - x, 0f, x - k.box.right)
                val dy = maxOf(k.box.top - y, 0f, y - k.box.bottom)
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = k }
            }
        }
        return best
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            // A key acts as soon as it's touched, so fast typing with two thumbs never drops one.
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val k = keyAt(e.getX(i), e.getY(i)) ?: return true
                pressed[e.getPointerId(i)] = k
                runCatching { press(k) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                when (pressed.remove(e.getPointerId(e.actionIndex))?.kind) {
                    Kind.BACK -> main.removeCallbacks(repeat)
                    Kind.SPACE -> main.removeCallbacks(spaceHeld)
                    else -> Unit
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed.clear()
                main.removeCallbacks(repeat)
                main.removeCallbacks(spaceHeld)
            }
        }
        invalidate()
        return true
    }

    private val repeat = object : Runnable {
        override fun run() {
            service.backspace()
            main.postDelayed(this, 45)
        }
    }

    /** Space held: Android's keyboard picker, to switch to another keyboard. */
    private val spaceHeld = Runnable {
        service.backspace()
        service.pickKeyboard()
    }

    private fun press(k: Key) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (k.kind) {
            Kind.CHAR -> {
                service.type(if (shift > 0 && !symbols) k.label.uppercase() else k.label)
                if (shift == 1) shift = 0
            }
            Kind.SHIFT -> {
                val now = android.os.SystemClock.uptimeMillis()
                // Twice quickly locks capitals.
                shift = if (shift != 2 && now - shiftAt < 350) 2 else if (shift == 0) 1 else 0
                shiftAt = now
            }
            Kind.BACK -> {
                service.backspace()
                main.removeCallbacks(repeat)
                main.postDelayed(repeat, 400)
            }
            Kind.PAGE -> {
                symbols = !symbols
                val held = HashMap(pressed)
                build()
                // The key under the finger keeps its press, so letting go doesn't misfire.
                held.forEach { (id, old) -> rows.flatten().firstOrNull { it.kind == old.kind && it.kind == Kind.PAGE }?.let { pressed[id] = it } }
            }
            Kind.SPACE -> {
                service.type(" ")
                main.removeCallbacks(spaceHeld)
                main.postDelayed(spaceHeld, 700)
            }
            Kind.ACTION -> service.action()
        }
    }

    private companion object {
        val INK = Color.rgb(237, 230, 218)
        val MUTED = Color.rgb(163, 154, 142)
    }
}
