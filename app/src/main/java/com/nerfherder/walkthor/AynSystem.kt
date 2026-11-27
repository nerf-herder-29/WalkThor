package com.nerfherder.walkthor

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The Thor's own system helper, which its firmware runs from boot to power AYN's "Run Script as
 * Root" feature (and AYN's drawer). WalkThor uses it for one thing only: each screen's
 * brightness, AYN's own settings, and closing your open apps when you ask, all of which Android
 * reserves for system apps. Every command WalkThor sends is built here from values it has
 * checked itself (numbers, AYN's setting names, app package names), never from free text.
 *
 * The helper can only be reached while the Thor's "Force SELinux" setting is off (how it ships);
 * if it can't be reached, WalkThor dims the bottom screen instead.
 */
object AynSystem {
    private const val SERVICE = "PServerBinder"
    private const val RUN = 0

    @Volatile private var binder: IBinder? = null
    @Volatile private var works: Boolean? = null
    @Volatile private var lastTry = 0L
    /** Why it isn't available, for Diagnostics. */
    @Volatile var problem: String? = null

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor brightness").apply { isDaemon = true } }

    private fun service(): IBinder? {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        return runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/os/ServiceManager;")
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, SERVICE) as? IBinder
        }.onFailure { problem = "couldn't reach AYN's system helper (${it.javaClass.simpleName})" }
            .getOrNull()?.also { binder = it }
    }

    /** Runs [command] through the helper; its first line of output, or null. Not on the main thread. */
    private fun run(command: String): String? {
        val b = service() ?: run { if (problem == null) problem = "AYN's system helper isn't running"; return null }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(command, "0"))
            if (!b.transact(RUN, data, reply, 0)) null
            else reply.createByteArray()?.let { String(it).trim() }
        } catch (t: Throwable) {
            problem = "AYN's system helper refused (${t.javaClass.simpleName}; is Force SELinux on?)"
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Runs [command] as the system and gives back the first line it printed; null if the helper isn't usable. Not on the main thread. */
    fun shell(command: String): String? = if (works == false) null else run(command)

    /**
     * Runs [command] as the system and gives back every line it printed (errors included), split
     * into lines. Null if the helper isn't usable. Not on the main thread.
     */
    fun shellLines(command: String): List<String>? {
        if (works == false) return null
        // AYN's helper only hands back the first line of what a command prints. So the command
        // writes everything to a file in WalkThor's own storage instead (readable to it), and
        // WalkThor reads that. (Before, every multi-line answer here was cut to its first line.)
        val path = "$OUT_DIR/helper-${android.os.Process.myPid()}-${outSeq.incrementAndGet()}.txt"
        run("( $command ) >$path 2>&1; chmod 644 $path") ?: return null
        val file = java.io.File(path)
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        if (text == null) { problem = "couldn't read what AYN's system helper wrote"; return emptyList() }
        return text.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
    }

    /**
     * Starts joining the Joy-Cons whose raw files are [left] and [right] (/dev/hidrawN) into one
     * controller ([com.nerfherder.walkthor.controls.JoyConJoinTool]), in the background. Not on the main thread.
     */
    fun joinJoyCons(context: Context, left: String, right: String, range: Int = 1700): Boolean {
        val raw = Regex("^/dev/hidraw\\d+$")
        if (!raw.matches(left) || !raw.matches(right)) return false
        val apk = context.applicationInfo.sourceDir
        if ("'" in apk) return false
        // The helper cuts off long commands, so the start-up goes in a little script in
        // WalkThor's own files, and the helper is asked only to run that.
        val log = "$OUT_DIR/joycons.log"
        val script = java.io.File(context.filesDir, "joycons.sh")
        runCatching {
            script.writeText(
                "pkill -f '[J]oyConJoinTool'\n" +
                    "cd /\n" +
                    "CLASSPATH='$apk' setsid app_process /system/bin ${com.nerfherder.walkthor.controls.JoyConJoinTool::class.java.name} $left $right ${range.coerceIn(900, 2600)} </dev/null >$log 2>&1 &\n" +
                    "sleep 1\n" +
                    "chmod 644 $log\n",
            )
            script.setReadable(true, false)
        }.onFailure { return false }
        return run("sh $OUT_DIR/joycons.sh") != null
    }

    /** Stops joining the Joy-Cons (they go back to being two controllers). Not on the main thread. */
    fun separateJoyCons() { run("pkill -f '[J]oyConJoinTool'") }

    /** What the joining program last said ("OK", or why it stopped), for the Controllers screen. */
    fun joyConsLog(): String? = shellLines("cat $OUT_DIR/joycons.log; echo running: \$(pgrep -f '[J]oyConJoinTool' | head -1)")
        ?.joinToString(" / ")?.ifBlank { null }

    /** WalkThor's own files folder (the helper, as the system, can write there; WalkThor can read). */
    private val OUT_DIR = "/data/data/${BuildConfig.APPLICATION_ID}/files"
    private val outSeq = java.util.concurrent.atomic.AtomicInteger()

    /** Whether the helper works here (checked once it succeeds; retried now and then if not). Not on the main thread. */
    fun available(): Boolean {
        works?.let { if (it) return true }
        val now = android.os.SystemClock.uptimeMillis()
        if (works == false && now - lastTry < 15_000) return false
        lastTry = now
        val ok = run("id")?.contains("uid=0") == true
        works = ok
        if (ok) problem = null
        return ok
    }

    /**
     * AYN's settings WalkThor may change through the helper: the stick lights', and the performance
     * mode, fan mode and controller layout (as AYN's own drawer sets them).
     */
    private val SETTINGS = setOf(
        "joystick_light_enabled", "joystick_led_light_picker_color", "led_light_brightness_percent",
        "performance_mode", "fan_mode", "temp_abxy_layout_mode", "flip_button_layout", "no_create_gamepad_button_layout",
        "secondary_screen_volume_level",
    )

    /**
     * The performance mode (0 Standard, 1 Medium, 2 High). AYN applies it through a system property
     * as well as the setting, and puts the property back from the setting when apps change, so both
     * are written. Not on the main thread.
     */
    fun setPerformance(mode: Int): Boolean {
        if (mode !in 0..2 || !available()) return false
        return run("settings put system performance_mode $mode; setprop persist.vendor.debug.mode $mode") != null
    }

    /**
     * Sets one of AYN's own system settings (the stick lights'), which Android lets only system
     * apps change. Only the names above, and only values made of digits, letters, '#', ',' and '.'.
     * Not on the main thread. Returns whether it went through.
     */
    fun putSetting(name: String, value: String): Boolean {
        if (name !in SETTINGS || !value.all { it.isLetterOrDigit() || it in "#,." }) return false
        if (!available()) return false
        return run("settings put system $name '$value'") != null
    }

    /**
     * The packages with a task in Android's recent apps (what you have open). Not on the main thread.
     */
    fun openApps(): List<String>? {
        if (!available()) return null
        val out = run("dumpsys activity recents | grep -oE 'A=[0-9]+:[A-Za-z0-9_.]+' | sort -u") ?: return null
        return out.lines().mapNotNull { it.substringAfter(':', "").trim().takeIf { p -> PACKAGE.matches(p) } }.distinct()
    }

    /**
     * The cards in Android's recent apps: each one's task number and app. Home's own card is left
     * out. Not on the main thread.
     */
    fun recentTasks(): List<Pair<Int, String>>? {
        if (!available()) return null
        val out = run("dumpsys activity recents | grep 'Recent #'") ?: return null
        return out.lines().mapNotNull { line ->
            val m = RECENT.find(line) ?: return@mapNotNull null
            if (m.groupValues[2] == "home") return@mapNotNull null
            val pkg = m.groupValues[3].takeIf { PACKAGE.matches(it) } ?: return@mapNotNull null
            (m.groupValues[1].toIntOrNull() ?: return@mapNotNull null) to pkg
        }
    }

    /** "… Task{1a2b3c #123 type=standard A=10161:com.example.app …}" (or I=com.example.app/.Main). */
    private val RECENT = Regex("""#(\d+) type=(\w+) (?:A=\d+:|I=)([A-Za-z0-9_.]+)""")

    /**
     * Takes every card out of recent apps except those of [keep]. What the tool said ("OK 3 3":
     * removed, found), or null. Not on the main thread.
     */
    fun clearRecents(context: Context, keep: Set<String>): String? {
        if (!available()) return null
        val apk = context.applicationInfo.sourceDir
        if ("'" in apk) return null
        val names = keep.filter { PACKAGE.matches(it) }.joinToString(" ")
        return run("CLASSPATH='$apk' app_process /system/bin ${RecentsTool::class.java.name} clear $names 2>&1")?.lines()?.lastOrNull { it.startsWith("OK") || it.startsWith("ERR") }
    }

    /**
     * Gives the controller to display [displayId]: makes the app on top there the one Android
     * sends button presses to, without touching anything. "OK …" or "ERR …"; null if the helper
     * isn't usable. Not on the main thread.
     */
    fun focusDisplay(context: Context, displayId: Int): String? {
        if (!available()) return null
        val apk = context.applicationInfo.sourceDir
        if ("'" in apk) return null
        return run("CLASSPATH='$apk' app_process /system/bin ${FocusTool::class.java.name} $displayId 2>&1")
            ?.lines()?.lastOrNull { it.startsWith("OK") || it.startsWith("ERR") }
    }

    /** Takes card [taskId] out of recent apps. Not on the main thread. */
    fun removeTask(taskId: Int): Boolean {
        if (!available()) return false
        return run("am stack remove $taskId >/dev/null 2>&1 || am task remove $taskId >/dev/null 2>&1; echo done") != null
    }

    /** Switches Android to the keyboard [id] ("package/class"), turning it on first. Not on the main thread. */
    fun setKeyboard(id: String): Boolean {
        if (!Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(id) || !available()) return false
        val out = run("ime enable '$id' >/dev/null 2>&1; ime set '$id' 2>&1") ?: return false
        return "selected" in out
    }

    /** The names of every running process (an app's main process is named after its package). Not on the main thread. */
    fun runningNames(): Set<String>? {
        if (!available()) return null
        val out = run("ps -A -o NAME") ?: return null
        return out.lines().map { it.trim().substringBefore(':') }.filter { PACKAGE.matches(it) }.toSet()
    }

    /** Closes [pkg] completely, as "Force stop" in Android's settings does. Not on the main thread. */
    fun forceStop(pkg: String): Boolean {
        if (!PACKAGE.matches(pkg) || !available()) return false
        // Asks Android to stop it; if any of it is still running a moment later, ends those
        // processes directly. True only once nothing of it is left running.
        val left = run(
            "am force-stop $pkg >/dev/null 2>&1; sleep 0.4; " +
                "for p in \$(pidof $pkg); do kill -9 \$p 2>/dev/null; done; sleep 0.2; pidof $pkg; echo done"
        ) ?: return false
        // Anything before "done" is a process of it that's still running (a number); a stray note
        // from kill, its process already gone, isn't.
        return left.lines().map { it.trim() }.none { l -> l.isNotEmpty() && l.all { it.isDigit() || it == ' ' } }
    }

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /** Known without waiting: true if the helper has worked, false if it has failed, null if not tried. */
    fun knownAvailable(): Boolean? = works

    /** Runs WalkThor's brightness tool (in [ScreenBrightnessTool]) with numbers only. */
    private fun tool(context: Context, vararg args: String): String? {
        require(args.all { a -> a.all { it.isLetterOrDigit() || it == '.' } }) // numbers and get/set only
        val apk = context.applicationInfo.sourceDir
        if ("'" in apk) return null
        return run("CLASSPATH='$apk' app_process /system/bin ${ScreenBrightnessTool::class.java.name} ${args.joinToString(" ")} 2>/dev/null")
    }

    /** Each screen's brightness as last read or set, so it needn't be asked for again mid-game. */
    private val lastLevel = java.util.concurrent.ConcurrentHashMap<Int, Float>()

    /**
     * [displayId]'s brightness, 0–1, or null. Not on the main thread. Asking starts a small Java
     * program through the helper (tens of MB, for a moment), so while a game is playing the last
     * known level is used instead (null if there isn't one: the caller falls back).
     */
    fun brightness(context: Context, displayId: Int): Float? {
        if (EmulatorWatch.gameShowing.value) return lastLevel[displayId]
        if (!available()) return null
        return tool(context, "get", displayId.toString())?.takeIf { it.startsWith("OK ") }?.removePrefix("OK ")?.trim()?.toFloatOrNull()
            ?.also { lastLevel[displayId] = it }
    }

    // ---- the live brightness program: kept running while the brightness controls are on screen ----

    /** How many brightness controls are on screen (the program runs while any is). */
    private val liveUsers = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var liveOut: java.io.FileDescriptor? = null
    /** The live program couldn't be reached this time the controls are open: don't keep trying. */
    @Volatile private var liveFailed = false
    /** How the bottom screen's brightness is being set, for Diagnostics. */
    @Volatile var liveNote: String = "not used yet"

    private fun fifo(context: Context) = java.io.File(context.filesDir, "bright.fifo")

    /**
     * A brightness control came on screen: start the live program (in the background), so the
     * first drag is smooth. Not while a game is playing: the program is a second Java runtime
     * (tens of MB), and the drawer coming up as a game loads is the worst moment for it. Then it
     * starts on the first drag instead (see sendLive).
     */
    fun liveStart(context: Context) {
        val app = context.applicationContext
        if (liveUsers.incrementAndGet() == 1) {
            liveFailed = false
            if (!EmulatorWatch.gameShowing.value) worker.execute { runCatching { openLive(app) } }
        }
    }

    /** A brightness control left the screen: the program stops when none are left. */
    fun liveStop() {
        if (liveUsers.decrementAndGet() > 0) return
        liveUsers.set(0)
        worker.execute { closeLive(sendQuit = true) }
    }

    /**
     * Connects to the live program, starting it if it isn't running. Levels go to it through a
     * pipe in WalkThor's private storage, which no other app can reach. Worker thread only.
     */
    private fun openLive(context: Context): Boolean {
        liveOut?.let { return true }
        if (liveFailed || !available()) return false
        val pipe = fifo(context)
        if (!pipe.exists()) runCatching { android.system.Os.mkfifo(pipe.absolutePath, 0x180 /* 0600 */) }
        fun tryOpen(): java.io.FileDescriptor? = runCatching {
            android.system.Os.open(pipe.absolutePath, android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_NONBLOCK, 0)
        }.getOrNull()
        tryOpen()?.let { liveOut = it; return true }
        // Not running: start it, then wait for it to open its end of the pipe.
        val apk = context.applicationInfo.sourceDir
        val path = pipe.absolutePath
        if ("'" in apk || "'" in path) return false
        // Fully detached (its own session, no ties to the helper's shell), so it keeps running
        // after the helper's command finishes.
        run("(CLASSPATH='$apk' setsid app_process /system/bin ${ScreenBrightnessTool::class.java.name} serve '$path' </dev/null >/dev/null 2>&1 &)")
        val until = android.os.SystemClock.uptimeMillis() + 2_500
        while (android.os.SystemClock.uptimeMillis() < until) {
            tryOpen()?.let { liveOut = it; liveNote = "live (smooth)"; return true }
            Thread.sleep(60)
        }
        // Couldn't reach it: one change at a time for now (slower, but it works).
        liveFailed = true
        liveNote = "one change at a time (the live program didn't start)"
        return false
    }

    private fun closeLive(sendQuit: Boolean) {
        val fd = liveOut ?: return
        liveOut = null
        if (sendQuit) runCatching { android.system.Os.write(fd, "q\n".toByteArray(), 0, 2) }
        runCatching { android.system.Os.close(fd) }
    }

    /** Sends one level to the live program; false if it isn't running. Worker thread only. */
    private fun sendLive(context: Context, displayId: Int, level: Float): Boolean {
        if (liveUsers.get() <= 0) return false
        if (liveOut == null && !openLive(context)) return false
        val line = "s $displayId ${"%.4f".format(java.util.Locale.US, level)}\n".toByteArray()
        return runCatching { android.system.Os.write(liveOut!!, line, 0, line.size); true }
            .getOrElse { closeLive(sendQuit = false); false } // it quit (idle): the next level tries to restart it
    }

    /** The level waiting to be applied, per screen (one each, so neither screen's change is lost). */
    private val pendingLevels = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    private val draining = java.util.concurrent.atomic.AtomicBoolean(false)
    private val retry = android.os.Handler(android.os.Looper.getMainLooper())
    /** Right after a critical memory warning: no new programs started through the helper for this long. */
    private const val CRITICAL_HOLD_MS = 10_000L

    /**
     * Sets [displayId]'s brightness (0–1) in the background. While a slider moves, only the latest
     * level for each screen is applied, so the screen keeps up instead of working through every step.
     */
    fun setBrightness(context: Context, displayId: Int, level: Float) {
        val app = context.applicationContext
        val l = level.coerceIn(0f, 1f)
        lastLevel[displayId] = l
        pendingLevels[displayId] = l
        drain(app)
    }

    private fun drain(app: Context) {
        if (!draining.compareAndSet(false, true)) return // already working through them
        worker.execute {
            try {
                while (true) {
                    // Just after Android's critical memory warning, nothing new is started through
                    // the helper (that's what froze the device once): the levels wait a moment and
                    // are applied after, rather than dropped (which let the screens drift apart).
                    if (liveOut == null && MemoryWatch.msSinceCritical() < CRITICAL_HOLD_MS) {
                        retry.postDelayed({ drain(app) }, 2_000)
                        return@execute
                    }
                    val e = pendingLevels.entries.firstOrNull() ?: break
                    if (!pendingLevels.remove(e.key, e.value)) continue // a newer level came in: take that
                    if (!available()) { pendingLevels.clear(); break }
                    // Straight to the live program if it's running (or can start); otherwise one at a time.
                    if (!sendLive(app, e.key, e.value) && !sendLive(app, e.key, e.value)) {
                        tool(app, "set", e.key.toString(), "%.4f".format(java.util.Locale.US, e.value))
                        if (liveUsers.get() <= 0) liveNote = "one change at a time"
                    }
                }
            } finally {
                draining.set(false)
            }
            // One that came in just as it finished.
            if (pendingLevels.isNotEmpty()) drain(app)
        }
    }
}

