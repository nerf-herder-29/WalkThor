package com.nerfherder.walkthor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Keeps keyboard/controller focus on the game while one is running.
 *
 * Android gives its full performance (top-app CPU priority and boost) only to the focused app.
 * If WalkThor takes focus when Cocoon opens it as a companion, or when you touch the bottom
 * screen, the game on the top screen loses it, which can halve its frame rate, and some
 * emulators misbehave when they lose focus. So while a game is running, WalkThor's window is
 * made non-focusable. You can still tap, scroll and turn pages, but the game stays the
 * active app. The search field asks for focus only while you're typing in it.
 */
object GameFocus {
    private const val PREFS = "focus"
    private const val KEY_KEEP = "keepOnGame"

    /** The setting: keep focus on a running game (on by default). */
    val keepOnGame = MutableStateFlow(true)
    /** Cocoon launched a game recently (from its launch log). */
    val gameRunning = MutableStateFlow(false)
    /** Something in WalkThor needs real focus right now (typing a search). */
    val wantFocus = MutableStateFlow(false)

    fun init(context: Context) {
        keepOnGame.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP, true)
    }

    fun setKeepOnGame(context: Context, on: Boolean) {
        keepOnGame.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP, on).apply()
    }
}
