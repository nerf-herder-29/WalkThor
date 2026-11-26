package com.nerfherder.walkthor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice

/**
 * Button shortcuts: each press WalkThor's accessibility service can tell apart (a tap, double
 * press or hold of the AYN button, Back pressed twice, Back with a D-pad direction) does the
 * thing you chose for it in Settings → Shortcuts, with a short rumble as it does.
 */
object Shortcuts {
    enum class Action(val label: String) {
        NOTHING("Nothing"),
        DRAWER("Open the drawer"),
        /** Only AYN's software can open its drawer, so this lets the press go on to AYN untouched. */
        AYN_DRAWER("AYN's own drawer"),
        DIM("Dim the bottom screen"),
        BOTTOM_OFF("Bottom screen off"),
        CLOSE_ALL("Close all apps"),
        SCREENSHOT("Screenshot"),
        CLIP("Save the last seconds"),
        FPS("Performance overlay on / off"),
        STICK_LIGHTS("Stick lights on / off"),
        KEYBOARD("Show / hide the keyboard"),
        TRACKPAD("Trackpad on / off"),
        BRIGHTER("Brightness up"),
        DIMMER("Brightness down"),
        LOUDER("Volume up"),
        QUIETER("Volume down"),
        LOCK_TOP("Lock controller to top screen"),
        LOCK_BOTTOM("Lock controller to bottom screen"),
        PERF_MODE("Next performance mode"),
        FAN_MODE("Next fan mode"),
    }

    enum class Trigger(val key: String, val label: String, val detail: String) {
        AYN_PRESS("aynPress", "AYN button: press", "One tap"),
        AYN_DOUBLE("aynDouble", "AYN button: double press", "Two quick taps"),
        AYN_HOLD("aynHold", "AYN button: hold", "Held for over half a second"),
        BACK_DOUBLE("backDouble", "Back button: double press", "Two presses within 0.4 s. Both still go to the app in front"),
        BACK_UP("backUp", "Back + D-pad up", "Hold Back and press up"),
        BACK_DOWN("backDown", "Back + D-pad down", "Hold Back and press down"),
        BACK_LEFT("backLeft", "Back + D-pad left", "Hold Back and press left"),
        BACK_RIGHT("backRight", "Back + D-pad right", "Hold Back and press right"),
        BACK_L1("backL1", "Back + L1", "Hold Back and press L1"),
        BACK_R1("backR1", "Back + R1", "Hold Back and press R1"),
        BACK_L2("backL2", "Back + L2", "Hold Back and press L2"),
        BACK_R2("backR2", "Back + R2", "Hold Back and press R2"),
        ;

        /** One of the combinations with Back held. */
        val combo get() = this != AYN_PRESS && this != AYN_DOUBLE && this != AYN_HOLD && this != BACK_DOUBLE
    }


    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor shortcuts").apply { isDaemon = true } }
    private val cache = java.util.concurrent.ConcurrentHashMap<Trigger, Action>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun prefs(c: Context) = SwipeLauncher.prefs(c)

    /** What can be chosen for [t]: passing the press on to AYN only makes sense for the AYN button's taps. */
    fun actionsFor(t: Trigger): List<Action> = Action.entries.filter {
        it != Action.AYN_DRAWER || t == Trigger.AYN_PRESS || t == Trigger.AYN_DOUBLE
    }

    /** What [t] does. Before you've chosen, it's what the older switches in Settings had it doing. */
    fun get(c: Context, t: Trigger): Action {
        // The AYN button's tap: its own switch says whether WalkThor takes the button at all.
        if (t == Trigger.AYN_PRESS && !prefs(c).getBoolean("aynButton", false)) return Action.AYN_DRAWER
        cache[t]?.let { return it }
        val stored = prefs(c).getString("shortcut." + t.key, null)?.let { n -> Action.entries.firstOrNull { it.name == n } }
        val action = stored?.takeIf { it in actionsFor(t) } ?: when (t) {
            Trigger.AYN_PRESS -> Action.DRAWER
            Trigger.AYN_DOUBLE -> Action.AYN_DRAWER
            Trigger.AYN_HOLD -> if (prefs(c).getBoolean("bottomOffHold", false)) Action.DIM else Action.NOTHING
            Trigger.BACK_DOUBLE -> if (prefs(c).getBoolean("tripleClose", false)) Action.CLOSE_ALL else Action.NOTHING
            else -> Action.NOTHING
        }
        cache[t] = action
        return action
    }

