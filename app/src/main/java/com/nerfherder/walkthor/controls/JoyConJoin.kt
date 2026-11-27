package com.nerfherder.walkthor.controls

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Run as `app_process` by the Thor's system helper (as root): joins a left and a right Joy-Con
 * into one controller, "WalkThor Joy-Cons".
 *
 * It reads each Joy-Con's raw reports (/dev/hidrawN, which Nintendo's controller driver keeps in
 * full mode, so both sticks are analog) and presents one gamepad through Linux's virtual-device
 * file (/dev/uhid). Android sees that as a real controller: A/B/X/Y, L/R, ZL/ZR, Minus/Plus,
 * Home, both stick clicks, a D-pad and two sticks. It ends by itself when either Joy-Con goes,
 * or when WalkThor stops it.
 *
 * `JoyConJoinTool <left hidraw> <right hidraw>`.
 */
object JoyConJoinTool {
    private const val UHID_DESTROY = 1
    private const val UHID_CREATE2 = 11
    private const val UHID_INPUT2 = 12
    private const val BUS_BLUETOOTH = 5

    /** pid.codes' test vendor and product: not Nintendo's, so Android treats it as a plain gamepad. */
    private const val VENDOR = 0x1209
    private const val PRODUCT = 0x4a43

    /**
     * A gamepad: 16 buttons (in Linux's gamepad order: A, B, C, X, Y, Z, L1, R1, L2, R2, Select,
     * Start, Mode, left stick, right stick, spare), a hat for the D-pad, then X, Y, Z and Rz.
     */
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

    // Buttons, as bits of the report's first two bytes.
    private const val B_A = 0; private const val B_B = 1; private const val B_X = 3; private const val B_Y = 4
    private const val B_L = 6; private const val B_R = 7; private const val B_ZL = 8; private const val B_ZR = 9
    private const val B_MINUS = 10; private const val B_PLUS = 11; private const val B_HOME = 12
    private const val B_LSTICK = 13; private const val B_RSTICK = 14

    @Volatile private var leftButtons = 0
    @Volatile private var rightButtons = 0
    @Volatile private var shared = 0
    @Volatile private var lx = 128; @Volatile private var ly = 128
    @Volatile private var rx = 128; @Volatile private var ry = 128
    private val lock = Object()

