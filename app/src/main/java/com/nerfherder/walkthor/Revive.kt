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
    /** Never sooner than this after its service comes back: the game is usually still loading. */
    private const val MIN_WAIT_MS = 10_000L
    /** How often memory is checked while waiting. */
    private const val CHECK_EVERY_MS = 2_000L
    /** Memory must look fine this many checks in a row. */
    private const val CALM_CHECKS = 4
    /** Gives up (stays closed) if memory hasn't recovered by then. */
    private const val GIVE_UP_MS = 3 * 60_000L
    /** Free memory, beyond Android's low-memory line, that WalkThor wants before it reopens. */
    private const val SPARE_BYTES = 768L * 1024 * 1024
    /** At most this many reopens in [LIMIT_WINDOW_MS]. */
    private const val MAX_REOPENS = 3
    private const val LIMIT_WINDOW_MS = 10 * 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** WalkThor is up during a game (called every few seconds while it's on screen). */
    fun heartbeat(context: Context) {
        prefs(context).edit().putLong("seen", System.currentTimeMillis()).apply()
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

    /** Called by the accessibility service ([context]) when it starts, including after Android restarts it. */
    fun onServiceStart(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val p = prefs(context)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exit = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrNull()
            ?.firstOrNull { it.processName == context.packageName } ?: return
        if (exit.timestamp <= p.getLong("handled", 0L)) return
        p.edit().putLong("handled", exit.timestamp).apply()

        val why = when (exit.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "to free memory for the game"
            ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_CRASH -> "after a crash"
            ApplicationExitInfo.REASON_ANR -> "because it stopped responding"
            else -> return // closed by you, by an update, or by a restart: leave it closed
        }
        if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || exit.reason == ApplicationExitInfo.REASON_ANR) {
            CrashLog.recordExit(context, exit)
        }
        // Stopped responding: reopening it could just do the same again, so leave it closed.
        if (exit.reason == ApplicationExitInfo.REASON_ANR) {
            val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(exit.timestamp))
            p.edit().putString("note", "Android closed WalkThor $why at $at. A report was saved (About & licences).").apply()
            return
        }

        if (!enabled(context)) {
            p.edit().putString("note", "Android closed WalkThor $why at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(exit.timestamp))}. Reopening is switched off in Settings, so it stayed closed.").apply()
            return
        }
        // Only when it was showing during a game right up to being closed, and only just now.
        val seen = p.getLong("seen", 0L)
        val now = System.currentTimeMillis()
        val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(exit.timestamp))
        if (seen == 0L || exit.timestamp - seen > 30_000L || now - exit.timestamp > 3 * 60_000L) {
            p.edit().putString("note", "Android closed WalkThor $why at $at (not during a game, so it stayed closed).").apply()
            return
        }
        // Don't loop if Android keeps closing it.
        val recent = (p.getString("reopens", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it < LIMIT_WINDOW_MS }
        if (recent.size >= MAX_REOPENS) {
            p.edit().putString("note", "Android closed WalkThor $why at $at. It had already reopened $MAX_REOPENS times in 10 minutes, so it stayed closed.").apply()
            return
        }
        p.edit()
            .putString("reopens", (recent + now).joinToString(","))
            .putString("note", "Android closed WalkThor $why at $at during a game; waiting for memory to recover before reopening.")
            .apply()

        // Wait until the device has memory to spare again (and the game has had time to load).
        val handler = Handler(Looper.getMainLooper())
        val started = android.os.SystemClock.elapsedRealtime()
        var calm = 0
        val check = object : Runnable {
            override fun run() {
                if (AppState.activity?.get() != null) return // already back (e.g. you swiped it open)
                val waited = android.os.SystemClock.elapsedRealtime() - started
                calm = if (memoryCalm(am)) calm + 1 else 0
                when {
                    waited >= MIN_WAIT_MS && calm >= CALM_CHECKS -> {
                        val secs = waited / 1000
                        p.edit().putString("note", "Android closed WalkThor $why at $at during a game; WalkThor reopened itself ${secs}s later, once memory had recovered.").apply()
                        reopen(context)
                    }
                    waited >= GIVE_UP_MS ->
                        p.edit().putString("note", "Android closed WalkThor $why at $at during a game. Memory stayed low, so it stayed closed to leave the game room. Swipe down to open it.").apply()
                    else -> handler.postDelayed(this, CHECK_EVERY_MS)
                }
            }
        }
        handler.postDelayed(check, CHECK_EVERY_MS)
    }

    /** Opened from the service itself (as a swipe does), which Android allows from the background. */
    private fun reopen(context: Context) {
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
