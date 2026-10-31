package com.nerfherder.walkthor.data

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Clips of the top screen, with the game's sound (never the microphone). Two ways:
 *  - **Keep the last X seconds** (up to 60): while it's on, the top screen is encoded into a
 *    rolling buffer in memory, each second dropped as a new one arrives. Nothing is saved until
 *    you press Save; then those seconds become an MP4 in Movies/WalkThor.
 *  - **Record from now** until you press Stop, written straight to an MP4.
 * It only runs while you've switched it on and WalkThor is open during a game (it uses Mirror's
 * screen access, which ends when WalkThor is put away).
 */
object Clips {
    private const val FOLDER = "Movies/WalkThor"
    private const val PREFS = "clips"
    private const val SAMPLE_RATE = 48_000
    /** Longest "record from now" clip, so it can't fill the storage by being forgotten. */
    private const val MAX_RECORD_US = 30L * 60 * 1_000_000

    /** Picture quality: size, frames per second and bit rate. */
    enum class Quality(val label: String, val height: Int, val fps: Int, val bitrate: Int) {
        LIGHT("720p", 720, 30, 5_000_000),
        STANDARD("1080p", 1080, 30, 8_000_000),
        SMOOTH("1080p 60", 1080, 60, 12_000_000),
    }
    val LENGTHS = listOf(10, 15, 30, 45, 60)

    /** Keeping the last seconds. */
    val buffering = MutableStateFlow(false)
    /** Recording from now, and since when (elapsed millis), for the timer. */
    val recording = MutableStateFlow(false)
    val recordingSince = MutableStateFlow(0L)
    /** Saved or deleted a clip: lists re-read. */
    val version = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun seconds(c: Context) = prefs(c).getInt("seconds", 30).coerceIn(5, 60)
    fun setSeconds(c: Context, s: Int) = prefs(c).edit().putInt("seconds", s).apply()
    fun quality(c: Context) = Quality.entries.firstOrNull { it.name == prefs(c).getString("quality", null) } ?: Quality.LIGHT
    fun setQuality(c: Context, q: Quality) = prefs(c).edit().putString("quality", q.name).apply()

    /** One encoded piece of video or sound. */
    private class Sample(val video: Boolean, val data: ByteArray, val ptsUs: Long, val flags: Int)

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()
    @Volatile private var videoFormat: MediaFormat? = null
    @Volatile private var audioFormat: MediaFormat? = null
    @Volatile private var keepUs = 30L * 1_000_000

    // The running pipeline (one at a time; a stopping one finishes on its own thread).
    @Volatile private var pipe: Pipeline? = null
    /** Whether clips are drawing through Mirror's display (so Mirror leaves its surface alone). */
    val drawing get() = pipe != null

    /** The live view came or went (another tab): clips take the lighter path when there's none. */
    fun previewChanged(surface: android.view.Surface?) { pipe?.route(surface) }

    // Recording from now.
    private var muxer: MediaMuxer? = null
    private var muxerFd: android.os.ParcelFileDescriptor? = null
    private var recordUri: Uri? = null
    private var muxVideoTrack = -1
    private var muxAudioTrack = -1
    private var muxStarted = false
    private var muxBaseUs = -1L
    private var recordFromUs = 0L

    /** Starts keeping the last seconds. */
    fun startBuffer(context: Context, projection: MediaProjection) {
        error.value = null
        keepUs = seconds(context) * 1_000_000L
        if (!ensurePipeline(context, projection)) return
        buffering.value = true
    }

    fun stopBuffer() {
        buffering.value = false
        if (!recording.value) stopPipeline()
    }

    /** Stops everything (WalkThor put away, or the screen access ended). */
    fun stopAll() {
        if (recording.value) runCatching { finishRecording() }
        buffering.value = false
        stopPipeline()
    }

