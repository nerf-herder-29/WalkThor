package com.nerfherder.walkthor

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Pauses your home app (Cocoon, or any other that isn't Android's own launcher) while a game is
 * playing, and puts it back after.
 *
 * Android treats whichever app is home as one it must keep alive: when memory runs short and it's
 * closed, Android starts it again straight away, on both screens, and a heavy frontend coming back
 * over and over can be what tips a game over (the bottom screen going white and flashing). So when
 * a game comes to the front, home is switched to Android's own launcher and your home app is
 * closed; once no game has been in front for a little while, yours is home again.
 *
 * Done through AYN's system helper, only as a game starts or after it's gone (never while memory
 * is critical). If WalkThor stops mid-game, the switch is undone the next time it starts.
 */
object HomeSwap {
    private const val PREFS = "homeswap"
    private const val KEY_ON = "on"
    private const val KEY_ORIGINAL = "original"
    private const val KEY_STOCK = "stock"
    /** How long no game has to be in front before your home app comes back (not just a quick look at the drawer). */
    private const val BACK_AFTER_MS = 20_000L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor home swap").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var pendingRestore: Runnable? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(c: Context) = prefs(c).getBoolean(KEY_ON, false)

    fun setOn(c: Context, on: Boolean) {
        val app = c.applicationContext
        prefs(app).edit().putBoolean(KEY_ON, on).apply()
        // Turned off mid-game: yours comes back now.
        if (!on) worker.execute { runCatching { restore(app, "turned off") } }
    }

    /** Your home app, if it's one this would pause (not Android's own), for Settings. */
    fun pausable(c: Context): String? = runCatching {
        prefs(c).getString(KEY_ORIGINAL, null)?.let { ComponentName.unflattenFromString(it)?.packageName }?.let { return label(c, it) }
        val home = currentHome(c) ?: return null
        if (isSystem(c, home.packageName)) null else label(c, home.packageName)
    }.getOrNull()

    /** A game came to the front, or went (from EmulatorWatch). */
    fun gameChanged(c: Context, playing: Boolean) {
        val app = c.applicationContext
        main.post {
            pendingRestore?.let { main.removeCallbacks(it) }
            pendingRestore = null
            if (playing) {
                if (isOn(app)) worker.execute { runCatching { pause(app) } }
            } else if (prefs(app).getString(KEY_ORIGINAL, null) != null) {
                lateinit var r: Runnable
                r = Runnable {
                    when {
                        EmulatorWatch.emulatorShowing.value -> pendingRestore = null
                        // A launcher came over the game as the drawer opened: the game's still there.
                        EmulatorWatch.gameCovered -> main.postDelayed(r, BACK_AFTER_MS)
                        else -> { pendingRestore = null; worker.execute { runCatching { restore(app, "no game in front") } } }
                    }
                }
                pendingRestore = r
                main.postDelayed(r, BACK_AFTER_MS)
            }
        }
    }

    /** WalkThor (re)started: if it left your home app paused and no game is playing, it comes back. */
    fun recover(c: Context) {
        val app = c.applicationContext
        if (prefs(app).getString(KEY_ORIGINAL, null) == null) return
        gameChanged(app, EmulatorWatch.emulatorShowing.value)
    }

    private fun pause(c: Context) {
        if (MemoryWatch.yielding) return
        if (prefs(c).getString(KEY_ORIGINAL, null) != null) return // already paused
        val home = currentHome(c) ?: return
        if (isSystem(c, home.packageName)) return // Android's own already: nothing to do
        val stock = stockLauncher(c) ?: run {
            MemoryWatch.noteFrom(c, "Home app: couldn't find Android's own launcher to use while playing")
            return
        }
        if (!AynSystem.available()) {
            MemoryWatch.noteFrom(c, "Home app: AYN's system helper isn't available, so ${label(c, home.packageName)} stays home")
            return
        }
        // Noted first, so that if anything stops part-way, it's put back.
        prefs(c).edit().putString(KEY_ORIGINAL, home.flattenToString()).putString(KEY_STOCK, stock.packageName).commit()
        AynSystem.shellLines("cmd package set-home-activity --user 0 ${stock.flattenToString()}; am force-stop ${home.packageName}")
        val now = currentHome(c)
        if (now?.packageName == stock.packageName) {
            MemoryWatch.noteFrom(c, "Home app: ${label(c, home.packageName)} paused for the game (${label(c, stock.packageName)} is home meanwhile)")
        } else {
            prefs(c).edit().remove(KEY_ORIGINAL).remove(KEY_STOCK).commit()
            MemoryWatch.noteFrom(c, "Home app: Android didn't take the switch, so ${label(c, home.packageName)} stays home")
        }
    }

    private fun restore(c: Context, why: String) {
        val p = prefs(c)
        val original = p.getString(KEY_ORIGINAL, null)?.let { ComponentName.unflattenFromString(it) } ?: return
        val stock = p.getString(KEY_STOCK, null)
        if (MemoryWatch.yielding) { // later, once memory has settled
            main.postDelayed({ gameChanged(c, EmulatorWatch.emulatorShowing.value) }, BACK_AFTER_MS)
            return
        }
        // Gone since (uninstalled): nothing to put back.
        val installed = runCatching { c.packageManager.getPackageInfo(original.packageName, 0) }.isSuccess
        if (installed) {
            if (!AynSystem.available()) return // tried again next time
            AynSystem.shellLines("cmd package set-home-activity --user 0 ${original.flattenToString()}")
        }
        p.edit().remove(KEY_ORIGINAL).remove(KEY_STOCK).commit()
        if (!installed) return
        MemoryWatch.noteFrom(c, "Home app: ${label(c, original.packageName)} is home again ($why)")
        // Where Android's launcher is showing now, yours takes its place.
        val bottom = runCatching { DisplayHelper.bottomDisplay(c)?.displayId }.getOrNull()
        val displays = listOfNotNull(0, bottom).distinct()
        main.post {
            displays.forEach { id ->
                if (stock != null && EmulatorWatch.frontOn(id) == stock) runCatching {
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(original.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    c.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
                }
            }
        }
    }

    private val homeIntent get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** The home app Android uses now; null if none is set (it would ask). */
    private fun currentHome(c: Context): ComponentName? {
        val ri = c.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val ai = ri.activityInfo ?: return null
        if (ai.packageName == "android") return null // the "which app?" chooser
        return ComponentName(ai.packageName, ai.name)
    }

    /** Android's own launcher: a home app that came with the system (not Settings' stand-in). */
    private fun stockLauncher(c: Context): ComponentName? {
        val all = runCatching { c.packageManager.queryIntentActivities(homeIntent, 0) }.getOrDefault(emptyList())
            .mapNotNull { it.activityInfo }
            .filter { isSystem(c, it.packageName) && it.packageName != "com.android.settings" && it.packageName != "android" }
        val pick = all.firstOrNull { "quickstep" in it.packageName || "launcher" in it.packageName.lowercase() } ?: all.firstOrNull()
        return pick?.let { ComponentName(it.packageName, it.name) }
    }

    private fun isSystem(c: Context, pkg: String) = runCatching {
        (c.packageManager.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)

    private fun label(c: Context, pkg: String) =
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
}
