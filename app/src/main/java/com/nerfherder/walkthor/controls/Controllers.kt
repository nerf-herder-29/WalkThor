package com.nerfherder.walkthor.controls

import android.content.Context
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * "Controls": which Thor button does what, for the system the game was made for.
 *
 * The diagrams are deliberately generic: a plain body shape of WalkThor's own, with each system's
 * buttons placed roughly where they sit on that system's controller and labelled with their
 * names. No logos, brand colours or traced controller outlines.
 */

/** A physical input on the Thor. Face buttons are named by position, since labels vary. */
enum class ThorInput(val label: String, val short: String, val keys: IntArray) {
    SOUTH("Bottom face button", "", intArrayOf(KeyEvent.KEYCODE_BUTTON_A)),
    EAST("Right face button", "", intArrayOf(KeyEvent.KEYCODE_BUTTON_B)),
    WEST("Left face button", "", intArrayOf(KeyEvent.KEYCODE_BUTTON_X)),
    NORTH("Top face button", "", intArrayOf(KeyEvent.KEYCODE_BUTTON_Y)),
    L1("L1", "L1", intArrayOf(KeyEvent.KEYCODE_BUTTON_L1)),
    R1("R1", "R1", intArrayOf(KeyEvent.KEYCODE_BUTTON_R1)),
    L2("L2", "L2", intArrayOf(KeyEvent.KEYCODE_BUTTON_L2)),
    R2("R2", "R2", intArrayOf(KeyEvent.KEYCODE_BUTTON_R2)),
    L3("Left stick press", "L3", intArrayOf(KeyEvent.KEYCODE_BUTTON_THUMBL)),
    R3("Right stick press", "R3", intArrayOf(KeyEvent.KEYCODE_BUTTON_THUMBR)),
    START("Start", "Start", intArrayOf(KeyEvent.KEYCODE_BUTTON_START)),
    SELECT("Select", "Select", intArrayOf(KeyEvent.KEYCODE_BUTTON_SELECT)),
    DPAD("D-pad", "D-pad", intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )),
    LEFT_STICK("Left stick", "Left stick", intArrayOf()),
    RIGHT_STICK("Right stick", "Right stick", intArrayOf()),
    NONE("Not mapped", "—", intArrayOf());

    val isFace get() = this == SOUTH || this == EAST || this == WEST || this == NORTH

    companion object {
        fun byName(name: String?) = entries.firstOrNull { it.name == name }
    }
}

enum class Shape { FACE, BIG_FACE, PILL, SHOULDER, TRIGGER, DPAD, STICK, SMALL }

/** Which side of a button its Thor label sits on. */
enum class Side { LEFT, RIGHT, TOP, BOTTOM }

/**
 * One button of the original controller, placed in a 2:1 box (x and y from 0 to 1).
 * [size] is a fraction of the box's width.
 */
data class PadButton(
    val id: String,
    val label: String,
    val shape: Shape,
    val x: Float,
    val y: Float,
    val size: Float,
    val default: ThorInput,
    val side: Side = Side.BOTTOM,
)

data class PadLayout(val id: String, val name: String, val buttons: List<PadButton>)

object Layouts {
    private const val F = 0.075f   // face button
    private const val S = 0.045f   // start/select
    private const val D = 0.13f    // d-pad
    private const val K = 0.11f    // stick
    private const val SH = 0.13f   // shoulder width

    /** Four face buttons in a diamond around (cx, cy); labels north/east/south/west. */
    private fun diamond(
        cx: Float, cy: Float,
        n: Pair<String, ThorInput>, e: Pair<String, ThorInput>, s: Pair<String, ThorInput>, w: Pair<String, ThorInput>,
        ids: List<String> = listOf(n.first, e.first, s.first, w.first),
    ) = listOf(
        PadButton(ids[0], n.first, Shape.FACE, cx, cy - 0.11f, F, n.second, Side.TOP),
        PadButton(ids[1], e.first, Shape.FACE, cx + 0.08f, cy, F, e.second, Side.RIGHT),
        PadButton(ids[2], s.first, Shape.FACE, cx, cy + 0.11f, F, s.second, Side.BOTTOM),
        PadButton(ids[3], w.first, Shape.FACE, cx - 0.08f, cy, F, w.second, Side.LEFT),
    )

