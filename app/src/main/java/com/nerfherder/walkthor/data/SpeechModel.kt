package com.nerfherder.walkthor.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * WalkThor's own speech recognizer for Captions, for devices without one: a small English model
 * for Vosk (offline speech recognition, Apache 2.0), downloaded once on request (about 40 MB) and
 * kept in WalkThor's storage. After that it works offline; nothing is sent anywhere.
 */
object SpeechModel {
    private const val URL_MODEL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    const val SIZE_LABEL = "40 MB"

    sealed interface State {
        data object Missing : State
        data class Downloading(val percent: Int) : State
        data object Unpacking : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Missing)

    fun dir(c: Context) = File(c.filesDir, "speech/en")
    private fun ready(c: Context) = File(dir(c), "am/final.mdl").exists() || File(dir(c), "final.mdl").exists()

    fun refresh(c: Context) {
        if (state.value is State.Downloading || state.value is State.Unpacking) return
        state.value = if (ready(c)) State.Ready else State.Missing
    }

    fun isReady(c: Context) = ready(c)

    /** Downloads and unpacks the model (only when you ask). */
    suspend fun download(context: Context) = withContext(Dispatchers.IO) {
        val c = context.applicationContext
        if (state.value is State.Downloading || state.value is State.Unpacking) return@withContext
        state.value = State.Downloading(0)
        val zip = File(c.cacheDir, "speech-model.zip")
        try {
            val conn = URL(URL_MODEL).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "WalkThor")
                if (conn.responseCode != 200) throw java.io.IOException("the download server answered ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: (40L * 1024 * 1024)
                conn.inputStream.use { input ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val p = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (p != shown) { shown = p; state.value = State.Downloading(p) }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            state.value = State.Unpacking
            val target = dir(c)
            val tmp = File(target.parentFile, "en.tmp").apply { deleteRecursively(); mkdirs() }
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    // Drop the zip's top folder ("vosk-model-small-en-us-0.15/").
                    val rel = e.name.substringAfter('/', "").takeIf { it.isNotEmpty() } ?: continue
                    val out = File(tmp, rel)
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath)) continue // never outside the folder
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                }
            }
            target.deleteRecursively()
            if (!tmp.renameTo(target)) throw java.io.IOException("couldn't keep the model")
            state.value = if (ready(c)) State.Ready else State.Failed("The model didn't unpack properly. Try again.")
        } catch (e: Exception) {
            state.value = State.Failed("Couldn't download the speech model (${e.message ?: e.javaClass.simpleName}).")
        } finally {
            zip.delete()
        }
    }

    fun delete(c: Context) {
        dir(c).deleteRecursively()
        state.value = State.Missing
    }
}
