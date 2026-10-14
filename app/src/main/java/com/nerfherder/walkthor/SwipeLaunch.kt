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
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference

/**
 * "Swipe down from the top of the bottom screen to open WalkThor."
 *
 * Android blocks ordinary apps from bringing themselves to the front from the background;
 * accessibility services are allowed to, so the gesture lives in a small accessibility service
 * that lays a thin invisible strip along the top edge of the Thor's bottom screen.
 * The service reads no screen content; swiping again while WalkThor is showing puts it away.
 */
object SwipeLauncher {
    const val PREFS = "swipe"
    const val KEY_ENABLED = "enabled"

    /** The user's choice (the toggle). */
    val enabled = MutableStateFlow(false)
    /** True when the toggle is on but WalkThor's accessibility service hasn't been switched on. */
    val needsPermission = MutableStateFlow(false)

    fun init(context: Context) = refresh(context)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        refresh(context)
    }

    /** Re-reads the settings and whether the service is on (e.g. after a trip to Settings). */
    fun refresh(context: Context) {
        enabled.value = prefs(context).getBoolean(KEY_ENABLED, false)
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

class SwipeGestureService : AccessibilityService() {

    private var strip: View? = null
    private var windowManager: WindowManager? = null
    private var attachedDisplay = -1
    private val handler = Handler(Looper.getMainLooper())

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        SwipeLauncher.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        detach()
        super.onDestroy()
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
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            .putExtra(DisplayHelper.EXTRA_RELAUNCHED, true)
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
