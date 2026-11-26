package com.nerfherder.walkthor

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * WalkThor as a drawer: instead of opening as an app, it slides up over the bottom screen, drawn
 * by WalkThor's accessibility service (like AYN's own drawer). Whatever is on the bottom screen
 * stays where it is underneath, and the game keeps its priority, since nothing new comes to the
 * front. During a game, the AYN button slides it in and out (Settings → General → AYN button).
 */
object Drawer {
    /** The drawer is up on the bottom screen. */
    val shown = MutableStateFlow(false)
    private var current: WalkThorDrawer? = null

    /** The drawer shows Home (clock, levels, apps) over WalkThor's screens: no game is running. */
    val home = MutableStateFlow(false)

    /**
     * None of your emulators is in front on the game screen (or, before anything's been seen there,
     * no game is known to be running).
     */
    fun noGame(): Boolean = !gameInFront()

    /** Why the drawer last couldn't open, for Diagnostics; null if it's been fine. */
    val problem = MutableStateFlow<String?>(null)

    /**
     * Slides the drawer up on [displayId] ([fade]: fades it in instead, for when it comes up by
     * itself, as a game starts or when it's back mid-game). Returns false if it couldn't (the
     * caller opens the app instead).
     */
    fun show(service: AccessibilityService, displayId: Int, opened: Boolean, fade: Boolean = false, onFail: () -> Unit = {}): Boolean {
        // Your settings, as the app reads them when it opens: the drawer can be the only WalkThor
        // that's run since the device started (or since a crash), and without this it would have
        // Auto-dim off, light pages and the play timer showing whatever you'd set.
        runCatching {
            com.nerfherder.walkthor.ui.PageStyle.init(service)
            com.nerfherder.walkthor.ui.AutoDim.init(service)
        }
        main.removeCallbacks(takeDown)
        return try {
            val d = current?.takeIf { it.displayId == displayId } ?: run {
                current?.destroy()
                WalkThorDrawer(service, displayId).also { current = it }
            }
            val ok = d.show(opened, fade, onFail)
            if (ok) { problem.value = null; runCatching { Revive.noteDrawer(service, true) } }
            ok
        } catch (e: Throwable) {
            problem.value = "Couldn't open the drawer (${e.javaClass.simpleName}: ${e.message?.take(120)})"
            runCatching { destroy() }
            false
        }
    }

    fun hide() { current?.hide() }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Put away for a while during a game: taken down to give the game its memory (built again on the next open). */
    private val takeDown = Runnable {
        if (!shown.value && current != null && gameInFront()) {
            runCatching { destroy() }
            MemoryWatch.noteDrawerTakenDown()
            SwipeGestureService.instance?.get()?.let { s -> if (Lean.isOn(s)) Lean.letGo(s) }
        }
    }

    /** The drawer was put away: during a game, it's taken down after a while. */
    internal fun afterHide() {
        main.removeCallbacks(takeDown)
        // Lean during games: let go as soon as it's put away (once it has slid down).
        val lean = SwipeGestureService.instance?.get()?.let { Lean.isOn(it) } == true && gameInFront()
        main.postDelayed(takeDown, if (lean) 400L else MemoryWatch.DRAWER_KEEP_MS)
    }

    /** Android is short of memory: if the drawer's away, take it down now. */
    fun trimIfHidden() {
        if (!shown.value && current != null) { main.removeCallbacks(takeDown); runCatching { destroy() } }
    }

    fun destroy() {
        current?.let { d -> runCatching { Revive.noteDrawer(d.host(), false) } }
        current?.destroy()
        current = null
        shown.value = false
    }
}

