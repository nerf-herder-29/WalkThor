package com.nerfherder.walkthor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nerfherder.walkthor.ui.Frontend
import com.nerfherder.walkthor.ui.TopSpotlight
import com.nerfherder.walkthor.ui.SettingsTop
import com.nerfherder.walkthor.ui.SettingsExplainer
import com.nerfherder.walkthor.ui.WalkThorTheme

/**
 * The top screen while you choose a game on the bottom one: the chosen game, big. Buttons
 * pressed while it has the controller go to the games on the bottom screen. It stays put when
 * something covers it for a moment (the screen turning off, another app); the games close it
 * when a game starts, so it holds no memory while you play.
 *
 * It's also WalkThor's home screen, for when you make WalkThor your home app: Home brings it
 * back, with your games on the bottom screen.
 */
class FrontendTopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        Frontend.top = java.lang.ref.WeakReference(this)
        // Back does nothing here (it's the home screen).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        bringGames()
        setContent {
            WalkThorTheme {
                val spot by Frontend.spotlight.collectAsState()
                val settings by SettingsTop.section.collectAsState()
                val setting by SettingsTop.current.collectAsState()
                val library by com.nerfherder.walkthor.ui.LibraryTop.active.collectAsState()
                val libraryGame by com.nerfherder.walkthor.ui.LibraryTop.current.collectAsState()
                val open = settings
                val apps by com.nerfherder.walkthor.ui.AppsTop.active.collectAsState()
                val app by com.nerfherder.walkthor.ui.AppsTop.current.collectAsState()
                val appCount by com.nerfherder.walkthor.ui.AppCatalog.apps.collectAsState()
                val updates by com.nerfherder.walkthor.ui.UpdatesTop.active.collectAsState()
                when {
                    open != null -> SettingsExplainer(open, setting, SettingsTop.titles.toList())
                    apps -> com.nerfherder.walkthor.ui.AppsExplainer(app, appCount?.size ?: 0)
                    updates -> com.nerfherder.walkthor.ui.UpdatesExplainer()
                    library -> com.nerfherder.walkthor.ui.LibrarySpotlight(libraryGame)
                    else -> TopSpotlight(spot)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        bringGames()
    }

    /** Opened as the home screen: make sure your games are up on the bottom screen. */
    private fun bringGames() {
        if (Frontend.keyHandler != null) return
        val bottom = runCatching { DisplayHelper.bottomDisplay(this) }.getOrNull() ?: return
        val intent = android.content.Intent(this, MainActivity::class.java)
            .setAction(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(DisplayHelper.EXTRA_RELAUNCHED, true)
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(bottom.displayId).toBundle()
        runCatching { startActivity(intent, options) }
    }

    /** Buttons pressed while the top screen has the controller work the games below. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (Frontend.keyHandler?.invoke(event) == true) return true
        // Otherwise (Settings, say) the bottom screen's WalkThor takes them.
        Frontend.bottom?.get()?.takeIf { !it.isFinishing }?.let { if (it.dispatchKeyEvent(event)) return true }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        Frontend.topVisible = true
    }

    override fun onStop() {
        Frontend.topVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        Frontend.topVisible = false
        if (Frontend.top?.get() === this) Frontend.top = null
        super.onDestroy()
    }
}
