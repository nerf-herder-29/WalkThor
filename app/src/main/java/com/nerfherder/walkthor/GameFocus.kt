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
 * Always on: there's no reason to give a running game's priority away.
 */
object GameFocus {
    /** Keep focus on a running game. Always on (it used to be a setting). */
    val keepOnGame = MutableStateFlow(true)
    /** Cocoon launched a game recently (from its launch log). */
    val gameRunning = MutableStateFlow(false)
    /** Something in WalkThor needs real focus right now (typing a search). */
    val wantFocus = MutableStateFlow(false)
    /**
     * WalkThor is leaving focus to a game right now (its window can't take focus). Its dialogs
     * and menus then open without taking focus either (see GameSafeDialogs).
     */
    val yielding = MutableStateFlow(false)

    @Suppress("UNUSED_PARAMETER")
    fun init(context: Context) {
        keepOnGame.value = true
    }
}