    private fun shoulders(l1: String, r1: String, l2: String?, r2: String?, l1d: ThorInput, r1d: ThorInput, l2d: ThorInput, r2d: ThorInput) =
        listOfNotNull(
            l2?.let { PadButton("l2", it, Shape.TRIGGER, 0.2f, 0.035f, SH, l2d, Side.LEFT) },
            PadButton("l1", l1, Shape.SHOULDER, 0.2f, 0.12f, SH, l1d, Side.LEFT),
            r2?.let { PadButton("r2", it, Shape.TRIGGER, 0.8f, 0.035f, SH, r2d, Side.RIGHT) },
            PadButton("r1", r1, Shape.SHOULDER, 0.8f, 0.12f, SH, r1d, Side.RIGHT),
        )

    val PLAYSTATION = PadLayout(
        "ps", "PlayStation-style layout",
        shoulders("L1", "R1", "L2", "R2", ThorInput.L1, ThorInput.R1, ThorInput.L2, ThorInput.R2) +
            listOf(PadButton("dpad", "", Shape.DPAD, 0.22f, 0.40f, D, ThorInput.DPAD, Side.LEFT)) +
            diamond(0.78f, 0.40f, "△" to ThorInput.NORTH, "○" to ThorInput.EAST, "×" to ThorInput.SOUTH, "□" to ThorInput.WEST,
                listOf("triangle", "circle", "cross", "square")) +
            listOf(
                PadButton("select", "Select", Shape.SMALL, 0.42f, 0.38f, S, ThorInput.SELECT, Side.TOP),
                PadButton("start", "Start", Shape.SMALL, 0.58f, 0.38f, S, ThorInput.START, Side.TOP),
                PadButton("ls", "L3", Shape.STICK, 0.36f, 0.66f, K, ThorInput.LEFT_STICK, Side.BOTTOM),
                PadButton("rs", "R3", Shape.STICK, 0.64f, 0.66f, K, ThorInput.RIGHT_STICK, Side.BOTTOM),
            ),
    )

    val PSP = PadLayout(
        "psp", "PSP-style layout",
        shoulders("L", "R", null, null, ThorInput.L1, ThorInput.R1, ThorInput.L2, ThorInput.R2) +
            listOf(
                PadButton("dpad", "", Shape.DPAD, 0.22f, 0.36f, D, ThorInput.DPAD, Side.LEFT),
                PadButton("ls", "", Shape.STICK, 0.22f, 0.66f, 0.085f, ThorInput.LEFT_STICK, Side.BOTTOM),
            ) +
            diamond(0.78f, 0.40f, "△" to ThorInput.NORTH, "○" to ThorInput.EAST, "×" to ThorInput.SOUTH, "□" to ThorInput.WEST,
                listOf("triangle", "circle", "cross", "square")) +
            listOf(
                PadButton("select", "Select", Shape.SMALL, 0.44f, 0.62f, S, ThorInput.SELECT, Side.BOTTOM),
                PadButton("start", "Start", Shape.SMALL, 0.56f, 0.62f, S, ThorInput.START, Side.BOTTOM),
            ),
    )

