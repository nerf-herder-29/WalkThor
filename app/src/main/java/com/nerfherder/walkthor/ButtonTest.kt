package com.nerfherder.walkthor

import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Diagnostics → Button test: for 30 seconds, notes which button presses reach WalkThor (the
 * AYN button especially) and which windows open, so we can tell whether WalkThor could open from
 * the AYN button. Every press is passed straight on; nothing is kept after the test.
 */
object ButtonTest {
    private const val LENGTH_MS = 30_000L
    @Volatile private var until = 0L
    private var startedAt = 0L

    /** What was seen, oldest first. */
    val seen = MutableStateFlow<List<String>>(emptyList())
    val running = MutableStateFlow(false)
    /** Why the test couldn't run, if it couldn't. */
    val problem = MutableStateFlow<String?>(null)

    val active get() = SystemClock.elapsedRealtime() < until

    fun start() {
        problem.value = null
        seen.value = emptyList()
        val service = SwipeGestureService.instance?.get()
        if (service == null) { problem.value = "Needs WalkThor's accessibility access switched on."; return }
        startedAt = SystemClock.elapsedRealtime()
        until = startedAt + LENGTH_MS
        running.value = true
        if (!service.setKeyWatch(true)) {
            problem.value = "Android hasn't given WalkThor button access yet: switch WalkThor off and on in Accessibility settings, then try again."
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stop() }, LENGTH_MS)
    }

    fun stop() {
        if (!running.value) return
        until = 0L
        running.value = false
        // Leave button watching on only if the Controls screen still wants it.
        SwipeGestureService.instance?.get()?.setKeyWatch(com.nerfherder.walkthor.controls.ControllerInput.watching.value)
        if (seen.value.isEmpty()) note("Nothing reached WalkThor.")
    }

    private fun at() = "%4.1fs".format((SystemClock.elapsedRealtime() - startedAt) / 1000f)

    private fun note(line: String) { seen.value = (seen.value + line).takeLast(60) }

    fun onKey(e: KeyEvent) {
        if (!active) return
        val action = when (e.action) { KeyEvent.ACTION_DOWN -> if (e.repeatCount > 0) "held" else "down"; KeyEvent.ACTION_UP -> "up"; else -> "other" }
        if (e.repeatCount > 1) return
        note("${at()} button ${KeyEvent.keyCodeToString(e.keyCode)} (code ${e.keyCode}, scan ${e.scanCode}) $action · ${e.device?.name ?: "device ${e.deviceId}"} · source 0x${Integer.toHexString(e.source)}")
    }

    fun onWindow(e: AccessibilityEvent) {
        if (!active || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val display = if (android.os.Build.VERSION.SDK_INT >= 30) " on screen ${e.displayId}" else ""
        note("${at()} window ${e.packageName}/${e.className?.toString()?.substringAfterLast('.')}$display")
    }

    fun report(): String = buildString {
        appendLine("WalkThor ${BuildConfig.VERSION_NAME} button test")
        appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        appendLine()
        seen.value.forEach { appendLine(it) }
        AynButton.log.value.takeIf { it.isNotEmpty() }?.let { appendLine(); it.forEach { l -> appendLine(l) } }
        Drawer.problem.value?.let { appendLine(); appendLine(it) }
        SwipeGestureService.instance?.get()?.let { s -> appendLine(); runCatching { ScreenControls.report(s) }.getOrNull()?.forEach { appendLine(it) } }
        // What AYN's drawer app offers, in case WalkThor can open it (say, on a long press).
        val ctx = SwipeGestureService.instance?.get() ?: return@buildString
        val pm = ctx.packageManager
        val pkg = "com.odin.dualscreen.assistant"
        runCatching {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES or android.content.pm.PackageManager.GET_SERVICES or android.content.pm.PackageManager.GET_RECEIVERS)
            appendLine()
            appendLine("$pkg (exported):")
            info.activities?.filter { it.exported }?.forEach { appendLine("  activity ${it.name}") }
            info.services?.filter { it.exported }?.forEach { appendLine("  service ${it.name}") }
            info.receivers?.filter { it.exported }?.forEach { appendLine("  receiver ${it.name}") }
        }.onFailure { appendLine(); appendLine("$pkg: not visible (${it.javaClass.simpleName})") }
    }
}
