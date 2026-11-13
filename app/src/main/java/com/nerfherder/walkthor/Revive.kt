package com.nerfherder.walkthor

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.text.DateFormat
import java.util.Date

/**
 * Brings WalkThor back when Android closes it mid-game.
 *
 * Heavy games (big Switch games especially) can use so much memory while they load that Android
 * closes other apps to make room, WalkThor included, even though it's on the bottom screen. With
 * WalkThor gone, whatever is underneath it on the bottom screen can take the game's top priority.
 *
 * Android restarts WalkThor's accessibility service soon after. When it does, this checks why
 * WalkThor last closed: if Android closed it (low memory, or a crash) while it was showing during
 * a game, it reopens WalkThor on the bottom screen once the device has memory to spare again
 * (reopening while the game is still short of memory can get the game itself closed), and
 * WalkThor then hands the game its priority back as usual. It never reopens WalkThor after you
 * closed it yourself, gives up after a few tries so it can't loop, and can be switched off.
 */
/** Set on the intent when WalkThor reopens itself after Android closed it. */
const val EXTRA_REVIVED = "walkthor.revived"

object Revive {
    private const val PREFS = "revive"
    /** After a crash, how long to wait before reopening (memory isn't the problem then). */
    private const val CRASH_WAIT_MS = 1_000L
    /** Closed for memory: never sooner than this after its service comes back. */
    private const val MIN_WAIT_MS = 2_000L
    /** How often memory is checked while waiting. */
    private const val CHECK_EVERY_MS = 1_000L
    /** Comes back by then whatever memory looks like. */
    private const val MAX_WAIT_MS = 10_000L
    /** Free memory, beyond Android's low-memory line, that WalkThor would like before it reopens. */
    private const val SPARE_BYTES = 256L * 1024 * 1024
    /** At most this many reopens in [LIMIT_WINDOW_MS]. */
    private const val MAX_REOPENS = 3
    /** Closed again within this long of coming back: it stays down. */
    private const val SETTLE_MS = 3 * 60_000L
    private const val LIMIT_WINDOW_MS = 10 * 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** WalkThor is up during a game (called every few seconds while it's on screen). */
    fun heartbeat(context: Context, drawer: Boolean = false) {
        prefs(context).edit().putLong("seen", System.currentTimeMillis()).putBoolean("drawer", drawer).apply()
    }

    /** You closed WalkThor yourself: don't bring it back. */
    fun closedOnPurpose(context: Context) {
        prefs(context).edit().putLong("seen", 0L).apply()
    }

    /** Whether WalkThor reopens itself after Android closes it during a game (on unless switched off). */
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("enabled", on).apply()

    /** Enough memory to spare that reopening won't push Android to close the game. */
    private fun memoryCalm(am: ActivityManager): Boolean {
        val info = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(info) }.onFailure { return false }
        return !info.lowMemory && info.availMem > info.threshold + SPARE_BYTES
    }

    /** What happened last time Android closed WalkThor, for Diagnostics; null if it hasn't. */
    fun lastNote(context: Context): String? = prefs(context).getString("note", null)

    // ---- what was up when WalkThor was last running, kept so it can be put back ----

    /** When the device started, to the nearest few seconds: what was up before a restart doesn't count. */
    private fun bootStamp() = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 10_000L
    private fun sameBoot(context: Context) = kotlin.math.abs(prefs(context).getLong("boot", -9L) - bootStamp()) <= 1L

    /** The drawer went up, or was put away. */
    fun noteDrawer(context: Context, up: Boolean) {
        prefs(context).edit().putBoolean("drawerUp", up).putLong("boot", bootStamp()).apply()
    }

    /** The bottom screen was switched off ([byGame]: because a game started), or back on. */
    fun noteBottomOff(context: Context, off: Boolean, byGame: Boolean) {
        prefs(context).edit().putBoolean("bottomOff", off).putBoolean("bottomOffByGame", byGame).putLong("boot", bootStamp()).apply()
    }

    /** The bottom screen was off when WalkThor last ran (since the device started): null, or whether a game did it. */
    fun bottomWasOff(context: Context): Boolean? =
        if (sameBoot(context) && prefs(context).getBoolean("bottomOff", false)) prefs(context).getBoolean("bottomOffByGame", false) else null