    val XBOX = PadLayout(
        "xbox", "Xbox 360-style layout",
        shoulders("LB", "RB", "LT", "RT", ThorInput.L1, ThorInput.R1, ThorInput.L2, ThorInput.R2) +
            listOf(
                PadButton("ls", "LS", Shape.STICK, 0.22f, 0.38f, K, ThorInput.LEFT_STICK, Side.LEFT),
                PadButton("dpad", "", Shape.DPAD, 0.36f, 0.66f, D, ThorInput.DPAD, Side.BOTTOM),
                PadButton("rs", "RS", Shape.STICK, 0.64f, 0.66f, K, ThorInput.RIGHT_STICK, Side.BOTTOM),
                PadButton("back", "Back", Shape.SMALL, 0.42f, 0.38f, S, ThorInput.SELECT, Side.TOP),
                PadButton("start", "Start", Shape.SMALL, 0.58f, 0.38f, S, ThorInput.START, Side.TOP),
            ) +
            diamond(0.78f, 0.38f, "Y" to ThorInput.NORTH, "B" to ThorInput.EAST, "A" to ThorInput.SOUTH, "X" to ThorInput.WEST),
    )

    val GAMECUBE = PadLayout(
        "gc", "GameCube-style layout",
        listOf(
            PadButton("l", "L", Shape.TRIGGER, 0.2f, 0.05f, SH, ThorInput.L2, Side.LEFT),
            PadButton("r", "R", Shape.TRIGGER, 0.8f, 0.05f, SH, ThorInput.R2, Side.RIGHT),
            PadButton("z", "Z", Shape.SHOULDER, 0.8f, 0.14f, 0.1f, ThorInput.R1, Side.RIGHT),
            PadButton("ls", "", Shape.STICK, 0.22f, 0.38f, K, ThorInput.LEFT_STICK, Side.LEFT),
            PadButton("dpad", "", Shape.DPAD, 0.36f, 0.68f, 0.1f, ThorInput.DPAD, Side.BOTTOM),
            PadButton("cs", "C", Shape.STICK, 0.64f, 0.68f, 0.085f, ThorInput.RIGHT_STICK, Side.BOTTOM),
            PadButton("start", "Start", Shape.SMALL, 0.5f, 0.40f, S, ThorInput.START, Side.TOP),
            PadButton("a", "A", Shape.BIG_FACE, 0.78f, 0.42f, 0.1f, ThorInput.SOUTH, Side.BOTTOM),
            PadButton("b", "B", Shape.FACE, 0.685f, 0.50f, 0.06f, ThorInput.WEST, Side.LEFT),
            PadButton("x", "X", Shape.PILL, 0.875f, 0.36f, 0.06f, ThorInput.EAST, Side.RIGHT),
            PadButton("y", "Y", Shape.PILL, 0.76f, 0.27f, 0.06f, ThorInput.NORTH, Side.TOP),
        ),
    )

    val N64 = PadLayout(
        "n64", "N64-style layout",
        listOf(
            PadButton("l", "L", Shape.SHOULDER, 0.2f, 0.08f, SH, ThorInput.L1, Side.LEFT),
            PadButton("r", "R", Shape.SHOULDER, 0.8f, 0.08f, SH, ThorInput.R1, Side.RIGHT),
            PadButton("dpad", "", Shape.DPAD, 0.2f, 0.40f, D, ThorInput.DPAD, Side.LEFT),
            PadButton("start", "Start", Shape.SMALL, 0.5f, 0.34f, S, ThorInput.START, Side.TOP),
            PadButton("stick", "", Shape.STICK, 0.5f, 0.64f, K, ThorInput.LEFT_STICK, Side.LEFT),
            PadButton("z", "Z (underneath)", Shape.SMALL, 0.5f, 0.90f, 0.06f, ThorInput.L2, Side.RIGHT),
            PadButton("b", "B", Shape.FACE, 0.66f, 0.46f, F, ThorInput.WEST, Side.LEFT),
            PadButton("a", "A", Shape.FACE, 0.72f, 0.56f, F, ThorInput.SOUTH, Side.BOTTOM),
            PadButton("cu", "C↑", Shape.FACE, 0.83f, 0.25f, 0.055f, ThorInput.RIGHT_STICK, Side.TOP),
            PadButton("cl", "C←", Shape.FACE, 0.77f, 0.34f, 0.055f, ThorInput.RIGHT_STICK, Side.LEFT),
            PadButton("cr", "C→", Shape.FACE, 0.89f, 0.34f, 0.055f, ThorInput.RIGHT_STICK, Side.RIGHT),
            PadButton("cd", "C↓", Shape.FACE, 0.83f, 0.43f, 0.055f, ThorInput.RIGHT_STICK, Side.RIGHT),
        ),
    )

