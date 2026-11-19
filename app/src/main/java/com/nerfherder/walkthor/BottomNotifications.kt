package com.nerfherder.walkthor

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Notifications on the bottom screen (Settings → General). Android's own pop-up banners always
 * appear on the top screen, over your game; with this on, WalkThor switches them off and shows
 * each notification itself, as a card that drops down from the top of the bottom screen (on the
 * top screen instead while the bottom one is off). Sounds, vibration and the notification shade
 * are untouched: only the banner moves.
 *
 * Needs notification access (to see notifications as they arrive), accessibility access (to show
 * the card) and, to switch Android's banners off, Force SELinux off (AYN's system helper). Whenever
 * one of those goes, Android's banners come back on. Nothing is stored or sent: each notification
 * is shown and forgotten.
 */
object BottomNotifications {
    private const val PREF = "bottomNotifications"
    /** WalkThor switched Android's banners off (so it's WalkThor's to switch back on). */
    private const val PREF_OFF = "bottomNotifications.bannersOff"
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WalkThor notifications").apply { isDaemon = true } }

    /** What happened last, for Diagnostics. */
    @Volatile var lastNote: String = "off"
        private set

    private fun prefs(c: Context) = SwipeLauncher.prefs(c)
    fun enabled(c: Context) = prefs(c).getBoolean(PREF, false)

    fun hasAccess(c: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    /** Opens Android's notification access page (for WalkThor, where Android allows). */
    fun openAccess(c: Context) {
        val forUs = if (android.os.Build.VERSION.SDK_INT >= 30)
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(c, NotifyListener::class.java).flattenToString())
        else null
        val all = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        for (i in listOfNotNull(forUs, all)) {
            if (runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
        }
    }

    /** The switch. Turning it on asks for notification access first, if WalkThor doesn't have it. */
    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(PREF, on).apply()
        if (on && !hasAccess(c)) openAccess(c)
        apply(c)
    }

    /**
     * Android's banners off while everything WalkThor needs to replace them is there, and back on
     * otherwise. Called whenever any of that changes.
     */
    fun apply(c: Context) {
        val app = c.applicationContext
        val replace = enabled(app) && hasAccess(app) && SwipeGestureService.instance?.get() != null
        worker.execute {
            val wasOff = prefs(app).getBoolean(PREF_OFF, false)
            when {
                replace && !wasOff -> {
                    if (banners(app, show = false)) {
                        prefs(app).edit().putBoolean(PREF_OFF, true).apply()
                        lastNote = "on: Android's banners off, WalkThor shows them"
                    } else {
                        lastNote = "on, but Android's banners couldn't be switched off (needs Force SELinux off), so both show"
                    }
                }
                replace -> lastNote = "on: Android's banners off, WalkThor shows them"
                else -> {
                    if (wasOff && banners(app, show = true)) prefs(app).edit().putBoolean(PREF_OFF, false).apply()
                    lastNote = when {
                        !enabled(app) -> "off"
                        !hasAccess(app) -> "waiting for notification access (Android's banners as usual)"
                        else -> "waiting for accessibility access (Android's banners as usual)"
                    }
                }
            }
        }
    }

    /** WalkThor's being switched off: Android's banners back on (in case nothing comes back to do it). */
    fun restore(c: Context) {
        val app = c.applicationContext
        if (!prefs(app).getBoolean(PREF_OFF, false)) return
        Thread({ if (banners(app, show = true)) prefs(app).edit().putBoolean(PREF_OFF, false).apply() }, "WalkThor banners back").start()
    }

    /** Android's pop-up banners on or off. True if it took. Not on the main thread. */
    private fun banners(c: Context, show: Boolean): Boolean {
        val v = if (show) 1 else 0
        // Allowed directly if WRITE_SECURE_SETTINGS was ever granted; otherwise through AYN's helper.
        if (runCatching { Settings.Global.putInt(c.contentResolver, "heads_up_notifications_enabled", v) }.getOrDefault(false)) return true
        if (!AynSystem.available()) return false
        AynSystem.shell("settings put global heads_up_notifications_enabled $v")
        return runCatching { Settings.Global.getInt(c.contentResolver, "heads_up_notifications_enabled") }.getOrNull() == v
    }

    // ---- what's shown ----

