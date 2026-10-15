package com.nerfherder.walkthor

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.BookRepository
import com.nerfherder.walkthor.data.BookType
import com.nerfherder.walkthor.data.LaunchedGame
import com.nerfherder.walkthor.ui.AutoDim
import com.nerfherder.walkthor.ui.BootScreen
import com.nerfherder.walkthor.ui.NowPlayingScreen
import com.nerfherder.walkthor.data.GameArt
import com.nerfherder.walkthor.ui.BootStatus
import com.nerfherder.walkthor.ui.DarkPages
import com.nerfherder.walkthor.ui.LibraryActions
import com.nerfherder.walkthor.ui.LibraryScreen
import com.nerfherder.walkthor.ui.LocalPageColors
import com.nerfherder.walkthor.ui.PlaySession
import com.nerfherder.walkthor.ui.ReaderScreen
import com.nerfherder.walkthor.ui.WalkThorTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders WalkThor's screens at the Thor bottom screen's 1240×1080 for sharing (README, posts).
 * Uses a fictional sample guide and generated covers. Output: app/build/screenshots/.
 * Run: ./gradlew testDebugUnitTest --tests "*StoreScreenshots*"
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w620dp-h540dp-land-xhdpi")
class StoreScreenshots {

    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun shot(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/screenshots/$name.png")
    }

    /** Lets the view model's debounce and background work finish (they run on real time and the main looper). */
    private fun settle() {
        repeat(12) {
            compose.mainClock.advanceTimeBy(150)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            Thread.sleep(80)
        }
        compose.waitForIdle()
    }

