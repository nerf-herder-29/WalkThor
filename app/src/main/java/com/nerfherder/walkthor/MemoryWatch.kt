package com.nerfherder.walkthor

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Memory during games, for finding out why the bottom screen flashes or its app crashes in heavy
 * games. While one of your emulators is in front, it notes the free memory every few seconds,
 * and, where AYN's system helper is usable (Force SELinux off), when the bottom screen's apps
 * (your home app, whatever's in front there, and WalkThor) are closed by Android and start
 * again, with Android's reason. Kept in a small file, so it survives WalkThor itself being
 * closed. About & licences → Diagnostics shows it and can share it.
 *
 * It also makes WalkThor leaner while you play: when a game comes up it lets go of cached pages
 * and images, and the drawer is fully taken down when it's been put away for a while (it's
 * built again the next time you open it).
 */
object MemoryWatch {
    private const val MAX_LINES = 400
    private const val PLAY_EVERY_MS = 3_000L
    private const val IDLE_EVERY_MS = 10_000L
    /** How long the drawer stays built while put away during a game. */
    const val DRAWER_KEEP_MS = 30_000L

    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()
    @Volatile private var thread: Thread? = null
    /** Each watched app's process id as last seen ("" when it wasn't running). */
    private val pids = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var lastFreeMb = -1L
    private var lastWrite = 0L
    private var beats = 0

    // What the last game looked like, for the summary.
    @Volatile private var gameSince = 0L
    @Volatile private var lowestMb = -1L
    @Volatile private var lowCount = 0
    @Volatile private var restarts = 0

    private fun file(c: Context) = File(c.filesDir, "memory-log.txt")