    fun set(c: Context, t: Trigger, a: Action) {
        cache[t] = a
        prefs(c).edit().putString("shortcut." + t.key, a.name).apply()
        // The AYN button is WalkThor's unless its tap is left to AYN.
        if (t == Trigger.AYN_PRESS) AynButton.set(c, a != Action.AYN_DRAWER)
        // Start (or stop) hearing button presses to match.
        SwipeGestureService.instance?.get()?.let { s ->
            runCatching { s.setKeyWatch(com.nerfherder.walkthor.controls.ControllerInput.watching.value) }
        }
    }

    /** The AYN button's double press and hold only reach WalkThor while its tap does. */
    fun usable(c: Context, t: Trigger): Boolean =
        (t != Trigger.AYN_DOUBLE && t != Trigger.AYN_HOLD) || get(c, Trigger.AYN_PRESS) != Action.AYN_DRAWER

    /** A shortcut other than the AYN button's needs WalkThor to hear button presses. */
    fun wantsKeys(c: Context): Boolean = get(c, Trigger.BACK_DOUBLE) != Action.NOTHING || anyCombo(c)

    /** Any Back + D-pad combination is set (then Back is held back until it's let go). */
    fun anyCombo(c: Context): Boolean = Trigger.entries.any { it.combo && get(c, it) != Action.NOTHING }

    /** The combination for a D-pad key, or null. */
    fun comboFor(keyCode: Int): Trigger? = when (keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_UP -> Trigger.BACK_UP
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> Trigger.BACK_DOWN
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> Trigger.BACK_LEFT
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> Trigger.BACK_RIGHT
        android.view.KeyEvent.KEYCODE_BUTTON_L1 -> Trigger.BACK_L1
        android.view.KeyEvent.KEYCODE_BUTTON_R1 -> Trigger.BACK_R1
        android.view.KeyEvent.KEYCODE_BUTTON_L2 -> Trigger.BACK_L2
        android.view.KeyEvent.KEYCODE_BUTTON_R2 -> Trigger.BACK_R2
        else -> null
    }

    // ---- rumble ----

    fun rumbleOn(c: Context) = prefs(c).getBoolean("shortcutRumble", true)
    fun setRumble(c: Context, on: Boolean) = prefs(c).edit().putBoolean("shortcutRumble", on).apply()

    /** Why the last rumble didn't happen, for Diagnostics; null when it did. */
    @Volatile var rumbleProblem: String? = null

