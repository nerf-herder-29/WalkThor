package com.nerfherder.walkthor.controls

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent

/**
 * Controllers connected to the Thor, and a check of how it sees Switch Joy-Cons: the first step
 * towards using a pair of them as one controller. Android shows each Joy-Con as its own small
 * controller; joining them needs a controller made up by WalkThor (through the Thor's system
 * helper), and how that can be done depends on what the check finds.
 */
object JoyCons {
    private const val NINTENDO = 0x057e
    private const val JOYCON_L = 0x2006
    private const val JOYCON_R = 0x2007
    private const val JOYCON_GRIP = 0x200e
    private const val PRO = 0x2009

    /** One controller Android knows about. */
    data class Pad(
        val id: Int,
        val name: String,
        val vendor: Int,
        val product: Int,
        /** "Left Joy-Con", "Right Joy-Con", "Pro Controller", or null. */
        val nintendo: String?,
        val builtIn: Boolean,
        /** Its sticks and triggers ("X Y Z RZ HAT_X..."). */
        val axes: List<String>,
        /** Battery, 0–100, when Android knows it. */
        val battery: Int?,
        val descriptor: String,
    )

    /** Every controller connected now (the Thor's own included), Joy-Cons first. */
    fun pads(c: Context): List<Pad> {
        val im = c.getSystemService(InputManager::class.java) ?: return emptyList()
        return im.inputDeviceIds.toList().mapNotNull { id ->
            val d = im.getInputDevice(id) ?: return@mapNotNull null
            val gamepad = d.supportsSource(InputDevice.SOURCE_GAMEPAD) || d.supportsSource(InputDevice.SOURCE_JOYSTICK)
            val nintendo = if (d.vendorId == NINTENDO) when (d.productId) {
                JOYCON_L -> "Left Joy-Con"
                JOYCON_R -> "Right Joy-Con"
                JOYCON_GRIP -> "Joy-Cons in the grip"
                PRO -> "Pro Controller"
                else -> "Nintendo controller"
            } else if (d.name.contains("joy-con", ignoreCase = true) || d.name.contains("joycon", ignoreCase = true)) {
                if (d.name.contains("(L)") || d.name.contains("left", true)) "Left Joy-Con"
                else if (d.name.contains("(R)") || d.name.contains("right", true)) "Right Joy-Con" else "Joy-Con"
            } else null
            if (!gamepad && nintendo == null) return@mapNotNull null
            val axes = d.motionRanges.map { MotionEvent.axisToString(it.axis).removePrefix("AXIS_") }.distinct()
            val battery = if (Build.VERSION.SDK_INT >= 31) runCatching {
                d.batteryState.takeIf { it.isPresent }?.capacity?.takeIf { !it.isNaN() }?.let { (it * 100).toInt() }
            }.getOrNull() else null
            Pad(id, d.name, d.vendorId, d.productId, nintendo, Build.VERSION.SDK_INT >= 29 && !d.isExternal, axes, battery, d.descriptor)
        }.sortedWith(compareBy({ it.nintendo == null }, { it.builtIn }, { it.name }))
    }

    /**
     * What the Thor's system says about Joy-Cons: whether its kernel has Nintendo's controller
     * driver, the raw controller files, and whether a made-up controller could be added. Fixed
     * commands only. Not on the main thread. Null when the system helper can't be reached.
     */
    fun probe(): List<String>? {
        // One short command at a time: AYN's helper cuts off long ones.
        val steps = listOf(
            "who" to "id; getenforce",
            "virtual controller files" to "ls -l /dev/uhid /dev/uinput",
            "drivers" to "ls /sys/bus/hid/drivers",
            "modules" to "grep -i -E 'nintendo|uhid|uinput' /proc/modules",
            "raw controller files" to "for h in /sys/class/hidraw/*; do echo \$h; grep -h -E 'HID_NAME|HID_ID' \$h/device/uevent; done",
            "input devices" to "grep -i -B1 -A8 -E 'nintendo|joy-con|057e|odin' /proc/bus/input/devices",
            "input handlers" to "ls -l /dev/input",
        )
        val out = ArrayList<String>()
        var reached = false
        for ((title, cmd) in steps) {
            out += "## $title"
            val lines = com.nerfherder.walkthor.AynSystem.shellLines(cmd)
            if (lines == null) out += "(not run: ${com.nerfherder.walkthor.AynSystem.problem ?: "the helper isn't usable"})"
            else { reached = true; if (lines.isEmpty()) out += "(nothing)" else out += lines.take(80) }
        }
        return if (reached) out else null
    }

    /** The made-up controller's name, as Android shows it. */
    const val JOINED_NAME = "WalkThor Joy-Cons"

    /** Whether the joined controller is there now. */
    fun joined(): Boolean = InputDevice.getDeviceIds().any { InputDevice.getDevice(it)?.name == JOINED_NAME }

    /**
     * While the Joy-Cons are joined, WalkThor ignores the two separate Joy-Cons themselves
     * (each press would otherwise count twice: from the Joy-Con and from the joined controller).
     */
    fun ignore(e: android.view.InputEvent): Boolean {
        val name = e.device?.name ?: return false
        if (!name.startsWith("Nintendo Switch") || !name.contains("Joy-Con")) return false
        return joined()
    }

    /**
     * Joins the connected left and right Joy-Cons into one controller. Null when it started,
     * else why not. Not on the main thread.
     */
    fun join(c: Context): String? {
        val lines = com.nerfherder.walkthor.AynSystem.shellLines(
            "for h in /sys/class/hidraw/*; do echo \$h \$(grep -h HID_ID \$h/device/uevent); done",
        ) ?: return "the Thor’s system helper couldn’t be reached"
        fun find(product: String) = lines.firstOrNull { it.contains("0000057E:0000$product", ignoreCase = true) }
            ?.substringBefore(' ')?.substringAfterLast('/')?.let { "/dev/$it" }
        val left = find("2006") ?: return "the left Joy-Con isn’t connected"
        val right = find("2007") ?: return "the right Joy-Con isn’t connected"
        if (!com.nerfherder.walkthor.AynSystem.joinJoyCons(c, left, right)) return "the joining couldn’t start"
        // Wait for the joined controller to appear.
        repeat(35) {
            Thread.sleep(150)
            if (joined()) return null
        }
        return "the joined controller didn’t appear (${com.nerfherder.walkthor.AynSystem.joyConsLog()?.take(160) ?: "no word from it"})"
    }

    fun separate() = com.nerfherder.walkthor.AynSystem.separateJoyCons()

    /** Everything above as text, to read or share. */
    fun report(c: Context, probe: List<String>?): String = buildString {
        appendLine("WalkThor controller check, ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        appendLine()
        appendLine("== Controllers Android sees ==")
        val pads = pads(c)
        if (pads.isEmpty()) appendLine("(none)")
        pads.forEach { p ->
            appendLine(
                "${p.name} · ${p.nintendo ?: if (p.builtIn) "built in" else "other"} · " +
                    "vendor %04x product %04x · id ${p.id}".format(p.vendor, p.product) +
                    (p.battery?.let { " · battery $it%" } ?: ""),
            )
            appendLine("   axes: ${p.axes.joinToString(" ").ifEmpty { "none" }}")
        }
        appendLine()
        appendLine("== The Thor's system ==")
        if (probe == null) appendLine("(AYN's system helper couldn't be reached)") else probe.forEach { appendLine(it) }
    }
}
