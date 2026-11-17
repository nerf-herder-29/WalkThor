package com.nerfherder.walkthor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * The dock's "Close all apps" button (it asks first), or pressing Back twice quickly when that's
 * switched on, closes every app you have open: the game included, but not WalkThor, your home
 * app or your keyboard.
 * Done through AYN's system helper, as only system apps may close others; where that can't be
 * reached, Android only lets WalkThor close apps in the background.
 */
object CloseApps {
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor close apps").apply { isDaemon = true } }

    /** Whether pressing Back twice quickly closes all apps (off unless switched on). */
    fun backEnabled(c: Context) = SwipeLauncher.prefs(c).getBoolean("tripleClose", false)
    fun setBackEnabled(c: Context, on: Boolean) {
        SwipeLauncher.prefs(c).edit().putBoolean("tripleClose", on).apply()
        // Start (or stop) hearing button presses for it.
        SwipeGestureService.instance?.get()?.let { s -> runCatching { s.setKeyWatch(com.nerfherder.walkthor.controls.ControllerInput.watching.value) } }
    }

    /**
     * From a shortcut or the dock: counts down first (the top screen goes black with a warning,
     * the bottom one shows a ring; Back or a tap cancels), then closes everything and says "Closed all apps". [ask] false when you've
     * already said yes somewhere else.
     */
    fun closeAllFromButton(context: Context, ask: Boolean = true) {
        val app = context.applicationContext
        val go = {
            closeAll(app) { message, _ ->
                CloseCountdown.finish()
                ScreenMessage.show(message)
            }
        }
        // A countdown first, so a slip can be taken back.
        val service = SwipeGestureService.instance?.get()
        if (service == null) { go(); return }
        runCatching { Drawer.hide() }
        CloseCountdown.start(service, ask) { go() }
    }

    /** What happened last time, for Diagnostics. */
    @Volatile var lastNote: String? = null

    /** [onDone] gets what happened in a few words, and the detail behind it. */
    fun closeAll(context: Context, onDone: (String, String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val pm = app.packageManager
            val keep = buildSet {
                add(app.packageName)
                add("com.android.systemui")
                // Your home app (your frontend, if it's set as home), so there's somewhere to land.
                runCatching { pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName }
                    .getOrNull()?.let { add(it) }
                // Your keyboard.
                runCatching { android.provider.Settings.Secure.getString(app.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/') }
                    .getOrNull()?.let { add(it) }
            }
            // Apps you installed that have a home-screen icon: the ones "open" can mean.
            val launchable = runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName }.distinct()
            }.getOrDefault(emptyList())
            val installed = launchable.filter { p ->
                runCatching { (pm.getApplicationInfo(p, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }.getOrDefault(false)
            }
            val helper = runCatching { AynSystem.available() }.getOrDefault(false)
            val message = if (helper) {
                // Everything in recent apps, plus any installed app that's running at all.
                val recent = runCatching { AynSystem.openApps() }.getOrNull().orEmpty()
                val running = runCatching { AynSystem.runningNames() }.getOrNull().orEmpty()
                // And whatever is in front on either screen right now (the game), first of all.
                val front = runCatching { EmulatorWatch.fronts() }.getOrDefault(emptyList())
                val targets = (front + recent + installed.filter { it in running }).distinct().filter { it !in keep }
                    // Only real apps (recent apps can name a screen inside one instead).
                    .filter { p -> runCatching { pm.getApplicationInfo(p, 0) }.isSuccess }
                // Each is closed, then checked: only ones that really stopped running count.
                val results = targets.associateWith { runCatching { AynSystem.forceStop(it) }.getOrDefault(false) }
                // And their cards out of recent apps, so nothing's left there either.
                val cleared = runCatching { AynSystem.clearRecents(app, keep) }.getOrNull() ?: "no answer"
                val closed = results.filterValues { it }.keys
                val stuck = results.filterValues { !it }.keys
                lastNote = "helper ok; in front: ${front.joinToString().ifEmpty { "unknown" }}; recent apps ${recent.size}; running installed apps ${installed.count { it in running }}; " +
                    "closed: ${closed.joinToString().ifEmpty { "none" }}; still running: ${stuck.joinToString().ifEmpty { "none" }}; " +
                    "recent apps cleared: $cleared" +
                    (AynSystem.problem?.let { " ($it)" } ?: "")
                // Just the outcome; which apps closed (or didn't) is in Diagnostics.
                when {
                    closed.isEmpty() && stuck.isEmpty() -> "No apps to close"
                    closed.isEmpty() -> "Couldn't close your apps"
                    else -> "Closed all apps"
                }
            } else {
                // No helper: Android lets an ordinary app close only what's in the background.
                val am = app.getSystemService(ActivityManager::class.java)
                installed.filter { it !in keep }.forEach { runCatching { am?.killBackgroundProcesses(it) } }
                lastNote = "AYN's helper couldn't be reached (${AynSystem.problem ?: "no reason given"}); only background apps closed"
                "Closed background apps only (AYN's helper wasn't reachable)"
            }
            AynButton.note("close all apps → $message")
            main.post { runCatching { onDone(message, lastNote) } }
        }
    }
}
