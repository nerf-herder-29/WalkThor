package com.nerfherder.walkthor

import android.content.Context

/**
 * "Lean during games": WalkThor keeps as little in memory as it can while a game plays, at the
 * cost of a slightly slower drawer. When a game starts, the full app is closed if it's only
 * sitting in the background (the drawer has the same things); while the drawer's put away, it's
 * let go straight away (not after 30 seconds), along with the image cache and rendered guide
 * pages. Nothing changes outside games.
 */
object Lean {

    /** Always: it costs only a moment's rebuild when you reopen the drawer, and that comes back where you were. */
    @Suppress("UNUSED_PARAMETER")
    fun isOn(c: Context) = true

    /** A game came to the front, or went (from EmulatorWatch). */
    fun gameChanged(c: Context, playing: Boolean) {
        if (!playing || !isOn(c)) return
        // The full app, if it's only in the background: the drawer has everything it does.
        val activity = AppState.activity?.get()
        if (activity != null && AppState.resumedDisplayId == null) {
            runCatching { activity.finishAndRemoveTask() }
            MemoryWatch.noteFrom(c, "Lean: closed WalkThor's app in the background for the game")
        }
        letGo(c)
    }

    /** Images and rendered guide pages, let go (loaded again when next shown). */
    fun letGo(c: Context) {
        runCatching { coil.Coil.imageLoader(c).memoryCache?.clear() }
        runCatching { com.nerfherder.walkthor.data.PdfSource.trimAll() }
    }
}
