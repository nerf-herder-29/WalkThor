package com.nerfherder.walkthor.controls

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Run as `app_process` by the Thor's system helper (as root): makes a left and a right Joy-Con
 * work as the Thor's own controller.
 *
 * It reads each Joy-Con's raw reports (/dev/hidrawN, which Nintendo's controller driver keeps in
 * full mode, so both sticks are analog) and feeds their presses into the Thor's built-in
 * controller ("Odin Controller") as if they came from it. Every emulator already set up for the
 * Thor then just works, and the Joy-Con's Home button is the Thor's Back button (so WalkThor's
 * and AYN's shortcuts work). A, B, X and Y are the Thor's A, B, X and Y (the Nintendo layout).
 *
 * If the Thor's controller can't be found, it makes a controller of its own instead,
 * "WalkThor Joy-Cons", through Linux's virtual-device file (/dev/uhid).
 *
 * Ends by itself when both Joy-Cons have gone, or when WalkThor stops it.
 * `JoyConJoinTool <left hidraw> <right hidraw>`.
 */
object JoyConJoinTool {
    // Linux input event types and codes.
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

    /** What both Joy-Cons are doing now. */
    private class Pad {
        var right = 0; var left = 0; var shared = 0
        var lx = 0f; var ly = 0f; var rx = 0f; var ry = 0f
    }
    private val pad = Pad()
    private val lock = Object()

    private interface Sink { fun send(p: Pad) }

    @JvmStatic
    fun main(args: Array<String>) {
        val left = args.getOrNull(0) ?: run { say("ERR usage"); System.exit(0); return }
        val right = args.getOrNull(1) ?: run { say("ERR usage"); System.exit(0); return }
        say("starting: left $left, right $right")
        var cleanup: (() -> Unit)? = null
        try {
            val thor = thorController()
            val sink: Sink = if (thor != null) {
                say("OK driving the Thor's controller (${thor.first})")
                thor.second
            } else {
                val (s, close) = ownController()
                cleanup = close
                say("OK made WalkThor Joy-Cons (the Thor's controller wasn't found)")
                s
            }
            val l = Thread { runCatching { read(left, true, sink) }.onFailure { say("ERR left: ${it.javaClass.simpleName} ${it.message}") }; say("left stopped") }.apply { isDaemon = true; start() }
            val r = Thread { runCatching { read(right, false, sink) }.onFailure { say("ERR right: ${it.javaClass.simpleName} ${it.message}") }; say("right stopped") }.apply { isDaemon = true; start() }
            // Until both Joy-Cons have gone (or this is stopped).
            while (l.isAlive || r.isAlive) Thread.sleep(500)
        } catch (t: Throwable) {
            say("ERR ${t.javaClass.simpleName} ${t.message}")
        } finally {
            runCatching { cleanup?.invoke() }
            System.out.flush()
            System.exit(0)
        }
    }

    private fun say(s: String) { println(s); System.out.flush() }

    // ---- the Thor's own controller ----

    /** The Thor's built-in controller's event file, ready to feed; null if it isn't there. */
    private fun thorController(): Pair<String, Sink>? {
        val node = runCatching {
            val text = File("/proc/bus/input/devices").readText()
            text.split("\n\n").firstOrNull { "Name=\"Odin Controller\"" in it }
                ?.let { Regex("Handlers=[^\\n]*?(event\\d+)").find(it)?.groupValues?.get(1) }
        }.getOrNull() ?: return null
        val path = "/dev/input/$node"
        // Each axis's range, from Android's own getevent.
        val ranges = HashMap<Int, IntArray>()
        runCatching {
            val p = ProcessBuilder("getevent", "-p", path).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            // Lines like "0000  : value 0, min -32768, max 32767, fuzz 16, flat 128, resolution 0".
            Regex("""([0-9a-fA-F]{4})\s*:\s*value -?\d+, min (-?\d+), max (-?\d+)""").findAll(text).forEach { m ->
                ranges[m.groupValues[1].toInt(16)] = intArrayOf(m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }
        }
        say("its axes: " + ranges.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}:${it.value[0]}..${it.value[1]}" })
        val out = runCatching { java.io.FileOutputStream(path) }.getOrElse { say("ERR opening $path: ${it.message}"); return null }
        val last = HashMap<Long, Int>()
        val sink = object : Sink {
            override fun send(p: Pad) {
                val events = ArrayList<IntArray>()
                fun key(code: Int, on: Boolean) {
                    val k = (EV_KEY.toLong() shl 16) or code.toLong()
                    val v = if (on) 1 else 0
                    if (last[k] != v) { last[k] = v; events += intArrayOf(EV_KEY, code, v) }
                }
                fun abs(code: Int, f: Float) {
                    val r = ranges[code] ?: return
                    val v = ((r[0] + r[1]) / 2f + f * (r[1] - r[0]) / 2f).toInt().coerceIn(r[0], r[1])
                    val k = (EV_ABS.toLong() shl 16) or code.toLong()
                    if (last[k] != v) { last[k] = v; events += intArrayOf(EV_ABS, code, v) }
                }
                // Face buttons by their letters, the Nintendo way, as the Thor itself sends them
                // (its A, on the right, is Linux's "A"; its X, on top, is Linux's "Y").
                key(BTN_SOUTH, p.right and 0x08 != 0) // A
                key(BTN_EAST, p.right and 0x04 != 0) // B
                key(BTN_WEST, p.right and 0x02 != 0) // X
                key(BTN_NORTH, p.right and 0x01 != 0) // Y
                key(BTN_TR, p.right and 0x40 != 0)
                key(BTN_TR2, p.right and 0x80 != 0)
                key(BTN_TL, p.left and 0x40 != 0)
                key(BTN_TL2, p.left and 0x80 != 0)
                // The triggers are analog on the Thor: fully in or fully out.
                abs(ABS_GAS, if (p.right and 0x80 != 0) 1f else -1f)
                abs(ABS_BRAKE, if (p.left and 0x80 != 0) 1f else -1f)
                key(BTN_SELECT, p.shared and 0x01 != 0)
                key(BTN_START, p.shared and 0x02 != 0)
                key(BTN_THUMBR, p.shared and 0x04 != 0)
                key(BTN_THUMBL, p.shared and 0x08 != 0)
                // Home is the Thor's Back button.
                key(KEY_BACK, p.shared and 0x10 != 0)
                val up = p.left and 0x02 != 0; val down = p.left and 0x01 != 0
                val rightD = p.left and 0x04 != 0; val leftD = p.left and 0x08 != 0
                abs(ABS_HAT0X, if (rightD) 1f else if (leftD) -1f else 0f)
                abs(ABS_HAT0Y, if (down) 1f else if (up) -1f else 0f)
                abs(ABS_X, p.lx); abs(ABS_Y, p.ly); abs(ABS_Z, p.rx); abs(ABS_RZ, p.ry)
                if (events.isEmpty()) return
                val b = ByteBuffer.allocate(24 * (events.size + 1)).order(ByteOrder.LITTLE_ENDIAN)
                for (e in events + listOf(intArrayOf(EV_SYN, 0, 0))) {
                    b.putLong(0); b.putLong(0); b.putShort(e[0].toShort()); b.putShort(e[1].toShort()); b.putInt(e[2])
                }
                out.write(b.array())
            }
        }
        return path to sink
    }

    // ---- a controller of WalkThor's own (when the Thor's can't be found) ----

    private const val UHID_DESTROY = 1
    private const val UHID_CREATE2 = 11
    private const val UHID_INPUT2 = 12

    /** 16 buttons (A, B, C, X, Y, Z, L1, R1, L2, R2, Select, Start, Mode, stick clicks, spare), a hat, X, Y, Z, Rz. */
    private val DESCRIPTOR = byteArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x10, 0x81.toByte(), 0x02,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14,
        0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x42,
        0x65, 0x00, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF.toByte(), 0x00,
        0x75, 0x08, 0x95.toByte(), 0x04, 0x81.toByte(), 0x02,
        0xC0.toByte(),
    )

    private fun ownController(): Pair<Sink, () -> Unit> {
        val out = java.io.RandomAccessFile("/dev/uhid", "rw")
        val c = ByteBuffer.allocate(4 + 128 + 64 + 64 + 2 + 2 + 4 + 4 + 4 + 4 + 4096).order(ByteOrder.LITTLE_ENDIAN)
        c.putInt(UHID_CREATE2)
        fun fixed(s: String, n: Int) { val bytes = s.toByteArray().take(n - 1); bytes.forEach { c.put(it) }; repeat(n - bytes.size) { c.put(0) } }
        fixed("WalkThor Joy-Cons", 128); fixed("walkthor/joycons", 64); fixed("", 64)
        c.putShort(DESCRIPTOR.size.toShort()); c.putShort(5); c.putInt(0x1209); c.putInt(0x4a43); c.putInt(1); c.putInt(0)
        c.put(DESCRIPTOR)
        out.write(c.array())
        // The kernel also sends the device's owner messages: read and dropped.
        Thread { runCatching { val buf = ByteArray(4380); while (out.read(buf) >= 0) Unit } }.apply { isDaemon = true; start() }
        val sink = object : Sink {
            override fun send(p: Pad) {
                var bits = 0
                fun on(cond: Boolean, bit: Int) { if (cond) bits = bits or (1 shl bit) }
                on(p.right and 0x08 != 0, 0); on(p.right and 0x04 != 0, 1); on(p.right and 0x02 != 0, 4); on(p.right and 0x01 != 0, 3)
                on(p.right and 0x40 != 0, 7); on(p.right and 0x80 != 0, 9); on(p.left and 0x40 != 0, 6); on(p.left and 0x80 != 0, 8)
                on(p.shared and 0x01 != 0, 10); on(p.shared and 0x02 != 0, 11); on(p.shared and 0x10 != 0, 12)
                on(p.shared and 0x08 != 0, 13); on(p.shared and 0x04 != 0, 14)
                val up = p.left and 0x02 != 0; val down = p.left and 0x01 != 0
                val rightD = p.left and 0x04 != 0; val leftD = p.left and 0x08 != 0
                val hat = when {
                    up && rightD -> 1; rightD && down -> 3; down && leftD -> 5; leftD && up -> 7
                    up -> 0; rightD -> 2; down -> 4; leftD -> 6; else -> 8
                }
                fun b(f: Float) = (128 + f * 127).toInt().coerceIn(0, 255).toByte()
                val data = byteArrayOf((bits and 0xFF).toByte(), ((bits shr 8) and 0xFF).toByte(), hat.toByte(), b(p.lx), b(p.ly), b(p.rx), b(p.ry))
                val r = ByteBuffer.allocate(4 + 2 + data.size).order(ByteOrder.LITTLE_ENDIAN)
                r.putInt(UHID_INPUT2); r.putShort(data.size.toShort()); r.put(data)
                out.write(r.array())
            }
        }
        val close = {
            runCatching { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(UHID_DESTROY).array()) }
            runCatching { out.close() }
            Unit
        }
        return sink to close
    }

    // ---- reading the Joy-Cons ----

    /** Reads one Joy-Con's full-mode reports (0x30) and updates its half of the pad. */
    private fun read(path: String, isLeft: Boolean, sink: Sink) {
        val buf = ByteArray(64)
        var cx = -1; var cy = -1
        // How far the stick goes from where it rests (measured from the first report).
        val range = 1350f
        FileInputStream(File(path)).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) return
                if (n < 12 || buf[0].toInt() and 0xFF != 0x30) continue
                val b3 = buf[3].toInt() and 0xFF
                val b4 = buf[4].toInt() and 0xFF
                val b5 = buf[5].toInt() and 0xFF
                val o = if (isLeft) 6 else 9
                val sx = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0x0F) shl 8)
                val sy = ((buf[o + 1].toInt() and 0xFF) shr 4) or ((buf[o + 2].toInt() and 0xFF) shl 4)
                if (cx < 0) { cx = sx; cy = sy; say("${if (isLeft) "left" else "right"} reading (stick rests at $sx, $sy)") }
                fun axis(v: Int, c: Int, flip: Boolean): Float {
                    var f = ((v - c) / range).coerceIn(-1f, 1f)
                    if (kotlin.math.abs(f) < 0.08f) f = 0f
                    return if (flip) -f else f
                }
                synchronized(lock) {
                    if (isLeft) {
                        pad.left = b5
                        pad.lx = axis(sx, cx, false); pad.ly = axis(sy, cy, true)
                        // Minus, the left stick click and Capture come from the left.
                        pad.shared = (pad.shared and 0b0001_0110) or (b4 and 0b0010_1001)
                    } else {
                        pad.right = b3
                        pad.rx = axis(sx, cx, false); pad.ry = axis(sy, cy, true)
                        // Plus, the right stick click and Home come from the right.
                        pad.shared = (pad.shared and 0b0010_1001) or (b4 and 0b0001_0110)
                    }
                    runCatching { sink.send(pad) }
                }
            }
        }
    }
}
