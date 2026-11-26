package com.nerfherder.walkthor

import android.content.Context
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * What WalkThor's screens need from whatever is showing them: the app (MainActivity) or the
 * drawer over the bottom screen (WalkThorDrawer). Pickers and Android's prompts go through here.
 */
interface WalkThorHost {
    /** A document handed over to import. */
    val incomingFlow: MutableStateFlow<Uri?>
    val hasOtherScreenFlow: MutableStateFlow<Boolean>
    /** Bumped each time WalkThor is opened by a swipe or the AYN button. */
    val swipeTickFlow: MutableStateFlow<Long>
    /** Ticks every few seconds while WalkThor is on screen, to notice a game launched meanwhile. */
    val pollTickFlow: MutableSharedFlow<Unit>
    /** Lives as long as the host. */
    val hostScope: CoroutineScope

    fun requestMirror()
    fun closeApp()
    fun pickGuide()
    fun pickRomFolder()
    fun pickArtFolder()
    fun swapScreen(bookId: String?)
}

/** Picker types shared by both hosts. */
internal val GUIDE_TYPES = arrayOf("application/pdf", "text/plain", "text/*", "application/octet-stream")

/** Your ROMs folder was picked: read its games. */
internal fun onRomFolderPicked(context: Context, scope: CoroutineScope, uri: Uri) {
    scope.launch {
        val lib = com.nerfherder.walkthor.data.RomLibrary.get(context)
        lib.link(uri)
        val n = lib.roms.value.size
        if (n == 0) {
            Toast.makeText(context, "No games found. Pick the folder that holds your system folders (3ds, gba, switch…).", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Found $n games", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Your art folder was picked: read its pictures. */
internal fun onArtFolderPicked(context: Context, scope: CoroutineScope, uri: Uri) {
    scope.launch {
        val art = com.nerfherder.walkthor.data.GameArt.get(context)
        art.link(uri)
        val roms = com.nerfherder.walkthor.data.RomLibrary.get(context).roms.value
        val n = art.covered(roms)
        Toast.makeText(
            context,
            if (n == 0) "No covers matched your games. ${art.lastScan ?: ""}" else "Found covers for $n games",
            Toast.LENGTH_LONG,
        ).show()
    }
}

/** Android's screen-capture prompt: the whole top screen, never "a single app". */
internal fun screenCaptureIntent(context: Context): android.content.Intent? {
    val mpm = context.getSystemService(android.media.projection.MediaProjectionManager::class.java) ?: return null
    return if (android.os.Build.VERSION.SDK_INT >= 34) {
        mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
        mpm.createScreenCaptureIntent()
    }
}
