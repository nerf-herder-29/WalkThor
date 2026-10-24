package com.nerfherder.walkthor

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
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

    /** With [hero], the screen sits over the game's dimmed art, as the game's tabs do in the app. */
    private fun content(hero: String? = null, body: @Composable () -> Unit) = compose.setContent {
        WalkThorTheme {
            CompositionLocalProvider(LocalPageColors provides DarkPages) {
                if (hero == null) body()
                else androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    com.nerfherder.walkthor.ui.GameBackdrop(hero)
                    CompositionLocalProvider(com.nerfherder.walkthor.ui.LocalBackdrop provides true) { body() }
                }
            }
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
    fun setupGuide() {
        content { Library() }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("How to set up WalkThor").performClick()
        shot("09-setup-guide")
    }

    @Test
    @Config(qualifiers = "w413dp-h360dp-land-xxhdpi")
    fun setupGuideDense() {
        content { Library() }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("How to set up WalkThor").performClick()
        shot("09-setup-guide-dense")
    }

    /** A made-up top-screen frame for the fictional game: a scene with a health bar, minimap and item box. */
    private fun fakeGameFrame(): androidx.compose.ui.graphics.ImageBitmap {
        val w = 960; val h = 540
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1B2A55.toInt(), 0xFFB0603A.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        p.color = 0xFF2A2238.toInt()
        c.drawPath(android.graphics.Path().apply { moveTo(0f, 330f); cubicTo(220f, 260f, 380f, 360f, 560f, 300f); cubicTo(740f, 240f, 860f, 340f, 960f, 290f); lineTo(960f, 540f); lineTo(0f, 540f); close() }, p)
        p.color = 0xFF1A1622.toInt()
        c.drawPath(android.graphics.Path().apply { moveTo(0f, 420f); cubicTo(300f, 380f, 600f, 450f, 960f, 400f); lineTo(960f, 540f); lineTo(0f, 540f); close() }, p)
        val rnd = java.util.Random(3)
        repeat(18) {
            val x = rnd.nextFloat() * w; val y = 60f + rnd.nextFloat() * 240f; val r = 4f + rnd.nextFloat() * 6f
            p.shader = android.graphics.RadialGradient(x, y, r * 4, 0xAAFFC56B.toInt(), 0x00FFC56B, android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r * 4, p); p.shader = null
            p.color = 0xFFFFE2A8.toInt(); c.drawRect(x - r * 0.5f, y - r * 0.7f, x + r * 0.5f, y + r * 0.7f, p)
        }
        // the hero, a simple cloaked figure
        p.color = 0xFF0E0C14.toInt()
        c.drawPath(android.graphics.Path().apply { moveTo(470f, 470f); lineTo(500f, 370f); lineTo(530f, 470f); close() }, p)
        c.drawCircle(500f, 362f, 14f, p)
        p.color = 0xFFFFD27A.toInt(); c.drawCircle(540f, 400f, 7f, p)
        // HUD: health and stamina, top left
        p.color = 0xAA000000.toInt(); c.drawRoundRect(20f, 20f, 300f, 92f, 12f, 12f, p)
        p.color = 0xFF3A1A1A.toInt(); c.drawRoundRect(36f, 34f, 284f, 54f, 8f, 8f, p)
        p.color = 0xFFD9443A.toInt(); c.drawRoundRect(36f, 34f, 220f, 54f, 8f, 8f, p)
        p.color = 0xFF1A2A1A.toInt(); c.drawRoundRect(36f, 64f, 284f, 78f, 6f, 6f, p)
        p.color = 0xFF6CC46A.toInt(); c.drawRoundRect(36f, 64f, 180f, 78f, 6f, 6f, p)
        // minimap, top right
        val cx = 870f; val cy = 92f; val mr = 70f
        p.color = 0xDD101418.toInt(); c.drawCircle(cx, cy, mr, p)
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xFFBFC4CC.toInt(); c.drawCircle(cx, cy, mr, p)
        p.strokeWidth = 6f; p.color = 0xFF5A6A7A.toInt()
        c.drawPath(android.graphics.Path().apply { moveTo(cx - 60f, cy + 20f); lineTo(cx - 10f, cy + 10f); lineTo(cx + 10f, cy - 40f); moveTo(cx - 10f, cy + 10f); lineTo(cx + 50f, cy + 35f) }, p)
        p.style = android.graphics.Paint.Style.FILL
        p.color = 0xFF3D6E9A.toInt(); c.drawCircle(cx + 30f, cy - 25f, 14f, p)
        p.color = 0xFFFFD27A.toInt(); c.drawCircle(cx + 10f, cy - 40f, 6f, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawPath(android.graphics.Path().apply { moveTo(cx - 10f, cy - 2f); lineTo(cx - 18f, cy + 18f); lineTo(cx - 10f, cy + 13f); lineTo(cx - 2f, cy + 18f); close() }, p)
        // item box, bottom right
        p.color = 0xAA000000.toInt(); c.drawRoundRect(830f, 440f, 940f, 520f, 12f, 12f, p)
        p.color = 0xFF6FB8E0.toInt(); c.drawCircle(865f, 480f, 18f, p)
        p.color = 0xFFFFFFFF.toInt(); p.textSize = 26f; p.isFakeBoldText = true; c.drawText("x3", 893f, 490f, p)
        return bmp.asImageBitmap()
    }

    @Test
    fun mirrorMinimap() {
        val game = "Lanterns of Emberfall"
        com.nerfherder.walkthor.Mirror.setAreas(app, game, listOf(floatArrayOf(800f / 960, 12f / 540, 950f / 960, 172f / 540)))
        com.nerfherder.walkthor.Mirror.setSelected(app, game, 0)
        val frame = fakeGameFrame()
        content(hero = fakeHero()) { com.nerfherder.walkthor.ui.MirrorScreen(game, onBack = {}, onStartCapture = {}, frame = frame, topBar = { tabBar(com.nerfherder.walkthor.ui.GameTab.MIRROR) }) }
        settle()
        shot("11-mirror")
    }

    @Test
    fun mirrorPick() {
        // First time for a game: a box in the bottom screen's shape to drag over the picture.
        val frame = fakeGameFrame()
        content { com.nerfherder.walkthor.ui.MirrorScreen("Lanterns of Emberfall", onBack = {}, onStartCapture = {}, frame = frame, topBar = { tabBar(com.nerfherder.walkthor.ui.GameTab.MIRROR) }) }
        settle()
        shot("11c-mirror-pick")
    }

    @Test
    fun mirrorAll() {
        val game = "Lanterns of Emberfall"
        com.nerfherder.walkthor.Mirror.setAreas(app, game, listOf(
            floatArrayOf(800f / 960, 12f / 540, 950f / 960, 172f / 540),
            floatArrayOf(14f / 960, 14f / 540, 306f / 960, 98f / 540),
            floatArrayOf(822f / 960, 432f / 540, 948f / 960, 528f / 540),
        ))
        com.nerfherder.walkthor.Mirror.setSelected(app, game, com.nerfherder.walkthor.Mirror.ALL)
        val frame = fakeGameFrame()
        content { com.nerfherder.walkthor.ui.MirrorScreen(game, onBack = {}, onStartCapture = {}, frame = frame, topBar = { tabBar(com.nerfherder.walkthor.ui.GameTab.MIRROR) }) }
        settle()
        shot("11b-mirror-all")
    }

    @Test
    fun controlsShapes() {
        // PlayStation-style shapes, as if the right face button (circle) is held.
        com.nerfherder.walkthor.controls.ControllerInput.pressed.value = setOf(android.view.KeyEvent.KEYCODE_BUTTON_A)
        com.nerfherder.walkthor.controls.ControllerInput.last.value = android.view.KeyEvent.KEYCODE_BUTTON_A
        com.nerfherder.walkthor.controls.ControlPrefs.setKeysConsent(app, true)
        content(hero = fakeHero()) {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalInspectionMode provides true) {
                com.nerfherder.walkthor.ui.ControlsScreen(
                    game = LaunchedGame("Lanterns of Emberfall", "lanterns.iso", "NetherSX2", 0L), onBack = {},
                    topBar = { tabBar(com.nerfherder.walkthor.ui.GameTab.FACE) },
                )
            }
        }
        settle()
        shot("12-face-buttons-shapes")
        com.nerfherder.walkthor.controls.ControllerInput.pressed.value = emptySet()
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

    /** The game's tab bar, as the app shows it above a tab. */
    @Composable
    private fun tabBar(selected: com.nerfherder.walkthor.ui.GameTab?) =
        com.nerfherder.walkthor.ui.GameTabBar(selected, onSelect = {}, onBack = {}, onClose = {})

    private fun importSample(): Book {
        val sample = File(app.cacheDir, "Lanterns of Emberfall - Complete Walkthrough.txt")
        javaClass.classLoader!!.getResourceAsStream("sample-guide.txt")!!.use { input ->
            sample.outputStream().use { input.copyTo(it) }
        }
        return runBlocking { BookRepository.get(app).importGuide(Uri.fromFile(sample)) {} }
    }

    @Test
    fun readerTabs() {
        val book = importSample()
        content(hero = fakeHero()) {
            ReaderScreen(
                bookId = book.id, onBack = {}, onClose = {}, onSwapScreen = {},
                tabs = com.nerfherder.walkthor.ui.TabsState(com.nerfherder.walkthor.ui.GameTab.GUIDE) {},
            )
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("— 1 —").fetchSemanticsNodes().isNotEmpty() }
        shot("03b-reader-tabs")
    }

    @Test
    @Config(qualifiers = "w413dp-h360dp-land-xxhdpi")
    fun readerTabsDense() {
        val book = importSample()
        content {
            ReaderScreen(
                bookId = book.id, onBack = {}, onClose = {}, onSwapScreen = {},
                tabs = com.nerfherder.walkthor.ui.TabsState(com.nerfherder.walkthor.ui.GameTab.GUIDE) {},
            )
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("— 1 —").fetchSemanticsNodes().isNotEmpty() }
        shot("check-reader-tabs-dense")
    }

    @Test
    fun guideTabEmpty() {
        content {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                tabBar(com.nerfherder.walkthor.ui.GameTab.GUIDE)
                com.nerfherder.walkthor.ui.NoGuidePanel(
                    game = noGuideGame, books = shelf, coverFile = { null }, onAddGuide = {}, onPickGuide = {},
                    modifier = androidx.compose.ui.Modifier.weight(1f),
                )
            }
        }
        settle()
        shot("06c-guide-tab-empty")
    }

    @Test
    @Config(qualifiers = "w413dp-h360dp-land-xxhdpi")
    fun mirrorTabsDense() {
        val game = "Lanterns of Emberfall"
        com.nerfherder.walkthor.Mirror.setAreas(app, game, listOf(floatArrayOf(800f / 960, 12f / 540, 950f / 960, 172f / 540)))
        val frame = fakeGameFrame()
        content { com.nerfherder.walkthor.ui.MirrorScreen(game, onBack = {}, onStartCapture = {}, frame = frame, topBar = { tabBar(com.nerfherder.walkthor.ui.GameTab.MIRROR) }) }
        settle()
        shot("check-mirror-tabs-dense")
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

    /** A made-up transparent logo for the fictional game. */
    private fun fakeLogo(): String {
        val w = 900; val h = 260
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        p.textAlign = android.graphics.Paint.Align.LEFT
        p.shader = android.graphics.LinearGradient(0f, 40f, 0f, 200f, 0xFFFFE2A8.toInt(), 0xFFE07A3A.toInt(), android.graphics.Shader.TileMode.CLAMP)
        p.textSize = 120f
        c.drawText("LANTERNS", 10f, 130f, p)
        p.shader = null
        p.color = 0xFFF3E6C8.toInt()
        p.textSize = 58f
        c.drawText("of  EMBERFALL", 16f, 215f, p)
        val f = File(app.cacheDir, "Lanterns of Emberfall (USA)-marquee.png")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f).toString()
    }

    /** A made-up wide "fan art" scene for the fictional game. */
    private fun fakeHero(): String {
        val w = 1280; val h = 720
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF101C3A.toInt(), 0xFF7A3418.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // distant hills
        p.shader = null
        p.color = 0xFF1A1424.toInt()
        val hills = android.graphics.Path().apply {
            moveTo(0f, 470f); cubicTo(260f, 380f, 420f, 520f, 640f, 440f); cubicTo(860f, 360f, 1040f, 500f, 1280f, 420f)
            lineTo(1280f, 720f); lineTo(0f, 720f); close()
        }
        c.drawPath(hills, p)
        // lanterns floating up
        val rnd = java.util.Random(7)
        repeat(26) {
            val x = rnd.nextFloat() * w; val y = 80f + rnd.nextFloat() * 380f; val r = 6f + rnd.nextFloat() * 10f
            p.shader = android.graphics.RadialGradient(x, y, r * 4, 0xAAFFC56B.toInt(), 0x00FFC56B, android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r * 4, p)
            p.shader = null; p.color = 0xFFFFE2A8.toInt()
            c.drawRect(x - r * 0.5f, y - r * 0.7f, x + r * 0.5f, y + r * 0.7f, p)
        }
        val f = File(app.cacheDir, "Lanterns of Emberfall (USA)-fanart.png")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f).toString()
    }

    private val noGuideGame get() = LaunchedGame("Lanterns of Emberfall", "Lanterns of Emberfall (USA).rvz", "Dolphin Emulator", System.currentTimeMillis())

    @Test
    fun noGuide() {
        val art = fakeBoxArt()
        val logo = fakeLogo()
        content {
            NowPlayingScreen(
                game = noGuideGame,
                art = GameArt(cover = art, logo = logo, hero = fakeHero()),
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
                art = GameArt(cover = fakeBoxArt(), logo = fakeLogo(), hero = fakeHero()),
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
        com.nerfherder.walkthor.ui.GameTabs.state.value = com.nerfherder.walkthor.ui.TabsState(null) {}
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
        com.nerfherder.walkthor.ui.GameTabs.state.value = null
        AutoDim.dimmed.value = false
        AutoDim.delayMs.value = 0L
    }

    /** Half-strength dim over Now playing: the whole screen evenly darker, nothing patchy. */
    @Test
    fun dimmedHalf() {
        AutoDim.delayMs.value = 60_000L
        AutoDim.level.value = 0.5f
        com.nerfherder.walkthor.ui.GameTabs.state.value = com.nerfherder.walkthor.ui.TabsState(null) {}
        content {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                NowPlayingScreen(
                    game = noGuideGame,
                    art = GameArt(cover = fakeBoxArt(), logo = fakeLogo(), hero = fakeHero()),
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
        shot("check-dimmed-half")
        com.nerfherder.walkthor.ui.GameTabs.state.value = null
        AutoDim.dimmed.value = false
        AutoDim.level.value = AutoDim.DEFAULT_LEVEL
        AutoDim.delayMs.value = 0L
    }

    private fun statsPreview(): com.nerfherder.walkthor.ui.StatsPreview {
        val rnd = java.util.Random(4)
        val fps = MutableList(60) { 60 - (if (rnd.nextFloat() < 0.08f) 4 + rnd.nextInt(10) else 0) - rnd.nextInt(2) }
        listOf(47, 44, 49, 55, 58).forEachIndexed { i, v -> fps[38 + i] = v }
        return com.nerfherder.walkthor.ui.StatsPreview(
            fps = fps,
            snapshot = com.nerfherder.walkthor.ui.StatsSnapshot(
                watts = 9.8f, charging = false, minutesLeft = 160, heatWord = "Normal", heatFraction = 0.35f,
                batteryTempC = 34f, memFree = 3_100_000_000L, memTotal = 12_000_000_000L, cpuGhz = 2.84f,
            ),
            fullChargeHours = 3.1f,
        )
    }

    @Test
    fun statsTab() {
        // As if Mirror is this game's opening tab (the small dot).
        com.nerfherder.walkthor.ui.GameTabs.state.value = com.nerfherder.walkthor.ui.TabsState(
            selected = com.nerfherder.walkthor.ui.GameTab.STATS, pinned = com.nerfherder.walkthor.ui.GameTab.MIRROR, onPin = {}, onSelect = {},
        )
        val game = LaunchedGame("Lanterns of Emberfall", "lanterns.nsp", "Eden", System.currentTimeMillis() - 47 * 60_000L)
        content(hero = fakeHero()) {
            com.nerfherder.walkthor.ui.StatsScreen(
                game = game, onStartCapture = {}, topBar = { tabBar(com.nerfherder.walkthor.ui.GameTab.STATS) },
                preview = statsPreview(),
            )
        }
        settle()
        shot("13-stats")
        com.nerfherder.walkthor.ui.GameTabs.state.value = null
    }

    @Test
    @Config(qualifiers = "w413dp-h360dp-land-xxhdpi")
    fun statsTabDense() {
        val game = LaunchedGame("Lanterns of Emberfall", "lanterns.nsp", "Eden", System.currentTimeMillis() - 47 * 60_000L)
        content {
            com.nerfherder.walkthor.ui.StatsScreen(
                game = game, onStartCapture = {}, topBar = { tabBar(com.nerfherder.walkthor.ui.GameTab.STATS) },
                preview = statsPreview(),
            )
        }
        settle()
        shot("check-stats-dense")
    }

    private fun allTab(name: String) {
        val title = "Lanterns of Emberfall"
        com.nerfherder.walkthor.Mirror.setAreas(app, title, listOf(floatArrayOf(800f / 960, 12f / 540, 950f / 960, 172f / 540)))
        com.nerfherder.walkthor.controls.ControllerInput.pressed.value = setOf(android.view.KeyEvent.KEYCODE_BUTTON_A)
        com.nerfherder.walkthor.ui.GameTabs.state.value = com.nerfherder.walkthor.ui.TabsState(
            selected = com.nerfherder.walkthor.ui.GameTab.ALL, onSelect = {},
        )
        val text = javaClass.classLoader!!.getResourceAsStream("sample-guide.txt")!!.bufferedReader().readText()
        val frame = fakeGameFrame()
        val game = LaunchedGame(title, "lanterns.iso", "NetherSX2", System.currentTimeMillis() - 47 * 60_000L)
        content(hero = fakeHero()) {
            com.nerfherder.walkthor.ui.AllScreen(
                game = game, guide = null, frame = frame, onOpen = {}, onStartCapture = {},
                topBar = { tabBar(com.nerfherder.walkthor.ui.GameTab.ALL) },
                preview = com.nerfherder.walkthor.ui.AllPreview(text.substring(text.indexOf("1. CONTROLS") - 82), statsPreview()),
            )
        }
        settle()
        shot(name)
        com.nerfherder.walkthor.ui.GameTabs.state.value = null
        com.nerfherder.walkthor.controls.ControllerInput.pressed.value = emptySet()
    }

    @Test
    fun allTabShot() = allTab("14-all")

    @Test
    @Config(qualifiers = "w413dp-h360dp-land-xxhdpi")
    fun allTabDense() = allTab("check-all-dense")
}
