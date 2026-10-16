package com.nerfherder.walkthor

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.widget.Toast
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Keeping WalkThor on the AYN Thor's bottom screen.
 *
 * The Thor exposes its second panel as an extra Android display. The bottom panel (1240×1080)
 * is the smaller of the two, so "bottom" = the switched-on, non-private display with the fewest
 * pixels. Android launches apps on the top screen, so on start we relaunch ourselves on the
 * bottom one with [ActivityOptions.setLaunchDisplayId].
 */
object DisplayHelper {
    const val EXTRA_RELAUNCHED = "walkthor.relaunched"
    const val EXTRA_BOOK = "walkthor.book"
    private const val PREFS = "display"
    private const val KEY_ALWAYS_BOTTOM = "alwaysBottom"
    private const val KEY_LAST_BOUNCE = "lastBounce"
    /** Don't move ourselves to the bottom screen again within this long of the last move. */
    private const val BOUNCE_COOLDOWN_MS = 10_000L

    /** "Always open on the bottom screen" setting (on by default). */
    val alwaysBottom = MutableStateFlow(true)

    fun init(context: Context) {
        alwaysBottom.value = prefs(context).getBoolean(KEY_ALWAYS_BOTTOM, true)
    }

    fun setAlwaysBottom(context: Context, on: Boolean) {
        alwaysBottom.value = on
        prefs(context).edit().putBoolean(KEY_ALWAYS_BOTTOM, on).apply()
    }

    fun currentDisplayId(activity: Activity): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.displayId
        }

    private fun usableDisplays(context: Context): List<Display> {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        return dm.displays.filter { it.state != Display.STATE_OFF && (it.flags and Display.FLAG_PRIVATE) == 0 }
    }

    /** The bottom screen, or null on single-screen devices. */
    fun bottomDisplay(context: Context): Display? {
        val displays = usableDisplays(context)
        if (displays.size < 2) return null
        return displays.minByOrNull { d -> d.mode.physicalWidth.toLong() * d.mode.physicalHeight }
    }

    fun otherDisplay(activity: Activity): Display? {
        val current = currentDisplayId(activity)
        return usableDisplays(activity).firstOrNull { it.displayId != current }
    }

    /** The game's screen: the biggest switched-on screen other than WalkThor's own. */
    fun gameDisplay(activity: Activity): Display? {
        val current = currentDisplayId(activity)
        return usableDisplays(activity)
            .filter { it.displayId != current }
            .maxByOrNull { d -> d.mode.physicalWidth.toLong() * d.mode.physicalHeight }
    }

    fun isOnBottom(activity: Activity): Boolean {
        val bottom = bottomDisplay(activity) ?: return true
        return bottom.displayId == currentDisplayId(activity)
    }

    /**
     * Called first thing in onCreate. If we should be on the bottom screen and aren't, relaunch
     * there (carrying along any "Open with"/"Share" document) and return true.
     */
    fun ensureOnBottomScreen(activity: Activity, savedInstanceState: Bundle?): Boolean {
        if (savedInstanceState != null || !alwaysBottom.value) return false
        // Already relaunched once: never bounce again, even if the system put us elsewhere.
        if (activity.intent.getBooleanExtra(EXTRA_RELAUNCHED, false)) return false
        val bottom = bottomDisplay(activity) ?: return false
        if (bottom.displayId == currentDisplayId(activity)) return false
        // If we bounced to the bottom screen moments ago and are back on the top one, another app
        // (a screen manager like Wayfinder, or a frontend) put us there on purpose. Stay put rather
        // than fight it: ping-ponging between screens disrupts the game launching alongside.
        val p = prefs(activity)
        val now = System.currentTimeMillis()
        if (now - p.getLong(KEY_LAST_BOUNCE, 0L) < BOUNCE_COOLDOWN_MS) return false
        p.edit().putLong(KEY_LAST_BOUNCE, now).apply()
        return relaunch(activity, bottom.displayId, Intent(activity.intent), quiet = true)
    }

    fun moveToOtherScreen(activity: Activity, openBookId: String?) {
        val target = otherDisplay(activity)
        if (target == null) {
            Toast.makeText(activity, "No other screen found", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(activity, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .putExtra(EXTRA_BOOK, openBookId)
        relaunch(activity, target.displayId, intent, quiet = false)
    }

    private fun relaunch(activity: Activity, displayId: Int, base: Intent, quiet: Boolean): Boolean {
        val intent = base
            .setClass(activity, MainActivity::class.java)
            // Pass on read access to an "Open with" document, if there is one.
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            .putExtra(EXTRA_RELAUNCHED, true)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        return try {
            activity.startActivity(intent, options.toBundle())
            activity.finishAndRemoveTask()
            // No slide animation between screens.
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
            true
        } catch (e: Exception) {
            if (!quiet) Toast.makeText(activity, "Couldn't move to that screen", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Controller / keyboard page-turn commands routed from the activity to the reader. */
object KeyBus {
    enum class Command { NEXT, PREV }

    val commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)

    /** True while a reader is open; set by the reader screen. */
    @Volatile var readerActive = false

    /** True while the search field has focus, so D-pad keys go to the text field instead. */
    @Volatile var textFieldFocused = false
}
