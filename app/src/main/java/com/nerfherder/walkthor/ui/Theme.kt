package com.nerfherder.walkthor.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Immutable
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import kotlinx.coroutines.launch
import androidx.compose.ui.node.invalidateDraw

/**
 * Modern dark chrome with antique accents: true-black (OLED) backgrounds, a single brass accent,
 * serif display type, and warm parchment pages.
 */
object BookColors {
    // chrome
    val Ink = Color(0xFF000000)           // app background: true black for the OLED panels
    val Surface = Color(0xFF0E0C0E)
    val SurfaceHigh = Color(0xFF181519)
    val Hairline = Color(0xFF2A262B)
    val Text = Color(0xFFEDE6DA)
    val TextMuted = Color(0xFFA39A8E)
    /** Accent for text, icons, ticks and lines: a medium grey (it used to be brass). */
    val Brass = Color(0xFF85858B)
    val BrassSoft = Color(0xFFB4B4B9)
    /** Fill for buttons and selected chips: a medium-dark grey, with light text on it. */
    /** Buttons, chips and highlights: OLED black, with a thin light edge ([ButtonEdge]) to show them. */
    val ButtonFill = Color(0xFF000000)
    val ButtonEdge = Color(0x99FFFFFF)

    // pages
    val Paper = Color(0xFFF4ECDD)
    val PaperEdge = Color(0xFFE2D6BF)
    val PageInk = Color(0xFF2A241D)
    val PageInkFaint = Color(0xFF9A8B76)
    val Highlight = Color(0xFFFFD65C)
}

/** Space Grotesk, bundled with the app (SIL Open Font License 1.1). */
val SpaceGrotesk = FontFamily(
    Font(com.nerfherder.walkthor.R.font.space_grotesk_400, FontWeight.Normal),
    Font(com.nerfherder.walkthor.R.font.space_grotesk_500, FontWeight.Medium),
    Font(com.nerfherder.walkthor.R.font.space_grotesk_600, FontWeight.SemiBold),
    Font(com.nerfherder.walkthor.R.font.space_grotesk_700, FontWeight.Bold),
)

/** The app's one typeface, used for every heading, label and number. */
object AppFont {
    var family: FontFamily by mutableStateOf(SpaceGrotesk)
}

/** Headings use the same typeface as everything else. */
val DisplayFont: FontFamily get() = AppFont.family

/** Colours of the pages themselves (as opposed to the app chrome). */
@Immutable
data class PageColors(
    val paper: Color,
    val edge: Color,
    val ink: Color,
    val inkFaint: Color,
    /** Background behind search matches in text guides. */
    val highlight: Color,
    /** True for dark pages (PDF pages themselves are always shown as they are). */
    val dark: Boolean,
)

val LightPages = PageColors(
    paper = BookColors.Paper, edge = BookColors.PaperEdge, ink = BookColors.PageInk,
    inkFaint = BookColors.PageInkFaint, highlight = BookColors.Highlight, dark = false,
)

/** Near-black pages with parchment-coloured text, to sit on the OLED background. */
val DarkPages = PageColors(
    paper = Color(0xFF141216), edge = Color(0xFF2B272D), ink = Color(0xFFE4D9C6),
    inkFaint = Color(0xFF8C8275), highlight = Color(0xFF6E5214), dark = true,
)

val LocalPageColors = staticCompositionLocalOf { DarkPages }

/** The "Dark pages" setting (on by default). */
object PageStyle {
    val dark = MutableStateFlow(true)
    fun init(context: Context) {
        dark.value = context.getSharedPreferences("pages", Context.MODE_PRIVATE).getBoolean("dark", true)
    }
    fun setDark(context: Context, on: Boolean) {
        dark.value = on
        context.getSharedPreferences("pages", Context.MODE_PRIVATE).edit().putBoolean("dark", on).apply()
    }
}

private val scheme = darkColorScheme(
    primary = BookColors.Brass,
    onPrimary = BookColors.Text,
    primaryContainer = BookColors.ButtonFill,
    onPrimaryContainer = BookColors.BrassSoft,
    secondary = BookColors.BrassSoft,
    background = BookColors.Ink,
    onBackground = BookColors.Text,
    surface = BookColors.Surface,
    onSurface = BookColors.Text,
    surfaceVariant = BookColors.SurfaceHigh,
    onSurfaceVariant = BookColors.TextMuted,
    surfaceContainerLowest = BookColors.Ink,
    surfaceContainerLow = BookColors.Surface,
    surfaceContainer = BookColors.Surface,
    surfaceContainerHigh = BookColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF211D22),
    outline = BookColors.Hairline,
    outlineVariant = BookColors.Hairline,
    error = Color(0xFFFF8A80),
)

private val base = Typography()

