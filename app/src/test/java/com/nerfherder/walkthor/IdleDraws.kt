package com.nerfherder.walkthor

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.BookRepository
import com.nerfherder.walkthor.data.BookType
import com.nerfherder.walkthor.data.GameArt
import com.nerfherder.walkthor.data.LaunchedGame
import com.nerfherder.walkthor.ui.AutoDim
import com.nerfherder.walkthor.ui.AutoDimOverlay
import com.nerfherder.walkthor.ui.BookColors
import com.nerfherder.walkthor.ui.DarkPages
import com.nerfherder.walkthor.ui.LocalPageColors
import com.nerfherder.walkthor.ui.NowPlayingScreen
import com.nerfherder.walkthor.ui.PlaySession
import com.nerfherder.walkthor.ui.ReaderScreen
import com.nerfherder.walkthor.ui.StatusClock
import com.nerfherder.walkthor.ui.WalkThorTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * How often each screen redraws while it should be sitting still (e.g. dimmed during a game).
 * Counts window draws over 10 seconds after everything has settled and writes the results to
 * build/screenshots/idle-draws.txt. Ideally every number is 0.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w620dp-h540dp-land-xhdpi")
class IdleDraws {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private var draws = 0

    @Before
    fun setUp() {
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 78)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
        )
        PlaySession.current.value = LaunchedGame("Lanterns of Emberfall", null, "Dolphin Emulator", System.currentTimeMillis() - 47 * 60_000L)
        PlaySession.showTimer.value = true
        AutoDim.dimmed.value = false
        AutoDim.delayMs.value = 0L
        compose.activity.window.decorView.viewTreeObserver.addOnDrawListener { draws++ }
    }

    private fun step(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun run(seconds: Int) = repeat(seconds * 10) { step(100) }

    private fun content(body: @Composable () -> Unit) = compose.setContent {
        WalkThorTheme {
            CompositionLocalProvider(LocalPageColors provides DarkPages) {
                Box(Modifier.fillMaxSize().background(BookColors.Ink)) { body() }
            }
        }
    }

    /** Settles, optionally dims, then counts draws over 10 s. */
    private fun measure(name: String, dim: Boolean) {
        run(4)
        if (dim) {
            AutoDim.delayMs.value = 60_000L
            AutoDim.dimmed.value = true
            run(4)
        }
        draws = 0
        run(10)
        val line = "$name${if (dim) " (dimmed)" else ""}: $draws draws in 10 s"
        println("IDLE-DRAWS $line")
        File("build/screenshots").mkdirs()
        File("build/screenshots/idle-draws.txt").appendText(line + "\n")
        AutoDim.dimmed.value = false
        AutoDim.delayMs.value = 0L
    }

    private val guide = Book("g1", "Lanterns of Emberfall - Complete Walkthrough", BookType.TXT, "x", 0L)
    private val game = LaunchedGame("Lanterns of Emberfall", "Lanterns of Emberfall (USA).rvz", "Dolphin Emulator", System.currentTimeMillis())

    @Test fun a_blank() { content { }; measure("blank screen", dim = false) }

    @Test fun b_statusClock() { content { StatusClock() }; measure("status clock", dim = false) }

    @Test fun c_dimOverlay() { content { AutoDimOverlay() }; measure("dim overlay alone", dim = true) }

    @Test fun d_nowPlaying() {
        content {
            NowPlayingScreen(game, GameArt(null), listOf(guide), { null }, {}, {}, {}, {}, guide = guide)
        }
        measure("now playing", dim = false)
    }

    @Test fun e_nowPlayingDimmed() {
        content {
            NowPlayingScreen(game, GameArt(null), listOf(guide), { null }, {}, {}, {}, {}, guide = guide)
            AutoDimOverlay()
        }
        measure("now playing + dim", dim = true)
    }

    @Test fun f_readerDimmed() {
        val sample = File(app.cacheDir, "Lanterns.txt")
        javaClass.classLoader!!.getResourceAsStream("sample-guide.txt")!!.use { i -> sample.outputStream().use { i.copyTo(it) } }
        val book = runBlocking { BookRepository.get(app).importGuide(Uri.fromFile(sample)) {} }
        content {
            ReaderScreen(bookId = book.id, onBack = {}, onClose = {}, onSwapScreen = {})
            AutoDimOverlay()
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("— 1 —").fetchSemanticsNodes().isNotEmpty() }
        measure("reader", dim = false)
        measure("reader + dim", dim = true)
    }

    /** Control: the full boot animation redraws continuously, so this must NOT be 0. */
    @Test fun z_controlBootAnimation() {
        content { com.nerfherder.walkthor.ui.BootScreen(com.nerfherder.walkthor.ui.BootStatus.Checking, false, {}, {}, light = false) }
        measure("CONTROL boot animation (should be high)", dim = false)
    }
}
