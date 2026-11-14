package com.nerfherder.walkthor

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Pressing Back twice quickly closes every app you have open (Settings → General → Access, off
 * unless you switch it on): the game included, but not WalkThor, your home app or your keyboard.
 * Done through AYN's system helper, as only system apps may close others; where that can't be
 * reached, Android only lets WalkThor close apps in the background.
 */
object CloseApps {
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor close apps").apply { isDaemon = true } }

    fun enabled(c: Context) = SwipeLauncher.prefs(c).getBoolean("tripleClose", false)
    fun setEnabled(c: Context, on: Boolean) {
        SwipeLauncher.prefs(c).edit().putBoolean("tripleClose", on).apply()
        // Start (or stop) hearing button presses for it.
        SwipeGestureService.instance?.get()?.let { s -> runCatching { s.setKeyWatch(com.nerfherder.walkthor.controls.ControllerInput.watching.value) } }
    }

    /** What happened last time, for Diagnostics. */
    @Volatile var lastNote: String? = null

    fun closeAll(service: AccessibilityService) {
        val app = service.applicationContext
        runCatching { Drawer.hide() }
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
                val targets = (recent + installed.filter { it in running }).distinct().filter { it !in keep }
                val closed = targets.count { runCatching { AynSystem.forceStop(it) }.getOrDefault(false) }
                lastNote = "helper ok; recent apps found ${recent.size}, running installed apps ${installed.count { it in running }}, closed $closed" +
                    (AynSystem.problem?.let { " ($it)" } ?: "")
                if (closed == 1) "Closed 1 app" else "Closed $closed apps"
            } else {
                // No helper: Android lets an ordinary app close only what's in the background.
                val am = app.getSystemService(ActivityManager::class.java)
                installed.filter { it !in keep }.forEach { runCatching { am?.killBackgroundProcesses(it) } }
                lastNote = "AYN's helper couldn't be reached (${AynSystem.problem ?: "no reason given"}); only background apps closed"
                "Closed background apps only (AYN's helper wasn't reachable)"
            }
            AynButton.note("back twice → $message")
            main.post { runCatching { Toast.makeText(app, message, Toast.LENGTH_LONG).show() } }
        }
    }
}
