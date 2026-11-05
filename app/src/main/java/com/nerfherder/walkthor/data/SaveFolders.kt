package com.nerfherder.walkthor.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Where screenshots and clips are saved: Pictures/WalkThor and Movies/WalkThor unless you pick
 * another folder. Android keeps pictures in Pictures or DCIM and videos in Movies or DCIM, so a
 * chosen folder has to be somewhere in those.
 */
object SaveFolders {
    const val SHOTS_DEFAULT = "Pictures/WalkThor"
    const val CLIPS_DEFAULT = "Movies/WalkThor"
    private val SHOT_ROOTS = listOf("Pictures", "DCIM")
    private val CLIP_ROOTS = listOf("Movies", "DCIM")

    private fun prefs(c: Context) = c.getSharedPreferences("save_folders", Context.MODE_PRIVATE)

    fun shots(c: Context): String = prefs(c).getString("shots", null) ?: SHOTS_DEFAULT
    fun clips(c: Context): String = prefs(c).getString("clips", null) ?: CLIPS_DEFAULT

    /** Where to look for this kind's files: the chosen folder, and the default (for ones saved before). */
    fun shotFolders(c: Context) = listOf(shots(c), SHOTS_DEFAULT).distinct()
    fun clipFolders(c: Context) = listOf(clips(c), CLIPS_DEFAULT).distinct()

    /**
     * A folder picked in Android's folder picker as a path on the device's storage
     * ("Pictures/Game shots"), or null if it isn't on the device's own storage.
     */
    fun pathOf(tree: Uri): String? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        if (!id.startsWith("primary:")) return null
        return id.substringAfter(':').trim('/')
    }

    /** Uses [path] for screenshots; false (and nothing changed) if it isn't in Pictures or DCIM. */
    fun setShots(c: Context, path: String): Boolean = set(c, "shots", path, SHOT_ROOTS)
    /** Uses [path] for clips; false (and nothing changed) if it isn't in Movies or DCIM. */
    fun setClips(c: Context, path: String): Boolean = set(c, "clips", path, CLIP_ROOTS)

    private fun set(c: Context, key: String, path: String, roots: List<String>): Boolean {
        val clean = path.trim('/')
        val root = clean.substringBefore('/')
        if (roots.none { it.equals(root, ignoreCase = true) }) return false
        // Just "Pictures" or "DCIM" is fine too, though a folder of its own is tidier.
        prefs(c).edit().putString(key, clean).apply()
        return true
    }

    fun reset(c: Context) = prefs(c).edit().clear().apply()
}
