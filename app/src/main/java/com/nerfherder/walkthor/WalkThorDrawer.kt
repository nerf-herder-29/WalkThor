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
 * front. The AYN button (or the swipe) slides it in and out. Switched on in Settings → General.
 */
object Drawer {
    /** The drawer is up on the bottom screen. */
    val shown = MutableStateFlow(false)
    private var current: WalkThorDrawer? = null

    fun enabled(context: Context) = SwipeLauncher.prefs(context).getBoolean("drawer", false)

    fun setEnabled(context: Context, on: Boolean) {
        SwipeLauncher.prefs(context).edit().putBoolean("drawer", on).apply()
        if (!on) destroy()
    }

    /** The drawer is in use right now (so the app version shouldn't open). */
    fun usable(context: Context) = enabled(context) && SwipeGestureService.instance?.get() != null

    fun toggle(service: AccessibilityService, displayId: Int) {
        if (shown.value) hide() else show(service, displayId, opened = true)
    }

    /** Slides the drawer up on [displayId]. [opened]: by you (or a game starting), so it checks what's playing. */
    fun show(service: AccessibilityService, displayId: Int, opened: Boolean) {
        val d = current?.takeIf { it.displayId == displayId } ?: run {
            current?.destroy()
            WalkThorDrawer(service, displayId).also { current = it }
        }
        d.show(opened)
    }

    fun hide() { current?.hide() }

    fun destroy() {
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
    override val pickedArtFolderFlow = MutableStateFlow<Uri?>(null)
    override val pickedCocoonFolderFlow = MutableStateFlow<Uri?>(null)
    override val pickedEsdeFolderFlow = MutableStateFlow<Uri?>(null)
    override val swipeTickFlow = MutableStateFlow(0L)
    override val pollTickFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val pickedCoverFlow = MutableStateFlow<Pair<String, Uri>?>(null)
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
    private var firstShow = true
    private var coverFor: String? = null

    private val guidePicker = activityResultRegistry.register("guide", ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) incomingFlow.value = uri }
    private val artPicker = activityResultRegistry.register("art", ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) pickedArtFolderFlow.value = uri }
    private val cocoonPicker = activityResultRegistry.register("cocoon", ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) pickedCocoonFolderFlow.value = uri }
    private val esdePicker = activityResultRegistry.register("esde", ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) pickedEsdeFolderFlow.value = uri }
    private val romPicker = activityResultRegistry.register("roms", ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) onRomFolderPicked(app, lifecycleScope, uri) }
    private val imagePicker = activityResultRegistry.register("image", ActivityResultContracts.OpenDocument()) { uri ->
        val id = coverFor
        if (uri != null && id != null) pickedCoverFlow.value = id to uri
    }

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
                            Revive.heartbeat(app)
                            if (beats % 12 == 0) {
                                com.nerfherder.walkthor.ui.PlaySession.current.value?.takeIf { !it.unknown }?.title
                                    ?.let { com.nerfherder.walkthor.ui.PlayStats.record(app, it) }
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

    fun show(opened: Boolean) {
        if (!attached) attach()
        if (opened) swipeTickFlow.value = System.currentTimeMillis()
        com.nerfherder.walkthor.ui.AutoDim.wake()
        up = true
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        Drawer.shown.value = true
        AppState.drawerDisplayId = displayId
    }

    fun hide() {
        up = false
        Drawer.shown.value = false
        AppState.drawerDisplayId = null
    }

    /** Slid all the way down: take the window off the screen (the screens stay as they were for next time). */
    private fun hidden() {
        if (up) return
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        detach()
    }

    fun destroy() {
        up = false
        detach()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
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
                val boot = firstShow
                setContent { DrawerContent(boot) }
            }
            firstShow = false
            f.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            root = f
        }
        runCatching { wm.addView(frame, params()) }.onSuccess { attached = true }
    }

    private fun detach() {
        val r = root ?: return
        if (attached) runCatching { wm.removeViewImmediate(r) }
        attached = false
    }

    private fun setFocusable(on: Boolean) {
        val r = root ?: return
        if (!attached) return
        val p = (r.layoutParams as? WindowManager.LayoutParams) ?: return
        val flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
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
            slide.animateTo(if (target) 1f else 0f, tween(if (target) 280 else 220, easing = FastOutSlowInEasing))
            if (!target) hidden()
        }
        CompositionLocalProvider(
            LocalActivityResultRegistryOwner provides this,
            LocalOnBackPressedDispatcherOwner provides this,
            com.nerfherder.walkthor.ui.LocalInDrawer provides true,
        ) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { translationY = size.height * (1f - slide.value) }
                    // The app's window paints this behind every screen; the drawer has to itself.
                    .background(androidx.compose.ui.graphics.Color.Black),
            ) {
                WalkThorRoot(initialBookId = null, boot = boot, host = this@WalkThorDrawer)
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
            detach()
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
    override fun pickArtFolder() = artPicker.launch(null)
    override fun pickCocoonFolder() = cocoonPicker.launch(null)
    override fun pickEsdeFolder() = esdePicker.launch(null)
    override fun pickRomFolder() = romPicker.launch(null)
    override fun pickCoverImage(bookId: String) {
        coverFor = bookId
        imagePicker.launch(arrayOf("image/*"))
    }
    override fun swapScreen(bookId: String?) = Unit
}
