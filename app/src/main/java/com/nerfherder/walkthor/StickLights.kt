package com.nerfherder.walkthor

import android.content.Context
import android.provider.Settings
import java.io.File
import java.util.concurrent.Executors

/**
 * The lights around the Thor's sticks: their colour and brightness, from the Light & Sound tab.
 *
 * On the stock Thor each ring is driven through two system files that any app may write:
 * `enable` ("1" or "0") and `brightness` ("Z-R:G:B:255", where Z is the half of the ring: 1 or 2).
 * There's no separate brightness, so the colour is scaled down.
 *
 * AYN keeps its own stick-light settings and draws them whenever the screen wakes (and whenever
 * they change). So once you've stopped changing yours, WalkThor sets AYN's to match, through the
 * Thor's system helper: on/off, the colour, and the brightness already eased the way WalkThor eases
 * it, so AYN draws exactly what you see and nothing jumps. If AYN's say "off", AYN switches the
 * lights off after every wake.
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
    /** AYN's own settings: matched once you've stopped changing yours (its own thread, so it never holds up the lights). */
    private val syncer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "WalkThor stick lights sync").apply { isDaemon = true } }
    @Volatile private var syncLater: java.util.concurrent.ScheduledFuture<*>? = null

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
        if (syncAyn) matchAynLater(app, 1_500)
    }

    /** Sets AYN's settings to match yours after [delayMs] (restarting the wait if called again). */
    fun matchAynLater(c: Context, delayMs: Long) {
        val app = c.applicationContext
        if (!chosen(app) || demo) return
        syncLater?.cancel(false)
        syncLater = syncer.schedule({ runCatching { matchAyn(app) } }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * AYN's own stick-light settings, set to what you chose in WalkThor (only those that differ), so
     * AYN draws your lights after a wake instead of its own (or none). AYN redraws the lights when
     * they change, so yours are written again straight after. Sync thread.
     */
    private fun matchAyn(c: Context) {
        val cr = c.contentResolver
        val color = prefs(c).getInt("color", COLORS[0])
        val level = prefs(c).getFloat("level", 0.5f)
        val on = color != 0 && level > 0.005f
        val enabled = if (on) "1,1" else "0,0"
        val hex = String.format("#FF%06X", color and 0xFFFFFF)
        val k = eased(level)
        val raw = Settings.System.getString(cr, "led_light_brightness_percent")
        val hundred = (raw?.toFloatOrNull() ?: 0f) > 1f
        val levelText = if (hundred) Math.round(k * 100).toString() else "%.3f".format(java.util.Locale.US, k)
        val changes = buildList {
            if (Settings.System.getString(cr, "joystick_light_enabled")?.replace(" ", "") != enabled) add("joystick_light_enabled" to enabled)
            if (on) {
                val now = Settings.System.getString(cr, "joystick_led_light_picker_color")?.split(",")?.map { s -> runCatching { android.graphics.Color.parseColor(s.trim()) }.getOrNull() }
                if (now == null || now.size != 2 || now.any { it == null || (it and 0xFFFFFF) != (color and 0xFFFFFF) }) add("joystick_led_light_picker_color" to "$hex,$hex")
                val cur = raw?.toFloatOrNull()?.let { if (hundred) it / 100f else it }
                if (cur == null || Math.abs(cur - k) > 0.006f) add("led_light_brightness_percent" to levelText)
            }
        }
        if (changes.isEmpty()) { aynNote = "AYN's settings match (${now()})"; log("AYN settings already match"); return }
        var ok = true
        for ((name, value) in changes) {
            val done = AynSystem.putSetting(name, value)
            if (!done) ok = false
            log("set AYN $name=$value" + if (done) "" else " FAILED")
        }
        aynNote = if (ok) "AYN's settings set to match at ${now()}" else "couldn't set AYN's settings (system helper unavailable) at ${now()}"
        // AYN redraws on its own a moment after its settings change: make sure yours are what shows.
        for (wait in longArrayOf(250, 900, 2_000)) {
            Thread.sleep(wait)
            worker.execute { if (pending.get() == null) writeRings(prefs(c).getInt("color", color), prefs(c).getFloat("level", level)) }
        }
    }
    @Volatile var aynNote: String = ""


    private val pending = java.util.concurrent.atomic.AtomicReference<Pair<Int, Float>?>(null)

    /**
     * Writes both rings. The lights look brighter than the number suggests at low levels, so the
     * level is eased (squared, near enough) to make the slider feel even. Worker thread.
     */
    private fun writeRings(color: Int, level: Float) {
        val on = color != 0 && level > 0.005f
        var failed: Throwable? = null
        for (side in SIDES) {
            if (enabledWritten != on) runCatching { File("$side/enable").writeText(if (on) "1" else "0") }
                .onFailure { failed = it; log("${tag(side)} enable=${if (on) 1 else 0} FAILED ${it.javaClass.simpleName}") }
                .onSuccess { log("${tag(side)} enable=${if (on) 1 else 0}") }
            if (on) failed = writeColour(side, color, level) ?: failed
        }
        enabledWritten = if (failed == null) on else null
        failed?.let { note = "couldn't write the lights (${it.javaClass.simpleName}) at ${now()}" }
    }

    /** Writes the colour to both halves of one ring; the failure, if any. */
    private fun writeColour(side: String, color: Int, level: Float): Throwable? {
        var failed: Throwable? = null
        val rgb = drive(color, level)
        val result = StringBuilder()
        for (half in 1..2) {
            // The second half straight after the first can be missed, so give the chip a moment.
            if (half == 2) Thread.sleep(25)
            runCatching { File("$side/brightness").writeText("$half-$rgb:255") }
                .onSuccess { result.append(" $half-$rgb") }
                .onFailure { failed = it; result.append(" $half-$rgb FAILED ${it.javaClass.simpleName}") }
        }
        log("${tag(side)} colour$result")
        return failed
    }

    private fun tag(side: String) = if (side.endsWith("l/led")) "left" else "right"

    /** The level eased (the lights look brighter than the number suggests at low levels), 0–1. */
    private fun eased(level: Float) = Math.pow(level.coerceIn(0f, 1f).toDouble(), 1.8).toFloat()

    /** What the lights are sent for [color] at [level]: "R:G:B", eased so the slider feels even. */
    private fun drive(color: Int, level: Float): String {
        val k = eased(level)
        val r = Math.round(android.graphics.Color.red(color) * k)
        val g = Math.round(android.graphics.Color.green(color) * k)
        val b = Math.round(android.graphics.Color.blue(color) * k)
        return "$r:$g:$b"
    }

    /** A running record of everything done to and seen of the lights, for Diagnostics (memory only). */
    private val logLines = ArrayDeque<String>()
    private val logStamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    fun log(line: String) = synchronized(logLines) {
        logLines.addLast(logStamp.format(java.util.Date()) + "  " + line)
        while (logLines.size > 200) logLines.removeFirst()
    }
    fun logged(): List<String> = synchronized(logLines) { logLines.toList() }

    /** What's in the lights' system folders (names, and contents where readable), for the log. */
    fun probe(c: Context): List<String> = buildList {
        for (side in SIDES) {
            val dir = File(side)
            add("$side: " + (dir.listFiles()?.sortedBy { it.name }?.joinToString(", ") { f ->
                val v = if (f.isFile) runCatching { f.readText().trim().replace('\n', ' ').take(60) }.getOrElse { "(${it.javaClass.simpleName})" } else "(dir)"
                "${f.name}${if (f.canWrite()) "[w]" else ""}=$v"
            } ?: "can't list"))
        }
        val cr = c.contentResolver
        for (k in listOf("joystick_light_enabled", "joystick_led_light_picker_color", "led_light_brightness_percent"))
            add("AYN $k = " + Settings.System.getString(cr, k))
        add("WalkThor: colour=" + String.format("#%06X", prefs(c).getInt("color", 0) and 0xFFFFFF) + " level=" + prefs(c).getFloat("level", -1f) + " helper=" + AynSystem.knownAvailable())
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
                log("check ${tag(side)} read enable=${en ?: "?"} brightness=${br ?: "?"}")
                seen.append(if (seen.isEmpty()) "" else ", ").append(side.substringAfterLast('/').removePrefix("sn3112")).append(' ').append(en ?: "?").append('/').append(br ?: "?")
                val enOk = en?.let { on == !it.startsWith("0") }
                val brOk = if (!on) true else br?.let { it.substringAfter('-').split(':').take(3).joinToString(":") == want }
                if (enOk == null || brOk == null) unread++
                val writeEn = enOk == false || (enOk == null && evenIfUnknown)
                val writeBr = on && (brOk == false || (brOk == null && evenIfUnknown))
                if (writeEn) runCatching { File("$side/enable").writeText(if (on) "1" else "0") }.onFailure { failed = it }
                if (writeBr) failed = writeColour(side, color, level) ?: failed
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
