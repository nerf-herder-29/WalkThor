package com.nerfherder.walkthor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice

/**
 * Button shortcuts: each press WalkThor's accessibility service can tell apart (a tap, double
 * press or hold of the AYN button, Back pressed twice, Start and Select held together) does the
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
        FPS("FPS counter on / off"),
        PERF_MODE("Next performance mode"),
        FAN_MODE("Next fan mode"),
    }

    enum class Trigger(val key: String, val label: String, val detail: String) {
        AYN_PRESS("aynPress", "AYN button: press", "One tap"),
        AYN_DOUBLE("aynDouble", "AYN button: double press", "Two quick taps"),
        AYN_HOLD("aynHold", "AYN button: hold", "Held for over half a second"),
        BACK_DOUBLE("backDouble", "Back button: double press", "Two presses within 0.4 s. Both still go to the app in front"),
        START_SELECT("startSelect", "Start + Select: hold", "Both held for a second. The game still gets them"),
    }

    /** How long Start and Select are held together. */
    const val COMBO_HOLD_MS = 1_000L

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor shortcuts").apply { isDaemon = true } }
    private val cache = java.util.concurrent.ConcurrentHashMap<Trigger, Action>()

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
            Trigger.START_SELECT -> Action.NOTHING
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
    fun wantsKeys(c: Context): Boolean =
        get(c, Trigger.BACK_DOUBLE) != Action.NOTHING || get(c, Trigger.START_SELECT) != Action.NOTHING

    // ---- rumble ----

    fun rumbleOn(c: Context) = prefs(c).getBoolean("shortcutRumble", true)
    fun setRumble(c: Context, on: Boolean) = prefs(c).edit().putBoolean("shortcutRumble", on).apply()

    /** A short buzz: the device's own motor, or else the built-in controller's. */
    fun rumble(c: Context) {
        if (!rumbleOn(c)) return
        runCatching {
            val effect = VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE)
            @Suppress("DEPRECATION")
            val own = c.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
            val motor = own ?: InputDevice.getDeviceIds().asSequence()
                .mapNotNull { InputDevice.getDevice(it) }
                .filter { (it.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD }
                .map { @Suppress("DEPRECATION") it.vibrator }
                .firstOrNull { it.hasVibrator() }
                ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                // Marked as accessibility feedback, which Android lets a service give while another app is in front.
                motor.vibrate(effect, android.os.VibrationAttributes.Builder().setUsage(android.os.VibrationAttributes.USAGE_ACCESSIBILITY).build())
            } else motor.vibrate(effect)
        }
    }

    // ---- doing it ----

    /** [t] was pressed: does what it's set to, with a rumble. Call on the main thread. */
    fun fire(service: SwipeGestureService, t: Trigger) {
        val a = get(service, t)
        if (a == Action.NOTHING || a == Action.AYN_DRAWER) return
        AynButton.note("${t.label} → ${a.label}")
        rumble(service)
        runCatching { perform(service, a) }
            .onFailure { AynButton.note("couldn't (${it.javaClass.simpleName}: ${it.message?.take(100)})") }
    }

    private fun perform(service: SwipeGestureService, a: Action) {
        val app = service.applicationContext
        when (a) {
            Action.NOTHING, Action.AYN_DRAWER -> Unit
            Action.DRAWER -> service.toggleDrawer()
            Action.DIM -> BottomOff.holdToggle(service)
            Action.BOTTOM_OFF -> if (BottomOff.off.value) BottomOff.turnOn(service) else BottomOff.turnOff(service, game = false)
            Action.CLOSE_ALL -> CloseApps.closeAllFromButton(service)
            Action.SCREENSHOT -> {
                val ok = Build.VERSION.SDK_INT >= 28 && service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                if (!ok) ScreenMessage.show("Couldn't take a screenshot")
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
                ScreenMessage.show(if (on) "FPS counter on" else "FPS counter off", 2_000)
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