/** Every Material text style in [f], with semibold headings and titles. */
private fun typographyFor(f: FontFamily) = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = f),
    displayMedium = base.displayMedium.copy(fontFamily = f),
    displaySmall = base.displaySmall.copy(fontFamily = f),
    headlineLarge = base.headlineLarge.copy(fontFamily = f),
    headlineMedium = base.headlineMedium.copy(fontFamily = f),
    headlineSmall = base.headlineSmall.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = f),
    bodyLarge = base.bodyLarge.copy(fontFamily = f),
    bodyMedium = base.bodyMedium.copy(fontFamily = f),
    bodySmall = base.bodySmall.copy(fontFamily = f),
    labelLarge = base.labelLarge.copy(fontFamily = f),
    labelMedium = base.labelMedium.copy(fontFamily = f),
    labelSmall = base.labelSmall.copy(fontFamily = f),
)

/** Quiet label used for section headers ("Your library"): small, dim, normal case. */
val EyebrowStyle: TextStyle get() = TextStyle(
    fontFamily = AppFont.family,
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.2.sp,
    color = BookColors.TextMuted,
)

@Composable
fun WalkThorTheme(content: @Composable () -> Unit) {
    val f = AppFont.family
    val typography = androidx.compose.runtime.remember(f) { typographyFor(f) }
    // Where the controller is, everywhere: a ring in the accent colour around whatever is focused.
    PadIndication.ring = frontAccent()
    MaterialTheme(colorScheme = scheme, typography = typography) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides PadIndication, content = content)
    }
}

/**
 * How anything you can press shows it's been reached with the controller (a ring in the accent
 * colour) or is being pressed (a soft light), in place of the touch ripple.
 */
object PadIndication : androidx.compose.foundation.IndicationNodeFactory {
    @Volatile var ring: Color = Color.White

    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        Node(interactionSource)

    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7

    private class Node(private val source: androidx.compose.foundation.interaction.InteractionSource) :
        androidx.compose.ui.Modifier.Node(), androidx.compose.ui.node.DrawModifierNode {
        private var focused = false
        private var pressed = false

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { i ->
                    when (i) {
                        is androidx.compose.foundation.interaction.FocusInteraction.Focus -> focused = true
                        is androidx.compose.foundation.interaction.FocusInteraction.Unfocus -> focused = false
                        is androidx.compose.foundation.interaction.PressInteraction.Press -> pressed = true
                        is androidx.compose.foundation.interaction.PressInteraction.Release,
                        is androidx.compose.foundation.interaction.PressInteraction.Cancel -> pressed = false
                    }
                    invalidateDraw()
                }
            }
        }

        override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
            drawContent()
            if (pressed) drawRect(Color.White.copy(alpha = 0.08f))
            if (focused) {
                val w = 2.5.dp.toPx()
                val r = minOf(size.height / 2f, 18.dp.toPx())
                drawRoundRect(
                    ring,
                    topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(w),
                )
            }
        }
    }
}

/**
 * A presses this when the controller is on it. For dialogs and menus, which are windows of their
 * own where A isn't turned into Android's "select" key.
 */
fun Modifier.pressWithA(enabled: Boolean = true, onClick: () -> Unit): Modifier = this.onKeyEvent { e ->
    val k = e.nativeKeyEvent
    if (k.keyCode != android.view.KeyEvent.KEYCODE_BUTTON_A || !enabled) return@onKeyEvent false
    if (k.action == android.view.KeyEvent.ACTION_UP) onClick()
    true
}

/**
 * Pop-ups (dialogs and menus) that are open on the bottom screen. When the top screen has
 * Android's focus, WalkThor hands controller presses down to the bottom screen, and they go to
 * the newest pop-up here rather than to the screen behind it.
 */
object PadWindows {
    private val open = java.util.LinkedHashMap<android.view.View, Int>()

    @Synchronized fun add(root: android.view.View) { open[root] = (open[root] ?: 0) + 1 }
    @Synchronized fun remove(root: android.view.View) {
        val n = (open[root] ?: return) - 1
        if (n <= 0) open.remove(root) else open[root] = n
    }
    /** The newest pop-up still showing, other than [except] (the screen's own window). */
    @Synchronized fun top(except: android.view.View): android.view.View? =
        open.keys.lastOrNull { it !== except && it.isAttachedToWindow && it.isShown }
}

/** Lets this pop-up take controller presses handed down from the top screen (see [PadWindows]). */
@Composable
fun TakePadPresses() {
    val root = androidx.compose.ui.platform.LocalView.current.rootView
    androidx.compose.runtime.DisposableEffect(root) {
        PadWindows.add(root)
        onDispose { PadWindows.remove(root) }
    }
}

/**
 * Takes [root]'s window out of touch mode so the controller can land on rows and buttons there
 * (Android only lets them take focus once a key has been pressed in that window, and presses
 * handed down from the other screen don't count).
 */
fun leaveTouchMode(root: android.view.View) {
    if (root.isInTouchMode) runCatching { (root.findFocus() ?: root).requestFocusFromTouch() }
}

/** A text button (in dialogs and menus) whose ring shows when the controller is on it. */
@Composable
fun FocusTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    TakePadPresses()
    androidx.compose.material3.TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .onFocusChanged { focused = it.hasFocus }
            .pressWithA(enabled, onClick)
            .border(2.dp, if (focused) frontAccent() else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(50)),
        content = content,
    )
}