    @JvmStatic
    fun main(args: Array<String>) {
        val left = args.getOrNull(0) ?: run { println("ERR usage"); System.exit(0); return }
        val right = args.getOrNull(1) ?: run { println("ERR usage"); System.exit(0); return }
        say("starting: left $left, right $right")
        // Read and write: the kernel also sends the device's owner messages, which are read and dropped.
        val out = try { java.io.RandomAccessFile("/dev/uhid", "rw") } catch (t: Throwable) { say("ERR opening /dev/uhid: ${t.message}"); System.exit(0); return }
        try {
            out.write(create())
            say("controller made")
            Thread { drain(out) }.apply { isDaemon = true; start() }
            val l = Thread { runCatching { read(left, isLeft = true, out) }.onFailure { say("ERR left: ${it.javaClass.simpleName} ${it.message}") }; say("left stopped") }.apply { isDaemon = true; start() }
            val r = Thread { runCatching { read(right, isLeft = false, out) }.onFailure { say("ERR right: ${it.javaClass.simpleName} ${it.message}") }; say("right stopped") }.apply { isDaemon = true; start() }
            say("OK")
            // Until both Joy-Cons have gone (or this is stopped).
            while (l.isAlive || r.isAlive) Thread.sleep(500)
        } catch (t: Throwable) {
            say("ERR ${t.javaClass.simpleName} ${t.message}")
        } finally {
            runCatching { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(UHID_DESTROY).array()) }
            runCatching { out.close() }
            System.out.flush()
            System.exit(0)
        }
    }

    private fun say(s: String) { println(s); System.out.flush() }

    /** Nothing to do with what the kernel sends back (start, open, close): keeps its queue empty. */
    private fun drain(out: java.io.RandomAccessFile) {
        runCatching {
            val buf = ByteArray(4380)
            while (true) if (out.read(buf) < 0) break
        }
    }

    private fun create(): ByteArray {
        val b = ByteBuffer.allocate(4 + 128 + 64 + 64 + 2 + 2 + 4 + 4 + 4 + 4 + 4096).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(UHID_CREATE2)
        fun fixed(s: String, n: Int) { val bytes = s.toByteArray(); b.put(bytes, 0, minOf(bytes.size, n - 1)); repeat(n - minOf(bytes.size, n - 1)) { b.put(0) } }
        fixed("WalkThor Joy-Cons", 128)
        fixed("walkthor/joycons", 64)
        fixed("", 64)
        b.putShort(DESCRIPTOR.size.toShort())
        b.putShort(BUS_BLUETOOTH.toShort())
        b.putInt(VENDOR)
        b.putInt(PRODUCT)
        b.putInt(1)
        b.putInt(0)
        b.put(DESCRIPTOR)
        return b.array()
    }

    /** Reads one Joy-Con's full-mode reports (0x30) and updates its half of the controller. */
    private fun read(path: String, isLeft: Boolean, out: java.io.RandomAccessFile) {
        val buf = ByteArray(64)
        var cx = -1; var cy = -1
        // Where the stick sits at rest (taken from the first reports) and how far it goes.
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
                fun axis(v: Int, c: Int, flip: Boolean): Int {
                    var f = (v - c) / range
                    if (kotlin.math.abs(f) < 0.08f) f = 0f
                    if (flip) f = -f
                    return (128 + f * 127).toInt().coerceIn(0, 255)
                }
                synchronized(lock) {
                    if (isLeft) {
                        leftButtons = b5
                        lx = axis(sx, cx, false); ly = axis(sy, cy, true)
                    } else {
                        rightButtons = b3
                        rx = axis(sx, cx, false); ry = axis(sy, cy, true)
                    }
                    // Minus and the left stick click come from the left; Plus, Home and the right stick click from the right.
                    shared = if (isLeft) (shared and 0b0001_0110) or (b4 and 0b0010_1001)
                    else (shared and 0b0010_1001) or (b4 and 0b0001_0110)
                    runCatching { out.write(report()) }
                }
            }
        }
    }

    private fun report(): ByteArray {
        var bits = 0
        fun on(cond: Boolean, bit: Int) { if (cond) bits = bits or (1 shl bit) }
        // Right Joy-Con: Y, X, B, A, SR, SL, R, ZR.
        on(rightButtons and 0x08 != 0, B_A)
        on(rightButtons and 0x04 != 0, B_B)
        on(rightButtons and 0x02 != 0, B_X)
        on(rightButtons and 0x01 != 0, B_Y)
        on(rightButtons and 0x40 != 0, B_R)
        on(rightButtons and 0x80 != 0, B_ZR)
        // Left Joy-Con: Down, Up, Right, Left, SR, SL, L, ZL.
        on(leftButtons and 0x40 != 0, B_L)
        on(leftButtons and 0x80 != 0, B_ZL)
        // Shared: Minus, Plus, right stick, left stick, Home, Capture.
        on(shared and 0x01 != 0, B_MINUS)
        on(shared and 0x02 != 0, B_PLUS)
        on(shared and 0x04 != 0, B_RSTICK)
        on(shared and 0x08 != 0, B_LSTICK)
        on(shared and 0x10 != 0, B_HOME)
        val up = leftButtons and 0x02 != 0
        val down = leftButtons and 0x01 != 0
        val rightD = leftButtons and 0x04 != 0
        val leftD = leftButtons and 0x08 != 0
        // The hat: 0 up, then clockwise to 7; 8 for none.
        val hat = when {
            up && rightD -> 1; rightD && down -> 3; down && leftD -> 5; leftD && up -> 7
            up -> 0; rightD -> 2; down -> 4; leftD -> 6; else -> 8
        }
        val data = byteArrayOf(
            (bits and 0xFF).toByte(), ((bits shr 8) and 0xFF).toByte(), hat.toByte(),
            lx.toByte(), ly.toByte(), rx.toByte(), ry.toByte(),
        )
        val b = ByteBuffer.allocate(4 + 2 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(UHID_INPUT2)
        b.putShort(data.size.toShort())
        b.put(data)
        return b.array()
    }
}
