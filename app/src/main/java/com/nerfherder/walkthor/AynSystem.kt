package com.nerfherder.walkthor

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The Thor's own system helper, which its firmware runs from boot to power AYN's "Run Script as
 * Root" feature (and AYN's drawer). WalkThor uses it for one thing only: each screen's
 * brightness, which Android reserves for system apps. Every command WalkThor sends is built here
 * from numbers it worked out itself (a screen number and a level), never from text from
 * anywhere else.
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

    /** Known without waiting: true if the helper has worked, false if it has failed, null if not tried. */
    fun knownAvailable(): Boolean? = works

    /** Runs WalkThor's brightness tool (in [ScreenBrightnessTool]) with numbers only. */
    private fun tool(context: Context, vararg args: String): String? {
        require(args.all { a -> a.all { it.isLetterOrDigit() || it == '.' } }) // numbers and get/set only
        val apk = context.applicationInfo.sourceDir
        if ("'" in apk) return null
        return run("CLASSPATH='$apk' app_process /system/bin ${ScreenBrightnessTool::class.java.name} ${args.joinToString(" ")} 2>/dev/null")
    }

    /** [displayId]'s brightness, 0–1, or null. Not on the main thread. */
    fun brightness(context: Context, displayId: Int): Float? {
        if (!available()) return null
        return tool(context, "get", displayId.toString())?.takeIf { it.startsWith("OK ") }?.removePrefix("OK ")?.trim()?.toFloatOrNull()
    }

    private val pending = AtomicReference<Pair<Int, Float>?>(null)

    /**
     * Sets [displayId]'s brightness (0–1) in the background. While a slider moves, only the latest
     * level is applied, so the screen keeps up instead of working through every step.
     */
    fun setBrightness(context: Context, displayId: Int, level: Float) {
        val app = context.applicationContext
        if (pending.getAndSet(displayId to level.coerceIn(0f, 1f)) != null) return // one's already queued
        worker.execute {
            val next = pending.getAndSet(null) ?: return@execute
            if (available()) tool(app, "set", next.first.toString(), "%.4f".format(java.util.Locale.US, next.second))
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
            val display = args.getOrNull(1)?.toIntOrNull() ?: run { println("ERR usage"); return }
            val dm = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
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
}
