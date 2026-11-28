package com.nerfherder.walkthor.controls

import android.content.Context
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Any other controller (Bluetooth or USB) as the Thor's own, like the Joy-Cons: joined, its
 * presses come from the Thor's controller, so every emulator set up for the Thor just works.
 * One at a time. The work is done by [PadJoinTool], through the Thor's system helper.
 */
object PadJoin {
    /** The joined controller's name, or null. */
    val active = MutableStateFlow<String?>(null)
    /** Why the last join didn't work, for the Controllers tab. */
    val problem = MutableStateFlow<String?>(null)

    private fun prefs(c: Context) = c.getSharedPreferences("controllers", Context.MODE_PRIVATE)
    fun autoJoin(c: Context) = prefs(c).getBoolean("padAutoJoin", true)
    fun setAutoJoin(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("padAutoJoin", on).apply()
        if (on) maybeJoin(c)
    }
    /** Face buttons by where they sit, rather than by their letters. */
    fun byPosition(c: Context) = prefs(c).getBoolean("padByPosition", false)
    fun setByPosition(c: Context, on: Boolean) { prefs(c).edit().putBoolean("padByPosition", on).apply() }

    /** A controller that can join: connected from outside, a gamepad, and not a single Joy-Con. */
    class Candidate(val name: String, val vendor: Int, val product: Int)

    fun candidates(): List<Candidate> = InputDevice.getDeviceIds().toList().mapNotNull { id ->
        val d = InputDevice.getDevice(id) ?: return@mapNotNull null
        if (!d.isExternal || d.isVirtual) return@mapNotNull null
        if (!d.supportsSource(InputDevice.SOURCE_GAMEPAD) && !d.supportsSource(InputDevice.SOURCE_JOYSTICK)) return@mapNotNull null
        val n = d.name
        if (n == "Odin Controller" || n == JoyCons.JOINED_NAME) return@mapNotNull null
        // Single Joy-Cons join in pairs, separately.
        if (d.vendorId == 0x057e && (d.productId == 0x2006 || d.productId == 0x2007)) return@mapNotNull null
        if (n.startsWith("Nintendo Switch") && n.contains("Joy-Con") && !n.contains("Grip", true)) return@mapNotNull null
        if (d.vendorId == 0 && d.productId == 0) return@mapNotNull null
        Candidate(n, d.vendorId, d.productId)
    }.distinctBy { it.vendor to it.product }

    /** Joins [pad] (or the first controller that can join). Null when it worked, else why not. Not on the main thread. */
    fun join(c: Context, pad: Candidate? = null): String? {
        val p = pad ?: candidates().firstOrNull() ?: return fail("no other controller is connected")
        if (!com.nerfherder.walkthor.AynSystem.joinPad(c, "%04x".format(p.vendor), "%04x".format(p.product), byPosition(c))) {
            return fail("the joining couldn’t start")
        }
        var log: String? = null
        repeat(12) {
            Thread.sleep(400)
            log = com.nerfherder.walkthor.AynSystem.padLog()
            if (log?.contains("OK driving") == true) {
                active.value = p.name
                prefs(c).edit().putString("padJoined", p.name).apply()
                problem.value = null
                com.nerfherder.walkthor.MemoryWatch.noteFrom(c, "Controller joined: " + log!!.take(300))
                return null
            }
            if (log?.contains("ERR") == true) return fail(log!!.take(300))
        }
        return fail("no word that it’s working (${log?.take(200) ?: "nothing"})")
    }

    private fun fail(why: String): String { problem.value = why; return why }

    fun separate(c: Context) {
        com.nerfherder.walkthor.AynSystem.separatePad()
        active.value = null
        // Separated by hand: don't join it again by itself until it reconnects.
        skipped = candidates().firstOrNull()?.let { it.vendor to it.product }
    }

    @Volatile private var skipped: Pair<Int, Int>? = null

    /** Asks the system whether a controller is joined (WalkThor may have restarted since). Not on the main thread. */
    fun refresh(c: Context) {
        active.value = if (com.nerfherder.walkthor.AynSystem.padRunning()) prefs(c).getString("padJoined", null) ?: "A controller" else null
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor controller").apply { isDaemon = true } }

    /** A controller connected: join it if that's on and none is joined yet. */
    fun maybeJoin(c: Context) {
        if (!autoJoin(c)) return
        val first = candidates().firstOrNull() ?: return
        if (skipped == (first.vendor to first.product)) return
        worker.execute {
            refresh(c)
            if (active.value == null) candidates().firstOrNull()?.let { join(c, it) }
        }
    }

    /** A controller disconnected: forget it if it was the joined one (the program ends by itself). */
    fun removed() {
        val left = candidates()
        if (left.isEmpty()) { active.value = null; skipped = null }
        else if (skipped != null && left.none { (it.vendor to it.product) == skipped }) skipped = null
    }
}
