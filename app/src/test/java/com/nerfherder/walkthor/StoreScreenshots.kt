package com.nerfherder.walkthor

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import androidx.compose.runtime.Composable
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
                BootStatus.Playing("Lanterns of Emberfall", shelf[0], null),
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
        compose.onNode(hasSetTextAction()).performImeAction()
        settle()
        shot("04b-search-highlights")

        compose.onNodeWithContentDescription("Two-page spread").performClick()
        settle()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("— 2 —").fetchSemanticsNodes().isNotEmpty() }
        settle()
        shot("03b-reader-spread")
    }
}
