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
            // Ask to be started again in a moment. Android only restarts WalkThor's service after
            // a delay that grows with each crash, but as soon as WalkThor's process is running
            // again it restarts the service straight away; this brings that moment forward.
            runCatching { ComeBack.schedule(app) }
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

/**
 * Started by an alarm just after a crash: starting it starts WalkThor's process again, and Android
 * then restarts its accessibility service (and so the drawer and the AYN button) right away
 * instead of after its own wait. It does nothing else.
 */
class ComeBack : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}

    companion object {
        private fun pending(context: Context, code: Int) = android.app.PendingIntent.getBroadcast(
            context, code, Intent(context, ComeBack::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Start WalkThor again in a moment (just before going down in a crash, or from the keeper). */
        fun schedule(context: Context, afterMs: Long = 700) {
            val am = context.getSystemService(android.app.AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + afterMs, pending(context, 0))
        }

        /**
         * The standing alarm: WalkThor keeps pushing it a little further into the future for as
         * long as it's running. If Android closes WalkThor, nothing pushes it back, it goes off,
         * and that starts WalkThor again. It never wakes a sleeping device.
         */
        fun arm(context: Context, afterMs: Long) {
            val am = context.getSystemService(android.app.AlarmManager::class.java) ?: return
            val at = android.os.SystemClock.elapsedRealtime() + afterMs
            val exact = android.os.Build.VERSION.SDK_INT < 31 || runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
            runCatching {
                if (exact) am.setExact(android.app.AlarmManager.ELAPSED_REALTIME, at, pending(context, 1))
                else am.set(android.app.AlarmManager.ELAPSED_REALTIME, at, pending(context, 1))
            }
        }

        fun disarm(context: Context) {
            runCatching { context.getSystemService(android.app.AlarmManager::class.java)?.cancel(pending(context, 1)) }
        }
    }
}

/**
 * A tiny second process whose only job is to notice WalkThor going. WalkThor's service holds a
 * connection to it; if Android closes WalkThor, that connection drops, and this starts WalkThor
 * again straight away (which makes Android restart its service, the drawer and the AYN button,
 * at once rather than after its own wait). It holds nothing and draws nothing.
 */
class Keeper : android.app.Service() {
    override fun onCreate() {
        super.onCreate()
        Guard.start(applicationContext)
    }

    override fun onBind(intent: Intent?): android.os.IBinder = android.os.Binder()

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { sendBroadcast(Intent(this, ComeBack::class.java)) }
        runCatching { ComeBack.schedule(this, 1_000) }
        return false
    }
}

/**
 * Keeper's second job: while a game is in front, once a second, keeps the home app, WalkThor and
 * Keeper itself ranked just below the game for Android's memory killer (through AYN's system
 * helper, Force SELinux off). WalkThor does this too, but in a burst of memory kills WalkThor is
 * often closed as well; this carries on meanwhile, so the home app isn't closed again each time
 * it starts. Only ever lowers a rank. What it changes is noted for WalkThor's memory log.
 */
private object Guard {
    @Volatile private var thread: Thread? = null
    private val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    fun start(c: Context) {
        if (thread?.isAlive == true) return
        thread = Thread({
            var helperChecked = 0L
            var helper = false
            var ticks = 0
            status(c, "running, no game in front")
            while (true) {
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { return@Thread }
                ticks++
                runCatching {
                    val flag = MemoryWatch.guardFile(c)
                    if (!flag.exists()) { if (ticks % 10 == 0) status(c, "running, no game in front"); return@runCatching }
                    // Every 3 seconds: each look starts a shell through the helper, and ranks don't
                    // drift that fast.
                    if (ticks % 3 != 0) return@runCatching
                    // Whether the helper's usable: checked now and then, not every second.
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - helperChecked > 30_000) { helper = AynSystem.available(); helperChecked = now }
                    if (!helper) {
                        if (ticks % 5 == 0) status(c, "running, but can't use AYN's system helper (${AynSystem.problem ?: "not usable"})")
                        return@runCatching
                    }
                    val text = flag.readText().trim()
                    // Memory's critical: the game comes first, so it stands aside.
                    if (text.startsWith("yield")) { if (ticks % 5 == 0) status(c, "standing aside: memory is critical, the game comes first"); return@runCatching }
                    // (Keeper used to set ranks here; that doesn't move the memory killer: see MemoryWatch.protect.)
                    if (ticks % 9 == 0) status(c, "running during the game (ranks are left to Android)")
                }
            }
        }, "WalkThor keeper guard").apply { isDaemon = true; start() }
    }

    private fun name(c: Context, pkg: String) = when (pkg) {
        c.packageName -> "WalkThor"
        "keeper" -> "Keeper"
        else -> runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun status(c: Context, text: String) = runCatching {
        MemoryWatch.guardStatusFile(c).writeText("${clock.format(java.util.Date())} $text")
    }

    private fun note(c: Context, text: String) = runCatching {
        val f = MemoryWatch.guardLog(c)
        val lines = (runCatching { f.readLines() }.getOrDefault(emptyList()) + "${clock.format(java.util.Date())} $text").takeLast(40)
        f.writeText(lines.joinToString("\n") + "\n")
    }
}