/** The drawer's window and everything WalkThor's screens need from their host. */
class WalkThorDrawer(private val service: AccessibilityService, val displayId: Int) :
    WalkThorHost, LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory, OnBackPressedDispatcherOwner, ActivityResultRegistryOwner {

    private val app = service.applicationContext as Application
    internal fun host(): android.content.Context = service
    private val main = Handler(Looper.getMainLooper())

    // ---- what an app screen would normally provide ----
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras().apply { set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, app) }
    override val onBackPressedDispatcher = OnBackPressedDispatcher()

    /** Pickers and permission prompts open on a momentary app screen ([PromptActivity]). */
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            val ready = contract.getSynchronousResult(context, input)
            if (ready != null) { main.post { dispatchResult(requestCode, ready.value) }; return }
            ask(contract.createIntent(context, input)) { code, data -> main.post { dispatchResult(requestCode, code, data) } }
        }
    }

    // ---- WalkThorHost ----
    override val incomingFlow = MutableStateFlow<Uri?>(null)
    override val hasOtherScreenFlow = MutableStateFlow(false)
    override val swipeTickFlow = MutableStateFlow(0L)
    override val pollTickFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val hostScope get() = lifecycleScope

    // ---- the window ----
    private val windowContext: Context = run {
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
    }
    /** What the screens see as their context: starting another app slides the drawer away first. */
    private val context: Context = DrawerContext(windowContext)
    private val wm = windowContext.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var attached = false
    /** Where the drawer is headed: up (true) or away (false). */
    private var up by mutableStateOf(false)
    /** Coming up by zooming in (not sliding); putting it away always slides. */
    private var fading by mutableStateOf(false)
    private var firstShow = true
    /**
     * The drawer's own update loop for its screens. Compose's usual one ends whenever the window
     * comes off the screen, which froze the screens the next time the drawer slid up.
     */
    private val uiJob = kotlinx.coroutines.SupervisorJob()
    private val uiContext = androidx.compose.ui.platform.AndroidUiDispatcher.Main + uiJob
    private val recomposer = androidx.compose.runtime.Recomposer(uiContext).also { r ->
        kotlinx.coroutines.CoroutineScope(uiContext).launch { r.runRecomposeAndApplyChanges() }
    }

    private val guidePicker = activityResultRegistry.register("guide", ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) incomingFlow.value = uri }
    private val romPicker = activityResultRegistry.register("roms", ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) onRomFolderPicked(app, lifecycleScope, uri) }
    private val artPicker = activityResultRegistry.register("art", ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) onArtFolderPicked(app, lifecycleScope, uri) }
    init {
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        // While it's up: notice games launched meanwhile, and note what WalkThor is doing (as the app does).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var beats = 0
                while (true) {
                    pollTickFlow.tryEmit(Unit)
                    if (beats % 2 == 0) {
                        ExitHistory.snapshot(app)
                        if (GameFocus.gameRunning.value) {
                            if (beats % 12 == 0) {
                                EmulatorWatch.topGame(app)?.name?.let { com.nerfherder.walkthor.ui.PlayStats.record(app, it) }
                            }
                        }
                    }
                    beats++
                    delay(3_000)
                }
            }
        }
        // Typing (a search): the drawer takes focus so the keyboard works, then gives it back.
        lifecycleScope.launch {
            GameFocus.wantFocus.collect { want -> setFocusable(want) }
        }
    }

    /** When the drawer's screens last drew (uptimeMillis). */
    @Volatile private var drewAt = 0L

    fun show(opened: Boolean, fade: Boolean = false, onFail: () -> Unit = {}): Boolean {
        if (!attached) attach()
        // Already up: nothing to animate.
        if (!up) {
            fading = fade
            // Zooming in: the screen goes black at once (before the drawer's first frame is even
            // drawn), and the drawer comes up out of that, so what was underneath never shows.
            root?.setBackgroundColor(if (fade) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT)
        }
        if (!attached) return false
        // Safety net: if it hasn't actually drawn shortly after, take it away and fall back.
        val askedAt = android.os.SystemClock.uptimeMillis()
        main.postDelayed({
            if (up && drewAt < askedAt) {
                Drawer.problem.value = "The drawer opened but didn't draw, so WalkThor opened as an app instead."
                Drawer.destroy()
                onFail()
            }
        }, 2_000)
        if (opened) swipeTickFlow.value = System.currentTimeMillis()
        // Opened with no game running: Home on its own. (During a game: Home under the tabs.)
        Drawer.home.value = Drawer.noGame()
        com.nerfherder.walkthor.ui.AutoDim.wake()
        reveal()
        up = true
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        Drawer.shown.value = true
        AppState.drawerDisplayId = displayId
        return true
    }

    fun hide() {
        fading = false
        // Sliding away shows what's underneath as it goes.
        root?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        up = false
        Drawer.shown.value = false
        AppState.drawerDisplayId = null
        // Put away on purpose: if WalkThor closes now, it stays closed.
        runCatching { Revive.closedOnPurpose(service); Revive.noteDrawer(service, false) }
        Drawer.afterHide()
    }

    /** Slid all the way down: put away (the screens stay as they were for next time). */
    private fun hidden() {
        if (up) return
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        conceal()
    }

    /*
     * The window stays on the screen the whole time, and is only made invisible and see-through
     * to touches when the drawer's away. Taking it off the screen and back again is what kept
     * it from coming back.
     */
    private fun conceal() {
        root?.visibility = android.view.View.GONE
        setFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, true)
    }

    private fun reveal() {
        root?.visibility = android.view.View.VISIBLE
        setFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, false)
    }

    fun destroy() {
        up = false
        detach()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        runCatching { recomposer.cancel() }
        uiJob.cancel()
        if (AppState.drawerDisplayId == displayId) AppState.drawerDisplayId = null
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        title = "WalkThor"
    }

    private fun attach() {
        val frame = root ?: object : FrameLayout(context) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                // Any touch counts as activity for auto-dim, as in the app.
                com.nerfherder.walkthor.ui.AutoDim.interact()
                return super.dispatchTouchEvent(ev)
            }
        }.also { f ->
            f.setViewTreeLifecycleOwner(this)
            f.setViewTreeSavedStateRegistryOwner(this)
            f.setViewTreeViewModelStoreOwner(this)
            val compose = ComposeView(context).apply {
                // Kept between openings (so it reopens where you were); ended only with the drawer.
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@WalkThorDrawer))
                setParentCompositionContext(recomposer)
                val boot = firstShow
                setContent { DrawerContent(boot) }
            }
            firstShow = false
            f.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            // Each time the window draws (it always does when it's shown again).
            f.viewTreeObserver.addOnDrawListener { drewAt = android.os.SystemClock.uptimeMillis() }
            root = f
        }
        runCatching { wm.addView(frame, params()) }
            .onSuccess { attached = true }
            .onFailure { Drawer.problem.value = "Android didn't allow the drawer's window (${it.javaClass.simpleName}: ${it.message?.take(120)})" }
    }

    private fun detach() {
        val r = root ?: return
        if (attached) runCatching { wm.removeViewImmediate(r) }
        attached = false
    }

    private fun setFocusable(on: Boolean) = setFlag(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, !on)

    private fun setFlag(flag: Int, on: Boolean) {
        val r = root ?: return
        if (!attached) return
        val p = (r.layoutParams as? WindowManager.LayoutParams) ?: return
        val flags = if (on) p.flags or flag else p.flags and flag.inv()
        if (flags != p.flags) {
            p.flags = flags
            runCatching { wm.updateViewLayout(r, p) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun DrawerContent(boot: Boolean) {
        val slide = remember { Animatable(0f) }
        val target = up
        LaunchedEffect(target) {
            // Zooming in (coming up by itself): from slightly smaller, quick at first and settling.
            val zoomEase = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
            slide.animateTo(if (target) 1f else 0f, tween(if (target) (if (fading) 380 else 280) else 220, easing = if (target && fading) zoomEase else FastOutSlowInEasing))
            if (!target) hidden()
        }
        CompositionLocalProvider(
            LocalActivityResultRegistryOwner provides this,
            LocalOnBackPressedDispatcherOwner provides this,
            com.nerfherder.walkthor.ui.LocalInDrawer provides true,
        ) {
            // Behind a zoom: black, so nothing underneath shows around or through it.
            Box(Modifier.fillMaxSize().then(if (fading) Modifier.background(androidx.compose.ui.graphics.Color.Black) else Modifier)) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        if (fading) {
                            // Grows in from 90%, fading up as it does.
                            alpha = slide.value
                            val s = 0.9f + 0.1f * slide.value
                            scaleX = s; scaleY = s
                        } else translationY = size.height * (1f - slide.value)
                    }
                    // The app's window paints this behind every screen; the drawer has to itself.
                    .background(androidx.compose.ui.graphics.Color.Black),
            ) {
                WalkThorRoot(initialBookId = null, boot = boot, host = this@WalkThorDrawer)
                // A game came to the front while Home was up: over to Home under the tabs.
                val emulatorShowing by EmulatorWatch.emulatorShowing.collectAsState()
                // And when the game's gone, back to Home on its own.
                LaunchedEffect(emulatorShowing) { Drawer.home.value = Drawer.noGame() }
                val home by Drawer.home.collectAsState()
                if (home) {
                    // Home, and one swipe left: Updates for WalkThor and your emulators.
                    val pages = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 2 })
                    // Back on Home each time the drawer comes up.
                    LaunchedEffect(up) { if (up) pages.scrollToPage(0) }
                    val pageScope = androidx.compose.runtime.rememberCoroutineScope()
                    val updateContext = androidx.compose.ui.platform.LocalContext.current
                    LaunchedEffect(Unit) { com.nerfherder.walkthor.data.Updates.load(updateContext) }
                    val updates by com.nerfherder.walkthor.data.Updates.entries.collectAsState()
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.pager.HorizontalPager(
                        state = pages,
                        modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                        beyondViewportPageCount = 0,
                    ) { page ->
                        if (page == 0) {
                            com.nerfherder.walkthor.ui.HomeScreen(
                                onOpenWalkThor = { Drawer.home.value = false },
                                onOpenApp = { pkg ->
                                    if (com.nerfherder.walkthor.ui.openFromHome(service, pkg, displayId)) hide()
                                },
                            )
                        } else {
                            com.nerfherder.walkthor.ui.UpdatesScreen(header = true)
                        }
                    }
                    // Tabs on the edge saying what a swipe brings up (a tap does the same).
                    if (pages.currentPage == 0) {
                        com.nerfherder.walkthor.ui.SwipeTab(
                            "Updates", toLeft = false, count = com.nerfherder.walkthor.data.Updates.waiting(updates),
                            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd),
                        ) { pageScope.launch { pages.animateScrollToPage(1) } }
                    } else {
                        com.nerfherder.walkthor.ui.SwipeTab(
                            "Home", toLeft = true,
                            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart),
                        ) { pageScope.launch { pages.animateScrollToPage(0) } }
                    }
                    }
                }
            }
            }
        }
    }

    // ---- prompts and other apps ----

    /** Asks Android on a momentary app screen: the drawer slides away meanwhile (it would cover the prompt) and comes back after. */
    private fun ask(intent: Intent, onAnswer: (Int, Intent?) -> Unit) {
        val wasUp = up
        hideNow()
        PromptActivity.ask(app, displayId, intent) { code, data ->
            // Straight away, while the prompt's screen is still up (screen capture must start then).
            onAnswer(code, data)
            if (wasUp) main.post { show(opened = false) }
        }
    }

    /** Off the screen at once (no slide), for something that has to show in its place. */
    private fun hideNow() {
        hide()
        // After the tap that asked for it has finished being handled.
        main.post {
            if (up) return@post
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            conceal()
        }
    }


    /** Starting another app (sharing, playing a clip, an app from Apps): the drawer gets out of the way. */
    private inner class DrawerContext(base: Context) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) = startActivity(intent, null)
        override fun startActivity(intent: Intent, options: Bundle?) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            hideNow()
            super.startActivity(intent, options ?: ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        }
    }

    override fun requestMirror() {
        val intent = screenCaptureIntent(app) ?: return
        ask(intent) { code, data ->
            if (code == android.app.Activity.RESULT_OK && data != null) Mirror.start(app, code, data)
        }
    }

    override fun closeApp() = hide()
    override fun pickGuide() = guidePicker.launch(GUIDE_TYPES)
    override fun pickRomFolder() = romPicker.launch(null)
    override fun pickArtFolder() = artPicker.launch(null)
    override fun swapScreen(bookId: String?) = Unit
}
