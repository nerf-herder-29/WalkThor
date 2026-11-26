package com.nerfherder.walkthor

import android.content.ComponentName
import android.content.Context
import java.util.concurrent.Executors

/**
 * What's left of "Pause your home app while you play" (removed in 1.94.0): if a version that had
 * it left your home app paused (WalkThor updated or closed mid-game), it's put back once, the next
 * time WalkThor starts. Nothing else.
 */
object HomeSwap {
    private const val PREFS = "homeswap"
    private const val KEY_ORIGINAL = "original"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor home back").apply { isDaemon = true } }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** WalkThor started: your home app back, if an older version left it paused. */
    fun recover(c: Context) {
        val app = c.applicationContext
        val original = prefs(app).getString(KEY_ORIGINAL, null)?.let { ComponentName.unflattenFromString(it) }
        if (original == null) {
            // The setting itself is gone: clear what it kept.
            if (prefs(app).all.isNotEmpty()) prefs(app).edit().clear().apply()
            return
        }
        worker.execute {
            runCatching {
                val installed = runCatching { app.packageManager.getPackageInfo(original.packageName, 0) }.isSuccess
                if (installed) {
                    if (!AynSystem.available()) return@runCatching // tried again next start
                    AynSystem.shellLines("cmd package set-home-activity --user 0 ${original.flattenToString()}")
                    MemoryWatch.noteFrom(app, "Home app: ${label(app, original.packageName)} is home again (an older WalkThor had paused it)")
                }
                prefs(app).edit().clear().commit()
            }
        }
    }

    private fun label(c: Context, pkg: String) =
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
}
