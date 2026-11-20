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
 * While you play, it also keeps the bottom screen's apps (your home app and WalkThor) next in line
 * after the game, rather than level with anything else that's visible, so Android's memory killer
 * takes them only after everything else (through the system helper; the game always stays safest).
 * And each time WalkThor starts, and as games end, it writes in why Android last closed the home
 * app and WalkThor, and the memory killer's own records of closing them.
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

    /** How high the bottom screen's apps may rank for the memory killer during a game (visible apps are 100; the game 0). */
    private const val PROTECT_ADJ = 50
    @Volatile private var protectedNoted = false

    // ---- the game comes first ----
    // When memory gets critical, the bottom screen's apps stop being protected, and WalkThor lets
    // go of what it can: Android may then close the bottom screen (a blink there) rather than the game.
    /** Standing aside for the game: memory is critical. */
    @Volatile var yielding = false
        private set
    @Volatile private var lastCritical = 0L
    /** Free memory under this, during a game, counts as critical. */
    private const val CRITICAL_MB = 1_500L
    /** Protection comes back once free memory is over this, with no critical warning for [CALM_MS]. */
    private const val RECOVERED_MB = 2_500L
    private const val CALM_MS = 20_000L

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
            protectedNoted = false
            yielding = false
            Thread({ setGuarding(app, true) }, "WalkThor guard").start()
            val mi = memInfo(app)
            note(app, "Game started · free ${mb(mi.availMem)} of ${gb(mi.totalMem)}" + swap())
            trim(app, "game started")
        } else if (gameSince > 0) {
            yielding = false
            Thread({ setGuarding(app, false) }, "WalkThor guard").start()
            note(app, "Game closed · ${summaryOfGame()}")
            Thread({ runCatching { closeRecords(app) } }, "WalkThor close records").start()
        }
    }

    /** Android asked for memory back (from the service's onTrimMemory). */
    fun trimRequested(context: Context, level: Int) {
        @Suppress("DEPRECATION")
        val serious = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        if (gameInFront()) note(context, "Android is short of memory (level $level)")
        // Its most serious warning, mid-game: the game comes first.
        @Suppress("DEPRECATION")
        // (Exactly that level: the higher ones only mean WalkThor's own screens are out of sight.)
        if (gameInFront() && level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            val app = context.applicationContext
            Thread({ runCatching { yieldToGame(app, "Android's most serious memory warning") } }, "WalkThor yield").start()
        }
        if (serious || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) trim(context, null)
    }

    /** Lets go of cached guide pages and images. */
    private fun trim(context: Context, why: String?) {
        runCatching { com.nerfherder.walkthor.data.PdfSource.trimAll() }
        runCatching { coil.Coil.imageLoader(context).memoryCache?.clear() }
        if (why != null) Runtime.getRuntime().gc()
    }

    /** " · swap used X of Y MB" when there's swap; "" without. */
    private fun swap(): String = runCatching {
        val m = File("/proc/meminfo").readLines().associate { l -> l.substringBefore(':') to (l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L) }
        val total = (m["SwapTotal"] ?: 0L) / 1024; val free = (m["SwapFree"] ?: 0L) / 1024
        if (total <= 0) "" else " · swap used ${total - free} of $total MB"
    }.getOrDefault("")

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
            note(c, "Free ${free} MB" + (if (mi.lowMemory) " (LOW: Android is closing apps)" else "") + swap() + pressure + own)
            lastFreeMb = free
        }
        if (free < CRITICAL_MB) yieldToGame(c, "free memory under ${CRITICAL_MB / 1000.0} GB")
        else if (yielding && free > RECOVERED_MB && System.currentTimeMillis() - lastCritical > CALM_MS) standBack(c, free)
        watchApps(c)
        protect(c)
        // Keeps Keeper's flag fresh (and right, if the game started before WalkThor came back).
        if (beats % 5 == 1) { if (!yielding) setGuarding(c, true); importGuard(c) }
        if (beats % 10 == 0) closeRecords(c)
    }

    /** The default home app's package (whatever you use: Cocoon, ES-DE, a launcher…); null if there's none. */
    private fun homePackage(c: Context): String? = runCatching {
        c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName?.takeIf { it != "android" }
    }.getOrNull()

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /**
     * Keeps the home app and WalkThor ranked just below the game for Android's memory killer
     * (Android re-ranks apps now and then, so it's put back every few seconds while you play).
     * Only ever lowers a rank, never raises one.
     */
    private fun protect(c: Context, always: Boolean = false) {
        if (AynSystem.knownAvailable() != true) return
        if (yielding) return
        val pkgs = listOfNotNull(if (always && !gameInFront()) null else homePackage(c), c.packageName)
            .filter { PACKAGE.matches(it) }.distinct()
        val out = AynSystem.shellLines(protectScript(pkgs)) ?: return
        val ranks = out.filter { it.startsWith("R ") }.map { it.split(' ') }.filter { it.size >= 5 }
        val changed = ranks.filter { it[3] != it[4] }
        val failed = ranks.filter { (it[4].toIntOrNull() ?: 0) > PROTECT_ADJ }
        if (failed.isNotEmpty() && !protectedNoted) {
            protectedNoted = true
            val errors = out.filterNot { it.startsWith("R ") }.take(2).joinToString("; ")
            note(c, "Couldn't keep " + failed.joinToString(" and ") { "${label(c, it[1])} (ranked ${it[4]})" } +
                " next in line" + (if (errors.isNotEmpty()) ": $errors" else ""))
        } else if (changed.isNotEmpty() && (!protectedNoted || always)) {
            protectedNoted = true
            note(c, "Kept " + changed.joinToString(" and ") { "${label(c, it[1])} (${it[3]} → ${it[4]})" } +
                " next in line after the game")
        }
    }

    /**
     * The command that lowers [pkgs]' processes (and any [pids]) to [PROTECT_ADJ] where they rank
     * higher, never raising one. Each line it prints: "R package process before after".
     */
    internal fun protectScript(pkgs: List<String>, pids: List<Int> = emptyList()): String {
        fun one(pkg: String, list: String) = "for p in $list; do a=\$(cat /proc/\$p/oom_score_adj); " +
            "if [ \"\$a\" -gt $PROTECT_ADJ ]; then echo $PROTECT_ADJ > /proc/\$p/oom_score_adj; fi; " +
            "echo \"R $pkg \$p \$a \$(cat /proc/\$p/oom_score_adj)\"; done;"
        return pkgs.filter { PACKAGE.matches(it) }.distinct().joinToString(" ") { one(it, "\$(pidof $it)") } +
            pids.joinToString("") { " " + one("keeper", it.toString()) }
    }

    /** Memory's critical: stops protecting the bottom screen's apps, so the game's kept instead. Not on the main thread. */
    @Synchronized private fun yieldToGame(c: Context, why: String) {
        lastCritical = System.currentTimeMillis()
        if (yielding) return
        yielding = true
        val home = homePackage(c)
        // Keeper stands aside too.
        runCatching { guardFile(c).writeText("yield:" + home.orEmpty()) }
        // Back to their usual rank, where WalkThor had lowered them (it only ever set 50).
        if (AynSystem.knownAvailable() == true) {
            val names = listOfNotNull(home, c.packageName, c.packageName + ":keeper").filter { n -> PACKAGE.matches(n.substringBefore(':')) }
            AynSystem.shellLines(names.joinToString(" ") { n ->
                "for p in \$(pidof $n); do if [ \"\$(cat /proc/\$p/oom_score_adj)\" -eq $PROTECT_ADJ ]; then echo 100 > /proc/\$p/oom_score_adj; fi; done;"
            } + " echo done")
        }
        // And WalkThor lets go of what it can.
        trim(c, "the game comes first")
        android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { Drawer.trimIfHidden() } }
        note(c, "Memory critical ($why): the game comes first. The bottom screen's apps aren't protected until it recovers")
    }

    /** Memory's recovered: back to protecting the bottom screen's apps. */
    @Synchronized private fun standBack(c: Context, freeMb: Long) {
        if (!yielding) return
        yielding = false
        protectedNoted = false
        setGuarding(c, true)
        note(c, "Memory recovered (free $freeMb MB): protecting the bottom screen's apps again")
        protect(c)
    }

    // ---- Keeper's share ----
    // Keeper (a tiny second process) carries on the protection while WalkThor's own process is
    // gone: in a burst of memory kills, WalkThor is often closed too, and the home app, started
    // again at its usual rank, would be the next one closed.

    /** Read by Keeper: present while a game is in front, holding the home app's package. */
    internal fun guardFile(c: Context) = File(c.filesDir, "guard-playing")
    /** Written by Keeper: what it did. */
    internal fun guardLog(c: Context) = File(c.filesDir, "guard-log.txt")
    /** Written by Keeper every few seconds: how it's doing. */
    internal fun guardStatusFile(c: Context) = File(c.filesDir, "guard-status.txt")

    /** Keeper's latest status, for Diagnostics (with how long ago it wrote it). */
    fun keeperStatus(c: Context): String {
        val f = guardStatusFile(c)
        if (!f.exists()) return "hasn't reported yet (it starts with WalkThor's accessibility service)"
        val ago = (System.currentTimeMillis() - f.lastModified()) / 1000
        val text = runCatching { f.readText().trim() }.getOrDefault("?")
        return "$text (${if (ago < 2) "just now" else "${ago} s ago"})" + if (ago > 30) ". It may not be running" else ""
    }

    private fun setGuarding(c: Context, on: Boolean) {
        runCatching { if (on) guardFile(c).writeText(homePackage(c).orEmpty()) else guardFile(c).delete() }
    }

    /** Puts what Keeper did into the log (once each). */
    private fun importGuard(c: Context) {
        val lines = runCatching { guardLog(c).readLines() }.getOrNull() ?: return
        val last = seen(c).getString("guardSeen", "") ?: ""
        val at = lines.lastIndexOf(last)
        val fresh = if (at >= 0) lines.drop(at + 1) else lines
        fresh.forEach { note(c, "Keeper: $it") }
        lines.lastOrNull()?.let { seen(c).edit().putString("guardSeen", it).apply() }
    }

    /** How the bottom screen's apps rank now, and how the memory killer is set up, for the log. */
    private fun report(c: Context) {
        if (AynSystem.knownAvailable() != true) { note(c, "System helper not usable: no ranks or records"); return }
        val pkgs = listOfNotNull(homePackage(c), c.packageName).filter { PACKAGE.matches(it) }.distinct()
        val ranks = AynSystem.shellLines(pkgs.joinToString(" ") { "for p in \$(pidof $it); do echo \"$it \$(cat /proc/\$p/oom_score_adj)\"; done;" })
            .orEmpty().mapNotNull { l -> l.split(' ').takeIf { it.size == 2 }?.let { "${label(c, it[0])} ${it[1]}" } }
        if (ranks.isNotEmpty()) note(c, "Ranks for the memory killer (lower is safer; the game is 0): " + ranks.joinToString(", "))
        val props = AynSystem.shellLines("getprop | grep -E 'lmk|lowmem' | head -20").orEmpty()
            .map { it.replace("[", "").replace("]", "") }.filter { ':' in it }
        if (props.isNotEmpty()) note(c, "Memory killer settings: " + props.joinToString("; "))
    }

    /** Where we've got to in Android's records, so each is written in once. */
    private fun seen(c: Context) = c.getSharedPreferences("memory_watch", Context.MODE_PRIVATE)

    /**
     * Writes in why Android last closed the home app and WalkThor (from Android's own records), and
     * the memory killer's records of closing them, if they're newer than what's already written.
     */
    private fun closeRecords(c: Context) {
        if (AynSystem.knownAvailable() != true) return
        val prefs = seen(c)
        val pkgs = listOfNotNull(homePackage(c), c.packageName).distinct()
        // Android's reason, per app.
        for (pkg in pkgs) runCatching {
            val out = AynSystem.shellLines("dumpsys activity exit-info $pkg | head -30")?.joinToString("\n") ?: return@runCatching
            val stamp = Regex("timestamp=(\\S+ \\S+)").find(out)?.groupValues?.get(1) ?: return@runCatching
            if (stamp <= (prefs.getString("exit.$pkg", "") ?: "")) return@runCatching
            prefs.edit().putString("exit.$pkg", stamp).apply()
            val first = out.substring(out.indexOf("timestamp=$stamp")).lineSequence().take(4).joinToString(" ")
            val why = Regex("reason=\\d+ \\(([A-Z_]+)\\)").find(first)?.groupValues?.get(1)?.lowercase()?.replace('_', ' ') ?: "unknown"
            val desc = Regex("description=([^\\n]*?)(?: state=| trace=|$)").find(first)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() && it != "null" }
            note(c, "Android's record: ${label(c, pkg)} closed at ${stamp.substringAfter(' ').substringBefore('.')} (${why}${desc?.let { ", $it" } ?: ""})")
        }
        // The memory killer's own record of each close (it says which rule it acted on).
        runCatching {
            val out = AynSystem.shellLines("logcat -d -b all -t 3000 | grep -E \"Kill '|killinfo\" | tail -40")?.joinToString("\n") ?: return@runCatching
            val last = prefs.getString("lmk", "") ?: ""
            var newest = last
            out.lines().filter { l -> l.contains("Kill '") && pkgs.any { l.contains("'$it'") || l.contains("'$it:") } }.forEach { l ->
                val stamp = l.take(18)
                if (stamp <= last) return@forEach
                if (stamp > newest) newest = stamp
                val name = pkgs.firstOrNull { l.contains("'$it") }?.let { label(c, it) } ?: "?"
                val adj = Regex("oom_score_adj (-?\\d+)").find(l)?.groupValues?.get(1)
                val reason = l.substringAfter("reason: ", "").trim().takeIf { it.isNotEmpty() }
                note(c, "Memory killer closed $name at ${stamp.substringAfter(' ').substringBefore('.')}" +
                    (adj?.let { " (ranked $it)" } ?: "") + (reason?.let { ": $it" } ?: ""))
            }
            if (newest != last) prefs.edit().putString("lmk", newest).apply()
        }
    }

    /** The bottom screen's apps: notes when one is closed and starts again, and Android's reason. */
    private fun watchApps(c: Context) {
        if (AynSystem.knownAvailable() != true) return
        val bottom = runCatching { DisplayHelper.bottomDisplay(c)?.displayId }.getOrNull()
        val home = homePackage(c)
        val watched = linkedSetOf<String>().apply {
            home?.let(::add)
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
        val out = AynSystem.shellLines("dumpsys activity exit-info $pkg | head -30")?.joinToString("\n") ?: return ""
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

    /** How long WalkThor waits to put things back on the screen after Android closed it for memory. */
    const val SETTLE_AFTER_KILL_MS = 10_000L

    /** Android closed WalkThor for memory within the last minute (it's in a squeeze, maybe a loop). */
    fun closedForMemoryJustNow(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val now = System.currentTimeMillis()
        val recent = runCatching {
            c.getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(c.packageName, 0, 5)
                ?.filter { it.processName == c.packageName && now - it.timestamp < 60_000 &&
                    it.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY }
        }.getOrNull().orEmpty()
        if (recent.isNotEmpty()) note(c, "Closed for memory ${recent.size}× in the last minute: the performance overlay waits ${SETTLE_AFTER_KILL_MS / 1000} s before coming back")
        return recent.isNotEmpty()
    }

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
        // Why it (and the home app) was last closed, once the system helper has been checked.
        Thread({
            runCatching { AynSystem.available() }
            // Closed while standing aside for the game: still standing aside, until memory recovers.
            runCatching {
                if (guardFile(c).takeIf { it.exists() }?.readText()?.startsWith("yield") == true) {
                    yielding = true
                    lastCritical = System.currentTimeMillis()
                }
            }
            // Straight away: a fresh WalkThor is the easiest thing for the killer to take again.
            runCatching { protectedNoted = false; protect(c, always = true) }
            // What Keeper did while WalkThor was closed.
            runCatching { importGuard(c) }
            runCatching { closeRecords(c) }
            runCatching { report(c) }
        }, "WalkThor close records").apply { isDaemon = true }.start()
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
