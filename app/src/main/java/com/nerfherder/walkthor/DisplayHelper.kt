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

/**
 * Moving WalkThor between the AYN Thor's two screens.
 *
 * The Thor exposes its second (bottom) panel as an extra Android display. We relaunch the activity
 * on the chosen display with [ActivityOptions.setLaunchDisplayId] and remember the choice, so the
 * next cold start goes straight to the same screen.
 */
object DisplayHelper {
    const val EXTRA_RELAUNCHED = "walkthor.relaunched"
    const val EXTRA_BOOK = "walkthor.book"
    private const val PREFS = "display"
    private const val KEY_PREFERRED = "preferredDisplay"

    fun currentDisplayId(activity: Activity): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.displayId
        }

    /** Another switched-on display (on the Thor: the other screen), if there is one. */
    fun otherDisplay(activity: Activity): Display? {
        val dm = activity.getSystemService(DisplayManager::class.java) ?: return null
        val current = currentDisplayId(activity)
        return dm.displays.firstOrNull { it.displayId != current && it.state != Display.STATE_OFF }
    }

    fun moveToOtherScreen(activity: Activity, openBookId: String?) {
        val target = otherDisplay(activity)
        if (target == null) {
            Toast.makeText(activity, "No other screen found", Toast.LENGTH_SHORT).show()
            return
        }
        prefs(activity).edit().putInt(KEY_PREFERRED, target.displayId).apply()
        relaunch(activity, target.displayId, openBookId)
    }

    /**
     * On a cold start from the launcher, hop to the screen the user last moved WalkThor to.
     * Returns true if the activity is being relaunched (caller should stop setting up).
     */
    fun maybeRelaunchOnPreferredScreen(activity: Activity, savedInstanceState: Bundle?): Boolean {
        val intent = activity.intent
        if (savedInstanceState != null || intent.getBooleanExtra(EXTRA_RELAUNCHED, false)) return false
        // Don't relaunch for "open with"/share intents: the URI permission belongs to this instance.
        if (intent.action != null && intent.action != Intent.ACTION_MAIN) return false
        val preferred = prefs(activity).getInt(KEY_PREFERRED, -1)
        if (preferred < 0 || preferred == currentDisplayId(activity)) return false
        val dm = activity.getSystemService(DisplayManager::class.java) ?: return false
        val display = dm.getDisplay(preferred) ?: return false
        if (display.state == Display.STATE_OFF) return false
        return relaunch(activity, preferred, null)
    }

    private fun relaunch(activity: Activity, displayId: Int, openBookId: String?): Boolean {
        val intent = Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(EXTRA_RELAUNCHED, true)
            .putExtra(EXTRA_BOOK, openBookId)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        return try {
            activity.startActivity(intent, options.toBundle())
            activity.finishAndRemoveTask()
            true
        } catch (e: Exception) {
            Toast.makeText(activity, "Couldn't move to that screen", Toast.LENGTH_SHORT).show()
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
