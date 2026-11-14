package com.nerfherder.walkthor

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Triple-pressing the AYN button closes every app you have open (Settings → General → Access,
 * off unless you switch it on): everything in Android's recent apps, the game included, except
 * WalkThor and your home app. Done through AYN's system helper, as only system apps may close
 * others; where that can't be reached, Android only lets WalkThor close apps in the background.
 */
object CloseApps {
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor close apps").apply { isDaemon = true } }

    fun enabled(c: Context) = SwipeLauncher.prefs(c).getBoolean("tripleClose", false)
    fun setEnabled(c: Context, on: Boolean) = SwipeLauncher.prefs(c).edit().putBoolean("tripleClose", on).apply()

    fun closeAll(service: AccessibilityService) {
        val app = service.applicationContext
        runCatching { Drawer.hide() }
        worker.execute {
            val keep = buildSet {
                add(app.packageName)
                // Your home app (your frontend, if it's set as home), so there's somewhere to land.
                runCatching {
                    app.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
                }.getOrNull()?.let { add(it) }
                add("com.android.systemui")
            }
            val open = runCatching { AynSystem.openApps() }.getOrNull()
            val message = if (open != null) {
                val closed = open.filter { it !in keep }.count { runCatching { AynSystem.forceStop(it) }.getOrDefault(false) }
                if (closed == 1) "Closed 1 app" else "Closed $closed apps"
            } else {
                // No helper: Android lets an ordinary app close only what's in the background.
                val am = app.getSystemService(ActivityManager::class.java)
                val launchable = runCatching {
                    app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                        .map { it.activityInfo.packageName }.distinct()
                }.getOrDefault(emptyList())
                launchable.filter { it !in keep }.forEach { runCatching { am?.killBackgroundProcesses(it) } }
                "Closed apps in the background (AYN's helper is needed to close the one in front)"
            }
            AynButton.note("triple press → $message")
            main.post { runCatching { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() } }
        }
    }
}
