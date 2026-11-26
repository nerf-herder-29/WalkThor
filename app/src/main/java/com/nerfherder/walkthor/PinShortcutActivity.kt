package com.nerfherder.walkthor

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import com.nerfherder.walkthor.data.PinnedShortcuts

/**
 * Android asks the home app to confirm when another app adds a shortcut to the home screen
 * (PokeDaisy adding a game, for example). WalkThor takes it straight away: the shortcut joins
 * your games.
 */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val la = getSystemService(LauncherApps::class.java)
        val request = runCatching { la?.getPinItemRequest(intent) }.getOrNull()
        val info = request?.shortcutInfo
        if (request != null && request.isValid && info != null && request.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            val ok = runCatching { request.accept() }.getOrDefault(false)
            if (ok) {
                val p = PinnedShortcuts.add(this, info)
                Toast.makeText(this, "Added ${p.label} to your games", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Couldn't add that shortcut", Toast.LENGTH_SHORT).show()
            }
        } else {
            // A widget, or nothing usable: WalkThor has nowhere to put those.
            Toast.makeText(this, "WalkThor can only add shortcuts, not widgets", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
