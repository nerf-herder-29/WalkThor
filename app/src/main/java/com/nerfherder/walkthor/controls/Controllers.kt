package com.nerfherder.walkthor.controls

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * "Controls": a live view of the Thor's own buttons, lighting up whatever you press.
 *
 * The diagram is deliberately generic: a plain body shape of WalkThor's own with the buttons
 * placed roughly where the Thor has them. No logos or traced outlines.
 */

/** A physical input on the Thor and the Android key codes it sends. */
enum class ThorInput(val label: String, val keys: IntArray) {
    A("A", intArrayOf(KeyEvent.KEYCODE_BUTTON_A)),
    B("B", intArrayOf(KeyEvent.KEYCODE_BUTTON_B)),
    X("X", intArrayOf(KeyEvent.KEYCODE_BUTTON_X)),
    Y("Y", intArrayOf(KeyEvent.KEYCODE_BUTTON_Y)),
    L1("L1", intArrayOf(KeyEvent.KEYCODE_BUTTON_L1)),
    R1("R1", intArrayOf(KeyEvent.KEYCODE_BUTTON_R1)),
    L2("L2", intArrayOf(KeyEvent.KEYCODE_BUTTON_L2)),
    R2("R2", intArrayOf(KeyEvent.KEYCODE_BUTTON_R2)),
    L3("Left stick press", intArrayOf(KeyEvent.KEYCODE_BUTTON_THUMBL)),
    R3("Right stick press", intArrayOf(KeyEvent.KEYCODE_BUTTON_THUMBR)),
    START("Start", intArrayOf(KeyEvent.KEYCODE_BUTTON_START)),
    SELECT("Select", intArrayOf(KeyEvent.KEYCODE_BUTTON_SELECT)),
    DPAD("D-pad", intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    ));
}

enum class Shape { FACE, SHOULDER, TRIGGER, DPAD, STICK, SMALL }

/**
 * One Thor button, placed in a 2:1 box (x and y from 0 to 1). [size] is a fraction of the box's
 * width.
 */
data class PadButton(
    val input: ThorInput,
    val label: String,
    val shape: Shape,
    val x: Float,
    val y: Float,
    val size: Float,
)

object ThorPad {
    private const val F = 0.075f
    private const val SH = 0.13f

    /** The Thor's controls, roughly where they sit: stick over D-pad on the left, face buttons over stick on the right. */
    val BUTTONS = listOf(
        PadButton(ThorInput.L2, "L2", Shape.TRIGGER, 0.2f, 0.035f, SH),
        PadButton(ThorInput.L1, "L1", Shape.SHOULDER, 0.2f, 0.12f, SH),
        PadButton(ThorInput.R2, "R2", Shape.TRIGGER, 0.8f, 0.035f, SH),
        PadButton(ThorInput.R1, "R1", Shape.SHOULDER, 0.8f, 0.12f, SH),
        PadButton(ThorInput.L3, "", Shape.STICK, 0.22f, 0.38f, 0.11f),
        PadButton(ThorInput.DPAD, "", Shape.DPAD, 0.36f, 0.66f, 0.13f),
        PadButton(ThorInput.R3, "", Shape.STICK, 0.64f, 0.66f, 0.11f),
        PadButton(ThorInput.SELECT, "Select", Shape.SMALL, 0.42f, 0.38f, 0.045f),
        PadButton(ThorInput.START, "Start", Shape.SMALL, 0.58f, 0.38f, 0.045f),
        PadButton(ThorInput.Y, "Y", Shape.FACE, 0.78f, 0.27f, F),
        PadButton(ThorInput.B, "B", Shape.FACE, 0.86f, 0.38f, F),
        PadButton(ThorInput.A, "A", Shape.FACE, 0.78f, 0.49f, F),
        PadButton(ThorInput.X, "X", Shape.FACE, 0.70f, 0.38f, F),
    )
}

/** Your choices for the Controls screen. */
object ControlPrefs {
    private const val PREFS = "controls"

    /** Whether you've agreed to let WalkThor see button presses while the Controls screen is open. */
    fun keysConsent(context: Context) = prefs(context).getBoolean("keysConsent", false)
    fun setKeysConsent(context: Context, on: Boolean) = prefs(context).edit().putBoolean("keysConsent", on).apply()
    fun showPresses(context: Context) = prefs(context).getBoolean("showPresses", false)
    fun setShowPresses(context: Context, on: Boolean) = prefs(context).edit().putBoolean("showPresses", on).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Android's short name for a key, e.g. "BUTTON_L1", "DPAD_UP". */
fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

/**
 * Buttons being held on the Thor right now, reported by the accessibility service only while the
 * Controls screen is open with "Show presses" on. Presses always pass straight on to the game.
 */
object ControllerInput {
    /** Whether the Controls screen wants presses right now. */
    val watching = MutableStateFlow(false)
    val pressed = MutableStateFlow<Set<Int>>(emptySet())
    /** The last button pressed (a key code), for the "you pressed" line. */
    val last = MutableStateFlow<Int?>(null)

    private val KNOWN = ThorInput.entries.flatMap { it.keys.toList() }.toSet()
    private const val PAD_SOURCES = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD

    /** Called by the accessibility service for each key event; must stay trivial. */
    fun onKey(event: KeyEvent) {
        val code = event.keyCode
        // Controller keys only: the usual ones, any gamepad button, or anything from a controller.
        val fromPad = (event.source and PAD_SOURCES) != 0
        if (code !in KNOWN && !KeyEvent.isGamepadButton(code) && !fromPad) return
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                pressed.value = pressed.value + code
                last.value = code
            }
            KeyEvent.ACTION_UP -> pressed.value = pressed.value - code
        }
    }

    fun clear() { pressed.value = emptySet() }

    fun inputFor(code: Int): ThorInput? = ThorInput.entries.firstOrNull { code in it.keys }
}
