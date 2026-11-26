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
    /** The game your home app was paused for (so a report can still be saved if WalkThor was closed with it). */
    private const val KEY_GAME = "game"
    /** How long no game has to be in front before your home app comes back (just enough to ride out a flicker). */
    private const val BACK_AFTER_MS = 300L
    /** While the drawer may be covering the game, how often to look again, and for how long at most. */
    private const val COVERED_RECHECK_MS = 2_000L
    private const val COVERED_MAX_MS = 10_000L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor home swap").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var pendingRestore: Runnable? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(c: Context) = prefs(c).getBoolean(KEY_ON, false)

    /** Your home app is paused right now (Android's launcher standing in). */
    fun pausedNow(c: Context) = prefs(c).getString(KEY_ORIGINAL, null) != null

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
                val since = android.os.SystemClock.elapsedRealtime()
                lateinit var r: Runnable
                r = Runnable {
                    when {
                        EmulatorWatch.emulatorShowing.value -> pendingRestore = null
                        // A launcher came over the game as the drawer opened: the game's still there
                        // (but not for long, or a game closed from the drawer would hold it up).
                        EmulatorWatch.gameCovered && android.os.SystemClock.elapsedRealtime() - since < COVERED_MAX_MS ->
                            main.postDelayed(r, COVERED_RECHECK_MS)
                        else -> { pendingRestore = null; worker.execute { runCatching { restoreIfGameGone(app) } } }
                    }
                }
                pendingRestore = r
                // While the drawer may be over the game, a little longer before looking.
                main.postDelayed(r, if (EmulatorWatch.gameCovered) COVERED_RECHECK_MS else BACK_AFTER_MS)
            }
        }
    }

    /** The game turned out to have closed while WalkThor was down: your home app back, and up on the screens. */
    fun gameGone(c: Context, attempt: Int = 1) {
        val app = c.applicationContext
        main.post { pendingRestore?.let { main.removeCallbacks(it) }; pendingRestore = null }
        worker.execute {
            runCatching { restore(app, "the game had closed", bringUp = true) }
            // Just after WalkThor restarts, AYN's helper can take a moment to answer: again shortly.
            if (pausedNow(app)) {
                // Every 5 seconds for 2 minutes.
                if (attempt < 24) main.postDelayed({ gameGone(app, attempt + 1) }, 5_000)
                else MemoryWatch.noteFrom(app, "Home app: couldn't put ${pausedName(app)} back (AYN's system helper didn't answer); it will be at the next game, or set it in Android's settings")
            }
        }
    }

    private fun pausedName(c: Context) =
        prefs(c).getString(KEY_ORIGINAL, null)?.let { ComponentName.unflattenFromString(it)?.packageName }?.let { label(c, it) } ?: "your home app"

    /** WalkThor (re)started: if it left your home app paused and no game is playing, it comes back. */
    fun recover(c: Context) {
        val app = c.applicationContext
        if (prefs(app).getString(KEY_ORIGINAL, null) == null) {
            // Nothing paused: WalkThor's stand-in home screen shouldn't be on (or home).
            worker.execute { runCatching { if (currentHome(app)?.packageName != app.packageName) enableGameHome(app, false) } }
            return
        }
        gameChanged(app, EmulatorWatch.emulatorShowing.value)
    }

    private fun pause(c: Context) {
        if (MemoryWatch.yielding) return
        val home = currentHome(c) ?: return
        if (prefs(c).getString(KEY_ORIGINAL, null) != null) {
            // Already paused, unless you've set a home app yourself since: then that's the one to pause.
            if (home.packageName == prefs(c).getString(KEY_STOCK, null)) return
            prefs(c).edit().remove(KEY_ORIGINAL).remove(KEY_STOCK).commit()
        }
        if (isSystem(c, home.packageName)) return // Android's own already: nothing to do
        // WalkThor's own stand-in already home (left from a pause that wasn't undone): not yours to pause.
        if (home.packageName == c.packageName) return
        // WalkThor's own black home screen stands in (it hands the game's top spot straight back);
        // Android's launcher if that can't be switched on.
        val own = gameHome(c)
        val stock = if (enableGameHome(c, true)) own else (stockLauncher(c) ?: run {
            MemoryWatch.noteFrom(c, "Home app: couldn't find a home screen to use while playing")
            return
        })
        if (!AynSystem.available()) {
            enableGameHome(c, false)
            MemoryWatch.noteFrom(c, "Home app: AYN's system helper isn't available, so ${label(c, home.packageName)} stays home")
            return
        }
        // Noted first, so that if anything stops part-way, it's put back.
        prefs(c).edit().putString(KEY_ORIGINAL, home.flattenToString()).putString(KEY_STOCK, stock.packageName)
            .putString(KEY_GAME, MemoryWatch.gamePkg).commit()
        // WalkThor's black screen up on the bottom screen first, over your home app: when that's
        // closed, there's no empty screen for Android to fill. (Android picks the bottom screen's
        // home on its own, and on this device that's always its own launcher, so being home isn't
        // enough to be what shows there.)
        if (stock == own) showGameHome(c)
        AynSystem.shellLines("cmd package set-home-activity --user 0 ${stock.flattenToString()}; am force-stop ${home.packageName}")
        val now = currentHome(c)
        if (now?.packageName == stock.packageName) {
            MemoryWatch.noteFrom(c, "Home app: ${label(c, home.packageName)} paused for the game (" +
                (if (stock == own) "WalkThor's black home screen" else label(c, stock.packageName)) + " is home meanwhile)")
            // Android's launcher on the bottom screen takes the game's top spot: taps put it back.
            // (WalkThor's own home screen hands it back itself, the moment it gets it.)
            if (stock != own) runCatching { FocusBack.afterPause(c) }
        } else {
            prefs(c).edit().remove(KEY_ORIGINAL).remove(KEY_STOCK).commit()
            enableGameHome(c, false)
            MemoryWatch.noteFrom(c, "Home app: Android didn't take the switch, so ${label(c, home.packageName)} stays home")
        }
    }

    /**
     * Puts your home app back now, for "Close all apps" (so its message can say so), and brings
     * it up on the screens. The name of the app that's home again, or null if none was paused.
     * Not on the main thread.
     */
    fun restoreNow(c: Context): String? {
        val app = c.applicationContext
        main.post { pendingRestore?.let { main.removeCallbacks(it) }; pendingRestore = null }
        val pkg = prefs(app).getString(KEY_ORIGINAL, null)?.let { ComponentName.unflattenFromString(it)?.packageName } ?: return null
        restore(app, "apps closed", bringUp = true)
        return if (prefs(app).getString(KEY_ORIGINAL, null) == null) label(app, pkg) else null
    }

    /**
     * The game seemed to leave the screen: first asks Android what's really in front. A launcher
     * restarting (Android's memory killer closes it, and it starts again) puts a window on the game's
     * screen that looked like you'd left the game, and your home app was then brought up over the
     * game, which was still running. If the game's still in front, nothing's changed.
     */
    private fun restoreIfGameGone(c: Context) {
        val game = MemoryWatch.gamePkg
        if (game != null && gameStillInFront(game)) {
            MemoryWatch.noteFrom(c, "Home app: not put back: the game is still in front (a launcher restarting looked like you'd left it)")
            main.post { runCatching { EmulatorWatch.gameStillThere(c, game) } }
            return
        }
        // WalkThor was closed along with the game (it's only just started again): it never saw the
        // game leave, so the game report is taken now.
        val pausedFor = prefs(c).getString(KEY_GAME, null)
        val justStarted = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() < 60_000L
        restore(c, "no game in front")
        if (justStarted && pausedFor != null) {
            MemoryWatch.noteFrom(c, "The game closed while WalkThor was closed too: saving its report now")
            GameReport.capture(c, pausedFor, "the game closed while WalkThor was closed too")
        }
    }

    /** Whether [pkg] has the resumed (front) activity on any screen, by Android's own account. */
    private fun gameStillInFront(pkg: String): Boolean {
        val lines = AynSystem.shellLines("dumpsys activity activities | grep -E 'ResumedActivity'") ?: return false
        return lines.any { pkg in it }
    }

    @Synchronized
    private fun restore(c: Context, why: String, bringUp: Boolean = false) {
        val p = prefs(c)
        val original = p.getString(KEY_ORIGINAL, null)?.let { ComponentName.unflattenFromString(it) } ?: return
        val stock = p.getString(KEY_STOCK, null)
        if (MemoryWatch.yielding) { // later, once memory has settled
            main.postDelayed({ gameChanged(c, EmulatorWatch.emulatorShowing.value) }, 5_000L)
            return
        }
        // Gone since (uninstalled): nothing to put back.
        val installed = runCatching { c.packageManager.getPackageInfo(original.packageName, 0) }.isSuccess
        if (installed) {
            if (!AynSystem.available()) return // tried again next time
            AynSystem.shellLines("cmd package set-home-activity --user 0 ${original.flattenToString()}")
        }
        p.edit().remove(KEY_ORIGINAL).remove(KEY_STOCK).remove(KEY_GAME).commit()
        // WalkThor's stand-in home screen off again (a moment later, once yours has come up),
        // unless a new game has paused things again meanwhile.
        main.postDelayed({ if (!pausedNow(c)) worker.execute { enableGameHome(c, false) } }, 3_000L)
        if (!installed) return
        MemoryWatch.noteFrom(c, "Home app: ${label(c, original.packageName)} is home again ($why)")
        // Where Android's launcher is showing now, yours takes its place.
        val bottom = runCatching { DisplayHelper.bottomDisplay(c)?.displayId }.getOrNull()
        val displays = listOfNotNull(0, bottom).distinct()
        main.post {
            displays.forEach { id ->
                if (bringUp || (stock != null && EmulatorWatch.frontOn(id) == stock)) runCatching {
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(original.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    c.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
                }
            }
        }
    }

    /** Opens WalkThor's black screen on the bottom screen, and waits a moment for it to be up. Not on the main thread. */
    private fun showGameHome(c: Context) {
        val bottom = runCatching { DisplayHelper.bottomDisplay(c)?.displayId }.getOrNull() ?: return
        // A game drawn on both screens has the bottom screen itself.
        if (runCatching { EmulatorWatch.bothScreensGameShowing(c) }.getOrDefault(false)) return
        val started = java.util.concurrent.CountDownLatch(1)
        main.post {
            runCatching {
                val intent = Intent(c, GameHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                c.startActivity(intent, ActivityOptions.makeCustomAnimation(c, 0, 0).setLaunchDisplayId(bottom).toBundle())
            }.onFailure { MemoryWatch.noteFrom(c, "Home app: couldn't open WalkThor's black screen on the bottom screen (${it.javaClass.simpleName})") }
            started.countDown()
        }
        started.await(2, java.util.concurrent.TimeUnit.SECONDS)
        Thread.sleep(600)
    }

    /** WalkThor's stand-in home screen. */
    private fun gameHome(c: Context) = ComponentName(c, GameHomeActivity::class.java)

    /** Switches WalkThor's stand-in home screen on or off (off, it isn't offered as a home app). True if it took. */
    private fun enableGameHome(c: Context, on: Boolean): Boolean = runCatching {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        c.packageManager.setComponentEnabledSetting(gameHome(c), state, PackageManager.DONT_KILL_APP)
        true
    }.getOrDefault(false)

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
