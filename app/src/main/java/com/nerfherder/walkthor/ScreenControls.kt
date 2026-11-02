package com.nerfherder.walkthor

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * The quick controls on Now playing: each screen's brightness and the volume, plus the top
 * screen's frame rate read from the display driver (no screen capture).
 *
 * - Top screen brightness is Android's own brightness setting, which needs "Modify system
 *   settings" for WalkThor once.
 * - The bottom screen's brightness is AYN's; if WalkThor finds a setting for it that it's allowed
 *   to change, it uses that. Otherwise it dims the bottom screen itself (darker only), through its
 *   accessibility service.
 * - Volume is the device's media volume (one for both screens).
 */
object ScreenControls {
    private const val PREFS = "screen_controls"

    enum class Target(val label: String) { TOP("Top screen"), BOTTOM("Bottom screen"), BOTH("Both") }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun target(c: Context) = Target.entries.firstOrNull { it.name == prefs(c).getString("target", null) } ?: Target.BOTH
    fun setTarget(c: Context, t: Target) = prefs(c).edit().putString("target", t.name).apply()

    // ---- top screen (Android's brightness) ----

    fun canSetTop(c: Context) = Settings.System.canWrite(c)

    /** Opens Android's "Modify system settings" page for WalkThor. */
    fun askTop(c: Context) {
        runCatching {
            c.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun top(c: Context): Float =
        runCatching { Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)

    fun setTop(c: Context, level: Float) {
        StickLights.screensChanged(c, level)
        if (!canSetTop(c)) return
        runCatching {
            Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (1 + level.coerceIn(0f, 1f) * 254).toInt())
        }
    }

    // ---- bottom screen ----

    /** AYN's setting for the bottom screen's brightness, if WalkThor found one it may change; else null. */
    @Volatile private var bottomKey: String? = null
    @Volatile private var bottomKeyChecked = false
    /** How the bottom screen's brightness was set last, for Diagnostics. */
    val bottomHow = MutableStateFlow("not tried yet")

    /** Settings names that look like the bottom screen's brightness. */
    private val BOTTOM_HINTS = listOf("second", "sub", "bottom", "external", "aux", "dual", "lower", "secondary")

    /** Why AYN's bottom-screen setting couldn't be changed, for Diagnostics. */
    @Volatile var bottomKeyProblem: String? = null

    /** AYN's settings that look like the bottom screen's brightness. */
    private fun bottomCandidates(c: Context) = systemSettingNames(c).filter { n ->
        val l = n.lowercase()
        "bright" in l && BOTTOM_HINTS.any { it in l } && "mode" !in l && "auto" !in l
    }

    private fun findBottomKey(c: Context): String? {
        if (bottomKeyChecked) return bottomKey
        // Changing any system setting needs "Modify system settings": until it's allowed, look
        // again next time (checking now would fail and wrongly rule AYN's setting out for good).
        if (!canSetTop(c)) { bottomKeyProblem = "needs Modify system settings"; return null }
        bottomKeyChecked = true
        for (name in bottomCandidates(c)) {
            val result = runCatching {
                val now = Settings.System.getInt(c.contentResolver, name)
                Settings.System.putInt(c.contentResolver, name, now)
            }
            if (result.getOrDefault(false)) { bottomKey = name; bottomKeyProblem = null; break }
            bottomKeyProblem = "$name: " + (result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message?.take(120)}" } ?: "not changed")
        }
        return bottomKey
    }

    /**
     * The top of AYN's bottom-screen scale. Android's own brightness runs 0–255; if AYN's setting
     * already holds a bigger number, it uses a bigger scale.
     */
    private fun bottomMax(c: Context, key: String): Int {
        val now = runCatching { Settings.System.getInt(c.contentResolver, key) }.getOrDefault(0)
        return when {
            now <= 255 -> 255
            now <= 1023 -> 1023
            now <= 2047 -> 2047
            else -> 4095
        }.also { max -> prefs(c).edit().putInt("bottomMax", maxOf(max, prefs(c).getInt("bottomMax", 0))).apply() }
            .let { maxOf(it, prefs(c).getInt("bottomMax", 0)) }
    }

    /** The names of Android's system settings (for finding AYN's; also listed in Diagnostics). */
    fun systemSettingNames(c: Context): List<String> = runCatching {
        c.contentResolver.query(Settings.System.CONTENT_URI, arrayOf("name"), null, null, null)?.use { cur ->
            buildList { while (cur.moveToNext()) cur.getString(0)?.let { add(it) } }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    /** The bottom screen's level: its real brightness (through AYN's helper or setting), else WalkThor's dimming. Not on the main thread. */
    fun bottom(c: Context): Float {
        bottomDisplay(c)?.let { id -> AynSystem.brightness(c, id)?.let { return it } }
        findBottomKey(c)?.let { key ->
            val v = runCatching { Settings.System.getInt(c.contentResolver, key) }.getOrNull()
            if (v != null) return (v / bottomMax(c, key).toFloat()).coerceIn(0f, 1f)
        }
        return prefs(c).getFloat("bottomDim", 1f)
    }

    fun setBottom(c: Context, level: Float) {
        if (!canSetTop(c)) StickLights.screensChanged(c, level)
        val l = level.coerceIn(0f, 1f)
        // The real brightness, through the Thor's system helper (as AYN's drawer does it).
        val id = bottomDisplay(c)
        if (id != null && AynSystem.knownAvailable() == true) {
            AynSystem.setBrightness(c, id, l)
            bottomHow.value = "the Thor's system helper"
            if (prefs(c).getFloat("bottomDim", 1f) < 0.99f) { prefs(c).edit().putFloat("bottomDim", 1f).apply(); BottomDim.set(c, 1f) }
            return
        }
        val key = findBottomKey(c)
        val max = key?.let { bottomMax(c, it) } ?: 255
        if (key != null && canSetTop(c) && runCatching { Settings.System.putInt(c.contentResolver, key, (1 + l * (max - 1)).toInt()) }.getOrDefault(false)) {
            bottomHow.value = "AYN's setting \"$key\""
            BottomDim.set(c, 1f)
            return
        }
        prefs(c).edit().putFloat("bottomDim", l).apply()
        bottomHow.value = if (BottomDim.set(c, l)) "WalkThor dims it (darker only)" else "needs accessibility access to dim it"
    }

    /** Whether the bottom slider can only make it darker (no way to change its real brightness). Not on the main thread. */
    fun bottomDimOnly(c: Context) = !AynSystem.available() && findBottomKey(c) == null

    private fun bottomDisplay(c: Context): Int? = runCatching { DisplayHelper.bottomDisplay(c)?.displayId }.getOrNull()

    // ---- volume ----

    fun volume(c: Context): Float {
        val am = c.getSystemService(AudioManager::class.java) ?: return 0f
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    fun setVolume(c: Context, level: Float) {
        val am = c.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(level.coerceIn(0f, 1f) * max), 0) }
    }

    // ---- the top screen's frame rate, from the display driver ----

    /** Where Snapdragon display drivers report each screen's measured frame rate. */
    val FPS_FILES = listOf(
        "/sys/class/drm/sde-crtc-0/measured_fps",
        "/sys/class/drm/sde-crtc-1/measured_fps",
        "/sys/class/drm/sde-crtc-2/measured_fps",
        "/sys/class/graphics/fb0/measured_fps",
        "/sys/class/graphics/fb1/measured_fps",
    )

    /** The number in a measured_fps file ("fps: 59.9 duration:…"), or null if it can't be read. */
    fun readFps(path: String): Float? = runCatching {
        val text = File(path).readText()
        Regex("""(\d+(?:\.\d+)?)""").find(text.substringAfter("fps", text))?.value?.toFloat()
    }.getOrNull()

    /** The top screen's frame rate (the first readable file), or null if the device doesn't allow it. */
    fun topFps(): Float? = demoFps ?: FPS_FILES.firstNotNullOfOrNull { readFps(it) }

    /** For store screenshots only. */
    internal var demoFps: Float? = null

    /** What WalkThor can and can't do here, for Diagnostics. */
    fun report(c: Context): List<String> = buildList {
        add("Top brightness: " + if (canSetTop(c)) "can change (Modify system settings allowed)" else "needs Modify system settings")
        val helper = AynSystem.available()
        val key = findBottomKey(c)
        add("Bottom brightness: " + if (helper) "the Thor's system helper (real brightness)" else if (key != null) "AYN's setting \"$key\" (scale 0–${bottomMax(c, key)})" else "WalkThor dims it" + (bottomKeyProblem?.let { " ($it)" } ?: ""))
        if (!helper) add("Thor's system helper: " + (AynSystem.problem ?: "not available"))
        else add("Bottom slider: " + AynSystem.liveNote)
        val bright = systemSettingNames(c).filter { "bright" in it.lowercase() }
        add("Brightness settings on this device: " + bright.ifEmpty { listOf("none visible") }.joinToString(", ") { n ->
            n + "=" + (runCatching { Settings.System.getString(c.contentResolver, n) }.getOrNull() ?: "?")
        })
        FPS_FILES.forEach { p ->
            val f = File(p)
            add("$p: " + when {
                !f.exists() -> "not there"
                else -> readFps(p)?.let { "reads $it" } ?: "there, but not readable"
            })
        }
    }
}

/**
 * Darkens the bottom screen (over everything on it, games and frontends included) when WalkThor
 * can't change its real brightness. Drawn by the accessibility service; touches pass through.
 */
object BottomDim {
    private var view: View? = null
    private var wm: WindowManager? = null

    /** Returns false if it can't (no accessibility access). [level] 1 = no dimming. */
    fun set(context: Context, level: Float): Boolean {
        val service = SwipeGestureService.instance?.get() ?: return false
        val alpha = ((1f - level.coerceIn(0f, 1f)) * 0.85f)
        if (alpha <= 0.01f) { remove(); return true }
        val v = view ?: run {
            val bottom = DisplayHelper.bottomDisplay(service) ?: return false
            val display = service.getSystemService(DisplayManager::class.java).getDisplay(bottom.displayId)
            val ctx = service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val w = ctx.getSystemService(WindowManager::class.java) ?: return false
            val nv = View(ctx).apply { setBackgroundColor(Color.BLACK) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                title = "WalkThor bottom dimming"
            }
            if (runCatching { w.addView(nv, params) }.isFailure) return false
            wm = w
            view = nv
            nv
        }
        v.alpha = alpha
        return true
    }

    fun remove() {
        view?.let { v -> runCatching { wm?.removeViewImmediate(v) } }
        view = null
        wm = null
    }

    /** After the service (re)starts: put back the dimming you'd chosen. */
    fun restore(context: Context) {
        val level = context.getSharedPreferences("screen_controls", Context.MODE_PRIVATE).getFloat("bottomDim", 1f)
        if (level < 0.99f) set(context, level)
    }
}
