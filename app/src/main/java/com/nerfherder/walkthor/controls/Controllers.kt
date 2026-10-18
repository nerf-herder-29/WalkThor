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
    /** For the four face buttons: which position this is ("top", "right", "bottom", "left"). */
    val face: String? = null,
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
        // Face buttons by position; which button sits where comes from [ControlPrefs.faceCode].
        PadButton(ThorInput.X, "", Shape.FACE, 0.78f, 0.27f, F, face = "top"),
        PadButton(ThorInput.A, "", Shape.FACE, 0.86f, 0.38f, F, face = "right"),
        PadButton(ThorInput.B, "", Shape.FACE, 0.78f, 0.49f, F, face = "bottom"),
        PadButton(ThorInput.Y, "", Shape.FACE, 0.70f, 0.38f, F, face = "left"),
    )

    val FACE_ORDER = listOf("top", "right", "bottom", "left")
}

/**
 * How the four face buttons are labelled for the system being played, top/right/bottom/left.
 * Generic: plain monochrome letters and shapes, no brand colours or logos.
 */
enum class FaceStyle(val title: String, val labels: List<String>?) {
    /** The Thor's own letters, as it reports them. */
    THOR("Thor", null),
    PLAYSTATION("Shapes", listOf("triangle", "circle", "cross", "square")),
    XBOX("Letters · A at bottom", listOf("Y", "B", "A", "X")),
    NINTENDO("Letters · A at right", listOf("X", "A", "B", "Y"));

    companion object {
        fun byName(name: String?) = entries.firstOrNull { it.name == name }

        /** A guess from the emulator's name and the game file's extension. */
        fun detect(player: String?, file: String?): FaceStyle {
            val p = player?.lowercase().orEmpty()
            val ext = file?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return when {
                listOf("aethersx2", "nethersx2", "pcsx2", "armsx2", "duckstation", "swanstation", "epsxe", "fpse", "ppsspp", "beetle psx", "pcsx").any { it in p } -> PLAYSTATION
                listOf("ax360", "xenia", "x360").any { it in p } -> XBOX
                listOf("dolphin", "eden", "citron", "yuzu", "sudachi", "suyu", "ryujinx", "torzu", "citra", "lime3ds", "azahar",
                    "melonds", "drastic", "mupen", "snes", "mgba", "nestopia", "fceumm").any { it in p } -> NINTENDO
                ext in setOf("iso", "chd", "cue", "pbp", "cso") && "retroarch" in p -> PLAYSTATION
                ext in setOf("sfc", "smc", "nes", "gba", "gb", "gbc", "nds", "3ds", "z64", "n64", "v64", "nsp", "xci", "rvz", "gcz", "wbfs") -> NINTENDO
                ext == "xex" -> XBOX
                else -> THOR
            }
        }
    }
}

/** Your choices for the Controls screen. */
object ControlPrefs {
    private const val PREFS = "controls"

    /** Whether you've agreed to let WalkThor see button presses while the Controls screen is open. */
    fun keysConsent(context: Context) = prefs(context).getBoolean("keysConsent", false)
    fun setKeysConsent(context: Context, on: Boolean) = prefs(context).edit().putBoolean("keysConsent", on).apply()
    fun showPresses(context: Context) = prefs(context).getBoolean("showPresses", false)
    fun setShowPresses(context: Context, on: Boolean) = prefs(context).edit().putBoolean("showPresses", on).apply()

    /**
     * The key code the Thor sends for the face button at [position]. Defaults to the Thor's
     * Nintendo-style layout (X top, A right, B bottom, Y left); "Set up face buttons" records the
     * real ones.
     */
    fun faceCode(context: Context, position: String): Int = prefs(context).getInt("face/$position", when (position) {
        "top" -> KeyEvent.KEYCODE_BUTTON_X
        "right" -> KeyEvent.KEYCODE_BUTTON_A
        "bottom" -> KeyEvent.KEYCODE_BUTTON_B
        else -> KeyEvent.KEYCODE_BUTTON_Y
    })

    fun setFaceCode(context: Context, position: String, code: Int) = prefs(context).edit().putInt("face/$position", code).apply()

    /** The face-button style for an emulator: your pick, else a guess. */
    fun faceStyle(context: Context, player: String?, file: String?): FaceStyle =
        FaceStyle.byName(prefs(context).getString("style/" + emu(player), null)) ?: FaceStyle.detect(player, file)

    fun setFaceStyle(context: Context, player: String?, style: FaceStyle) =
        prefs(context).edit().putString("style/" + emu(player), style.name).apply()

    private fun emu(player: String?) = player?.substringBefore(" (")?.trim()?.lowercase()?.ifEmpty { null } ?: "unknown"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Android's short name for a key, e.g. "BUTTON_L1", "DPAD_UP". */
fun keyName(code: Int): String = KNOWN_NAMES[code] ?: KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

/** The letter or name printed for a button, e.g. "A", "L1". */
fun buttonLabel(code: Int): String = keyName(code).removePrefix("BUTTON_")

private val KNOWN_NAMES = mapOf(
    KeyEvent.KEYCODE_BUTTON_A to "BUTTON_A", KeyEvent.KEYCODE_BUTTON_B to "BUTTON_B",
    KeyEvent.KEYCODE_BUTTON_C to "BUTTON_C", KeyEvent.KEYCODE_BUTTON_X to "BUTTON_X",
    KeyEvent.KEYCODE_BUTTON_Y to "BUTTON_Y", KeyEvent.KEYCODE_BUTTON_Z to "BUTTON_Z",
    KeyEvent.KEYCODE_BUTTON_L1 to "BUTTON_L1", KeyEvent.KEYCODE_BUTTON_R1 to "BUTTON_R1",
    KeyEvent.KEYCODE_BUTTON_L2 to "BUTTON_L2", KeyEvent.KEYCODE_BUTTON_R2 to "BUTTON_R2",
    KeyEvent.KEYCODE_BUTTON_THUMBL to "BUTTON_THUMBL", KeyEvent.KEYCODE_BUTTON_THUMBR to "BUTTON_THUMBR",
    KeyEvent.KEYCODE_BUTTON_START to "BUTTON_START", KeyEvent.KEYCODE_BUTTON_SELECT to "BUTTON_SELECT",
    KeyEvent.KEYCODE_BUTTON_MODE to "BUTTON_MODE",
    KeyEvent.KEYCODE_DPAD_UP to "DPAD_UP", KeyEvent.KEYCODE_DPAD_DOWN to "DPAD_DOWN",
    KeyEvent.KEYCODE_DPAD_LEFT to "DPAD_LEFT", KeyEvent.KEYCODE_DPAD_RIGHT to "DPAD_RIGHT",
    KeyEvent.KEYCODE_BACK to "BACK", KeyEvent.KEYCODE_HOME to "HOME",
)

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
    /** Counts presses, so the same button pressed twice still registers as a new press. */
    val count = MutableStateFlow(0)

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
                count.value = count.value + 1
            }
            KeyEvent.ACTION_UP -> pressed.value = pressed.value - code
        }
    }

    fun clear() { pressed.value = emptySet() }

    fun inputFor(code: Int): ThorInput? = ThorInput.entries.firstOrNull { code in it.keys }
}
