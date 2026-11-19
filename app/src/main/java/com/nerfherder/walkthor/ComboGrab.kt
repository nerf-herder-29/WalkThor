package com.nerfherder.walkthor

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * While Back is held for the Back + D-pad shortcuts: a tiny, invisible window on each screen takes
 * the controller for itself, so the game gets nothing (no D-pad, sticks or buttons) until Back is
 * let go. The D-pad reports its directions as movement, which only the window that has the
 * controller can see, so the direction is read here and its shortcut done. Touches pass through.
 */
object ComboGrab {
    private val main = Handler(Looper.getMainLooper())
    private val views = mutableListOf<Pair<WindowManager, Catcher>>()
    /** Told which direction was pressed: one of the D-pad key codes. */
    private var onDirection: ((Int) -> Unit)? = null

    /** Never left up if Back's release somehow isn't heard. */
    private const val LONGEST_MS = 8_000L

    val up get() = views.isNotEmpty()

    /** Takes the controller until [release]. Main thread. */
    fun grab(service: SwipeGestureService, direction: (Int) -> Unit) {
        onDirection = direction
        if (views.isNotEmpty()) return
        val displays = service.getSystemService(DisplayManager::class.java)?.displays.orEmpty().filter { it.state != Display.STATE_OFF }
        for (display in displays) runCatching {
            val ctx = service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = ctx.getSystemService(WindowManager::class.java) ?: return@runCatching
            val v = Catcher(ctx)
            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Focusable (that's what takes the controller), but never touched.
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "WalkThor shortcut"
            }
            wm.addView(v, params)
            v.requestFocus()
            views += wm to v
        }
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, LONGEST_MS)
    }

    /** Back let go: the game has the controller again. Main thread. */
    fun release() {
        main.removeCallbacks(giveUp)
        onDirection = null
        views.forEach { (wm, v) -> runCatching { wm.removeViewImmediate(v) } }
        views.clear()
    }

    private val giveUp = Runnable { release() }

    private class Catcher(context: Context) : View(context) {
        private var hatX = 0
        private var hatY = 0
        private var l2 = false
        private var r2 = false

        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        // Reported as a plain view, so WalkThor doesn't take it for an app coming to the front.
        override fun getAccessibilityClassName(): CharSequence = "android.view.View"

        /** The D-pad (and sticks): read for a direction, and kept from the game. */
        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (event.source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
            ) {
                val x = Math.round(event.getAxisValue(MotionEvent.AXIS_HAT_X))
                val y = Math.round(event.getAxisValue(MotionEvent.AXIS_HAT_Y))
                // Only the moment a direction is first pressed.
                if (x != hatX && x != 0) onDirection?.invoke(if (x < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                if (y != hatY && y != 0) onDirection?.invoke(if (y < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
                hatX = x
                hatY = y
                // L2 and R2, where they're sent as movement (how far they're pulled) rather than presses.
                val left = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.6f
                val right = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) > 0.6f
                if (left && !l2) onDirection?.invoke(KeyEvent.KEYCODE_BUTTON_L2)
                if (right && !r2) onDirection?.invoke(KeyEvent.KEYCODE_BUTTON_R2)
                l2 = left
                r2 = right
            }
            return true
        }

        /** Buttons pressed meanwhile go nowhere (a D-pad that sends keys is read by the service). */
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = true
    }
}
