package com.nerfherder.walkthor

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * WalkThor's stand-in home screen while your home app is paused for a game: a plain black screen
 * (no WalkThor screens loaded, next to no memory), with the drawer over it as usual.
 *
 * Android's own launcher used to stand in, but whatever starts on the bottom screen takes
 * Android's "app in front" spot from the game, and that spot decides who the memory killer closes
 * last: the game went first. This screen is told the moment it gets that spot, and hands it
 * straight back to the game with one tap on the very top edge of the game's screen (the hand-back
 * WalkThor's app already does after opening), then checks it worked.
 *
 * Only enabled while your home app is paused (see [HomeSwap]), so it isn't offered as a home app
 * the rest of the time.
 */
class GameHomeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var tries = 0
    private var handing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity) {
            tries = 0
            main.removeCallbacks(handBack)
            main.postDelayed(handBack, HAND_BACK_MS)
        } else {
            main.removeCallbacks(handBack)
            if (handing) {
                handing = false
                MemoryWatch.noteFrom(this, "WalkThor's home screen gave Android's top spot back to the game" +
                    (if (tries > 1) " (try $tries)" else ""))
            }
        }
    }

    private val handBack = object : Runnable {
        override fun run() {
            // Only on the bottom screen, with a game playing on the other one.
            if (!EmulatorWatch.gameShowing.value || GameFocus.wantFocus.value) return
            if (ControllerLock.locked.value == ControllerLock.Screen.BOTTOM) return
            val bottom = DisplayHelper.bottomDisplay(this@GameHomeActivity)?.displayId ?: return
            if (display?.displayId != bottom) return
            val game = DisplayHelper.usableDisplays(this@GameHomeActivity).filter { it.displayId != bottom }
                .maxByOrNull { d -> d.mode.physicalWidth.toLong() * d.mode.physicalHeight }?.displayId ?: return
            val service = SwipeGestureService.instance?.get() ?: run {
                MemoryWatch.noteFrom(this@GameHomeActivity, "WalkThor's home screen has Android's top spot, but can't hand it back (accessibility access is off)")
                return
            }
            tries++
            handing = true
            val how = service.handBack(game)
            if (tries == 1) MemoryWatch.noteFrom(this@GameHomeActivity, "WalkThor's home screen got Android's top spot as the game runs: handing it back ($how)")
            // Still on top a moment later: again, a few times.
            if (tries < 4) main.postDelayed(this, 1_500L)
            else MemoryWatch.noteFrom(this@GameHomeActivity, "WalkThor's home screen couldn't give the top spot back to the game after $tries tries")
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = Unit // it's home: nowhere to go back to

    override fun onDestroy() {
        main.removeCallbacks(handBack)
        super.onDestroy()
    }

    companion object {
        /** A moment after getting the spot, so Android has finished bringing the screen up. */
        private const val HAND_BACK_MS = 250L
    }
}
