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
 * brightness, so the colour is scaled down. AYN keeps its own stick-light settings and puts them
 * back when the screen wakes, so WalkThor puts your choice back straight after. (It leaves AYN's
 * settings alone: when they changed, AYN redrew the lights on its own brightness scale a moment
 * later, so they jumped and lagged.)
 */
object StickLights {
    private val SIDES = listOf("/sys/class/sn3112l/led", "/sys/class/sn3112r/led")
    private const val PREFS = "stick_lights"

    /** For store screenshots only. */
    internal var demo = false

    /**
     * The colours offered, as the lights should show them: AYN's own teal first, then the rest;
     * 0 = off. The lights glow brightly even at low levels, so these are pure colours (a screen
     * "red" with a little green and blue in it shows as pink on the lights).
     */
    val COLORS = listOf(
        0xFF2BE0D8.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFFFF3000.toInt(), 0xFFFF9000.toInt(),
        0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF7000FF.toInt(), 0xFFFF0060.toInt(), 0,
    )

    /** How each colour is shown on screen, to look like the lights. */
    val SWATCHES = listOf(
        0xFF2BE0D8.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF2A1F.toInt(), 0xFFFF6A1A.toInt(), 0xFFFFC21A.toInt(),
        0xFF2EE05A.toInt(), 0xFF2A5BFF.toInt(), 0xFF9A3BFF.toInt(), 0xFFFF2D86.toInt(), 0,
    )

    /** How [color] is shown on screen. */
    fun swatch(color: Int): Int = COLORS.indexOf(color).let { if (it >= 0) SWATCHES[it] else color }

    /** The lights themselves: written straight away. */
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
        enabledWritten = null
        if (chosen(c)) apply(c, syncAyn = false)
    }

    /** Writes the rings now. */
    private fun apply(c: Context, syncAyn: Boolean) {
        val app = c.applicationContext
        val color = prefs(app).getInt("color", COLORS[0])
        val level = prefs(app).getFloat("level", level(app))
        // The newest colour and level; a write is queued only if none is waiting already, and it
        // takes whatever is newest when it runs (so nothing is ever dropped, and none pile up).
        if (pending.getAndSet(color to level) == null) {
            worker.execute { pending.getAndSet(null)?.let { (c, l) -> writeRings(c, l) } }
        }

    }

    private val pending = java.util.concurrent.atomic.AtomicReference<Pair<Int, Float>?>(null)

    /**
     * Writes both rings. The lights look brighter than the number suggests at low levels, so the
     * level is eased (squared, near enough) to make the slider feel even. Worker thread.
     */
    private fun writeRings(color: Int, level: Float) {
        val on = color != 0 && level > 0.005f
        val drive = drive(color, level)
        var failed: Throwable? = null
        for (side in SIDES) {
            if (enabledWritten != on) runCatching { File("$side/enable").writeText(if (on) "1" else "0") }.onFailure { failed = it }
            if (on) runCatching { File("$side/brightness").writeText(drive) }.onFailure { failed = it }
        }
        enabledWritten = if (failed == null) on else null
        failed?.let { note = "couldn't write the lights (${it.javaClass.simpleName}) at ${now()}" }
    }

    /** What the lights are sent for [color] at [level]: "1-R:G:B", eased so the slider feels even. */
    private fun drive(color: Int, level: Float): String {
        val k = Math.pow(level.coerceIn(0f, 1f).toDouble(), 1.8).toFloat()
        val r = Math.round(android.graphics.Color.red(color) * k)
        val g = Math.round(android.graphics.Color.green(color) * k)
        val b = Math.round(android.graphics.Color.blue(color) * k)
        return "1-$r:$g:$b"
    }

    /** What happened last when WalkThor checked the lights, for Diagnostics. */
    @Volatile var note: String = "not checked yet"
    private fun now() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    /**
     * Checks the lights (after a wake, and now and then while the screen's on) and puts back only
     * what's been changed, side by side: rewriting a light that's already right (even just
     * switching it on again) makes it blink. With [evenIfUnknown], writes when they can't be read.
     */
    fun check(c: Context, evenIfUnknown: Boolean) {
        val app = c.applicationContext
        if (!chosen(app) || !available()) return
        val color = prefs(app).getInt("color", COLORS[0])
        val level = prefs(app).getFloat("level", level(app))
        worker.execute {
            if (pending.get() != null) return@execute // a change of yours is on its way anyway
            val on = color != 0 && level > 0.005f
            val want = drive(color, level)
            var fixed = 0
            var unread = 0
            var failed: Throwable? = null
            val seen = StringBuilder()
            for (side in SIDES) {
                val en = runCatching { File("$side/enable").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                val br = runCatching { File("$side/brightness").readText().trim() }.getOrNull()?.takeIf { it.contains(':') }
                seen.append(if (seen.isEmpty()) "" else ", ").append(side.substringAfterLast('/').removePrefix("sn3112")).append(' ').append(en ?: "?").append('/').append(br ?: "?")
                val enOk = en?.let { on == !it.startsWith("0") }
                val brOk = if (!on) true else br?.let { it.substringAfter('-') == want.substringAfter('-') }
                if (enOk == null || brOk == null) unread++
                val writeEn = enOk == false || (enOk == null && evenIfUnknown)
                val writeBr = on && (brOk == false || (brOk == null && evenIfUnknown))
                if (writeEn) runCatching { File("$side/enable").writeText(if (on) "1" else "0") }.onFailure { failed = it }
                if (writeBr) runCatching { File("$side/brightness").writeText(want) }.onFailure { failed = it }
                if (writeEn || writeBr) fixed++
            }
            if (failed == null) enabledWritten = on
            note = when {
                failed != null -> "couldn't write the lights (${failed!!.javaClass.simpleName}) at ${now()}"
                fixed > 0 -> "put back at ${now()} (read $seen)"
                note.startsWith("put back") || note.startsWith("couldn't") -> note
                unread > 0 -> "couldn't read them (${now()})"
                else -> "as you set them (${now()})"
            }
        }
    }
    @Volatile private var enabledWritten: Boolean? = null

}
