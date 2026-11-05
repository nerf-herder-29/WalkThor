package com.nerfherder.walkthor

import android.content.Context
import android.provider.Settings
import java.util.concurrent.Executors

/**
 * The Thor's performance mode, fan mode and controller layout: the same settings AYN's own drawer
 * changes, read from Android and set through the Thor's system helper ([AynSystem]). Since
 * WalkThor's drawer takes the AYN button's place, they're here too.
 */
object DeviceModes {
    enum class Performance(val value: Int, val label: String) { STANDARD(0, "Standard"), MEDIUM(1, "Medium"), HIGH(2, "High") }
    enum class Fan(val value: Int, val label: String) { OFF(0, "Off"), QUIET(1, "Quiet"), SMART(4, "Smart"), SPORTS(5, "Sports") }

    /** Which way round the face buttons are: Nintendo style (A on the right) or Xbox style (A at the bottom). */
    enum class Layout(val label: String) { NINTENDO("A on the right"), XBOX("A at the bottom") }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor device modes").apply { isDaemon = true } }

    private fun int(c: Context, name: String, def: Int) =
        runCatching { Settings.System.getInt(c.contentResolver, name, def) }.getOrDefault(def)

    fun performance(c: Context): Performance = int(c, "performance_mode", 0).let { v -> Performance.entries.firstOrNull { it.value == v } ?: Performance.STANDARD }
    /** The fan mode, or null for AYN's custom curve (set in AYN's settings). */
    fun fan(c: Context): Fan? = int(c, "fan_mode", 4).let { v -> Fan.entries.firstOrNull { it.value == v } }
    fun layout(c: Context): Layout = if (int(c, "temp_abxy_layout_mode", 1) == 0) Layout.XBOX else Layout.NINTENDO

    /** For screenshots: show the controls as if the helper were there. */
    @Volatile var demo = false

    /** Whether these can be changed here: the Thor's system helper is reachable (checked before, or now off the main thread). */
    fun canChange(): Boolean = demo || AynSystem.knownAvailable() == true

    /** Checks the helper (not on the main thread). */
    fun check(): Boolean = demo || AynSystem.available()

    fun setPerformance(m: Performance, done: (Boolean) -> Unit = {}) = worker.execute { done(AynSystem.setPerformance(m.value)) }

    fun setFan(f: Fan, done: (Boolean) -> Unit = {}) = worker.execute { done(AynSystem.putSetting("fan_mode", f.value.toString())) }

    /** As AYN's own switch does: Xbox style is mode 0 with the layout flipped; Nintendo style is mode 1. */
    fun setLayout(l: Layout, done: (Boolean) -> Unit = {}) = worker.execute {
        val xbox = l == Layout.XBOX
        val ok = AynSystem.putSetting("temp_abxy_layout_mode", if (xbox) "0" else "1") &&
            AynSystem.putSetting("flip_button_layout", if (xbox) "1" else "0") &&
            AynSystem.putSetting("no_create_gamepad_button_layout", "0")
        done(ok)
    }
}
