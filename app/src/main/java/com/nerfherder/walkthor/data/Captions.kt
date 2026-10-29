package com.nerfherder.walkthor.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Captions (test): captures the game's own sound (not the microphone) through the screen access
 * Mirror uses, shows how loud it is, and passes it to Android's speech recognizer to turn speech
 * into captions. Nothing is recorded, saved or sent by WalkThor; the sound only goes to the
 * recognizer on this device while captions are on.
 */
object Captions {
    private const val RATE = 16_000

    /** Running. */
    val on = MutableStateFlow(false)
    /** How loud the game's sound is right now, 0 (silent) to 1. */
    val level = MutableStateFlow(0f)
    /** The loudest it's been since starting, 0..1 (to tell "nothing arrives" from "quiet"). */
    val peak = MutableStateFlow(0f)
    /** Sound samples received so far (0 = nothing is arriving at all). */
    val samples = MutableStateFlow(0L)
    /** What the recognizer is doing, for the test readout. */
    val recognizer = MutableStateFlow("not started")
    /** The caption being heard now, and the last few finished lines. */
    val partial = MutableStateFlow("")
    val lines = MutableStateFlow<List<String>>(emptyList())
    /** Why it couldn't start, if it couldn't. */
    val error = MutableStateFlow<String?>(null)

    @Volatile private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var pipeOut: FileOutputStream? = null
    private var speech: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    /** Whether this Android can hand recorded sound to the speech recognizer (13 and newer). */
    val canRecognizeStream: Boolean get() = Build.VERSION.SDK_INT >= 33

    @SuppressLint("MissingPermission")
    fun start(context: Context, projection: MediaProjection) {
        if (on.value) return
        error.value = null
        if (Build.VERSION.SDK_INT < 29) { error.value = "Needs Android 10 or newer."; return }
        val app = context.applicationContext
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val rec = runCatching {
            AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(minBuf * 4).setAudioPlaybackCaptureConfig(config).build()
        }.getOrElse { error.value = "Couldn't capture the game's sound (${it.javaClass.simpleName})"; return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { error.value = "Android didn't allow capturing the game's sound."; rec.release(); return }
        record = rec
        level.value = 0f; peak.value = 0f; samples.value = 0L; partial.value = ""; lines.value = emptyList()
        on.value = true
        runCatching { rec.startRecording() }.onFailure { error.value = "Couldn't start capturing (${it.javaClass.simpleName})"; stop(); return }
        thread = Thread({ readLoop(rec) }, "WalkThor captions").apply { start() }
        startRecognizer(app)
    }

    fun stop() {
        on.value = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        thread = null
        closePipe()
        main.post {
            runCatching { speech?.destroy() }
            speech = null
            recognizer.value = "stopped"
        }
        level.value = 0f
    }

    private fun readLoop(rec: AudioRecord) {
        val buf = ShortArray(RATE / 10) // a tenth of a second
        val bytes = ByteArray(buf.size * 2)
        while (on.value && record === rec) {
            val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
            if (n <= 0) { if (n < 0) break else continue }
            var sum = 0.0
            for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
            val rms = sqrt(sum / n) / Short.MAX_VALUE
            // Shown on a decibel scale, so quiet speech still moves the meter.
            val db = if (rms <= 0.0) -60.0 else (20 * log10(rms)).coerceIn(-60.0, 0.0)
            val l = ((db + 60.0) / 60.0).toFloat()
            level.value = l
            if (l > peak.value) peak.value = l
            samples.value += n
            pipeOut?.let { out ->
                for (i in 0 until n) { bytes[2 * i] = (buf[i].toInt() and 0xFF).toByte(); bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                try { out.write(bytes, 0, n * 2) } catch (_: IOException) { closePipe() }
            }
        }
    }

    private fun closePipe() {
        runCatching { pipeOut?.close() }
        pipeOut = null
    }

    /** Starts (or restarts, after it finishes a stretch of speech) the recognizer on the game's sound. */
    private fun startRecognizer(context: Context) {
        if (!canRecognizeStream) { recognizer.value = "needs Android 13 to caption game sound"; return }
        main.post {
            if (!on.value) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                recognizer.value = "no speech recognizer on this device" + recognizers(context).let { if (it.isEmpty()) "" else " (found: $it)" }
                return@post
            }
            val onDevice = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
            val sr = runCatching {
                if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
            }.getOrElse { recognizer.value = "couldn't start the recognizer (${it.javaClass.simpleName})"; return@post }
            speech?.destroy()
            speech = sr
            val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse { recognizer.value = "couldn't connect the sound"; return@post }
            closePipe()
            pipeOut = FileOutputStream(pipe[1].fileDescriptor)
            val mode = if (onDevice) "on-device" else "speech service: " + recognizers(context).ifEmpty { "default" }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { recognizer.value = "listening ($mode)" }
                override fun onBeginningOfSpeech() { recognizer.value = "hearing speech ($mode)" }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(code: Int) {
                    recognizer.value = "recognizer: ${errorName(code)} (restarting)"
                    restart(context, pipe)
                }
                override fun onResults(results: Bundle?) {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { t ->
                        lines.value = (lines.value + t).takeLast(4)
                    }
                    partial.value = ""
                    restart(context, pipe)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial.value = it }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            recognizer.value = "starting ($mode)"
            runCatching { sr.startListening(intent) }.onFailure { recognizer.value = "couldn't start listening (${it.javaClass.simpleName})" }
        }
    }

    /** The speech recognizer apps Android lists, for the readout. */
    fun recognizers(context: Context): String = runCatching {
        context.packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), 0)
            .joinToString(", ") { it.serviceInfo.packageName }
    }.getOrDefault("")

    private fun restart(context: Context, old: Array<ParcelFileDescriptor>) {
        runCatching { old[0].close() }
        runCatching { old[1].close() }
        if (on.value) main.postDelayed({ startRecognizer(context) }, 300)
    }

    private fun errorName(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "no words recognised"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
        SpeechRecognizer.ERROR_AUDIO -> "sound problem"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "needs the internet"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not allowed"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_SERVER -> "server error"
        else -> "error $code"
    }
}
