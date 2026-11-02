package com.nerfherder.walkthor

import android.content.Context
import android.provider.Settings
import java.io.File
import java.util.concurrent.Executors

/**
 * The lights around the Thor's sticks: their colour and brightness, from the Light & Sound tab.
 *
 * On the stock Thor each ring is driven through two system files that any app may write:
 * `enable` ("1" or "0") and `brightness` ("1-R:G:B", the ring's colour). There's no separate
 * brightness, so the colour is scaled down. AYN keeps its own colour and brightness settings and
 * puts them back (say, when the screen wakes), so WalkThor also updates those through the Thor's
 * system helper where it can, and puts your choice back when the screen wakes.
 */
object StickLights {
    private val SIDES = listOf("/sys/class/sn3112l/led", "/sys/class/sn3112r/led")
    private const val PREFS = "stick_lights"

    /** For store screenshots only. */
    internal var demo = false

    /** The colours offered: AYN's own teal first, then the rest; 0 = off. */
    val COLORS = listOf(
        0xFF2BE0D8.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFD60A.toInt(),
        0xFF34C759.toInt(), 0xFF0A84FF.toInt(), 0xFFAF52DE.toInt(), 0xFFFF2D92.toInt(), 0,
    )

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor stick lights").apply { isDaemon = true } }

    /** Whether this device has the stick lights WalkThor can drive. */
    fun available(): Boolean = demo || SIDES.all { File("$it/brightness").let { f -> f.exists() && f.canWrite() } }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether you've set them from WalkThor (only then does WalkThor put them back after a wake). */
    fun chosen(c: Context) = prefs(c).contains("color")

    /** The colour (0 = off): yours if you've chosen one, else AYN's. */
    fun color(c: Context): Int {
        if (chosen(c)) return prefs(c).getInt("color", COLORS[0])
        val enabled = (Settings.System.getString(c.contentResolver, "joystick_light_enabled") ?: "1,1").split(",").firstOrNull()?.trim() != "0"
        if (!enabled) return 0
        val ayn = Settings.System.getString(c.contentResolver, "joystick_led_light_picker_color")?.split(",")?.firstOrNull()?.trim()
        return runCatching { android.graphics.Color.parseColor(ayn) }.getOrDefault(COLORS[0])
    }

    /** The brightness, 0–1: yours if you've chosen one, else AYN's. */
    fun level(c: Context): Float {
        if (prefs(c).contains("level")) return prefs(c).getFloat("level", 0.5f)
        val raw = Settings.System.getString(c.contentResolver, "led_light_brightness_percent")?.toFloatOrNull() ?: return 0.5f
        return (if (raw > 1f) raw / 100f else raw).coerceIn(0f, 1f)
    }

    /** They follow the screens' brightness (moving with the brightness sliders). */
    fun followsScreens(c: Context) = prefs(c).getBoolean("follow", false)
    fun setFollowsScreens(c: Context, on: Boolean) = prefs(c).edit().putBoolean("follow", on).apply()

    fun setColor(c: Context, color: Int) {
        prefs(c).edit().putInt("color", color).apply()
        apply(c, syncAyn = true)
    }

    fun setLevel(c: Context, level: Float) {
        prefs(c).edit().putFloat("level", level.coerceIn(0f, 1f)).apply()
        if (!prefs(c).contains("color")) prefs(c).edit().putInt("color", color(c)).apply()
        apply(c, syncAyn = true)
    }

    /** A brightness slider moved: the lights follow, if you've asked them to. */
    fun screensChanged(c: Context, level: Float) {
        if (followsScreens(c)) setLevel(c, level)
    }

    /** The screen woke (AYN puts its own lights back then): put yours back, if you chose them in WalkThor. */
    fun restore(c: Context) {
        if (chosen(c)) apply(c, syncAyn = false)
    }

    private var syncPending = false

    /** Writes the rings now; AYN's own settings follow a moment later (once you've stopped dragging). */
    private fun apply(c: Context, syncAyn: Boolean) {
        val app = c.applicationContext
        val color = prefs(app).getInt("color", COLORS[0])
        val level = prefs(app).getFloat("level", level(app))
        worker.execute {
            val on = color != 0
            for (side in SIDES) {
                runCatching { File("$side/enable").writeText(if (on) "1" else "0") }
                if (on) {
                    val r = (android.graphics.Color.red(color) * level).toInt()
                    val g = (android.graphics.Color.green(color) * level).toInt()
                    val b = (android.graphics.Color.blue(color) * level).toInt()
                    runCatching { File("$side/brightness").writeText("1-$r:$g:$b") }
                }
            }
        }
        if (syncAyn && !syncPending) {
            syncPending = true
            worker.execute {
                Thread.sleep(600)
                syncPending = false
                syncAynSettings(app)
            }
        }
    }

    /** AYN's own settings to match (so AYN's drawer shows the same, and AYN keeps it). Worker thread. */
    private fun syncAynSettings(c: Context) {
        val color = prefs(c).getInt("color", COLORS[0])
        val level = prefs(c).getFloat("level", 0.5f)
        val on = color != 0
        val hex = String.format("#%06X", color and 0xFFFFFF)
        val raw = Settings.System.getString(c.contentResolver, "led_light_brightness_percent")?.toFloatOrNull()
        // AYN's scale: 0–1, or 0–100 if that's what it holds now.
        val levelText = if (raw != null && raw > 1f) (level * 100).toInt().toString() else "%.2f".format(java.util.Locale.US, level)
        AynSystem.putSetting("joystick_light_enabled", if (on) "1,1" else "0,0")
        if (on) AynSystem.putSetting("joystick_led_light_picker_color", "$hex,$hex")
        AynSystem.putSetting("led_light_brightness_percent", levelText)
    }
}
