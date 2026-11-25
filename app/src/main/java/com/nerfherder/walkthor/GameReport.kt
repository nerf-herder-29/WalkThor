package com.nerfherder.walkthor

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A report of how a game ended, from the device's own records, saved a moment after each game
 * closes (only then: never while one plays). For telling what really closed a game: Android's
 * memory killer, the Linux kernel's own out-of-memory killer, the graphics driver, a crash in the
 * emulator, or something closing it on purpose, and what WalkThor was doing around then.
 *
 * Read through AYN's system helper (Force SELinux off). Kept on the device until you share it;
 * the last three are kept.
 */
object GameReport {
    private fun file(c: Context, n: Int = 0) = File(c.filesDir, if (n == 0) "game-report.txt" else "game-report-$n.txt")

    fun exists(c: Context) = file(c).exists()

    /** Captures the report for [pkg]'s game, [why] it's being taken. Not on the main thread. */
    fun capture(c: Context, pkg: String?, why: String) {
        val app = c.applicationContext
        Thread({
            runCatching {
                Thread.sleep(2_500) // a moment for Android to write its records
                if (AynSystem.knownAvailable() != true && !AynSystem.available()) return@runCatching
                val text = build(app, pkg, why)
                // Keep the last three.
                runCatching { file(app, 1).takeIf { it.exists() }?.renameTo(file(app, 2)) }
                runCatching { file(app).takeIf { it.exists() }?.renameTo(file(app, 1)) }
                file(app).writeText(text)
                MemoryWatch.noteFrom(app, "Game report saved (Diagnostics → Share game report)")
            }
        }, "WalkThor game report").apply { isDaemon = true }.start()
    }

    private fun section(sb: StringBuilder, title: String, cmd: String) {
        sb.appendLine().appendLine("== $title ==")
        val out = runCatching { AynSystem.shellLines(cmd) }.getOrNull()
        when {
            out == null -> sb.appendLine("(couldn't read)")
            out.none { it.isNotBlank() } -> sb.appendLine("(nothing)")
            else -> out.forEach { sb.appendLine(it) }
        }
    }

    private fun build(c: Context, pkg: String?, why: String): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.appendLine("WalkThor ${BuildConfig.VERSION_NAME}: game report, $now")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Game app: ${pkg ?: "unknown"} · taken because: $why")
        val safe = pkg?.takeIf { Regex("[A-Za-z0-9_.]+").matches(it) }
        if (safe != null) {
            section(sb, "Android's record of how the game's app ended (newest first)", "dumpsys activity exit-info $safe | head -70")
        }
        section(sb, "Apps killed, died, started or crashed (Android's event log, last few minutes)",
            "logcat -d -b events -t 6000 | grep -E 'am_kill|am_proc_died|am_proc_start|am_low_memory|am_crash|am_anr|am_wtf|am_process_start_timeout' | tail -90")
        section(sb, "Memory killer and crash lines (system log)",
            "logcat -d -b main,system -t 9000 | grep -iE 'lowmemorykiller|lmkd|ActivityManager: (Kill|Process .* has died)|Out of memory|kgsl|F DEBUG|Abort message|Fatal signal|SIGSEGV|SIGABRT|SIGKILL|vulkan.*(fail|lost)|DEVICE_LOST|GL_OUT_OF_MEMORY' | tail -140")
        section(sb, "Crash log", "logcat -d -b crash | tail -80")
        section(sb, "Kernel log (its own out-of-memory killer, the graphics driver)",
            "dmesg | grep -iE 'lowmem|oom|out of memory|killed process|kgsl|page allocation failure|ion_|dma_buf|psi' | tail -70")
        section(sb, "Memory now", "cat /proc/pressure/memory; grep -E '^(MemTotal|MemFree|MemAvailable|Cached|SwapTotal|SwapFree|Active|Inactive|KReclaimable|Shmem|CmaTotal|CmaFree)' /proc/meminfo")
        section(sb, "Memory killer settings", "getprop | grep -E 'lmk|lowmemorykiller|zram|swap' | head -40")
        sb.appendLine().appendLine("== WalkThor's memory log (last 80 lines) ==")
        MemoryWatch.logged().takeLast(80).forEach { sb.appendLine(it) }
        sb.appendLine().appendLine("== WalkThor's recent closes ==")
        runCatching { ExitHistory.lines(c).take(10).forEach { sb.appendLine(it) } }
        return sb.toString()
    }

    fun share(c: Context) {
        val text = runCatching { file(c).readText() }.getOrNull() ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "WalkThor game report")
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching { c.startActivity(Intent.createChooser(send, "Share game report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
