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

    /** WalkThor's own controller's name, if the Thor's couldn't be found to feed. */
    const val JOINED_NAME = "WalkThor Joy-Cons"

    /** Whether the Joy-Cons are joined (feeding the Thor's controller) right now, as far as WalkThor knows. */
    val active = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Why the last join didn't work, for the Controllers tab. */
    val problem = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun joined(): Boolean = active.value

    private fun isJoyCon(name: String) = name.startsWith("Nintendo Switch") && name.contains("Joy-Con")

    /** Both a left and a right Joy-Con are connected. */
    fun pairConnected(): Boolean {
        val names = InputDevice.getDeviceIds().toList().mapNotNull { id -> InputDevice.getDevice(id)?.name }
        return names.any { isJoyCon(it) && "Left" in it } && names.any { isJoyCon(it) && "Right" in it }
    }

    /**
     * While the Joy-Cons are joined, WalkThor ignores the two separate Joy-Cons themselves
     * (each press would otherwise count twice: from the Joy-Con and from the Thor's controller).
     */
    fun ignore(e: android.view.InputEvent): Boolean {
        if (!active.value) return false
        val name = e.device?.name ?: return false
        return isJoyCon(name)
    }

    /**
     * Joins the connected left and right Joy-Cons: from then on they work as the Thor's own
     * controller. Null when it worked, else why not. Not on the main thread.
     */
    fun join(c: Context): String? {
        val lines = com.nerfherder.walkthor.AynSystem.shellLines(
            "for h in /sys/class/hidraw/*; do echo \$h \$(grep -h HID_ID \$h/device/uevent); done",
        ) ?: return fail("the Thor’s system helper couldn’t be reached")
        fun find(product: String) = lines.firstOrNull { it.contains("0000057E:0000$product", ignoreCase = true) }
            ?.substringBefore(' ')?.substringAfterLast('/')?.let { "/dev/$it" }
        val left = find("2006") ?: return fail("the left Joy-Con isn’t connected")
        val right = find("2007") ?: return fail("the right Joy-Con isn’t connected")
        if (!com.nerfherder.walkthor.AynSystem.joinJoyCons(c, left, right, stickRange(c))) return fail("the joining couldn’t start")
        // Wait for it to say it's working.
        var log: String? = null
        repeat(12) {
            Thread.sleep(400)
            log = com.nerfherder.walkthor.AynSystem.joyConsLog()
            if (log?.contains("OK driving") == true || log?.contains("OK made") == true) {
                active.value = true
                problem.value = null
                com.nerfherder.walkthor.MemoryWatch.noteFrom(c, "Joy-Cons joined: " + log!!.take(300))
                return null
            }
            if (log?.contains("ERR") == true) return fail(log!!.take(300))
        }
        return fail("no word that it’s working (${log?.take(200) ?: "nothing"})")
    }

    private fun fail(why: String): String { problem.value = why; return why }

    fun separate() {
        com.nerfherder.walkthor.AynSystem.separateJoyCons()
        active.value = false
    }

    // ---- joining by itself ----

    private fun prefs(c: Context) = c.getSharedPreferences("controllers", Context.MODE_PRIVATE)
    fun autoJoin(c: Context) = prefs(c).getBoolean("autoJoin", true)

    /** Stick sensitivity: 0 gentle, 1 normal, 2 quick. */
    fun sensitivity(c: Context) = prefs(c).getInt("sensitivity", 0)
    fun setSensitivity(c: Context, v: Int) { prefs(c).edit().putInt("sensitivity", v).apply() }
    /** How far a Joy-Con stick moves to count as all the way, for [sensitivity]. */
    private fun stickRange(c: Context) = when (sensitivity(c)) { 2 -> 1400; 1 -> 1700; else -> 2000 }
    fun setAutoJoin(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("autoJoin", on).apply()
        if (on) maybeJoin(c)
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor Joy-Cons").apply { isDaemon = true } }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var watching = false

    /**
     * Asks the system whether the Joy-Cons are joined (the joining program keeps running even
     * if Android closed and restarted WalkThor, which forgets). Not on the main thread.
     */
    fun refresh() { active.value = com.nerfherder.walkthor.AynSystem.joyConsRunning() }

    /** Joins the Joy-Cons by itself whenever both connect (if that's on), and notices them going. */
    fun watch(c: Context) {
        if (watching) return
        watching = true
        val app = c.applicationContext
        worker.execute { refresh() }
        val im = app.getSystemService(InputManager::class.java) ?: return
        im.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) { main.removeCallbacksAndMessages(this@JoyCons); main.postDelayed({ maybeJoin(app) }, 1500) }
            override fun onInputDeviceRemoved(id: Int) {
                // Both gone: the joining program ends by itself.
                if (InputDevice.getDeviceIds().none { InputDevice.getDevice(it)?.name?.let(::isJoyCon) == true }) active.value = false
            }
            override fun onInputDeviceChanged(id: Int) {}
        }, main)
        main.postDelayed({ maybeJoin(app) }, 3000)
    }

    private fun maybeJoin(c: Context) {
        if (!autoJoin(c) || !pairConnected()) return
        worker.execute {
            refresh()
            if (!active.value && pairConnected()) join(c)
        }
    }

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