    @Before
    fun setUp() {
        // A believable battery reading for the status corner.
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 78)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
        )
        PlaySession.current.value = LaunchedGame(
            title = "Lanterns of Emberfall",
            file = null,
            player = "Dolphin Emulator",
            at = System.currentTimeMillis() - 47 * 60_000L,
        )
        PlaySession.showTimer.value = true
    }

    private fun content(body: @Composable () -> Unit) = compose.setContent {
        WalkThorTheme {
            CompositionLocalProvider(LocalPageColors provides DarkPages) { body() }
        }
    }

    private val shelf = listOf(
        "Lanterns of Emberfall - Complete Walkthrough",
        "Starfall Tactics - Unit & Class Guide",
        "Moss & Marrow 100% Guide",
        "Kingdom of Glass FAQ",
        "Neon Drift Racing - All Shortcuts",
        "The Sunken Archive Walkthrough",
        "Paper Knights - Side Quests",
        "Hollow Tide Boss Guide",
    ).mapIndexed { i, t -> Book("sample$i", t, if (i % 3 == 1) BookType.PDF else BookType.TXT, "x", 0L) }

    private fun actions() = LibraryActions(
        onOpen = {}, onImport = {}, onDelete = {}, onRename = { _, _ -> }, coverFile = { null },
        onPickCoverFile = {}, onSetCover = { _, _ -> }, onClearCover = {}, suggestions = { emptyList() },
        onLinkArtFolder = {}, onLinkCocoon = {}, onToggleGameGuide = {}, onUnlinkCocoon = {},
        onRescanArt = {}, onUnlinkArt = {}, onToggleAlwaysBottom = {}, onToggleSwipeToOpen = {},
        onClose = {}, onGrantSwipePermission = {}, hasSwipeConsent = { true }, onSwipeConsent = {},
        isSwipeServiceOn = { true }, onOpenAppInfo = {}, onSwapScreen = {},
    )

    @Composable
    private fun Library() = LibraryScreen(
        books = shelf,
        artLinked = true,
        artCount = 412,
        artScanning = null,
        alwaysBottom = true,
        swipeToOpen = true,
        gameGuide = true,
        cocoonLinked = true,
        swipeNeedsPermission = false,
        actions = actions(),
    )

    @Test
    fun library() {
        content { Library() }
        shot("02-library")
    }

    @Test
    fun autoDimSettings() {
        AutoDim.delayMs.value = 60_000L
        content { Library() }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Auto-dim").performClick()
        shot("05-auto-dim")
        AutoDim.delayMs.value = 0L
    }

    @Test
    fun bootScreen() {
        compose.mainClock.autoAdvance = false
        content { BootScreen(BootStatus.Checking, finished = false, onSkip = {}, onGone = {}) }
        compose.mainClock.advanceTimeBy(1_300)
        shot("01a-boot-checking")
    }

    @Test
    fun bootIntoGuide() {
        compose.mainClock.autoAdvance = false
        content {
            BootScreen(
                BootStatus.Playing("Lanterns of Emberfall", shelf[0], null, player = "Dolphin Emulator"),
                finished = false, onSkip = {}, onGone = {},
            )
        }
        compose.mainClock.advanceTimeBy(2_000)
        shot("01b-boot-now-playing")
    }

    @Test
    fun reader() {
        val sample = File(app.cacheDir, "Lanterns of Emberfall - Complete Walkthrough.txt")
        javaClass.classLoader!!.getResourceAsStream("sample-guide.txt")!!.use { input ->
            sample.outputStream().use { input.copyTo(it) }
        }
        val book = runBlocking { BookRepository.get(app).importGuide(Uri.fromFile(sample)) {} }

        content { ReaderScreen(bookId = book.id, onBack = {}, onClose = {}, onSwapScreen = {}) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("— 1 —").fetchSemanticsNodes().isNotEmpty() }
        shot("03-reader")

        // Search, then dismiss the keyboard so the highlighted matches show on the page.
        compose.onNode(hasSetTextAction()).performTextInput("lantern")
        settle()
        shot("04a-search-results")
        // Jump to a result: the list closes and the page shows its highlighted matches.
        compose.onNodeWithText("p. 2").performClick()
        settle()
        shot("04b-search-highlights")
    }

    /** A made-up box-art image for the fictional game, drawn on the fly. */
    private fun fakeBoxArt(): String {
        val w = 600; val h = 840
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1B2A4A.toInt(), 0xFF6B2E1F.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = android.graphics.RadialGradient(w / 2f, h * 0.62f, 220f, 0xFFFFC56B.toInt(), 0x00FFC56B, android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(w / 2f, h * 0.62f, 220f, p)
        p.shader = null
        p.color = 0xFF2B1A12.toInt()
        c.drawRect(w / 2f - 50f, h * 0.55f, w / 2f + 50f, h * 0.72f, p) // a lantern
        p.color = 0xFFFFE2A8.toInt()
        c.drawRect(w / 2f - 36f, h * 0.57f, w / 2f + 36f, h * 0.70f, p)
        p.color = 0xFFF3E6C8.toInt()
        p.textAlign = android.graphics.Paint.Align.CENTER
        p.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        p.textSize = 64f
        c.drawText("LANTERNS", w / 2f, 150f, p)
        p.textSize = 40f
        c.drawText("of EMBERFALL", w / 2f, 205f, p)
        val f = File(app.cacheDir, "Lanterns of Emberfall (USA).png")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f).toString()
    }

    private val noGuideGame get() = LaunchedGame("Lanterns of Emberfall", "Lanterns of Emberfall (USA).rvz", "Dolphin Emulator", System.currentTimeMillis())

    @Test
    fun noGuide() {
        val art = fakeBoxArt()
        content {
            NowPlayingScreen(
                game = noGuideGame,
                art = GameArt(cover = art),
                books = shelf.drop(1),
                coverFile = { null },
                onAddGuide = {}, onPickGuide = {}, onLibrary = {}, onClose = {},
            )
        }
        settle()
        shot("06-no-guide")
    }

    /** The same screen at a denser setting (fewer dp), to check nothing gets cut off. */
    @Test
    @Config(qualifiers = "w413dp-h360dp-land-xxhdpi")
    fun noGuideDense() {
        val art = fakeBoxArt()
        content {
            NowPlayingScreen(
                game = noGuideGame,
                art = GameArt(cover = art),
                books = shelf.drop(1),
                coverFile = { null },
                onAddGuide = {}, onPickGuide = {}, onLibrary = {}, onClose = {},
            )
        }
        settle()
        shot("check-no-guide-dense")
    }

    @Test
    fun bootIntoArt() {
        val art = fakeBoxArt()
        compose.mainClock.autoAdvance = false
        content {
            BootScreen(BootStatus.Playing("Lanterns of Emberfall", art = art, player = "Dolphin Emulator"), finished = false, onSkip = {}, onGone = {})
        }
        repeat(20) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(50)
        }
        shot("06a-boot-no-guide")
    }

    // ---- the same screens at a denser display setting, to catch anything cut off ----

    @Test
    @Config(qualifiers = "w413dp-h360dp-land-xxhdpi")
    fun libraryDense() {
        content { Library() }
        shot("check-library-dense")
    }

    @Test
    @Config(qualifiers = "w413dp-h360dp-land-xxhdpi")
    fun autoDimDense() {
        AutoDim.delayMs.value = 60_000L
        content { Library() }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Auto-dim").performClick()
        shot("check-auto-dim-dense")
        AutoDim.delayMs.value = 0L
    }

    @Test
    @Config(qualifiers = "w413dp-h360dp-land-xxhdpi")
    fun bootDense() {
        compose.mainClock.autoAdvance = false
        content {
            BootScreen(BootStatus.Playing("Lanterns of Emberfall", shelf[0], null, player = "Dolphin Emulator"), finished = false, onSkip = {}, onGone = {})
        }
        compose.mainClock.advanceTimeBy(2_000)
        shot("check-boot-dense")
    }

    @Test
    @Config(qualifiers = "w413dp-h360dp-land-xxhdpi")
    fun readerDense() {
        val sample = File(app.cacheDir, "Lanterns of Emberfall - Complete Walkthrough.txt")
        javaClass.classLoader!!.getResourceAsStream("sample-guide.txt")!!.use { input ->
            sample.outputStream().use { input.copyTo(it) }
        }
        val book = runBlocking { BookRepository.get(app).importGuide(Uri.fromFile(sample)) {} }
        content { ReaderScreen(bookId = book.id, onBack = {}, onClose = {}, onSwapScreen = {}) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("— 1 —").fetchSemanticsNodes().isNotEmpty() }
        settle()
        shot("check-reader-dense")
    }

    /** Dimmed during a game: the bright status corner plus what's playing, centred. */
    @Test
    fun dimmed() {
        PlaySession.current.value = LaunchedGame(
            "Legend of Zelda, The - The Wind Waker", null, "Dolphin Emulator", System.currentTimeMillis() - 47 * 60_000L,
        )
        AutoDim.delayMs.value = 60_000L
        content {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                Library()
                com.nerfherder.walkthor.ui.AutoDimOverlay()
            }
        }
        AutoDim.dimmed.value = true
        settle()
        shot("07-dimmed")
        AutoDim.dimmed.value = false
        AutoDim.delayMs.value = 0L
    }

    @Test
    fun nowPlayingWithGuide() {
        content {
            NowPlayingScreen(
                game = noGuideGame,
                art = GameArt(null),
                books = shelf,
                coverFile = { null },
                onAddGuide = {}, onPickGuide = {}, onLibrary = {}, onClose = {},
                guide = shelf[0],
            )
        }
        settle()
        shot("06b-now-playing-guide")
    }

    @Test
    fun dimmedWithGuide() {
        AutoDim.delayMs.value = 60_000L
        content {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                NowPlayingScreen(
                    game = noGuideGame,
                    art = GameArt(null),
                    books = shelf,
                    coverFile = { null },
                    onAddGuide = {}, onPickGuide = {}, onLibrary = {}, onClose = {},
                    guide = shelf[0],
                )
                com.nerfherder.walkthor.ui.AutoDimOverlay()
            }
        }
        settle()
        AutoDim.dimmed.value = true
        settle()
        shot("07b-dimmed-open-guide")
        AutoDim.dimmed.value = false
        AutoDim.delayMs.value = 0L
    }
}
