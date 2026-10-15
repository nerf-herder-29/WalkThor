package com.nerfherder.walkthor

import android.app.Activity
import android.os.Bundle

/**
 * An invisible screen opened for an instant on the game's display, then closed.
 *
 * Android gives its "top app" priority to the app on the most recently active display. When
 * Cocoon opens WalkThor after the game, the bottom screen becomes the active one and WalkThor
 * takes that priority from the game. Opening (and immediately closing) this on the game's
 * screen makes that screen active again, so the game gets top priority back while WalkThor
 * stays visible below.
 */
class HandBackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