/**
 * Run as `app_process` by AYN's system helper (with system rights): reads or sets one screen's
 * brightness through Android's per-screen brightness control, as AYN's drawer does.
 * `get <screen>` prints "OK <0–1>"; `set <screen> <0–1>` prints "OK".
 */
object ScreenBrightnessTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val dm = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
            if (args.getOrNull(0) == "serve") { serve(dm, args.getOrNull(1) ?: return); return }
            val display = args.getOrNull(1)?.toIntOrNull() ?: run { println("ERR usage"); return }
            when (args.getOrNull(0)) {
                "get" -> {
                    val info = dm.javaClass.getMethod("getBrightnessInfo", Int::class.javaPrimitiveType).invoke(dm, display)
                    val level = info?.javaClass?.getField("brightness")?.getFloat(info)
                    println(if (level != null) "OK $level" else "ERR none")
                }
                "set" -> {
                    val level = args.getOrNull(2)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: run { println("ERR usage"); return }
                    dm.javaClass.getMethod("setBrightness", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType).invoke(dm, display, level)
                    println("OK")
                }
                else -> println("ERR usage")
            }
        } catch (t: Throwable) {
            println("ERR ${(t.cause ?: t).javaClass.simpleName}")
        } finally {
            System.out.flush()
            System.exit(0)
        }
    }

    /**
     * The live program: reads "s <screen> <0–1>" lines from WalkThor's pipe and sets each at
     * once; "q" ends it. Ends by itself after half a minute without a level.
     */
    private fun serve(dm: Any, pipe: String) {
        val set = dm.javaClass.getMethod("setBrightness", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        val last = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        Thread {
            while (true) {
                Thread.sleep(2_000)
                if (System.currentTimeMillis() - last.get() > 30_000) { System.exit(0) }
            }
        }.apply { isDaemon = true; start() }
        while (true) {
            // Each time WalkThor reconnects, the pipe opens again.
            java.io.File(pipe).bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    last.set(System.currentTimeMillis())
                    val p = line.trim().split(' ')
                    when {
                        p.firstOrNull() == "q" -> System.exit(0)
                        p.size == 3 && p[0] == "s" -> {
                            val d = p[1].toIntOrNull() ?: continue
                            val v = p[2].toFloatOrNull()?.coerceIn(0f, 1f) ?: continue
                            runCatching { set.invoke(dm, d, v) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Run by AYN's helper as a system program: takes every card out of Android's recent apps except
 * those of the apps named (only system programs may). Prints "OK <removed> <found>" or "ERR …".
 */
/**
 * Run as the system by [AynSystem.focusDisplay]: moves Android's focus (where button presses go)
 * to the top app on one display. Prints "OK <task>" or "ERR <why>".
 */
object FocusTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
            println(focus(atm, args[0].toInt()))
        } catch (t: Throwable) {
            println("ERR ${(t.cause ?: t).javaClass.simpleName}: ${(t.cause ?: t).message?.take(80)}")
        } finally {
            System.out.flush()
            System.exit(0)
        }
    }

    private val int = Int::class.javaPrimitiveType

    /** Gives [displayId]'s top app the focus (and with it the controller). "OK <task>" or "ERR …". */
    private fun focus(atm: Any, displayId: Int): String {
        // The app on top on that display (Android 12+ calls them root tasks, before that stacks).
        @Suppress("UNCHECKED_CAST")
        val infos = (runCatching { atm.javaClass.getMethod("getAllRootTaskInfosOnDisplay", int).invoke(atm, displayId) }
            .recoverCatching { atm.javaClass.getMethod("getAllStackInfosOnDisplay", int).invoke(atm, displayId) }
            .getOrThrow() as? List<Any?>).orEmpty().filterNotNull()
        fun bool(o: Any, vararg names: String) = names.firstNotNullOfOrNull { n -> runCatching { o.javaClass.getField(n).getBoolean(o) }.getOrNull() }
        fun id(o: Any) = runCatching { o.javaClass.getField("taskId").getInt(o) }.getOrNull()
            ?: runCatching { o.javaClass.getField("stackId").getInt(o) }.getOrNull()
        val top = infos.firstOrNull { bool(it, "visible", "isVisible") == true } ?: infos.firstOrNull()
        // Android 12+ can give a display's top app the focus in one go (and bring that display to
        // the front for input); older ones, or if that's missing, focus the app itself.
        if (runCatching { atm.javaClass.getMethod("focusTopTask", int).invoke(atm, displayId) }.isSuccess) return "OK top task on $displayId"
        val task = top?.let(::id) ?: return "ERR nothing on display $displayId"
        val done = listOf("setFocusedRootTask", "setFocusedStack", "setFocusedTask").firstOrNull { m ->
            runCatching { atm.javaClass.getMethod(m, int).invoke(atm, task) }.isSuccess
        }
        return if (done != null) "OK $task ($done)" else "ERR couldn't focus $task"
    }
}

object RecentsTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val keep = args.drop(1).toSet()
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
            val int = Int::class.javaPrimitiveType
            val found: List<Any?> = try {
                val slice = atm.javaClass.getMethod("getRecentTasks", int, int, int).invoke(atm, 100, 0, 0)!!
                @Suppress("UNCHECKED_CAST")
                slice.javaClass.getMethod("getList").invoke(slice) as List<Any?>
            } catch (t: Throwable) {
                // Can't list them one by one here: clear the lot instead.
                atm.javaClass.getMethod("removeAllVisibleRecentTasks").invoke(atm)
                println("OK all")
                return
            }
            val remove = atm.javaClass.getMethod("removeTask", int)
            var removed = 0
            var cards = 0
            for (info in found) {
                info ?: continue
                val id = info.javaClass.getField("taskId").getInt(info)
                val intent = info.javaClass.getField("baseIntent").get(info) as? android.content.Intent
                val pkg = intent?.component?.packageName ?: intent?.`package`
                if (pkg != null && pkg in keep) continue
                cards++
                if (runCatching { remove.invoke(atm, id) }.isSuccess) removed++
            }
            println("OK $removed $cards")
        } catch (t: Throwable) {
            println("ERR ${(t.cause ?: t).javaClass.simpleName}: ${(t.cause ?: t).message?.take(80)}")
        } finally {
            System.out.flush()
            System.exit(0)
        }
    }
}