    /** Starts watching (from the accessibility service; it runs as long as that does). */
    fun start(context: Context) {
        if (thread?.isAlive == true) return
        val app = context.applicationContext
        load(app)
        thread = Thread({
            while (true) {
                val playing = gameInFront()
                runCatching { if (playing) sample(app) }
                try { Thread.sleep(if (playing) PLAY_EVERY_MS else IDLE_EVERY_MS) } catch (_: InterruptedException) { return@Thread }
            }
        }, "WalkThor memory watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /** A game came to the front, or went: notes it, and lets go of what WalkThor can rebuild. */
    fun gameChanged(context: Context, playing: Boolean) {
        val app = context.applicationContext
        if (playing) {
            gameSince = System.currentTimeMillis()
            lowestMb = -1L; lowCount = 0; restarts = 0
            pids.clear()
            val mi = memInfo(app)
            note(app, "Game started · free ${mb(mi.availMem)} of ${gb(mi.totalMem)}")
            trim(app, "game started")
        } else if (gameSince > 0) {
            note(app, "Game closed · ${summaryOfGame()}")
        }
    }

    /** Android asked for memory back (from the service's onTrimMemory). */
    fun trimRequested(context: Context, level: Int) {
        @Suppress("DEPRECATION")
        val serious = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        if (gameInFront()) note(context, "Android is short of memory (level $level)")
        if (serious || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) trim(context, null)
    }

    /** Lets go of cached guide pages and images. */
    private fun trim(context: Context, why: String?) {
        runCatching { com.nerfherder.walkthor.data.PdfSource.trimAll() }
        runCatching { coil.Coil.imageLoader(context).memoryCache?.clear() }
        if (why != null) Runtime.getRuntime().gc()
    }

    private fun memInfo(c: Context) = ActivityManager.MemoryInfo().also {
        c.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it)
    }

    private fun mb(bytes: Long) = "${bytes / (1024 * 1024)} MB"
    private fun gb(bytes: Long) = "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))

    private fun sample(c: Context) {
        val mi = memInfo(c)
        val free = mi.availMem / (1024 * 1024)
        if (lowestMb < 0 || free < lowestMb) lowestMb = free
        if (mi.lowMemory) lowCount++
        beats++
        // Free memory: every 15 s, or straight away when it moves a lot or runs low.
        val moved = lastFreeMb < 0 || kotlin.math.abs(free - lastFreeMb) >= 150
        if (moved || mi.lowMemory || beats % 5 == 0) {
            val pressure = runCatching {
                File("/proc/pressure/memory").readLines().firstOrNull()
                    ?.substringAfter("avg10=")?.substringBefore(' ')?.let { " · pressure $it%" }
            }.getOrNull() ?: ""
            val own = if (beats % 10 == 0) runCatching {
                c.getSystemService(ActivityManager::class.java)
                    ?.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))?.firstOrNull()
                    ?.totalPss?.let { " · WalkThor ${it / 1024} MB" }
            }.getOrNull() ?: "" else ""
            note(c, "Free ${free} MB" + (if (mi.lowMemory) " (LOW: Android is closing apps)" else "") + pressure + own)
            lastFreeMb = free
        }
        watchApps(c)
    }

    /** The bottom screen's apps: notes when one is closed and starts again, and Android's reason. */
    private fun watchApps(c: Context) {
        if (AynSystem.knownAvailable() != true) return
        val bottom = runCatching { DisplayHelper.bottomDisplay(c)?.displayId }.getOrNull()
        val home = runCatching {
            c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        }.getOrNull()
        val watched = linkedSetOf<String>().apply {
            home?.takeIf { it != "android" }?.let(::add)
            bottom?.let { EmulatorWatch.frontOn(it) }?.takeIf { it != c.packageName && it != "com.android.systemui" }?.let(::add)
            add(c.packageName)
        }
        for (pkg in watched) {
            val now = AynSystem.shell("pidof $pkg")?.trim()?.takeIf { it.isNotEmpty() }
            val seen = pids[pkg]
            val before = seen?.takeIf { it.isNotEmpty() }
            if (seen != null && before != now) {
                val name = label(c, pkg)
                when {
                    now == null -> note(c, "$name stopped running${reason(pkg)}")
                    before == null -> { restarts++; note(c, "$name started again") }
                    else -> { restarts++; note(c, "$name was closed and started again${reason(pkg)}") }
                }
            }
            pids[pkg] = now ?: ""
        }
    }

    /** Why Android last closed [pkg], from its own records. */
    private fun reason(pkg: String): String = runCatching {
        val out = AynSystem.shell("dumpsys activity exit-info $pkg") ?: return ""
        val r = Regex("reason=(\\d+)\\s*\\(([^)]*)\\)").find(out)?.groupValues?.get(2)
            ?: Regex("reason=([A-Z_]+)").find(out)?.groupValues?.get(1)
        val d = Regex("description=([^\\n]*)").find(out)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        if (r == null) "" else " (Android: ${r.lowercase().replace('_', ' ')}${d?.let { ", $it" } ?: ""})"
    }.getOrDefault("")

    private fun label(c: Context, pkg: String): String = when (pkg) {
        c.packageName -> "WalkThor"
        else -> runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun summaryOfGame(): String = buildList {
        val mins = ((System.currentTimeMillis() - gameSince) / 60_000).coerceAtLeast(0)
        add("$mins min")
        if (lowestMb >= 0) add("lowest free $lowestMb MB")
        if (lowCount > 0) add("low on memory $lowCount×")
        add(if (restarts == 0) "no app restarts" else "$restarts app restart${if (restarts == 1) "" else "s"}")
    }.joinToString(", ")

    /** The drawer was taken down while put away during a game. */
    fun noteDrawerTakenDown() {
        SwipeGestureService.instance?.get()?.let { note(it, "Drawer put away for a while: taken down to save memory") }
    }

    /** A short line for Diagnostics: the current or last game. */
    fun summary(): String? = if (gameSince == 0L) null else
        (if (gameInFront()) "This game so far: " else "Last game: ") + summaryOfGame()

    @Synchronized private fun note(c: Context, text: String) {
        val line = "${time.format(Date())}  $text"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        // Written out now and then (and always for anything but a plain reading), so it survives a close.
        val now = System.currentTimeMillis()
        if (!text.startsWith("Free") || now - lastWrite > 30_000) {
            lastWrite = now
            runCatching { file(c).writeText(lines.joinToString("\n")) }
        }
    }

    @Synchronized private fun load(c: Context) {
        if (lines.isNotEmpty()) return
        runCatching { file(c).takeIf { it.exists() }?.readLines()?.takeLast(MAX_LINES)?.forEach { lines.addLast(it) } }
        note(c, "WalkThor started")
    }

    @Synchronized fun logged(): List<String> = lines.toList()

    @Synchronized fun clear(c: Context) {
        lines.clear()
        runCatching { file(c).delete() }
    }

    fun share(c: Context) {
        val mi = memInfo(c)
        val text = buildString {
            appendLine("WalkThor ${BuildConfig.VERSION_NAME}: memory during games")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${gb(mi.totalMem)} memory")
            appendLine("System helper: " + when (AynSystem.knownAvailable()) { true -> "usable (app restarts are noted)"; false -> "not usable (Force SELinux is on), so app restarts aren't noted"; null -> "not checked yet" })
            summary()?.let { appendLine(it) }
            appendLine()
            logged().forEach { appendLine(it) }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "WalkThor memory log")
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching { c.startActivity(Intent.createChooser(send, "Share memory log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
