package com.nerfherder.walkthor.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Screenshots of the top screen, taken from the Shots tab: the whole screen or the part you
 * framed, saved as PNGs in Pictures/WalkThor and named after the game. Only taken when you press
 * the button; nothing is sent anywhere.
 */
object Shots {
    private const val FOLDER = "Pictures/WalkThor"
    private const val PREFS = "shots"

    /** One saved screenshot. */
    data class Shot(val uri: Uri, val name: String, val takenAt: Long, val area: Boolean)

    /** Bumped after a screenshot is saved or deleted, so lists re-read. */
    val version = MutableStateFlow(0)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(game: String) = Matcher.words(game).joinToString(" ")

    /** The part of the screen framed for [game] (fractions left, top, right, bottom), or null for the whole screen. */
    fun area(c: Context, game: String): FloatArray? =
        prefs(c).getString(key(game) + "#area", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 4 }?.toFloatArray()

    fun setArea(c: Context, game: String, area: FloatArray) {
        prefs(c).edit().putString(key(game) + "#area", area.joinToString(",")).apply()
    }

    /** Whether [game] last used an area (else the whole screen). */
    fun useArea(c: Context, game: String) = prefs(c).getBoolean(key(game) + "#useArea", false)
    fun setUseArea(c: Context, game: String, on: Boolean) { prefs(c).edit().putBoolean(key(game) + "#useArea", on).apply() }

    /** A file-name-safe version of the game's name, which also starts each of its screenshots' names. */
    private fun prefix(game: String) = game.replace(Regex("""[\\/:*?"<>|]"""), " ").replace(Regex("""\s+"""), " ").trim().take(60)

    /**
     * Takes a screenshot of the top screen at full resolution and saves it. [preview] (the live
     * picture) is used if a full-size capture isn't possible. Returns the saved shot, or null.
     */
    suspend fun take(context: Context, projection: MediaProjection?, preview: Bitmap?, game: String, area: FloatArray?): Shot? {
        if (Build.VERSION.SDK_INT < 29) return null
        val full = projection?.let { runCatching { captureFull(context, it) }.getOrNull() }
        val source = full ?: preview?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() } ?: return null
        val shot = crop(source, area)
        return withContext(Dispatchers.IO) { save(context, shot, game, area != null) }.also {
            if (shot !== source) source.recycle()
            shot.recycle()
            version.value++
        }
    }

    /** One frame of the top screen at its full size, through a short-lived mirror of it. */
    private suspend fun captureFull(context: Context, projection: MediaProjection): Bitmap? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val top = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val size = Point()
        @Suppress("DEPRECATION") top.getRealSize(size)
        val w = max(2, size.x) and 1.inv()
        val h = max(2, size.y) and 1.inv()
        val thread = HandlerThread("WalkThor shot").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var display: android.hardware.display.VirtualDisplay? = null
        try {
            return withTimeoutOrNull(2_000) {
                suspendCancellableCoroutine { cont ->
                    reader.setOnImageAvailableListener({ r ->
                        val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        val bmp = runCatching {
                            val plane = img.planes[0]
                            val rowPixels = plane.rowStride / plane.pixelStride
                            val raw = Bitmap.createBitmap(rowPixels, h, Bitmap.Config.ARGB_8888)
                            raw.copyPixelsFromBuffer(plane.buffer)
                            if (rowPixels == w) raw else Bitmap.createBitmap(raw, 0, 0, w, h).also { raw.recycle() }
                        }.getOrNull()
                        img.close()
                        r.setOnImageAvailableListener(null, null)
                        if (cont.isActive) cont.resume(bmp)
                    }, handler)
                    display = projection.createVirtualDisplay(
                        "WalkThor screenshot", w, h, context.resources.displayMetrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
                    )
                }
            }
        } finally {
            runCatching { display?.release() }
            runCatching { reader.close() }
            thread.quitSafely()
        }
    }

    private fun crop(src: Bitmap, a: FloatArray?): Bitmap {
        if (a == null) return src
        val l = (a[0] * src.width).roundToInt().coerceIn(0, src.width - 1)
        val t = (a[1] * src.height).roundToInt().coerceIn(0, src.height - 1)
        val r = (a[2] * src.width).roundToInt().coerceIn(l + 1, src.width)
        val b = (a[3] * src.height).roundToInt().coerceIn(t + 1, src.height)
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    private fun save(context: Context, bmp: Bitmap, game: String, area: Boolean): Shot? {
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date(now))
        val name = "${prefix(game).ifEmpty { "WalkThor" }} $stamp${if (area) " area" else ""}.png"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
        }.getOrDefault(false)
        if (!ok) { runCatching { resolver.delete(uri, null, null) }; return null }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return Shot(uri, name, now, area)
    }

    /** This game's screenshots, newest first. */
    suspend fun list(context: Context, game: String): List<Shot> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext emptyList()
        val start = prefix(game).ifEmpty { "WalkThor" } + " "
        val out = ArrayList<Shot>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("$FOLDER%", "$start%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    out += Shot(
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        name, c.getLong(2) * 1000L, name.endsWith(" area.png"),
                    )
                }
            }
        }
        out
    }

    fun delete(context: Context, shot: Shot) {
        runCatching { context.contentResolver.delete(shot.uri, null, null) }
        version.value++
    }

    fun share(context: Context, shot: Shot) {
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, shot.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(Intent.createChooser(send, "Share screenshot").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
