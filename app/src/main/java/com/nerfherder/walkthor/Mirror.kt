package com.nerfherder.walkthor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.nerfherder.walkthor.data.Matcher
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * "Mirror": shows a part of the top screen (a minimap, a HUD, a quest tracker) live on the
 * bottom screen, using Android's screen capture. Android asks permission each time it starts.
 * Frames stay in memory just long enough to draw; nothing is saved or sent anywhere.
 *
 * Android only allows screen capture from a foreground service, so [MirrorService] holds the
 * capture session while the mirror screen is open, and [stop] ends it when you leave.
 */
object Mirror {
    private const val PREFS = "mirror"

    /** The running capture session, or null. */
    val projection = MutableStateFlow<MediaProjection?>(null)

    /**
     * The one picture of the top screen this session may make (Android 14+ allows one display per
     * screen-access session). Mirror, Shots and Stats read it through an image queue; clips borrow
     * it and pass each frame on to that queue as well as to the video encoder.
     */
    class Shared(val display: android.hardware.display.VirtualDisplay, val width: Int, val height: Int, val dpi: Int)
    val shared = MutableStateFlow<Shared?>(null)

    /** Where the live picture goes when no clip is being kept (null while paused). */
    @Volatile var previewSurface: android.view.Surface? = null

    fun showPreviewOn(surface: android.view.Surface?) {
        previewSurface = surface
        if (com.nerfherder.walkthor.data.Clips.drawing) com.nerfherder.walkthor.data.Clips.previewChanged(surface)
        else runCatching { shared.value?.display?.surface = surface }
    }
    /** Why the last start failed, if it did. */
    val error = MutableStateFlow<String?>(null)

    /** Most areas one game can have. */
    const val MAX_AREAS = 4
    /** [selected] value meaning "show every area at once". */
    const val ALL = -1

    /**
     * The parts of the top screen to show for a game, each as fractions (left, top, right, bottom).
     * Empty if none has been picked yet.
     */
    fun areas(context: Context, game: String): List<FloatArray> {
        val s = prefs(context).getString(key(game), null) ?: return emptyList()
        return s.split(';').mapNotNull { one ->
            one.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 4 }?.toFloatArray()
        }.take(MAX_AREAS)
    }

    fun setAreas(context: Context, game: String, areas: List<FloatArray>) {
        prefs(context).edit()
            .putString(key(game), areas.joinToString(";") { it.joinToString(",") })
            .apply()
    }

    /** Which area was showing last time for a game (an index, or [ALL]). */
    fun selected(context: Context, game: String): Int = prefs(context).getInt(key(game) + "#sel", 0)

    fun setSelected(context: Context, game: String, index: Int) {
        prefs(context).edit().putInt(key(game) + "#sel", index).apply()
    }

    /** Starts the capture session with the permission Android just granted. */
    fun start(context: Context, resultCode: Int, data: Intent) {
        error.value = null
        val intent = Intent(context, MirrorService::class.java)
            .putExtra(MirrorService.EXTRA_CODE, resultCode)
            .putExtra(MirrorService.EXTRA_DATA, data)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }.onFailure { error.value = "Couldn't start the mirror (${it.javaClass.simpleName})" }
    }

    /** Ends the capture session (and the service holding it). */
    fun stop(context: Context) {
        com.nerfherder.walkthor.data.Clips.stopAll()
        projection.value?.let { runCatching { it.stop() } }
        projection.value = null
        runCatching { context.stopService(Intent(context, MirrorService::class.java)) }
    }

    private fun key(game: String) = Matcher.words(game).joinToString(" ")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Holds the screen-capture session while the mirror is open (Android requires a foreground service). */
class MirrorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val started = runCatching {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val projection = mpm.getMediaProjection(code, data) ?: error("no projection")
            // Android 14+ needs a callback registered before any capture starts.
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    com.nerfherder.walkthor.data.Clips.stopAll()
                    if (Mirror.projection.value === projection) Mirror.projection.value = null
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
            Mirror.projection.value = projection
        }
        if (started.isFailure) {
            Mirror.error.value = "Couldn't start the mirror (${started.exceptionOrNull()?.javaClass?.simpleName})"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        com.nerfherder.walkthor.data.Clips.stopAll()
        Mirror.projection.value?.let { runCatching { it.stop() } }
        Mirror.projection.value = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Mirror", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while WalkThor mirrors part of the top screen"
                }
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_mirror)
            .setContentTitle("WalkThor is using the top screen's picture")
            .setContentText("For Mirror or the FPS meter, only on this device. Leave the game's screens to stop.")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "mirror"
        private const val NOTIFICATION_ID = 7
    }
}
