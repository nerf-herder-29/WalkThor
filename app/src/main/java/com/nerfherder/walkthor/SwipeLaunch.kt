package com.nerfherder.walkthor

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference

/**
 * "Swipe down from the top of the bottom screen to open WalkThor."
 *
 * Android blocks ordinary apps from bringing themselves to the front from the background;
 * accessibility services are allowed to, so the gesture lives in a small accessibility service
 * that lays a thin invisible strip along the top edge of the Thor's bottom screen.
 *
 * With "Open the playing game's guide" on, the service also reads the game name that the
 * launcher (Cocoon) shows — the game you just launched, or its Now Playing screen — and hands it
 * to WalkThor so the matching guide opens. It only ever looks at the launcher's own windows.
 */
object SwipeLauncher {
    const val PREFS = "swipe"
    const val KEY_ENABLED = "enabled"
    const val KEY_GAME_GUIDE = "gameGuide"

    /** The user's choice (the toggle). */
    val enabled = MutableStateFlow(false)
    /** True when the toggle is on but WalkThor's accessibility service hasn't been switched on. */
    val needsPermission = MutableStateFlow(false)
    /** Open the guide for the game that's playing when swiping in. */
    val gameGuide = MutableStateFlow(true)

    fun init(context: Context) = refresh(context)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        refresh(context)
    }

    fun setGameGuide(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_GAME_GUIDE, on).apply()
        refresh(context)
    }

    /** Re-reads the settings and whether the service is on (e.g. after a trip to Settings). */
    fun refresh(context: Context) {
        enabled.value = prefs(context).getBoolean(KEY_ENABLED, false)
        gameGuide.value = prefs(context).getBoolean(KEY_GAME_GUIDE, true)
        needsPermission.value = enabled.value && !isServiceOn(context)
    }

    fun isServiceOn(context: Context): Boolean {
        val me = ComponentName(context, SwipeGestureService::class.java)
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Opens Accessibility settings, where the user switches on "WalkThor swipe to open". */
    fun requestPermission(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Opens WalkThor's App info page (for Android's "Allow restricted settings" on sideloaded apps). */
    fun openAppInfo(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Where WalkThor's activity currently is, so the gesture can bring it to (or take it off) the right screen. */
object AppState {
    /** Display of the live activity, or null if none is running. */
    @Volatile var activityDisplayId: Int? = null
    /** Display WalkThor is currently in front on, or null when it's in the background. */
    @Volatile var resumedDisplayId: Int? = null
    /** Guide open in the reader, if any. */
    @Volatile var openBookId: String? = null
    @Volatile var activity: WeakReference<Activity>? = null

    /** Closes WalkThor so whatever was underneath shows again. */
    fun close() {
        activity?.get()?.finishAndRemoveTask()
    }
}

/** A piece of on-screen text that might be the playing game's name, with how much to trust it. */
const val EXTRA_GAME_HINTS = "walkthor.gameHints"

class SwipeGestureService : AccessibilityService() {

    private var strip: View? = null
    private var windowManager: WindowManager? = null
    private var attachedDisplay = -1
    private val handler = Handler(Looper.getMainLooper())

    // What was highlighted in the launcher, and the game it launched.
    private var launcherFocus: String? = null
    private var launcherFocusAt = 0L
    private var launchedGame: String? = null
    private var launchedAt = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SwipeLauncher.KEY_ENABLED) reattach(0)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reattach(400)
        override fun onDisplayRemoved(displayId: Int) = reattach(400)
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == attachedDisplay || attachedDisplay == -1) reattach(400)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        SwipeLauncher.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, handler)
        attach()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        SwipeLauncher.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        detach()
        super.onDestroy()
    }

    // ---- remembering what the launcher launched ------------------------------------------------

    private val launcherPackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pm = packageManager
        @Suppress("DEPRECATION")
        val homes = pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY).mapNotNull { it.activityInfo?.packageName }
        homes.filterNot { it == "com.android.settings" }.toSet()
    }

    private fun isLauncher(pkg: CharSequence?): Boolean {
        val p = pkg?.toString() ?: return false
        return "cocoon" in p.lowercase() || p in launcherPackages
    }

    private fun isSystemish(pkg: String) =
        pkg == packageName || pkg == "com.android.systemui" || pkg.startsWith("com.android.inputmethod") ||
            pkg.contains("keyboard", ignoreCase = true)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !SwipeLauncher.prefs(this).getBoolean(SwipeLauncher.KEY_GAME_GUIDE, true)) return
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (isLauncher(pkg)) {
                val label = labelOf(event)
                if (label != null) {
                    launcherFocus = label
                    launcherFocusAt = SystemClock.elapsedRealtime()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // The launcher handed over to another app (an emulator): what it had highlighted
                // a moment ago is the game that was launched.
                if (!isLauncher(pkg) && !isSystemish(pkg)) {
                    val focus = launcherFocus
                    if (focus != null && SystemClock.elapsedRealtime() - launcherFocusAt < 60_000) {
                        launchedGame = focus
                        launchedAt = SystemClock.elapsedRealtime()
                        launcherFocus = null
                    }
                }
            }
        }
    }

    private fun labelOf(event: AccessibilityEvent): String? {
        val fromEvent = event.text?.joinToString(" ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: event.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (fromEvent != null) return fromEvent.take(120)
        val src = event.source ?: return null
        return try {
            collectText(src, ArrayList(), 0, 40).firstOrNull()
        } finally {
            @Suppress("DEPRECATION") runCatching { src.recycle() }
        }
    }

    /** Texts under a node (depth-first), up to [limit] entries. */
    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int, limit: Int): MutableList<String> {
        if (out.size >= limit || depth > 30) return out
        val t = (node.text ?: node.contentDescription)?.toString()?.trim()
        if (!t.isNullOrEmpty() && t.length in 2..120) out += t
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectText(c, out, depth + 1, limit)
            @Suppress("DEPRECATION") runCatching { c.recycle() }
        }
        return out
    }

    /**
     * Candidate game names visible on the bottom screen right now (from the launcher only), plus
     * the game it launched recently, each as "weight|text".
     */
    private fun gameHints(bottomDisplayId: Int): ArrayList<String> {
        val hints = LinkedHashMap<String, Float>()
        fun add(text: String, w: Float) {
            val t = text.trim()
            if (t.length < 2 || t.all { !it.isLetter() }) return
            hints[t] = maxOf(hints[t] ?: 0f, w)
        }
        launchedGame?.let { if (SystemClock.elapsedRealtime() - launchedAt < 6 * 60 * 60 * 1000L) add(it, 1f) }

        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val windows = windowsOnAllDisplays.get(bottomDisplayId) ?: emptyList()
                for (w in windows) {
                    val root = w.root ?: continue
                    try {
                        if (!isLauncher(root.packageName)) continue
                        val texts = collectText(root, ArrayList(), 0, 80)
                        // A Now Playing screen shows a handful of texts; a full game grid shows many,
                        // and then only the highlighted one is a strong signal.
                        val visibleWeight = if (texts.size <= 15) 0.95f else 0.6f
                        texts.forEach { add(it, visibleWeight) }
                        focusedText(root)?.let { add(it, 1f) }
                    } finally {
                        @Suppress("DEPRECATION") runCatching { root.recycle() }
                    }
                }
            }
        }
        return ArrayList(hints.entries.take(60).map { "${it.value}|${it.key}" })
    }

    private fun focusedText(root: AccessibilityNodeInfo): String? {
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return null
        return try {
            collectText(node, ArrayList(), 0, 3).firstOrNull()
        } finally {
            @Suppress("DEPRECATION") runCatching { node.recycle() }
        }
    }

    // ---- the strip ------------------------------------------------------------------------------

    private fun reattach(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ detach(); attach() }, delayMs)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach() {
        if (strip != null || !SwipeLauncher.prefs(this).getBoolean(SwipeLauncher.KEY_ENABLED, false)) return
        val display = DisplayHelper.bottomDisplay(this) ?: return
        val ctx = if (Build.VERSION.SDK_INT >= 30) {
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        } else {
            // Before Android 11 accessibility overlays can only go on the main display.
            if (display.displayId != android.view.Display.DEFAULT_DISPLAY) return
            this
        }
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val density = ctx.resources.displayMetrics.density
        val trigger = TRIGGER_DP * density

        val view = View(ctx)
        var startY = 0f
        var fired = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; fired = false }
                MotionEvent.ACTION_MOVE -> if (!fired && e.rawY - startY > trigger) {
                    fired = true
                    onSwipe(display.displayId)
                }
            }
            true
        }

        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (STRIP_DP * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "WalkThor swipe strip"
        }
        runCatching {
            wm.addView(view, params)
            strip = view
            windowManager = wm
            attachedDisplay = display.displayId
        }
    }

    private fun detach() {
        strip?.let { v -> runCatching { windowManager?.removeView(v) } }
        strip = null
        windowManager = null
        attachedDisplay = -1
    }

    private fun onSwipe(bottomDisplayId: Int) {
        // Swiping again while WalkThor is up on the bottom screen puts it away.
        if (AppState.resumedDisplayId == bottomDisplayId) {
            AppState.close()
            return
        }
        val hints = if (SwipeLauncher.prefs(this).getBoolean(SwipeLauncher.KEY_GAME_GUIDE, true)) gameHints(bottomDisplayId) else arrayListOf()

        val runningOn = AppState.activityDisplayId
        val book = AppState.openBookId
        val elsewhere = runningOn != null && runningOn != bottomDisplayId
        if (elsewhere) {
            // WalkThor is open on the other screen; Android would bring it back up there, so close
            // that copy and reopen it on the bottom screen at the same guide.
            getSystemService(ActivityManager::class.java)?.appTasks?.forEach { runCatching { it.finishAndRemoveTask() } }
        }
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            // CLEAR_TOP + SINGLE_TOP: a running WalkThor gets the hints in onNewIntent.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(DisplayHelper.EXTRA_RELAUNCHED, true)
            .putStringArrayListExtra(EXTRA_GAME_HINTS, hints)
        if (elsewhere && book != null) intent.putExtra(DisplayHelper.EXTRA_BOOK, book)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(bottomDisplayId)
        runCatching { startActivity(intent, options.toBundle()) }
    }

    companion object {
        /** Height of the invisible touch strip; thin so it rarely gets in the way of other apps. */
        private const val STRIP_DP = 12f
        /** How far the finger has to travel down before WalkThor opens. */
        private const val TRIGGER_DP = 36f
    }
}
