package com.nerfherder.walkthor

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Performance check" (⋮ menu): shows on the dimmed screen whether Android currently treats
 * WalkThor as its top app (the one that gets the fastest CPU cores and highest priority),
 * and how many frames WalkThor has drawn recently. For diagnosing frame drops in games.
 */
object PerfCheck {
    private const val PREFS = "perfcheck"
    private const val KEY_ON = "on"

    val enabled = MutableStateFlow(false)
    /** Android's "top resumed activity" flag for WalkThor; null until Android has said. */
    val walkThorIsTop = MutableStateFlow<Boolean?>(null)
    /** When [walkThorIsTop] last changed (elapsedRealtime). */
    val since = MutableStateFlow(SystemClock.elapsedRealtime())
    /** Frames WalkThor drew in the last 10 seconds. */
    val recentFrames = MutableStateFlow(0)
    /** What happened the last time WalkThor tried to hand top priority back to the game. */
    val handBack = MutableStateFlow<String?>(null)
    internal val frameCounter = AtomicInteger(0)

    fun init(context: Context) {
        enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }

    fun setTop(isTop: Boolean) {
        if (walkThorIsTop.value != isTop) {
            walkThorIsTop.value = isTop
            since.value = SystemClock.elapsedRealtime()
        }
    }
}
