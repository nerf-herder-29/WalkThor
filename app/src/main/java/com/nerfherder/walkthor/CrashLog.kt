package com.nerfherder.walkthor

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a copy of the last crash on this device, so it can be shared for a bug report from
 * About & licences. Nothing is sent anywhere automatically (WalkThor only goes online to scrape artwork).
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                File(app.filesDir, FILE).writeText(
                    buildString {
                        appendLine("WalkThor $version crash report")
                        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        appendLine("Thread: ${thread.name}")
                        appendLine()
                        append(trace)
                    }
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Saves what Android reports about a crash in native code (which the handler above can't catch). */
    fun recordExit(context: Context, exit: android.app.ApplicationExitInfo) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val trace = exit.traceInputStream?.use { it.readBytes().decodeToString().take(60_000) }
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            File(context.filesDir, FILE).writeText(
                buildString {
                    appendLine("WalkThor $version " + if (exit.reason == android.app.ApplicationExitInfo.REASON_ANR) "not-responding report" else "crash report (native)")
                    appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(exit.timestamp))}")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Reason: ${exit.description ?: exit.reason}")
                    appendLine()
                    append(trace ?: "(no trace)")
                }
            )
        }
    }

    fun file(context: Context): File? = File(context.filesDir, FILE).takeIf { it.exists() }

    /** Opens the share sheet with the report as plain text. */
    fun share(context: Context) {
        val text = file(context)?.readText() ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "WalkThor crash report")
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching {
            context.startActivity(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun clear(context: Context) { file(context)?.delete() }
}