    val SWITCH = PadLayout(
        "switch", "Switch-style layout",
        shoulders("L", "R", "ZL", "ZR", ThorInput.L1, ThorInput.R1, ThorInput.L2, ThorInput.R2) +
            listOf(
                PadButton("ls", "", Shape.STICK, 0.22f, 0.36f, K, ThorInput.LEFT_STICK, Side.LEFT),
                PadButton("dpad", "", Shape.DPAD, 0.36f, 0.66f, D, ThorInput.DPAD, Side.BOTTOM),
                PadButton("rs", "", Shape.STICK, 0.64f, 0.66f, K, ThorInput.RIGHT_STICK, Side.BOTTOM),
                PadButton("minus", "−", Shape.SMALL, 0.42f, 0.32f, S, ThorInput.SELECT, Side.TOP),
                PadButton("plus", "+", Shape.SMALL, 0.58f, 0.32f, S, ThorInput.START, Side.TOP),
            ) +
            // Nintendo positions: A on the right, B at the bottom. Mapped by name by default.
            diamond(0.78f, 0.38f, "X" to ThorInput.NORTH, "A" to ThorInput.SOUTH, "B" to ThorInput.EAST, "Y" to ThorInput.WEST),
    )

    val NINTENDO_HANDHELD = PadLayout(
        "3ds", "DS / 3DS-style layout",
        shoulders("L", "R", "ZL", "ZR", ThorInput.L1, ThorInput.R1, ThorInput.L2, ThorInput.R2) +
            listOf(
                PadButton("ls", "", Shape.STICK, 0.22f, 0.32f, 0.095f, ThorInput.LEFT_STICK, Side.LEFT),
                PadButton("dpad", "", Shape.DPAD, 0.22f, 0.64f, 0.11f, ThorInput.DPAD, Side.LEFT),
                PadButton("select", "Select", Shape.SMALL, 0.44f, 0.70f, S, ThorInput.SELECT, Side.BOTTOM),
                PadButton("start", "Start", Shape.SMALL, 0.56f, 0.70f, S, ThorInput.START, Side.BOTTOM),
            ) +
            diamond(0.78f, 0.40f, "X" to ThorInput.NORTH, "A" to ThorInput.SOUTH, "B" to ThorInput.EAST, "Y" to ThorInput.WEST),
    )

    val SNES = PadLayout(
        "snes", "SNES-style layout",
        listOf(
            PadButton("l", "L", Shape.SHOULDER, 0.2f, 0.08f, SH, ThorInput.L1, Side.LEFT),
            PadButton("r", "R", Shape.SHOULDER, 0.8f, 0.08f, SH, ThorInput.R1, Side.RIGHT),
            PadButton("dpad", "", Shape.DPAD, 0.22f, 0.44f, D, ThorInput.DPAD, Side.LEFT),
            PadButton("select", "Select", Shape.SMALL, 0.43f, 0.46f, S, ThorInput.SELECT, Side.BOTTOM),
            PadButton("start", "Start", Shape.SMALL, 0.55f, 0.46f, S, ThorInput.START, Side.BOTTOM),
        ) + diamond(0.78f, 0.44f, "X" to ThorInput.NORTH, "A" to ThorInput.EAST, "B" to ThorInput.SOUTH, "Y" to ThorInput.WEST),
    )

