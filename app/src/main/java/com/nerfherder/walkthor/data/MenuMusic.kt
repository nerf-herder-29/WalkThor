package com.nerfherder.walkthor.data

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Background music on your games: the tracks in a folder you pick (your own music, or a theme's
 * music folder, such as one of Cocoon's), shuffled, quietly, while the games are on screen. It
 * fades out as soon as you leave them or a game starts, and stays silent if something else (a
 * music app) is already playing. With no folder linked, WalkThor plays its own soft lo-fi tracks.
 */
object MenuMusic {
    private const val PREFS = "menu_music"
    /** WalkThor's own music, for when no folder is linked. */
    private val BUILT_IN = intArrayOf(com.nerfherder.walkthor.R.raw.lofi_1, com.nerfherder.walkthor.R.raw.lofi_2, com.nerfherder.walkthor.R.raw.lofi_3)
    private val AUDIO = setOf("mp3", "ogg", "opus", "m4a", "aac", "flac", "wav")

    private val _folder = MutableStateFlow<Uri?>(null)
    val folder: StateFlow<Uri?> = _folder.asStateFlow()
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on.asStateFlow()
    /** 0–1. */
    private val _volume = MutableStateFlow(0.35f)
    val volume: StateFlow<Float> = _volume.asStateFlow()
    private var loaded = false

    private var player: MediaPlayer? = null
    private var tracks: List<Uri> = emptyList()
    private var next = 0
    @Volatile private var wanted = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(c)
        _folder.value = p.getString("tree", null)?.let(Uri::parse)
        _on.value = p.getBoolean("on", true)
        _volume.value = p.getFloat("volume", 0.35f)
    }

    fun link(c: Context, tree: Uri) {
        load(c)
        runCatching { c.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs(c).edit().putString("tree", tree.toString()).apply()
        _folder.value = tree
        tracks = emptyList()
        if (wanted) { stopNow(); play(c) }
    }

    fun unlink(c: Context) {
        stopNow()
        prefs(c).edit().remove("tree").apply()
        _folder.value = null
        tracks = emptyList()
        if (wanted) play(c)
    }

    private fun builtIn(c: Context): List<Uri> =
        BUILT_IN.map { Uri.parse("android.resource://${c.packageName}/$it") }

    fun setOn(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("on", on).apply()
        _on.value = on
        if (!on) fadeOut() else if (wanted) play(c)
    }

    fun setVolume(c: Context, v: Float) {
        prefs(c).edit().putFloat("volume", v).apply()
        _volume.value = v
        runCatching { player?.setVolume(v, v) }
    }

    /** Your games are on screen ([showing]) or not. */
    fun showing(c: Context, showing: Boolean) {
        load(c)
        wanted = showing
        if (showing) play(c) else fadeOut()
    }

    private fun play(c: Context) {
        if (!_on.value || player != null) return
        val am = c.getSystemService(AudioManager::class.java)
        // Something else is playing (a music app): leave it be.
        if (am?.isMusicActive == true) return
        Background.dispatcher.executor.execute {
            if (tracks.isEmpty()) tracks = listTracks(c).ifEmpty { builtIn(c) }.shuffled()
            main.post { if (wanted && player == null) startTrack(c) }
        }
    }

    private fun startTrack(c: Context) {
        if (tracks.isEmpty()) return
        val uri = tracks[next % tracks.size]
        next++
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(c, uri)
            mp.setOnCompletionListener { done ->
                runCatching { done.release() }
                if (player === done) { player = null; if (wanted) startTrack(c) }
            }
            mp.setOnPreparedListener { ready ->
                ready.setVolume(0f, 0f)
                ready.start()
                fadeTo(ready, 0f, _volume.value)
            }
            mp.prepareAsync()
        }.onFailure {
            runCatching { mp.release() }
            player = null
        }
    }

    private fun fadeTo(mp: MediaPlayer, from: Float, to: Float, then: (() -> Unit)? = null) {
        val steps = 12
        for (i in 1..steps) {
            main.postDelayed({
                val v = from + (to - from) * i / steps
                runCatching { mp.setVolume(v, v) }
                if (i == steps) then?.invoke()
            }, i * 50L)
        }
    }

    private fun fadeOut() {
        val mp = player ?: return
        player = null
        fadeTo(mp, _volume.value, 0f) { runCatching { mp.stop() }; runCatching { mp.release() } }
    }

    private fun stopNow() {
        val mp = player ?: return
        player = null
        runCatching { mp.stop() }
        runCatching { mp.release() }
    }

    /** The music files in your folder (and one folder down). */
    private fun listTracks(c: Context): List<Uri> {
        val tree = _folder.value ?: return emptyList()
        val out = ArrayList<Uri>()
        fun walk(id: String, depth: Int) {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            runCatching {
                c.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null,
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val cid = cur.getString(0)
                        val name = cur.getString(1) ?: continue
                        if (cur.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < 2) walk(cid, depth + 1)
                        } else if (name.substringAfterLast('.', "").lowercase() in AUDIO) {
                            out += DocumentsContract.buildDocumentUriUsingTree(tree, cid)
                        }
                    }
                }
            }
        }
        runCatching { walk(DocumentsContract.getTreeDocumentId(tree), 0) }
        return out
    }

    /** How many tracks the folder has, for Settings. */
    fun count(c: Context): Int = listTracks(c).size
}
