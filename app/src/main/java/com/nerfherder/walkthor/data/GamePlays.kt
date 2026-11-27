package com.nerfherder.walkthor.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The games you start from WalkThor's game screen: when each was last played, how long you've
 * played it, and your favorites. Kept on this device only.
 *
 * Play time is the time the emulator was in front with the screen on (the same count as
 * Home's play time), added to the game WalkThor last started until you're back on the games.
 */
object GamePlays {
    private const val PREFS = "game_plays"
    private const val KEY_CURRENT = "current"
    private const val KEY_CURRENT_PKG = "currentPkg"
    private const val KEY_FAVS = "favorites"

    private val _changed = MutableStateFlow(0)
    /** Bumped whenever something here changes, so the screens read it again. */
    val changed: StateFlow<Int> = _changed.asStateFlow()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [rom] was just started, in the app [pkg] (when known). */
    fun started(c: Context, rom: Rom, pkg: String? = null) {
        prefs(c).edit().putString(KEY_CURRENT, rom.id).putString(KEY_CURRENT_PKG, pkg.orEmpty())
            .putLong("last:${rom.id}", System.currentTimeMillis()).apply()
        _changed.value++
    }

    /**
     * The game WalkThor last started, if it's the one playing in [frontPkg] (the app in front):
     * a game started from somewhere else isn't taken for it.
     */
    fun current(c: Context, frontPkg: String?): String? {
        val p = prefs(c)
        val id = p.getString(KEY_CURRENT, null) ?: return null
        val pkg = p.getString(KEY_CURRENT_PKG, "").orEmpty()
        return id.takeIf { pkg.isEmpty() || frontPkg == null || pkg == frontPkg }
    }

    /** Back on the games: the game started from here is over. */
    fun ended(c: Context) {
        if (prefs(c).contains(KEY_CURRENT)) {
            prefs(c).edit().remove(KEY_CURRENT).apply()
            _changed.value++
        }
    }

    /** [ms] of play just ended (from [com.nerfherder.walkthor.PlayToday]). */
    fun addTime(c: Context, ms: Long) {
        val p = prefs(c)
        val id = p.getString(KEY_CURRENT, null) ?: return
        if (ms <= 0) return
        p.edit().putLong("time:$id", p.getLong("time:$id", 0L) + ms).apply()
        _changed.value++
    }

    fun lastPlayed(c: Context, rom: Rom): Long = prefs(c).getLong("last:${rom.id}", 0L)
    fun playTime(c: Context, rom: Rom): Long = prefs(c).getLong("time:${rom.id}", 0L)

    fun favorites(c: Context): Set<String> = prefs(c).getStringSet(KEY_FAVS, emptySet()).orEmpty()
    fun isFavorite(c: Context, rom: Rom) = rom.id in favorites(c)
    fun toggleFavorite(c: Context, rom: Rom): Boolean {
        val now = favorites(c).toMutableSet()
        val on = if (!now.remove(rom.id)) { now.add(rom.id); true } else false
        prefs(c).edit().putStringSet(KEY_FAVS, now).apply()
        _changed.value++
        return on
    }

    private const val KEY_HIDDEN = "hidden"

    /** Games you've hidden from the games screen. */
    fun hidden(c: Context): Set<String> = prefs(c).getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
    fun setHidden(c: Context, rom: Rom, hide: Boolean) = setHidden(c, rom.id, hide)
    fun setHidden(c: Context, id: String, hide: Boolean) {
        val now = hidden(c).toMutableSet()
        if (hide) now.add(id) else now.remove(id)
        prefs(c).edit().putStringSet(KEY_HIDDEN, now).apply()
        _changed.value++
    }
    fun showAll(c: Context) {
        prefs(c).edit().remove(KEY_HIDDEN).apply()
        _changed.value++
    }

    /** "12 h 40 m", "35 m", or null for none. */
    fun timeText(ms: Long): String? {
        val mins = ms / 60_000L
        if (mins < 1) return null
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "$h h ${"%02d".format(m)} m" else "$m m"
    }

    /** "today", "yesterday", "3 days ago", "Mar 4", or null for never. */
    fun whenText(at: Long): String? {
        if (at <= 0) return null
        val day = 24 * 60 * 60 * 1000L
        val startOfToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return when {
            at >= startOfToday -> "today"
            at >= startOfToday - day -> "yesterday"
            at >= startOfToday - 6 * day -> "${((startOfToday - at) / day) + 1} days ago"
            else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(at))
        }
    }
}
