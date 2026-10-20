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
 * a game, it reopens WalkThor on the bottom screen once the game has had a moment to finish
 * loading, and WalkThor then hands the game its priority back as usual. It never reopens WalkThor
 * after you closed it yourself, and gives up after a few tries so it can't loop.
 */
object Revive {
    private const val PREFS = "revive"
    /** WalkThor reopens this long after the service comes back, so the game can finish loading. */
    private const val REOPEN_DELAY_MS = 2_000L
    /** At most this many reopens in [LIMIT_WINDOW_MS]. */
    private const val MAX_REOPENS = 5
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
        if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) CrashLog.recordExit(context, exit)

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
            .putString("note", "Android closed WalkThor $why at $at during a game; WalkThor reopened itself on the bottom screen.")
            .apply()

        // Opened from the service itself (as a swipe does), which Android allows from the background.
        Handler(Looper.getMainLooper()).postDelayed({
            if (AppState.activity?.get() != null) return@postDelayed // already back (e.g. you swiped it open)
            val bottom = DisplayHelper.bottomDisplay(context)?.displayId ?: return@postDelayed
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(DisplayHelper.EXTRA_RELAUNCHED, true)
                // Like a swipe: go straight to the running game's Now playing.
                .putExtra(EXTRA_FROM_SWIPE, true)
            runCatching { context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(bottom).toBundle()) }
        }, REOPEN_DELAY_MS)
    }
}