    /** Keys shown lately, so an update that asks not to alert again isn't shown again. */
    private val seen = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 60
    })

    /** A notification arrived (from [NotifyListener]): shown if it would have popped up. */
    fun posted(c: Context, sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?) {
        if (!enabled(c) || sbn.packageName == c.packageName) return
        val n = sbn.notification ?: return
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT) != 0) return
        // Calls and alarms open their own screen anyway.
        if (n.fullScreenIntent != null) return
        if (ranking != null) {
            if (ranking.importance < NotificationManager.IMPORTANCE_HIGH) return // wouldn't have popped up
            if (!ranking.matchesInterruptionFilter()) return // Do Not Disturb
            @Suppress("DEPRECATION")
            if (ranking.suppressedVisualEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK != 0) return
        }
        if (n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && seen.containsKey(sbn.key)) return
        seen[sbn.key] = System.currentTimeMillis()
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val pm = c.packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val icon = runCatching { pm.getApplicationIcon(sbn.packageName) }.getOrNull()
        val open = n.contentIntent
        main.post { runCatching { Card.show(label, title, text, icon) { runCatching { open?.send() } } } }
    }

    /** The card that drops down from the top of the bottom screen. */
    private object Card {
        private var shown: Pair<WindowManager, View>? = null
        private const val SHOW_MS = 5_500L
        private val hide = Runnable { dismiss() }

        fun show(app: String, title: String, text: String, icon: Drawable?, onOpen: () -> Unit) {
            val service = SwipeGestureService.instance?.get() ?: return
            dismiss(animate = false)
            val dm = service.getSystemService(DisplayManager::class.java) ?: return
            val bottom = runCatching { DisplayHelper.bottomDisplay(service) }.getOrNull()
            // The bottom screen, or the top one while the bottom's off (or there's only one).
            val display: Display = (if (bottom != null && bottom.state != Display.STATE_OFF && !BottomOff.off.value) bottom
            else dm.displays.firstOrNull { it.displayId != bottom?.displayId && it.state != Display.STATE_OFF }) ?: bottom ?: return
            val ctx = service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = ctx.getSystemService(WindowManager::class.java) ?: return
            val metrics = ctx.resources.displayMetrics
            // Sized to the screen, as in the design: u is a hundredth of its shorter side.
            val u = minOf(metrics.widthPixels, metrics.heightPixels) / 100f
            val card = build(ctx, u, app, title, text, icon)
            card.setOnClickListener { onOpen(); dismiss() }
            var downY = 0f
            card.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downY = e.rawY; main.removeCallbacks(hide) }
                    MotionEvent.ACTION_MOVE -> v.translationY = (e.rawY - downY).coerceAtMost(0f)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // Swiped up: away. A tap: opens it (the click listener).
                        if (e.rawY - downY < -u * 4) { dismiss(); return@setOnTouchListener true }
                        v.animate().translationY(0f).setDuration(150).start()
                        main.postDelayed(hide, SHOW_MS)
                    }
                }
                false
            }
            val params = WindowManager.LayoutParams(
                (metrics.widthPixels * 0.84f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (u * 2.6f).toInt()
                title = "WalkThor notification"
            }
            wm.addView(card, params)
            shown = wm to card
            card.translationY = -u * 30
            card.alpha = 0f
            card.animate().translationY(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
            main.postDelayed(hide, SHOW_MS)
        }

        fun dismiss(animate: Boolean = true) {
            main.removeCallbacks(hide)
            val (wm, v) = shown ?: return
            shown = null
            if (!animate) { runCatching { wm.removeViewImmediate(v) }; return }
            v.animate().translationY(-v.height.toFloat() * 1.3f).alpha(0f).setDuration(200)
                .withEndAction { runCatching { wm.removeViewImmediate(v) } }.start()
        }

        private fun build(ctx: Context, u: Float, app: String, title: String, text: String, icon: Drawable?): View {
            fun px(x: Float) = x.toInt()
            val ink = 0xFFEDE6DA.toInt()
            val muted = 0xFFA39A8E.toInt()
            val body = 0xFFC9C1B5.toInt()
            val accent = runCatching { StickLights.color(ctx).let { if (it == 0) ink else StickLights.swatch(it) or 0xFF000000.toInt() } }.getOrDefault(ink)
            val row = object : LinearLayout(ctx) {
                // Reported as a plain view, so WalkThor doesn't take it for an app coming to the front.
                override fun getAccessibilityClassName(): CharSequence = "android.view.View"
            }
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(px(u * 2.8f), px(u * 2.6f), px(u * 3.2f), px(u * 2.8f))
            row.background = GradientDrawable().apply {
                setColor(0xFF0E0C0E.toInt())
                cornerRadius = u * 3f
                setStroke(px(u * 0.15f).coerceAtLeast(1), 0xFF3A353B.toInt())
            }
            row.elevation = u * 2f
            val iconView = if (icon != null) ImageView(ctx).apply { setImageDrawable(icon) }
            else TextView(ctx).apply {
                // No icon: the app's first letter on your stick-light colour.
                this.text = app.take(1).uppercase()
                gravity = Gravity.CENTER
                setTextColor(0xFF000000.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 3.2f)
                typeface = WalkThorFont.get(ctx, 700)
                background = GradientDrawable().apply { setColor(accent); cornerRadius = u * 1.9f }
            }
            row.addView(iconView, LinearLayout.LayoutParams(px(u * 6.7f), px(u * 6.7f)))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            fun line(s: String, size: Float, color: Int, weight: Int, lines: Int, top: Float, spacing: Float = 0f) = TextView(ctx).apply {
                this.text = s
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                typeface = WalkThorFont.get(ctx, weight)
                letterSpacing = spacing
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, px(top), 0, 0)
            }
            texts.addView(line("${app.uppercase()} · NOW", u * 2.0f, muted, 600, 1, 0f, 0.14f))
            if (title.isNotEmpty()) texts.addView(line(title, u * 3.1f, ink, 600, 1, u * 0.9f))
            if (text.isNotEmpty()) texts.addView(line(text, u * 2.6f, body, 400, 2, u * 0.7f))
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(u * 2.6f) })
            return row
        }
    }
}

/** Hands each notification to [BottomNotifications] as it arrives. Only on while you've given notification access. */
class NotifyListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        BottomNotifications.apply(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Access taken away (or the listener stopped): Android's banners back on.
        BottomNotifications.restore(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val r = rankingMap?.let { map -> Ranking().takeIf { map.getRanking(sbn.key, it) } }
        runCatching { BottomNotifications.posted(this, sbn, r) }
    }
}
