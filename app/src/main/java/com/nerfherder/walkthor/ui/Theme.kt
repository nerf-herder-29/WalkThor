package com.nerfherder.walkthor.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
    val Brass = Color(0xFFC9A35B)
    val BrassSoft = Color(0xFFE3CD95)

    // pages
    val Paper = Color(0xFFF4ECDD)
    val PaperEdge = Color(0xFFE2D6BF)
    val PageInk = Color(0xFF2A241D)
    val PageInkFaint = Color(0xFF9A8B76)
    val Highlight = Color(0xFFFFD65C)
}

val SerifDisplay = FontFamily.Serif

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
    onPrimary = Color(0xFF1E1709),
    primaryContainer = Color(0xFF3A2F1A),
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
private val typography = base.copy(
    headlineSmall = base.headlineSmall.copy(fontFamily = SerifDisplay, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = SerifDisplay, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = SerifDisplay, fontWeight = FontWeight.SemiBold),
)

/** Small-caps-style label used for section headers ("YOUR SHELF"). */
val EyebrowStyle = TextStyle(
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 2.sp,
    color = BookColors.Brass,
)

@Composable
fun WalkThorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
