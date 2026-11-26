package com.nerfherder.walkthor

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nerfherder.walkthor.ui.Frontend
import com.nerfherder.walkthor.ui.TopSpotlight
import com.nerfherder.walkthor.ui.WalkThorTheme

/**
 * The top screen while you choose a game on the bottom one: the chosen game, big. It never
 * takes the controller (that stays with the bottom screen), and it closes as soon as anything
 * covers it (a game starting), so it holds no memory while you play.
 */
class FrontendTopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        Frontend.top = java.lang.ref.WeakReference(this)
        setContent {
            WalkThorTheme {
                val spot by Frontend.spotlight.collectAsState()
                TopSpotlight(spot)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Covered (a game started) or the screen went off: let it go; the games bring it back.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        if (Frontend.top?.get() === this) Frontend.top = null
        super.onDestroy()
    }
}
