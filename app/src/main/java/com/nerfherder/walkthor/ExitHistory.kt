package com.nerfherder.walkthor

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every time WalkThor's app closed and why, as Android reports it (low memory, a crash, not
 * responding, closed by you…), with how much memory WalkThor was using and what it was doing just
 * before (game running, Mirror, clips, captions). Kept on the device only, for Diagnostics, so a
 * pattern behind WalkThor closing can be found and reported.
 */
object ExitHistory {
    private const val PREFS = "exit_history"
    private const val KEEP = 25

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What WalkThor is doing right now, noted every few seconds while it's open. */
    fun snapshot(context: Context) {
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { runCatching { am?.getMemoryInfo(it) } }
        val doing = buildList {
            add(if (GameFocus.gameRunning.value) "game running" else "no game")
            com.nerfherder.walkthor.ui.PlaySession.current.value?.title?.let { add("\"$it\"") }
            if (Mirror.projection.value != null) add("screen capture on")
            if (com.nerfherder.walkthor.data.Clips.recording.value) add("recording a clip")
            else if (com.nerfherder.walkthor.data.Clips.buffering.value) add("keeping clips")
            if (com.nerfherder.walkthor.data.Captions.on.value) add("captions on")
            if (GameFocus.yielding.value) add("yielding focus")
            add("heap ${heapMb} MB, native ${nativeMb} MB")
            add("device free ${mem.availMem / (1024 * 1024)} MB${if (mem.lowMemory) " (low)" else ""}")
        }.joinToString(", ")
        prefs(context).edit().putLong("snapAt", System.currentTimeMillis()).putString("snap", doing).apply()
    }

    /** Adds any closes Android has reported since last time (on each start). */
    fun collect(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val p = prefs(context)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 16) }.getOrNull()
            ?.filter { it.processName == context.packageName } ?: return
        val last = p.getLong("lastAt", 0L)
        val fresh = exits.filter { it.timestamp > last }.sortedBy { it.timestamp }
        if (fresh.isEmpty()) return
        val list = load(context).toMutableList()
        val snapAt = p.getLong("snapAt", 0L)
        val snap = p.getString("snap", null)
        fresh.forEachIndexed { i, e ->
            list += JSONObject().apply {
                put("at", e.timestamp)
                put("reason", reasonName(e.reason))
                put("detail", e.description ?: "")
                put("importance", importanceName(e.importance))
                put("pssMb", e.pss / 1024)
                put("rssMb", e.rss / 1024)
                // What it was doing: the last note, if it was taken just before this close.
                if (i == fresh.lastIndex && snap != null && e.timestamp - snapAt in 0..60_000) put("doing", snap)
            }
        }
        val trimmed = list.sortedBy { it.optLong("at") }.takeLast(KEEP)
        p.edit().putString("list", JSONArray(trimmed).toString()).putLong("lastAt", fresh.last().timestamp).apply()
    }

    fun load(context: Context): List<JSONObject> = runCatching {
        val a = JSONArray(prefs(context).getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())

    fun clear(context: Context) = prefs(context).edit().remove("list").apply()

    /** One line per close, newest first. */
    fun lines(context: Context): List<String> {
        val fmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
        return load(context).sortedByDescending { it.optLong("at") }.map { o ->
            buildString {
                append(fmt.format(Date(o.optLong("at"))))
                append(" · ").append(o.optString("reason"))
                o.optString("detail").takeIf { it.isNotBlank() }?.let { append(" (").append(it.take(80)).append(")") }
                append(" · ").append(o.optString("importance"))
                append(" · using ").append(o.optLong("pssMb")).append(" MB")
                o.optString("doing").takeIf { it.isNotBlank() }?.let { append("\n   ").append(it) }
            }
        }
    }

    fun share(context: Context) {
        val text = buildString {
            appendLine("WalkThor ${BuildConfig.VERSION_NAME}: recent closes")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            lines(context).forEach { appendLine(it) }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "WalkThor recent closes")
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching { context.startActivity(Intent.createChooser(send, "Share recent closes").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "closed by Android for memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native)"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_EXIT_SELF -> "closed itself"
        ApplicationExitInfo.REASON_SIGNALED -> "stopped by a signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by you"
        ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "something it depends on stopped"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "updated"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "unknown ($r)"
    }

    private fun importanceName(i: Int) = when {
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "in the background (service)"
        else -> "in the background"
    }
}
