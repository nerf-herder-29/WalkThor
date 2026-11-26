package com.nerfherder.walkthor.data

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.nerfherder.walkthor.R

/**
 * The games screen's little sounds ("Whisper"): a low, round tap as you move between bubbles, a
 * soft rising pair of notes for a new tab, one soft note when you pick something (an app, a
 * game's options), and a slow, gentle swell when a game starts. Quiet, played as game sounds so
 * the volume buttons set them, and they can be turned off on the Library tab.
 */
object UiSounds {
    enum class Kind { MOVE, TAB, PICK, LAUNCH }

    private var pool: SoundPool? = null
    private val ids = HashMap<Kind, Int>()
    private var lastMove = 0L

    private fun prefs(c: Context) = c.getSharedPreferences("ui_sounds", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("on", on).apply()

    private fun ready(c: Context): SoundPool {
        pool?.let { return it }
        val p = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        val app = c.applicationContext
        ids[Kind.MOVE] = p.load(app, R.raw.ui_tick, 1)
        ids[Kind.TAB] = p.load(app, R.raw.ui_tab, 1)
        ids[Kind.PICK] = p.load(app, R.raw.ui_select, 1)
        ids[Kind.LAUNCH] = p.load(app, R.raw.ui_launch, 1)
        pool = p
        return p
    }

    /** Loads the sounds ahead (the first one would otherwise be silent while it loads). */
    fun warm(c: Context) { if (enabled(c)) runCatching { ready(c) } }

    fun play(c: Context, kind: Kind) {
        if (!enabled(c)) return
        // Holding the D-pad down: not a tap for every bubble that flies past.
        if (kind == Kind.MOVE) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastMove < 90) return
            lastMove = now
        }
        runCatching {
            val p = ready(c)
            val id = ids[kind] ?: return
            val vol = when (kind) { Kind.MOVE -> 0.09f; Kind.TAB -> 0.2f; Kind.PICK -> 0.22f; Kind.LAUNCH -> 0.32f }
            p.play(id, vol, vol, 1, 0, 1f)
        }
    }
}