    /** A short buzz on every motor there is: the device's own, and the built-in controller's. */
    fun rumble(c: Context) {
        if (!rumbleOn(c)) return
        val effect = VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
        val motors = buildList {
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) c.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator?.let(::add)
                else @Suppress("DEPRECATION") c.getSystemService(Vibrator::class.java)?.let(::add)
            }
            runCatching {
                InputDevice.getDeviceIds().forEach { id ->
                    val d = InputDevice.getDevice(id) ?: return@forEach
                    if ((d.sources and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) return@forEach
                    if (Build.VERSION.SDK_INT >= 31) d.vibratorManager.vibratorIds.forEach { v -> add(d.vibratorManager.getVibrator(v)) }
                    else @Suppress("DEPRECATION") add(d.vibrator)
                }
            }
        }.filter { runCatching { it.hasVibrator() }.getOrDefault(false) }
        if (motors.isEmpty()) { rumbleProblem = "no vibration motor found"; return }
        val problems = mutableListOf<String>()
        motors.forEach { motor ->
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    // Marked as accessibility feedback, which Android lets a service give while another app is in front.
                    motor.vibrate(effect, android.os.VibrationAttributes.Builder().setUsage(android.os.VibrationAttributes.USAGE_ACCESSIBILITY).build())
                } else {
                    @Suppress("DEPRECATION")
                    motor.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build())
                }
            }.onFailure { problems += "${it.javaClass.simpleName}: ${it.message?.take(80)}" }
        }
        rumbleProblem = if (problems.size == motors.size) problems.joinToString("; ") else null
        rumbleProblem?.let { AynButton.note("rumble: $it") }
    }

    // ---- brightness ----

    private const val STEP = 0.1f
    /** The levels the last press set, and when (only touched on the shortcuts' worker thread). */
    private var lastTop: Float? = null
    private var lastBottom: Float? = null
    private var lastStepAt = 0L

    /**
     * Brightness up or down a tenth, on the screen Home's brightness control is set to (top,
     * bottom or both). Worker thread: reading the bottom screen's level can take a moment.
     */
    private fun stepBrightness(app: Context, delta: Float) {
        val target = ScreenControls.target(app)
        fun next(now: Float) = (Math.round((now + delta) * 10f) / 10f).coerceIn(0.05f, 1f)
        val doTop = target != ScreenControls.Target.BOTTOM && ScreenControls.canSetTop(app)
        val doBottom = target != ScreenControls.Target.TOP
        if (!doTop && !doBottom) {
            ScreenMessage.show("Top screen brightness needs Modify system settings for WalkThor (Home → brightness)")
            return
        }
        // Pressed again within a few seconds: steps from the level it just set, since the screens
        // (the bottom one especially) may not have taken the last change yet.
        val t = android.os.SystemClock.uptimeMillis()
        val recent = t - lastStepAt < 4_000
        val topNow = if (doTop) (lastTop.takeIf { recent } ?: ScreenControls.top(app)) else null
        val bottomNow = if (doBottom) (lastBottom.takeIf { recent } ?: runCatching { ScreenControls.bottom(app) }.getOrDefault(0.5f)) else null
        // Both screens: one level for the two, as Home's slider sets them, stepped from the top
        // screen's (each screen keeps its level a little differently, so stepping each on its own
        // lets them drift apart).
        val shared = if (topNow != null && bottomNow != null) next(topNow) else null
        val top = shared ?: topNow?.let(::next)
        val bottom = shared ?: bottomNow?.let(::next)
        lastTop = top
        lastBottom = bottom
        lastStepAt = t
        fun pct(l: Float) = "${Math.round(l * 100)}%"
        main.post {
            top?.let { ScreenControls.setTop(app, it) }
            bottom?.let { ScreenControls.setBottom(app, it) }
            ScreenMessage.show(
                when {
                    top != null && bottom != null && top == bottom -> "Brightness ${pct(top)}"
                    top != null && bottom != null -> "Top ${pct(top)} · bottom ${pct(bottom)}"
                    top != null -> "Top screen ${pct(top)}"
                    else -> "Bottom screen ${pct(bottom!!)}" + if (target == ScreenControls.Target.BOTH) " (top: allow Modify system settings)" else ""
                },
                1_500,
            )
        }
    }

    /** Whether WalkThor is Android's home app. */
    fun isHomeApp(c: android.content.Context): Boolean = runCatching {
        c.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == c.packageName
    }.getOrDefault(false)

    // ---- doing it ----

    /** [t] was pressed: does what it's set to, with a rumble. Call on the main thread. */
    fun fire(service: SwipeGestureService, t: Trigger) {
        val a = get(service, t)
        if (a == Action.NOTHING || a == Action.AYN_DRAWER) return
        // With WalkThor as the home app, your games and the black bottom screen take the drawer's
        // place, so "Open the drawer" does nothing (it used to close the home screen itself).
        if (a == Action.DRAWER && isHomeApp(service)) {
            // With WalkThor as the home app: during a game, nothing (the bottom screen is WalkThor's
            // already); otherwise WalkThor's drawer slides up over the games, or away.
            if (GameFocus.gameRunning.value) { AynButton.note("${t.label} → nothing during a game (WalkThor is the home app)"); return }
            rumble(service)
            val bottom = DisplayHelper.bottomDisplay(service)?.displayId ?: return
            if (Drawer.shown.value) {
                Drawer.hide()
                ScreenMessage.show("Drawer put away", 1_200)
            } else if (Drawer.show(service, bottom, opened = true)) {
                AynButton.note("${t.label} → drawer opened over the games")
            }
            return
        }
        AynButton.note("${t.label} → ${a.label}")
        rumble(service)
        runCatching { perform(service, a) }
            .onFailure { AynButton.note("couldn't (${it.javaClass.simpleName}: ${it.message?.take(100)})") }
    }

    private fun perform(service: SwipeGestureService, a: Action) {
        val app = service.applicationContext
        when (a) {
            Action.NOTHING, Action.AYN_DRAWER -> Unit
            Action.DRAWER -> {
                val wasUp = Drawer.shown.value || AppState.resumedDisplayId != null
                service.toggleDrawer()
                ScreenMessage.show(if (wasUp) "Drawer put away" else "Drawer open", 1_500)
            }
            Action.DIM -> {
                BottomOff.holdToggle(service)
                ScreenMessage.show(if (BottomOff.dimmed(service)) "Bottom screen dimmed" else "Bottom screen brightness back", 2_000)
            }
            Action.BOTTOM_OFF -> {
                if (BottomOff.off.value) BottomOff.turnOn(service) else BottomOff.turnOff(service, game = false)
                ScreenMessage.show(if (BottomOff.off.value) "Bottom screen off" else "Bottom screen on", 2_000)
            }
            // The ring counts down first, then a message says what was closed.
            Action.CLOSE_ALL -> CloseApps.closeAllFromButton(service)
            Action.SCREENSHOT -> {
                val ok = Build.VERSION.SDK_INT >= 28 && service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                // After the screenshot is taken, so the message isn't in it.
                if (ok) main.postDelayed({ ScreenMessage.show("Screenshot saved", 2_000) }, 1_200)
                else ScreenMessage.show("Couldn't take a screenshot")
            }
            Action.CLIP -> {
                if (!com.nerfherder.walkthor.data.Clips.buffering.value) {
                    ScreenMessage.show("Nothing kept yet. Turn on Keep the last in Shots first")
                    return
                }
                val name = EmulatorWatch.session.value?.name ?: "WalkThor"
                worker.execute {
                    val secs = com.nerfherder.walkthor.data.Clips.seconds(app)
                    val uri = runCatching {
                        kotlinx.coroutines.runBlocking { com.nerfherder.walkthor.data.Clips.saveLast(app, name, secs) }
                    }.getOrNull()
                    ScreenMessage.show(if (uri != null) "Saved the last $secs seconds" else "Couldn't save the clip")
                }
            }
            Action.FPS -> {
                val on = !TopFps.enabled(app)
                TopFps.setEnabled(app, on)
                ScreenMessage.show(if (on) "Performance overlay on" else "Performance overlay off", 2_000)
            }
            Action.LOCK_TOP -> ControllerLock.toggle(service, ControllerLock.Screen.TOP)
            Action.LOCK_BOTTOM -> ControllerLock.toggle(service, ControllerLock.Screen.BOTTOM)
            Action.KEYBOARD -> ScreenMessage.show(
                when (com.nerfherder.walkthor.keyboard.WalkThorKeyboard.toggle(app)) {
                    com.nerfherder.walkthor.keyboard.WalkThorKeyboard.Companion.Toggle.SHOWN -> "Keyboard up"
                    com.nerfherder.walkthor.keyboard.WalkThorKeyboard.Companion.Toggle.HIDDEN -> "Keyboard put away"
                    com.nerfherder.walkthor.keyboard.WalkThorKeyboard.Companion.Toggle.NO_TEXT_BOX -> "Pick a text box first, then press it again"
                    com.nerfherder.walkthor.keyboard.WalkThorKeyboard.Companion.Toggle.NOT_IN_USE -> "Choose the WalkThor keyboard first (Settings → General)"
                },
                2_000,
            )
            // Its own message says whether it's on.
            Action.TRACKPAD -> Trackpad.toggle(service)
            Action.LOUDER, Action.QUIETER -> {
                val am = app.getSystemService(android.media.AudioManager::class.java)
                if (am == null) { ScreenMessage.show("Couldn't change the volume"); return }
                val stream = android.media.AudioManager.STREAM_MUSIC
                // No flags: Android's own volume slider stays off the game.
                am.adjustStreamVolume(stream, if (a == Action.LOUDER) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, 0)
                val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
                val now = am.getStreamVolume(stream)
                // The bottom screen's own volume too, with both screens together.
                ScreenControls.followTopVolume(app, now)
                ScreenMessage.show(if (now == 0) "Volume off" else "Volume ${Math.round(now * 100f / max)}%", 1_500)
            }
            Action.BRIGHTER -> worker.execute { stepBrightness(app, +STEP) }
            Action.DIMMER -> worker.execute { stepBrightness(app, -STEP) }
            Action.STICK_LIGHTS -> {
                val on = !StickLights.isOn(app)
                StickLights.setOn(app, on)
                ScreenMessage.show(if (on) "Stick lights on" else "Stick lights off", 2_000)
            }
            Action.PERF_MODE -> worker.execute {
                if (!DeviceModes.check()) { ScreenMessage.show("Performance modes need Force SELinux switched off"); return@execute }
                val modes = DeviceModes.Performance.entries
                val next = modes[(modes.indexOf(DeviceModes.performance(app)) + 1) % modes.size]
                val ok = AynSystem.setPerformance(next.value)
                ScreenMessage.show(if (ok) "Performance: ${next.label}" else "Couldn't change the performance mode", 2_500)
            }
            Action.FAN_MODE -> worker.execute {
                if (!DeviceModes.check()) { ScreenMessage.show("Fan modes need Force SELinux switched off"); return@execute }
                val modes = DeviceModes.Fan.entries
                val now = DeviceModes.fan(app)?.let { modes.indexOf(it) } ?: -1
                val next = modes[(now + 1) % modes.size]
                val ok = AynSystem.putSetting("fan_mode", next.value.toString())
                ScreenMessage.show(if (ok) "Fan: ${next.label}" else "Couldn't change the fan mode", 2_500)
            }
        }
    }
}