    val NES = PadLayout(
        "nes", "NES-style layout",
        listOf(
            PadButton("dpad", "", Shape.DPAD, 0.22f, 0.46f, D, ThorInput.DPAD, Side.LEFT),
            PadButton("select", "Select", Shape.SMALL, 0.43f, 0.52f, S, ThorInput.SELECT, Side.BOTTOM),
            PadButton("start", "Start", Shape.SMALL, 0.55f, 0.52f, S, ThorInput.START, Side.BOTTOM),
            PadButton("b", "B", Shape.FACE, 0.70f, 0.50f, F, ThorInput.SOUTH, Side.BOTTOM),
            PadButton("a", "A", Shape.FACE, 0.82f, 0.50f, F, ThorInput.EAST, Side.BOTTOM),
        ),
    )

    val GENESIS = PadLayout(
        "genesis", "Genesis-style layout (6 buttons)",
        listOf(
            PadButton("dpad", "", Shape.DPAD, 0.22f, 0.44f, D, ThorInput.DPAD, Side.LEFT),
            PadButton("start", "Start", Shape.SMALL, 0.5f, 0.30f, S, ThorInput.START, Side.TOP),
            PadButton("mode", "Mode", Shape.SMALL, 0.5f, 0.70f, S, ThorInput.SELECT, Side.BOTTOM),
            PadButton("x", "X", Shape.FACE, 0.66f, 0.34f, 0.065f, ThorInput.L1, Side.TOP),
            PadButton("y", "Y", Shape.FACE, 0.76f, 0.30f, 0.065f, ThorInput.NORTH, Side.TOP),
            PadButton("z", "Z", Shape.FACE, 0.86f, 0.26f, 0.065f, ThorInput.R1, Side.TOP),
            PadButton("a", "A", Shape.FACE, 0.66f, 0.54f, 0.065f, ThorInput.WEST, Side.BOTTOM),
            PadButton("b", "B", Shape.FACE, 0.76f, 0.50f, 0.065f, ThorInput.SOUTH, Side.BOTTOM),
            PadButton("c", "C", Shape.FACE, 0.86f, 0.46f, 0.065f, ThorInput.EAST, Side.BOTTOM),
        ),
    )

    val GBA = PadLayout(
        "gba", "Game Boy Advance-style layout",
        listOf(
            PadButton("l", "L", Shape.SHOULDER, 0.2f, 0.08f, SH, ThorInput.L1, Side.LEFT),
            PadButton("r", "R", Shape.SHOULDER, 0.8f, 0.08f, SH, ThorInput.R1, Side.RIGHT),
            PadButton("dpad", "", Shape.DPAD, 0.22f, 0.46f, D, ThorInput.DPAD, Side.LEFT),
            PadButton("select", "Select", Shape.SMALL, 0.44f, 0.74f, S, ThorInput.SELECT, Side.BOTTOM),
            PadButton("start", "Start", Shape.SMALL, 0.56f, 0.74f, S, ThorInput.START, Side.BOTTOM),
            PadButton("b", "B", Shape.FACE, 0.72f, 0.52f, F, ThorInput.SOUTH, Side.BOTTOM),
            PadButton("a", "A", Shape.FACE, 0.84f, 0.42f, F, ThorInput.EAST, Side.RIGHT),
        ),
    )

    val ALL = listOf(PLAYSTATION, PSP, XBOX, GAMECUBE, N64, SWITCH, NINTENDO_HANDHELD, SNES, NES, GENESIS, GBA)

    fun byId(id: String?) = ALL.firstOrNull { it.id == id }