    /** Saves the last [seconds] kept in the buffer as an MP4. Returns its location, or null. */
    suspend fun saveLast(context: Context, game: String, seconds: Int): Uri? = withContext(Dispatchers.IO) {
        val vf = videoFormat ?: return@withContext null
        val af = audioFormat
        val list = synchronized(lock) { samples.toList() }
        val lastVideo = list.lastOrNull { it.video } ?: return@withContext null
        val from = lastVideo.ptsUs - seconds * 1_000_000L
        // Start on a full picture (a key frame) at or after the start, else the first one there is.
        val key = list.firstOrNull { it.video && it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 && it.ptsUs >= from }
            ?: list.firstOrNull { it.video && it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
            ?: return@withContext null
        val (uri, pfd) = newVideoFile(context, game) ?: return@withContext null
        val ok = runCatching {
            val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vt = mux.addTrack(vf)
            val at = af?.let { mux.addTrack(it) } ?: -1
            mux.start()
            val info = MediaCodec.BufferInfo()
            for (s in list) {
                if (s.ptsUs < key.ptsUs) continue
                if (!s.video && at < 0) continue
                info.set(0, s.data.size, s.ptsUs - key.ptsUs, s.flags)
                mux.writeSampleData(if (s.video) vt else at, java.nio.ByteBuffer.wrap(s.data), info)
            }
            mux.stop()
            mux.release()
        }.isSuccess
        runCatching { pfd.close() }
        finishFile(context, uri, ok)
    }

    /** Starts recording from now (until [stopRecording]). */
    fun startRecording(context: Context, projection: MediaProjection, game: String) {
        if (recording.value) return
        error.value = null
        if (!ensurePipeline(context, projection)) return
        val (uri, pfd) = newVideoFile(context, game) ?: run { error.value = "Couldn't create the video file"; return }
        synchronized(lock) {
            recordUri = uri
            muxerFd = pfd
            muxer = runCatching { MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }.getOrNull()
            muxStarted = false
            muxBaseUs = -1L
            muxVideoTrack = -1
            muxAudioTrack = -1
            recordFromUs = System.nanoTime() / 1000
        }
        appContext = context.applicationContext
        // Ask for a full picture now, so the recording can start straight away.
        pipe?.requestKeyFrame()
        recordingSince.value = android.os.SystemClock.elapsedRealtime()
        recording.value = true
    }

    /** Stops recording and saves the clip. Returns its location, or null. */
    suspend fun stopRecording(): Uri? = withContext(Dispatchers.IO) {
        val uri = finishRecording()
        if (!buffering.value) stopPipeline()
        uri
    }

    @Volatile private var appContext: Context? = null

    private fun finishRecording(): Uri? {
        val (uri, ok) = synchronized(lock) {
            recording.value = false
            val m = muxer
            val started = muxStarted
            muxer = null
            val ok = m != null && started && runCatching { m.stop() }.isSuccess
            runCatching { m?.release() }
            runCatching { muxerFd?.close() }
            muxerFd = null
            val u = recordUri
            recordUri = null
            u to ok
        }
        val c = appContext ?: return null
        return uri?.let { finishFile(c, it, ok) }
    }

    // ---- the pipeline: screen → video encoder, game sound → AAC encoder ----

    private fun ensurePipeline(context: Context, projection: MediaProjection): Boolean {
        if (pipe != null) return true
        if (Build.VERSION.SDK_INT < 29) { error.value = "Clips need Android 10 or newer."; return false }
        val shared = com.nerfherder.walkthor.Mirror.shared.value
            ?: run { error.value = "The top screen isn't being captured yet. Try again in a moment."; return false }
        val p = Pipeline()
        return if (p.start(context, projection, shared)) {
            pipe = p
            true
        } else {
            error.value = p.problem ?: "Couldn't start recording"
            p.stop(shared)
            false
        }
    }

    private fun stopPipeline() {
        val p = pipe ?: return
        pipe = null
        synchronized(lock) { samples.clear() }
        p.stop(com.nerfherder.walkthor.Mirror.shared.value)
    }

    private class Pipeline {
        @Volatile var running = false
        var problem: String? = null
        private var videoCodec: MediaCodec? = null
        private var audioCodec: MediaCodec? = null
        private var audioRecord: AudioRecord? = null
        private var renderer: ClipRenderer? = null
        private var display: VirtualDisplay? = null
        private val threads = ArrayList<Thread>()

        fun start(context: Context, projection: MediaProjection, shared: com.nerfherder.walkthor.Mirror.Shared): Boolean {
            val q = quality(context)
            // The top screen's shape at the chosen height (even sizes, as encoders need).
            val landscape = shared.width >= shared.height
            val longOverShort = max(shared.width, shared.height).toFloat() / minOf(shared.width, shared.height).coerceAtLeast(1)
            val shortSide = q.height
            val longSide = (shortSide * longOverShort).toInt()
            val w = (if (landscape) longSide else shortSide) and 1.inv()
            val h = (if (landscape) shortSide else longSide) and 1.inv()
            return try {
                val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, q.bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, q.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    // Keep frames coming when the picture is still, so clips don't stall.
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                    // Encode no more than the chosen rate, however fast the game draws.
                    setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, q.fps.toFloat())
                    setInteger(MediaFormat.KEY_PRIORITY, 1) // not real-time: the game comes first
                    setInteger(MediaFormat.KEY_OPERATING_RATE, q.fps)
                }
                val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                videoCodec = vc
                vc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val encoderSurface = vc.createInputSurface()
                vc.start()
                this.encoderSurface = encoderSurface
                val r = ClipRenderer(
                    encoderSurface, w, h, q.fps,
                    previewSize = { shared.width to shared.height },
                    preview = { com.nerfherder.walkthor.Mirror.previewSurface },
                )
                renderer = r
                if (!r.start()) { problem = "Couldn't set up the video picture"; return false }
                videoFormat = null
                audioFormat = null
                running = true
                threads += background({ drain(vc, video = true) }, "WalkThor clips video")
                // Borrow Mirror's display: the top screen now draws at the clip's size.
                display = shared.display
                shared.display.resize(w, h, (shared.dpi * h / shared.height.coerceAtLeast(1)).coerceAtLeast(80))
                route(com.nerfherder.walkthor.Mirror.previewSurface)
                // The game's sound, if Android lets WalkThor capture it.
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    runCatching { startAudio(projection) }
                }
                true
            } catch (e: Exception) {
                problem = "Couldn't start recording (${e.javaClass.simpleName})"
                false
            }
        }

        private var encoderSurface: android.view.Surface? = null

        /**
         * Straight into the encoder when nothing on the bottom screen shows the live picture (the
         * lightest way); through the renderer, which also feeds the live view, only while one does.
         */
        fun route(preview: android.view.Surface?) {
            val d = display ?: return
            val r = renderer ?: return
            runCatching {
                if (preview == null) {
                    r.attachEncoder(false)
                    d.surface = encoderSurface
                } else {
                    d.surface = r.input
                    r.attachEncoder(true)
                }
            }
        }

        private fun background(body: () -> Unit, name: String) = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            body()
        }, name).apply { start() }

        @SuppressLint("MissingPermission")
        private fun startAudio(projection: MediaProjection) {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192)
            val rec = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(minBuf * 4).setAudioPlaybackCaptureConfig(config).build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            rec.startRecording()
            audioRecord = rec
            audioCodec = ac
            threads += background({ feedAudio(rec, ac) }, "WalkThor clips sound")
            threads += background({ drain(ac, video = false) }, "WalkThor clips sound out")
        }

        /** Game sound into the AAC encoder, timed by the clock the screen's frames use. */
        private fun feedAudio(rec: AudioRecord, ac: MediaCodec) {
            val buf = ByteArray(4096)
            while (running) {
                val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n < 0) break
                if (n == 0) continue
                // When the first of these samples was heard (4 bytes per stereo sample).
                val pts = System.nanoTime() / 1000 - (n / 4) * 1_000_000L / SAMPLE_RATE
                val idx = runCatching { ac.dequeueInputBuffer(10_000) }.getOrDefault(-1)
                if (idx < 0) continue
                val input = runCatching { ac.getInputBuffer(idx) }.getOrNull() ?: continue
                input.clear()
                val len = minOf(n, input.remaining())
                input.put(buf, 0, len)
                runCatching { ac.queueInputBuffer(idx, 0, len, pts, 0) }
            }
        }

        /** Takes the encoder's output into the buffer (and into a recording, while there is one). */
        private fun drain(codec: MediaCodec, video: Boolean) {
            val info = MediaCodec.BufferInfo()
            while (running) {
                val idx = runCatching { codec.dequeueOutputBuffer(info, 20_000) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = runCatching { codec.outputFormat }.getOrNull()
                        if (video) videoFormat = f else audioFormat = f
                    }
                    idx >= 0 -> {
                        val out = runCatching { codec.getOutputBuffer(idx) }.getOrNull()
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (out != null && info.size > 0 && !config) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            out.get(bytes)
                            if (pipe === this) add(Sample(video, bytes, info.presentationTimeUs, info.flags))
                        }
                        runCatching { codec.releaseOutputBuffer(idx, false) }
                    }
                }
            }
        }

        fun requestKeyFrame() {
            runCatching { videoCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }

        /** Gives Mirror its display back now, then frees the rest on a thread of its own. */
        fun stop(shared: com.nerfherder.walkthor.Mirror.Shared?) {
            running = false
            val d = display
            if (d != null && shared != null && shared.display === d) {
                runCatching {
                    d.surface = null
                    d.resize(shared.width, shared.height, shared.dpi)
                    d.surface = com.nerfherder.walkthor.Mirror.previewSurface
                }
            }
            display = null
            Thread({
                // Stop the sound capture first: that wakes a read waiting on a quiet game, so its
                // thread can finish. Freeing it while a read is still running crashes WalkThor.
                runCatching { audioRecord?.stop() }
                threads.forEach { runCatching { it.join(2_000) } }
                renderer?.let { it.release(); it.awaitReleased() }
                if (threads.none { it.isAlive }) runCatching { audioRecord?.release() }
                runCatching { audioCodec?.stop() }
                runCatching { audioCodec?.release() }
                runCatching { videoCodec?.stop() }
                runCatching { videoCodec?.release() }
            }, "WalkThor clips stop").start()
        }
    }

    private fun add(s: Sample) {
        synchronized(lock) {
            if (buffering.value) {
                samples.addLast(s)
                // Keep a little more than asked, so the clip can start on a full picture.
                val cut = s.ptsUs - keepUs - 2_000_000L
                while (samples.isNotEmpty() && samples.first().ptsUs < cut) samples.removeFirst()
            } else if (samples.isNotEmpty()) samples.clear()
            if (recording.value) writeToRecording(s)
        }
    }

    /** Called with [lock] held. */
    private fun writeToRecording(s: Sample) {
        val m = muxer ?: return
        if (!muxStarted) {
            // Wait for a full picture after the moment Record was pressed, and the formats.
            val vf = videoFormat ?: return
            if (!s.video || s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0 || s.ptsUs < recordFromUs - 500_000L) return
            val started = runCatching {
                muxVideoTrack = m.addTrack(vf)
                audioFormat?.let { muxAudioTrack = m.addTrack(it) }
                m.start()
            }.isSuccess
            if (!started) return
            muxStarted = true
            muxBaseUs = s.ptsUs
        }
        if (!s.video && muxAudioTrack < 0) return
        val t = s.ptsUs - muxBaseUs
        if (t < 0) return
        if (t > MAX_RECORD_US) {
            // Long enough: save what there is.
            recording.value = false
            Thread({ finishRecording(); if (!buffering.value) stopPipeline() }, "WalkThor clips save").start()
            return
        }
        val info = MediaCodec.BufferInfo().apply { set(0, s.data.size, t, s.flags) }
        runCatching { m.writeSampleData(if (s.video) muxVideoTrack else muxAudioTrack, java.nio.ByteBuffer.wrap(s.data), info) }
    }

    // ---- files ----

    private fun prefix(game: String) = game.replace(Regex("""[\\/:*?"<>|]"""), " ").replace(Regex("""\s+"""), " ").trim().take(60).ifEmpty { "WalkThor" }

    private fun newVideoFile(context: Context, game: String): Pair<Uri, android.os.ParcelFileDescriptor>? {
        if (Build.VERSION.SDK_INT < 29) return null
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${prefix(game)} $stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val pfd = runCatching { r.openFileDescriptor(uri, "rw") }.getOrNull() ?: run { r.delete(uri, null, null); return null }
        return uri to pfd
    }

    private fun finishFile(context: Context, uri: Uri, ok: Boolean): Uri? {
        val r = context.contentResolver
        if (!ok) { runCatching { r.delete(uri, null, null) }; return null }
        runCatching { r.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) }
        version.value++
        return uri
    }

    /** One saved clip. */
    data class Clip(val uri: Uri, val name: String, val takenAt: Long, val durationMs: Long)

    /** For store screenshots only: clips to list, a still to show for them, and controls switched on. */
    internal var demoClips: List<Clip>? = null
    internal var demoThumb: android.graphics.Bitmap? = null
    internal var demoReady = false

    /** This game's clips, newest first. */
    suspend fun list(context: Context, game: String): List<Clip> = withContext(Dispatchers.IO) {
        demoClips?.let { return@withContext it }
        if (Build.VERSION.SDK_INT < 29) return@withContext emptyList()
        val out = ArrayList<Clip>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("$FOLDER%", prefix(game) + " %"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Clip(
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                        c.getString(1) ?: "", c.getLong(2) * 1000L, c.getLong(3),
                    )
                }
            }
        }
        out
    }

    fun delete(context: Context, clip: Clip) {
        runCatching { context.contentResolver.delete(clip.uri, null, null) }
        version.value++
    }
}
