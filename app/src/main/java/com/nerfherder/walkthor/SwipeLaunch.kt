package com.nerfherder.walkthor

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * "Swipe down from the top of the bottom screen to open WalkThor."
 *
 * Android only lets an app see touches outside its own window through an overlay, so a thin,
 * invisible strip is laid along the top edge of the Thor's bottom display (needs "Display over
 * other apps"). A foreground service keeps it alive; it's restarted after reboot and app updates.
 */
object SwipeLauncher {
    private const val PREFS = "swipe"
    private const val KEY_ENABLED = "enabled"

    /** The user's choice (the toggle), independent of whether permission is granted yet. */
    val enabled = MutableStateFlow(false)
    /** True when the toggle is on but "Display over other apps" hasn't been allowed. */
    val needsPermission = MutableStateFlow(false)

    fun init(context: Context) {
        enabled.value = prefs(context).getBoolean(KEY_ENABLED, false)
        needsPermission.value = enabled.value && !Settings.canDrawOverlays(context)
    }

    fun canDrawOverlays(context: Context) = Settings.canDrawOverlays(context)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        enabled.value = on
        sync(context)
    }

    /** Opens the system screen where WalkThor can be allowed to display over other apps. */
    fun requestPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Starts or stops the gesture service to match the toggle and the permission. */
    fun sync(context: Context) {
        val on = prefs(context).getBoolean(KEY_ENABLED, false)
        val allowed = Settings.canDrawOverlays(context)
        enabled.value = on
        needsPermission.value = on && !allowed
        val intent = Intent(context, SwipeLaunchService::class.java)
        if (on && allowed && DisplayHelper.bottomDisplay(context) != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }
        } else {
            context.stopService(intent)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class SwipeLaunchService : Service() {

    private var strip: View? = null
    private var windowManager: WindowManager? = null
    private var attachedDisplay = -1
    private val handler = Handler(Looper.getMainLooper())

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reattach()
        override fun onDisplayRemoved(displayId: Int) = reattach()
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == attachedDisplay) reattach()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, handler)
        attach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF) {
            SwipeLauncher.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (strip == null) attach()
        return START_STICKY
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        detach()
        super.onDestroy()
    }

    private fun reattach() {
        handler.removeCallbacksAndMessages(null)
        // Displays report several changes in a row while waking; settle first.
        handler.postDelayed({ detach(); attach() }, 400)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach() {
        if (strip != null || !Settings.canDrawOverlays(this)) return
        val display = DisplayHelper.bottomDisplay(this) ?: return
        val displayContext = createDisplayContext(display)
        val ctx = if (Build.VERSION.SDK_INT >= 30) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else displayContext
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val density = ctx.resources.displayMetrics.density
        val stripHeight = (STRIP_DP * density).toInt()
        val trigger = TRIGGER_DP * density

        val view = View(ctx)
        var startY = 0f
        var fired = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; fired = false }
                MotionEvent.ACTION_MOVE -> if (!fired && e.rawY - startY > trigger) {
                    fired = true
                    openWalkThor(display.displayId)
                }
            }
            true
        }

        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            stripHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

    private fun openWalkThor(displayId: Int) {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            .putExtra(DisplayHelper.EXTRA_RELAUNCHED, true)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        runCatching { startActivity(intent, options.toBundle()) }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Swipe to open", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps the swipe-down gesture on the bottom screen active"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val off = PendingIntent.getService(
            this, 1,
            Intent(this, SwipeLaunchService::class.java).setAction(ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Swipe down to open WalkThor")
            .setContentText("From the top edge of the bottom screen")
            .setContentIntent(open)
            .addAction(0, "Turn off", off)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL = "swipe"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_TURN_OFF = "walkthor.swipe.OFF"
        /** Height of the invisible touch strip; thin so it rarely gets in the way of other apps. */
        private const val STRIP_DP = 12f
        /** How far the finger has to travel down before WalkThor opens. */
        private const val TRIGGER_DP = 36f
    }
}

/** Brings the gesture back after a reboot or an app update. */
class SwipeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> SwipeLauncher.sync(context)
        }
    }
}