    /**
     * The layout for a game, from its emulator's name and the ROM file's extension
     * (e.g. Dolphin → GameCube, AetherSX2 → PlayStation, RetroArch + .sfc → SNES). Null if unknown.
     */
    fun detect(player: String?, file: String?): PadLayout? {
        val p = player?.lowercase().orEmpty()
        val ext = file?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when {
            "dolphin" in p -> GAMECUBE
            listOf("aethersx2", "nethersx2", "pcsx2", "armsx2", "duckstation", "swanstation", "epsxe", "fpse").any { it in p } -> PLAYSTATION
            "ppsspp" in p -> PSP
            listOf("ax360", "xenia", "x360").any { it in p } -> XBOX
            listOf("eden", "citron", "yuzu", "sudachi", "suyu", "ryujinx", "torzu", "strato", "skyline").any { it in p } -> SWITCH
            listOf("citra", "lime3ds", "azahar", "melonds", "drastic", "mandarine", "panda3ds").any { it in p } -> NINTENDO_HANDHELD
            "mupen" in p || "m64" in p -> N64
            "snes" in p -> SNES
            "fceumm" in p || "nestopia" in p -> NES
            "genesis" in p || "picodrive" in p -> GENESIS
            "mgba" in p || "gba" in p -> GBA
            else -> byExtension(ext)
        }
    }

    private fun byExtension(ext: String) = when (ext) {
        "z64", "n64", "v64" -> N64
        "sfc", "smc" -> SNES
        "nes", "fds" -> NES
        "md", "gen", "smd", "32x" -> GENESIS
        "gba", "gb", "gbc" -> GBA
        "nds", "3ds", "cci", "cxi" -> NINTENDO_HANDHELD
        "nsp", "xci" -> SWITCH
        "gcm", "rvz", "gcz", "wbfs", "wad" -> GAMECUBE
        "cso", "pbp" -> PSP
        "xex" -> XBOX
        else -> null
    }
}

/** Your corrections to the default mappings, and which layout each emulator uses. */
object ControlPrefs {
    private const val PREFS = "controls"

    private fun emu(player: String?) = player?.substringBefore(" (")?.trim()?.lowercase()?.ifEmpty { null } ?: "unknown"

    fun layoutFor(context: Context, player: String?, file: String?): PadLayout =
        Layouts.byId(prefs(context).getString("layout/" + emu(player), null))
            ?: Layouts.detect(player, file)
            ?: Layouts.PLAYSTATION

    fun setLayout(context: Context, player: String?, layout: PadLayout) {
        prefs(context).edit().putString("layout/" + emu(player), layout.id).apply()
    }

    fun mapping(context: Context, player: String?, layout: PadLayout, button: PadButton): ThorInput =
        ThorInput.byName(prefs(context).getString(key(player, layout, button), null)) ?: button.default

    fun setMapping(context: Context, player: String?, layout: PadLayout, button: PadButton, input: ThorInput?) {
        val e = prefs(context).edit()
        if (input == null || input == button.default) e.remove(key(player, layout, button)) else e.putString(key(player, layout, button), input.name)
        e.apply()
    }

    fun resetAll(context: Context, player: String?, layout: PadLayout) {
        val e = prefs(context).edit()
        layout.buttons.forEach { e.remove(key(player, layout, it)) }
        e.apply()
    }

    /** Whether you've agreed to let WalkThor see button presses while the Controls screen is open. */
    fun keysConsent(context: Context) = prefs(context).getBoolean("keysConsent", false)
    fun setKeysConsent(context: Context, on: Boolean) = prefs(context).edit().putBoolean("keysConsent", on).apply()
    fun showPresses(context: Context) = prefs(context).getBoolean("showPresses", false)
    fun setShowPresses(context: Context, on: Boolean) = prefs(context).edit().putBoolean("showPresses", on).apply()

    private fun key(player: String?, layout: PadLayout, button: PadButton) = "map/${emu(player)}/${layout.id}/${button.id}"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

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

    private val GAMEPAD = (ThorInput.entries.flatMap { it.keys.toList() }).toSet()

    /** Called by the accessibility service for each key event; must stay trivial. */
    fun onKey(event: KeyEvent) {
        val code = event.keyCode
        if (code !in GAMEPAD) return
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
