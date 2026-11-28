package com.nerfherder.walkthor.controls

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Run as `app_process` by the Thor's system helper (as root): makes another controller (any
 * Bluetooth or USB one) work as the Thor's own controller, the way [JoyConJoinTool] does for
 * Joy-Cons. Every emulator already set up for the Thor then just works with it.
 *
 * It reads the controller's own input file and feeds each press into the Thor's built-in
 * controller ("Odin Controller"). Controllers number their buttons and sticks differently, so it
 * first reads the same key layout file Android uses for that controller (Vendor_…_Product_….kl,
 * or Generic.kl), which says what each button and axis is; then A is the Thor's A, the right
 * stick its right stick, and so on. Home (or Guide, or the PlayStation button) is the Thor's
 * Back button. With "position", the face buttons go by where they sit instead of their letters
 * (the bottom button is the Thor's bottom button, B, and so on).
 *
 * While joined, it holds the controller's input file to itself, so apps don't also see it as a
 * second player. Ends by itself when the controller disconnects, or when WalkThor stops it.
 *
 * `PadJoinTool <vendor hex> <product hex> <letter|position>`
 */
object PadJoinTool {
    private const val EV_SYN = 0; private const val EV_KEY = 1; private const val EV_ABS = 3
    private const val BTN_SOUTH = 0x130; private const val BTN_EAST = 0x131
    private const val BTN_NORTH = 0x133; private const val BTN_WEST = 0x134
    private const val BTN_TL = 0x136; private const val BTN_TR = 0x137
    private const val BTN_TL2 = 0x138; private const val BTN_TR2 = 0x139
    private const val BTN_SELECT = 0x13a; private const val BTN_START = 0x13b
    private const val BTN_THUMBL = 0x13d; private const val BTN_THUMBR = 0x13e
    private const val KEY_BACK = 158
    private const val ABS_X = 0; private const val ABS_Y = 1; private const val ABS_Z = 2; private const val ABS_RZ = 5
    private const val ABS_GAS = 9; private const val ABS_BRAKE = 10
    private const val ABS_HAT0X = 16; private const val ABS_HAT0Y = 17

    private fun say(s: String) { println(s); System.out.flush() }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (t: Throwable) {
            say("ERR ${t.javaClass.simpleName} ${t.message}")
        } finally {
            System.out.flush()
            System.exit(0)
        }
    }

    private class Device(val name: String, val node: String, val vendor: Int, val product: Int, val version: Int)

    private fun devices(): List<Pair<String, String>> =
        runCatching { File("/proc/bus/input/devices").readText().split("\n\n") }.getOrDefault(emptyList())
            .mapNotNull { b ->
                val name = Regex("Name=\"([^\"]*)\"").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
                name to b
            }

    private fun run(args: Array<String>) {
        val vendor = args.getOrNull(0)?.toIntOrNull(16) ?: run { say("ERR usage"); return }
        val product = args.getOrNull(1)?.toIntOrNull(16) ?: run { say("ERR usage"); return }
        val byPosition = args.getOrNull(2) == "position"
        say("starting: %04x:%04x, buttons by %s".format(vendor, product, if (byPosition) "position" else "letter"))

        // The controller's gamepad input file: same maker and model, with sticks (EV has ABS).
        val pad = devices().firstNotNullOfOrNull { (name, b) ->
            if (name == "Odin Controller") return@firstNotNullOfOrNull null
            val id = Regex("Vendor=([0-9a-fA-F]{4}) Product=([0-9a-fA-F]{4}) Version=([0-9a-fA-F]{4})").find(b) ?: return@firstNotNullOfOrNull null
            if (id.groupValues[1].toInt(16) != vendor || id.groupValues[2].toInt(16) != product) return@firstNotNullOfOrNull null
            val ev = Regex("B: EV=([0-9a-fA-F]+)").find(b)?.groupValues?.get(1)?.toLongOrNull(16) ?: 0L
            if (ev and (1L shl EV_ABS) == 0L || ev and (1L shl EV_KEY) == 0L) return@firstNotNullOfOrNull null
            val node = Regex("Handlers=[^\\n]*?(event\\d+)").find(b)?.groupValues?.get(1) ?: return@firstNotNullOfOrNull null
            Device(name, "/dev/input/$node", vendor, product, id.groupValues[3].toInt(16))
        } ?: run { say("ERR the controller's input file wasn't found"); return }
        say("controller: ${pad.name} (${pad.node})")

        val odin = devices().firstOrNull { it.first == "Odin Controller" }
            ?.let { Regex("Handlers=[^\\n]*?(event\\d+)").find(it.second)?.groupValues?.get(1) }
            ?.let { "/dev/input/$it" }
            ?: run { say("ERR the Thor's controller wasn't found"); return }

        val layout = keyLayout(pad)
        val padRanges = ranges(pad.node)
        val odinRanges = ranges(odin)
        say("its axes: " + padRanges.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}:${it.value[0]}..${it.value[1]}" })

        val input = FileInputStream(pad.node)
        hold(input)
        val out = java.io.FileOutputStream(odin)
        say("OK driving the Thor's controller from ${pad.name}")

        // Android's own names for this controller's buttons and axes, by its codes.
        val keys = layout.keys
        val axes = layout.axes
        // Some controllers call the right stick RX / RY rather than Z / RZ.
        val rightIsR = axes.values.none { it.name == "Z" || it.name == "RZ" }

        val last = HashMap<Long, Int>()
        val pending = ArrayList<IntArray>()
        fun key(code: Int, on: Boolean) {
            val k = (EV_KEY.toLong() shl 16) or code.toLong()
            val v = if (on) 1 else 0
            if (last[k] != v) { last[k] = v; pending += intArrayOf(EV_KEY, code, v) }
        }
        fun abs(code: Int, f: Float) {
            val r = odinRanges[code] ?: return
            val v = ((r[0] + r[1]) / 2f + f * (r[1] - r[0]) / 2f).toInt().coerceIn(r[0], r[1])
            val k = (EV_ABS.toLong() shl 16) or code.toLong()
            if (last[k] != v) { last[k] = v; pending += intArrayOf(EV_ABS, code, v) }
        }
        val dpad = BooleanArray(4) // up, down, left, right, from D-pad buttons
        var hatX = 0f; var hatY = 0f
        fun sendHat() {
            abs(ABS_HAT0X, if (dpad[3]) 1f else if (dpad[2]) -1f else hatX)
            abs(ABS_HAT0Y, if (dpad[1]) 1f else if (dpad[0]) -1f else hatY)
        }
        val swap = mapOf(BTN_SOUTH to BTN_EAST, BTN_EAST to BTN_SOUTH, BTN_NORTH to BTN_WEST, BTN_WEST to BTN_NORTH)
        fun face(code: Int) = if (byPosition) swap[code] ?: code else code

        val buf = ByteArray(24 * 64)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            var i = 0
            while (i + 24 <= n) {
                val type = bb.getShort(i + 16).toInt() and 0xFFFF
                val code = bb.getShort(i + 18).toInt() and 0xFFFF
                val value = bb.getInt(i + 20)
                i += 24
                when (type) {
                    EV_KEY -> {
                        if (value == 2) continue // auto-repeat
                        val on = value != 0
                        when (keys[code]) {
                            "BUTTON_A" -> key(face(BTN_SOUTH), on)
                            "BUTTON_B" -> key(face(BTN_EAST), on)
                            "BUTTON_X" -> key(face(BTN_NORTH), on)
                            "BUTTON_Y" -> key(face(BTN_WEST), on)
                            "BUTTON_L1" -> key(BTN_TL, on)
                            "BUTTON_R1" -> key(BTN_TR, on)
                            "BUTTON_L2" -> { key(BTN_TL2, on); abs(ABS_BRAKE, if (on) 1f else -1f) }
                            "BUTTON_R2" -> { key(BTN_TR2, on); abs(ABS_GAS, if (on) 1f else -1f) }
                            "BUTTON_SELECT" -> key(BTN_SELECT, on)
                            "BUTTON_START", "MENU" -> key(BTN_START, on)
                            "BUTTON_THUMBL" -> key(BTN_THUMBL, on)
                            "BUTTON_THUMBR" -> key(BTN_THUMBR, on)
                            "BUTTON_MODE", "HOME", "BACK" -> key(KEY_BACK, on)
                            "DPAD_UP" -> { dpad[0] = on; sendHat() }
                            "DPAD_DOWN" -> { dpad[1] = on; sendHat() }
                            "DPAD_LEFT" -> { dpad[2] = on; sendHat() }
                            "DPAD_RIGHT" -> { dpad[3] = on; sendHat() }
                        }
                    }
                    EV_ABS -> {
                        val a = axes[code] ?: continue
                        val r = padRanges[code] ?: continue
                        val lo = r[0].toFloat(); val hi = r[1].toFloat()
                        fun stick(): Float {
                            val mid = (lo + hi) / 2f; val half = (hi - lo) / 2f
                            if (half <= 0f) return 0f
                            val d = value - mid
                            if (kotlin.math.abs(d) <= maxOf(r[2].toFloat(), half * 0.04f)) return 0f
                            val f = (d / half).coerceIn(-1f, 1f)
                            return if (a.invert) -f else f
                        }
                        fun trigger(): Float = if (hi > lo) ((value - lo) / (hi - lo)).coerceIn(0f, 1f) else 0f
                        when (a.name) {
                            "X" -> abs(ABS_X, stick())
                            "Y" -> abs(ABS_Y, stick())
                            "Z" -> abs(ABS_Z, stick())
                            "RZ" -> abs(ABS_RZ, stick())
                            "RX" -> if (rightIsR) abs(ABS_Z, stick())
                            "RY" -> if (rightIsR) abs(ABS_RZ, stick())
                            "LTRIGGER", "BRAKE" -> { val t = trigger(); abs(ABS_BRAKE, t * 2 - 1); key(BTN_TL2, t > 0.4f) }
                            "RTRIGGER", "GAS" -> { val t = trigger(); abs(ABS_GAS, t * 2 - 1); key(BTN_TR2, t > 0.4f) }
                            "HAT_X" -> { hatX = Math.signum(value - (lo + hi) / 2f); sendHat() }
                            "HAT_Y" -> { hatY = Math.signum(value - (lo + hi) / 2f); sendHat() }
                        }
                    }
                    EV_SYN -> if (code == 0 && pending.isNotEmpty()) {
                        val b = ByteBuffer.allocate(24 * (pending.size + 1)).order(ByteOrder.LITTLE_ENDIAN)
                        for (e in pending + listOf(intArrayOf(EV_SYN, 0, 0))) {
                            b.putLong(0); b.putLong(0); b.putShort(e[0].toShort()); b.putShort(e[1].toShort()); b.putInt(e[2])
                        }
                        out.write(b.array())
                        pending.clear()
                    }
                }
            }
        }
        // Let go of everything that was held down, so nothing sticks on the Thor's controller.
        last.keys.toList().forEach { k ->
            val type = (k shr 16).toInt(); val code = (k and 0xFFFF).toInt()
            if (type == EV_KEY) key(code, false)
        }
        abs(ABS_X, 0f); abs(ABS_Y, 0f); abs(ABS_Z, 0f); abs(ABS_RZ, 0f); abs(ABS_GAS, -1f); abs(ABS_BRAKE, -1f)
        abs(ABS_HAT0X, 0f); abs(ABS_HAT0Y, 0f)
        if (pending.isNotEmpty()) runCatching {
            val b = ByteBuffer.allocate(24 * (pending.size + 1)).order(ByteOrder.LITTLE_ENDIAN)
            for (e in pending + listOf(intArrayOf(EV_SYN, 0, 0))) {
                b.putLong(0); b.putLong(0); b.putShort(e[0].toShort()); b.putShort(e[1].toShort()); b.putInt(e[2])
            }
            out.write(b.array())
        }
        say("controller gone")
    }

    /** Each axis's range and its flat (dead) zone: code → [min, max, flat], from Android's getevent. */
    private fun ranges(path: String): Map<Int, IntArray> {
        val ranges = HashMap<Int, IntArray>()
        runCatching {
            val p = ProcessBuilder("getevent", "-p", path).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            Regex("""([0-9a-fA-F]{4})\s*:\s*value -?\d+, min (-?\d+), max (-?\d+), fuzz -?\d+, flat (-?\d+)""").findAll(text).forEach { m ->
                ranges[m.groupValues[1].toInt(16)] = intArrayOf(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
            }
        }
        return ranges
    }

    private class Axis(val name: String, val invert: Boolean)
    private class Layout(val keys: Map<Int, String>, val axes: Map<Int, Axis>)

    /** The key layout file Android uses for [d], read the way Android picks it. */
    private fun keyLayout(d: Device): Layout {
        val names = listOf(
            "Vendor_%04x_Product_%04x_Version_%04x".format(d.vendor, d.product, d.version),
            "Vendor_%04x_Product_%04x".format(d.vendor, d.product),
            d.name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString(""),
            "Generic",
        )
        val dirs = listOf("/odm/usr/keylayout", "/vendor/usr/keylayout", "/system/usr/keylayout", "/data/system/devices/keylayout")
        for (n in names) for (dir in dirs) {
            val f = File("$dir/$n.kl")
            if (!f.canRead()) continue
            val keys = HashMap<Int, String>(); val axes = HashMap<Int, Axis>()
            f.readLines().forEach { raw ->
                val t = raw.substringBefore('#').trim().split(Regex("\\s+"))
                fun num(s: String) = if (s.startsWith("0x")) s.drop(2).toIntOrNull(16) else s.toIntOrNull()
                when {
                    t.size >= 3 && t[0] == "key" && t[1] != "usage" -> num(t[1])?.let { keys[it] = t[2] }
                    t.size >= 4 && t[0] == "axis" && t[2] == "invert" -> num(t[1])?.let { axes[it] = Axis(t[3], true) }
                    t.size >= 3 && t[0] == "axis" && t[2] != "split" -> num(t[1])?.let { axes[it] = Axis(t[2], false) }
                }
            }
            say("key layout: ${f.path}")
            return Layout(keys, axes)
        }
        say("no key layout file found: using the standard one")
        return Layout(
            mapOf(
                0x130 to "BUTTON_A", 0x131 to "BUTTON_B", 0x133 to "BUTTON_X", 0x134 to "BUTTON_Y",
                0x136 to "BUTTON_L1", 0x137 to "BUTTON_R1", 0x138 to "BUTTON_L2", 0x139 to "BUTTON_R2",
                0x13a to "BUTTON_SELECT", 0x13b to "BUTTON_START", 0x13c to "BUTTON_MODE",
                0x13d to "BUTTON_THUMBL", 0x13e to "BUTTON_THUMBR",
                0x220 to "DPAD_UP", 0x221 to "DPAD_DOWN", 0x222 to "DPAD_LEFT", 0x223 to "DPAD_RIGHT",
            ),
            mapOf(
                0 to Axis("X", false), 1 to Axis("Y", false), 2 to Axis("Z", false), 3 to Axis("RX", false),
                4 to Axis("RY", false), 5 to Axis("RZ", false), 9 to Axis("GAS", false), 10 to Axis("BRAKE", false),
                16 to Axis("HAT_X", false), 17 to Axis("HAT_Y", false),
            ),
        )
    }

    /** Holds the controller's input file (EVIOCGRAB) so only this program reads it. */
    private fun hold(input: FileInputStream) {
        runCatching {
            val os = Class.forName("libcore.io.Libcore").getField("os").get(null)
            val grab = 0x40044590
            val two = os.javaClass.methods.firstOrNull { it.name == "ioctlInt" && it.parameterTypes.size == 2 }
            if (two != null) two.invoke(os, input.fd, grab)
            else {
                val three = os.javaClass.methods.first { it.name == "ioctlInt" && it.parameterTypes.size == 3 }
                val ref = Class.forName("android.system.Int32Ref").getConstructor(Int::class.javaPrimitiveType).newInstance(1)
                three.invoke(os, input.fd, grab, ref)
            }
            say("holding it")
        }.onFailure { say("couldn't hold it: ${(it.cause ?: it).javaClass.simpleName} ${(it.cause ?: it).message}") }
    }
}