    /**
     * Called by the accessibility service ([context]) when it starts, including after Android
     * restarts it. If the drawer was up when WalkThor stopped (it didn't put it away itself, so
     * Android closed it or it crashed), the drawer goes back up; if the app was up during a game,
     * the app reopens.
     */
    fun onServiceStart(context: android.accessibilityservice.AccessibilityService) {
        val p = prefs(context)
        val drawerWasUp = sameBoot(context) && p.getBoolean("drawerUp", false)
        // Whatever happens next, that drawer is gone now.
        if (p.getBoolean("drawerUp", false)) p.edit().putBoolean("drawerUp", false).apply()
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exit = if (Build.VERSION.SDK_INT >= 30) {
            runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrNull()
                ?.firstOrNull { it.processName == context.packageName }
                ?.takeIf { it.timestamp > p.getLong("handled", 0L) }
        } else null
        exit?.let { p.edit().putLong("handled", it.timestamp).apply() }
        if (exit == null && !drawerWasUp) return

        val now = System.currentTimeMillis()
        val stopped = exit?.timestamp ?: now
        val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(stopped))
        val reason = exit?.reason
        val crashed = Build.VERSION.SDK_INT >= 30 &&
            (reason == ApplicationExitInfo.REASON_CRASH || reason == ApplicationExitInfo.REASON_CRASH_NATIVE)
        val why = if (Build.VERSION.SDK_INT >= 30) when (reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_CRASH -> "after a crash"
            ApplicationExitInfo.REASON_ANR -> "because it stopped responding"
            // Closed by you, by an update, or by you stopping it: only a drawer that was up comes back.
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> if (drawerWasUp) "" else return
            // Low memory, and the other ways Android reports having closed an app itself (many
            // devices report a low-memory close as just "signaled").
            else -> "to free memory"
        } else ""
        if (Build.VERSION.SDK_INT >= 30 && exit != null &&
            (reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR)
        ) CrashLog.recordExit(context, exit)
        val closed = "Android closed WalkThor" + (if (why.isEmpty()) "" else " $why") + " at $at"
        // Stopped responding: reopening it could just do the same again, so leave it closed.
        if (Build.VERSION.SDK_INT >= 30 && reason == ApplicationExitInfo.REASON_ANR) {
            p.edit().putString("note", "$closed. A report was saved (About & licences).").apply()
            return
        }
        if (!enabled(context)) {
            p.edit().putString("note", "$closed. Reopening is switched off in Settings, so it stayed closed.").apply()
            return
        }
        // The app (not the drawer) only comes back if it was showing during a game right up to being closed.
        if (!drawerWasUp) {
            val seen = p.getLong("seen", 0L)
            if (seen == 0L || stopped - seen > 30_000L || now - stopped > 3 * 60_000L) {
                p.edit().putString("note", "$closed (WalkThor wasn't up, so there was nothing to bring back).").apply()
                return
            }
        }
        // It only just came back and Android has closed it again: the game needs the memory, and
        // coming back again would just repeat that (with the bottom screen flicking between
        // WalkThor and what's under it each time). Stay down; the AYN button still opens it.
        val lastBack = p.getLong("lastBack", 0L)
        if (now - lastBack in 0 until SETTLE_MS) {
            p.edit().putString("note", "$closed, soon after it had come back, so it stayed down this time (the AYN button opens it).").apply()
            return
        }
        // Don't loop if Android keeps closing it.
        val recent = (p.getString("reopens", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it < LIMIT_WINDOW_MS }
        if (recent.size >= MAX_REOPENS) {
            p.edit().putString("note", "$closed. It had already come back $MAX_REOPENS times in 10 minutes, so it stayed closed.").apply()
            return
        }
        p.edit()
            .putString("reopens", (recent + now).joinToString(","))
            .putString("note", "$closed; coming back.")
            .apply()

        // After a crash, straight back. Closed for memory: as soon as there's a little to spare,
        // and after a few seconds regardless, so it always does come back.
        val handler = Handler(Looper.getMainLooper())
        val started = android.os.SystemClock.elapsedRealtime()
        val check = object : Runnable {
            override fun run() {
                if (AppState.activity?.get() != null || Drawer.shown.value) return // already back (e.g. you opened it)
                if (BottomOff.off.value) return // the bottom screen's off: nothing to show until it's on
                val waited = android.os.SystemClock.elapsedRealtime() - started
                if (crashed || (waited >= MIN_WAIT_MS && memoryCalm(am)) || waited >= MAX_WAIT_MS) {
                    val secs = (System.currentTimeMillis() - stopped) / 1000
                    p.edit().putString("note", "$closed; WalkThor was back ${secs}s later.").putLong("lastBack", System.currentTimeMillis()).apply()
                    reopen(context, drawerWasUp)
                } else handler.postDelayed(this, CHECK_EVERY_MS)
            }
        }
        handler.postDelayed(check, if (crashed) CRASH_WAIT_MS else MIN_WAIT_MS)
    }

    /** Opened from the service itself (as a swipe does), which Android allows from the background. */
    private fun reopen(context: android.accessibilityservice.AccessibilityService, inDrawer: Boolean) {
        // Back in the drawer if that's where it was. Never the app in its place: opening the app
        // takes the front from the game, which your frontend reacts to.
        if (inDrawer) {
            val bottom = DisplayHelper.bottomDisplay(context)?.displayId ?: return
            runCatching { Drawer.show(context, bottom, opened = true) }
            return
        }
        openApp(context)
    }

    private fun openApp(context: Context) {
        run {
            val bottom = DisplayHelper.bottomDisplay(context)?.displayId ?: return
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(DisplayHelper.EXTRA_RELAUNCHED, true)
                // Like a swipe: go straight to the running game's Now playing.
                .putExtra(EXTRA_FROM_SWIPE, true)
                // Coming back, not opening fresh: no startup animation, and a quicker hand-back.
                .putExtra(EXTRA_REVIVED, true)
            runCatching { context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(bottom).toBundle()) }
        }
    }
}
