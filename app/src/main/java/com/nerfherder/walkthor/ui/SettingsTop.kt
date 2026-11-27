package com.nerfherder.walkthor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow

/** The setting being explained on the top screen. */
data class SettingInfo(val section: String, val group: String?, val title: String, val text: String?, val value: String?)

/**
 * Settings across both screens: while Settings is open with no game running, the top screen
 * explains the setting you're on (the last one you touched or moved to).
 */
object SettingsTop {
    /** The open section's name, or null when Settings isn't open. */
    val section = MutableStateFlow<String?>(null)
    /** The setting you're on, or null for none yet. */
    val current = MutableStateFlow<SettingInfo?>(null)
    /** The open section's settings, top to bottom, for the overview before you pick one. */
    val titles = mutableStateListOf<String>()

    fun pick(info: SettingInfo) {
        if (section.value != null) current.value = info
    }
}

/** The settings group a row sits in, for the top screen's breadcrumb. */
val LocalSettingsGroup = compositionLocalOf<String?> { null }
/** The section a row sits in. */
val LocalSettingsSection = compositionLocalOf<String> { "" }

/** One tab: its name and its small icon, drawn in the given colour. */
class TabItem(val label: String, val icon: @Composable (Color) -> Unit)

/**
 * A row of tabs drawn like the tabs during a game: a small icon over each name, and a short line
 * under the open one that glides to whichever you pick.
 */
@Composable
fun TabsRow(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    // Many tabs: each keeps a comfortable size and the row scrolls, keeping the open one in view.
    if (items.size > 8) { ScrollingTabsRow(items, selected, onSelect, modifier); return }
    val narrow = LocalConfiguration.current.screenWidthDp < 480 || items.size > 6
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val indicator = remember { Animatable(Float.NaN) }
    val target = bounds[selected]?.let { (x, w) -> x + w / 2 }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        if (indicator.value.isNaN()) indicator.snapTo(target)
        else indicator.animateTo(target, spring(dampingRatio = 0.82f, stiffness = 420f))
    }
    Row(
        modifier
            .fillMaxHeight()
            .padding(vertical = 6.dp)
            .drawWithContent {
                drawContent()
                val cx = indicator.value
                if (!cx.isNaN()) {
                    val half = 14.dp.toPx()
                    val y = size.height - 2.dp.toPx()
                    drawLine(Color(0xFFF5F5FA), Offset(cx - half, y), Offset(cx + half, y), 3.dp.toPx(), StrokeCap.Round)
                }
            },
        horizontalArrangement = Arrangement.spacedBy(if (items.size > 6) 0.dp else if (narrow) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            val on = i == selected
            val tint = if (on) BookColors.Text else Color.White.copy(alpha = 0.55f)
            Column(
                Modifier
                    // Longer names get more room, so none is cut off.
                    .weight(item.label.length.coerceAtLeast(5) + 3f)
                    .fillMaxHeight()
                    .onGloballyPositioned { c -> bounds[i] = c.positionInParent().x to c.size.width.toFloat() }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                item.icon(tint)
                Spacer(Modifier.height(3.dp))
                Text(
                    item.label,
                    color = tint,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = if (items.size > 6) 10.5.sp else if (narrow) 11.sp else 12.sp,
                    letterSpacing = if (items.size > 6) (-0.2).sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** [TabsRow] for many tabs: full-size tabs in a row that scrolls, with soft fades at an edge that has more. */
@Composable
private fun ScrollingTabsRow(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val indicator = remember { Animatable(Float.NaN) }
    val scroll = androidx.compose.foundation.rememberScrollState()
    val target = bounds[selected]?.let { (x, w) -> x + w / 2 }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        if (indicator.value.isNaN()) indicator.snapTo(target)
        else indicator.animateTo(target, spring(dampingRatio = 0.82f, stiffness = 420f))
    }
    // Bring the open tab into the middle of the row.
    LaunchedEffect(selected, bounds[selected], scroll.viewportSize) {
        val (x, w) = bounds[selected] ?: return@LaunchedEffect
        val to = (x + w / 2 - scroll.viewportSize / 2f).toInt().coerceIn(0, scroll.maxValue)
        scroll.animateScrollTo(to)
    }
    val fade = 22.dp
    Box(
        modifier.fillMaxHeight()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val f = fade.toPx()
                if (scroll.value > 0) drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Transparent, f / size.width to Color.Black),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
                if (scroll.value < scroll.maxValue) drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(1f - f / size.width to Color.Black, 1f to Color.Transparent),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .horizontalScroll(scroll)
                .padding(vertical = 6.dp)
                .drawWithContent {
                    drawContent()
                    val cx = indicator.value
                    if (!cx.isNaN()) {
                        val half = 14.dp.toPx()
                        val y = size.height - 2.dp.toPx()
                        drawLine(Color(0xFFF5F5FA), Offset(cx - half, y), Offset(cx + half, y), 3.dp.toPx(), StrokeCap.Round)
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                val tint = if (on) BookColors.Text else Color.White.copy(alpha = 0.55f)
                Column(
                    Modifier
                        .fillMaxHeight()
                        .widthIn(min = 64.dp)
                        .onGloballyPositioned { c -> bounds[i] = c.positionInParent().x to c.size.width.toFloat() }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                        .padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    item.icon(tint)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.label,
                        color = tint,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** The settings sections as tabs (Settings during a game). */
@Composable
fun SettingsTabsRow(selected: SettingsSection, onSelect: (SettingsSection) -> Unit, modifier: Modifier = Modifier) {
    val items = remember { SettingsSection.entries.map { s -> TabItem(s.label) { c -> SectionIcon(s, c) } } }
    TabsRow(items, SettingsSection.entries.indexOf(selected), { onSelect(SettingsSection.entries[it]) }, modifier)
}

/** The bottom screen's tabs with no game running: your games, your apps, then each settings section. */
sealed class MainTab(val label: String) {
    data object Games : MainTab("Games")
    data object Apps : MainTab("Apps")
    /** Your emulators' updates (also in the drawer). */
    data object Updates : MainTab("Updates")
    class Settings(val section: SettingsSection) : MainTab(section.label) {
        override fun equals(other: Any?) = other is Settings && other.section == section
        override fun hashCode() = section.hashCode()
    }
    companion object {
        // Lazily: the tabs are subclasses, not yet made while this class is first being set up.
        val all: List<MainTab> by lazy { listOf(Games, Apps, Updates) + SettingsSection.entries.map { Settings(it) } }
        fun byName(name: String?): MainTab = all.firstOrNull { it.label == name } ?: Games
    }
}

/** The L1 / R1 handler for the main tabs, while they're up (MainActivity passes it presses first). */
object MainTabs {
    @Volatile var shoulder: ((android.view.KeyEvent) -> Boolean)? = null
    /** Whether a row of the open tab has the controller's focus (so the D-pad moves between rows). */
    @Volatile var rowFocused = false
    /** Puts the controller's focus on the open tab's first row; returns whether it could. */
    @Volatile var focusFirst: (() -> Boolean)? = null
}

/** The main tabs' row, with the search button (on Games) at the right. */
@Composable
fun MainTabBar(selected: MainTab, onSelect: (MainTab) -> Unit, onSearch: (() -> Unit)?) {
    val items = remember {
        MainTab.all.map { t ->
            TabItem(t.label) { c ->
                when (t) {
                    MainTab.Games -> GamesIcon(c)
                    MainTab.Apps -> AppsIcon(c)
                    MainTab.Updates -> UpdatesIcon(c)
                    is MainTab.Settings -> SectionIcon(t.section, c)
                }
            }
        }
    }
    // The whole width for the tabs, with just the search button's room at the right.
    Row(
        Modifier.fillMaxWidth().height(TabBarHeight).background(BookColors.Ink).padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabsRow(items, MainTab.all.indexOf(selected).coerceAtLeast(0), { onSelect(MainTab.all[it]) }, Modifier.weight(1f))
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
            if (onSearch != null) {
                androidx.compose.material3.IconButton(onClick = onSearch) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.Search,
                        contentDescription = "Search your games", tint = BookColors.TextMuted,
                    )
                }
            }
        }
    }
}

/** A rounded screen with a play mark: your games. */
@Composable
private fun GamesIcon(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val s = size.width
        val w = 1.6.dp.toPx()
        drawRoundRect(tint, Offset(s * 0.06f, s * 0.14f), Size(s * 0.88f, s * 0.72f), CornerRadius(s * 0.2f), style = Stroke(w))
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.42f, s * 0.34f); lineTo(s * 0.66f, s * 0.5f); lineTo(s * 0.42f, s * 0.66f); close()
        }
        drawPath(p, tint)
    }
}

/** An arrow down into a tray: updates. */
@Composable
private fun UpdatesIcon(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val s = size.width
        val st = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.5f, s * 0.1f); lineTo(s * 0.5f, s * 0.62f)
            moveTo(s * 0.3f, s * 0.43f); lineTo(s * 0.5f, s * 0.63f); lineTo(s * 0.7f, s * 0.43f)
            moveTo(s * 0.12f, s * 0.6f); lineTo(s * 0.12f, s * 0.88f); lineTo(s * 0.88f, s * 0.88f); lineTo(s * 0.88f, s * 0.6f)
        }
        drawPath(p, tint, style = st)
    }
}

/** Four small squares: your apps. */
@Composable
private fun AppsIcon(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val s = size.width
        val w = 1.6.dp.toPx()
        val k = s * 0.36f
        for ((x, y) in listOf(0.08f to 0.08f, 0.56f to 0.08f, 0.08f to 0.56f, 0.56f to 0.56f)) {
            drawRoundRect(tint, Offset(s * x, s * y), Size(k, k), CornerRadius(s * 0.08f), style = Stroke(w))
        }
    }
}

/** Each section's small line icon, in the same hand as the game tabs'. */
@Composable
fun SectionIcon(section: SettingsSection, tint: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier) {
        val w = 1.6.dp.toPx()
        val s = size.width
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        when (section) {
            SettingsSection.GENERAL -> {
                // A cog: a ring with teeth.
                drawCircle(tint, radius = s * 0.22f, style = stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Offset(s / 2, s / 2)
                    val from = c + Offset((kotlin.math.cos(a) * s * 0.32).toFloat(), (kotlin.math.sin(a) * s * 0.32).toFloat())
                    val to = c + Offset((kotlin.math.cos(a) * s * 0.45).toFloat(), (kotlin.math.sin(a) * s * 0.45).toFloat())
                    drawLine(tint, from, to, w, StrokeCap.Round)
                }
            }
            SettingsSection.SHORTCUTS -> {
                // Two buttons pressed together.
                drawCircle(tint, radius = s * 0.17f, center = Offset(s * 0.22f, s * 0.5f), style = stroke)
                drawCircle(tint, radius = s * 0.17f, center = Offset(s * 0.78f, s * 0.5f), style = stroke)
                drawLine(tint, Offset(s * 0.5f, s * 0.38f), Offset(s * 0.5f, s * 0.62f), w, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.38f, s * 0.5f), Offset(s * 0.62f, s * 0.5f), w, StrokeCap.Round)
            }
            SettingsSection.SCREEN -> {
                // The two screens, the top one wider.
                drawRoundRect(tint, Offset(s * 0.04f, s * 0.08f), Size(s * 0.92f, s * 0.42f), CornerRadius(s * 0.06f), style = stroke)
                drawRoundRect(tint, Offset(s * 0.16f, s * 0.58f), Size(s * 0.68f, s * 0.34f), CornerRadius(s * 0.06f), style = stroke)
            }
            SettingsSection.EMULATORS -> {
                // A controller: a body, a d-pad and two buttons.
                drawRoundRect(tint, Offset(s * 0.04f, s * 0.24f), Size(s * 0.92f, s * 0.52f), CornerRadius(s * 0.22f), style = stroke)
                drawLine(tint, Offset(s * 0.22f, s * 0.5f), Offset(s * 0.42f, s * 0.5f), w, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.32f, s * 0.4f), Offset(s * 0.32f, s * 0.6f), w, StrokeCap.Round)
                drawCircle(tint, radius = w * 0.9f, center = Offset(s * 0.66f, s * 0.44f))
                drawCircle(tint, radius = w * 0.9f, center = Offset(s * 0.76f, s * 0.56f))
            }
            SettingsSection.GUIDES -> {
                // An open book.
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(s * 0.5f, s * 0.26f)
                    quadraticTo(s * 0.28f, s * 0.14f, s * 0.06f, s * 0.2f)
                    lineTo(s * 0.06f, s * 0.8f)
                    quadraticTo(s * 0.28f, s * 0.74f, s * 0.5f, s * 0.86f)
                    quadraticTo(s * 0.72f, s * 0.74f, s * 0.94f, s * 0.8f)
                    lineTo(s * 0.94f, s * 0.2f)
                    quadraticTo(s * 0.72f, s * 0.14f, s * 0.5f, s * 0.26f)
                    lineTo(s * 0.5f, s * 0.86f)
                }
                drawPath(p, tint, style = stroke)
            }
            SettingsSection.ARTWORK -> {
                // A picture: a frame with a hill and a sun.
                drawRoundRect(tint, Offset(s * 0.06f, s * 0.14f), Size(s * 0.88f, s * 0.72f), CornerRadius(s * 0.1f), style = stroke)
                val hill = androidx.compose.ui.graphics.Path().apply {
                    moveTo(s * 0.14f, s * 0.76f); lineTo(s * 0.4f, s * 0.46f); lineTo(s * 0.58f, s * 0.64f)
                    lineTo(s * 0.68f, s * 0.54f); lineTo(s * 0.86f, s * 0.76f)
                }
                drawPath(hill, tint, style = stroke)
                drawCircle(tint, radius = s * 0.07f, center = Offset(s * 0.7f, s * 0.32f), style = stroke)
            }
            SettingsSection.CONTROLLERS -> {
                // Two Joy-Cons side by side, a stick on the left one and a button on the right.
                drawRoundRect(tint, Offset(s * 0.12f, s * 0.08f), Size(s * 0.34f, s * 0.84f), CornerRadius(s * 0.17f), style = stroke)
                drawRoundRect(tint, Offset(s * 0.54f, s * 0.08f), Size(s * 0.34f, s * 0.84f), CornerRadius(s * 0.17f), style = stroke)
                drawCircle(tint, radius = s * 0.07f, center = Offset(s * 0.29f, s * 0.34f), style = stroke)
                drawCircle(tint, radius = w * 0.9f, center = Offset(s * 0.71f, s * 0.6f))
            }
            SettingsSection.ABOUT -> {
                // An "i" in a circle.
                drawCircle(tint, radius = s * 0.44f, style = stroke)
                drawCircle(tint, radius = w * 0.8f, center = Offset(s / 2, s * 0.3f))
                drawLine(tint, Offset(s / 2, s * 0.45f), Offset(s / 2, s * 0.72f), w, StrokeCap.Round)
            }
        }
    }
}

/**
 * The top screen while Settings is open: the setting you're on, explained in full (where it is,
 * what it does, how it's set now). Before you pick one, the open section and what's in it.
 */
@Composable
fun SettingsExplainer(section: String, info: SettingInfo?, titles: List<String>) {
    val entry = SettingsSection.entries.firstOrNull { it.label == section }
    Box(Modifier.fillMaxSize().background(Color(0xFF0C0B0E))) {
        Column(Modifier.fillMaxSize().padding(start = 55.dp, end = 55.dp, top = 40.dp, bottom = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry != null) {
                    SectionIcon(entry, BookColors.TextMuted, Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                }
                val crumb = listOfNotNull("Settings", section, info?.group?.lowercase()?.replaceFirstChar { it.uppercase() })
                Text(crumb.joinToString("  ›  "), color = BookColors.TextMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(36.dp))
            if (info == null) {
                Text(section, color = Color.White, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 56.sp)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        titles.distinct().take(14).chunked(7).getOrNull(0)?.forEach { TitleLine(it) }
                    }
                    Column(Modifier.weight(1f)) {
                        titles.distinct().take(14).chunked(7).getOrNull(1)?.forEach { TitleLine(it) }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("Touch a setting or move to it to read about it here.", color = BookColors.TextMuted, fontSize = 15.sp)
            } else {
                Text(
                    info.title,
                    color = Color.White, fontFamily = DisplayFont, fontWeight = FontWeight.Bold,
                    fontSize = if (info.title.length > 28) 44.sp else 56.sp,
                    lineHeight = if (info.title.length > 28) 48.sp else 60.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                info.text?.let {
                    Text(
                        it, color = Color(0xFFCFC8BC), fontSize = 22.sp, lineHeight = 32.sp,
                        maxLines = 6, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 22.dp).fillMaxWidth(0.8f),
                    )
                }
                info.value?.let {
                    Text(
                        "Now: $it", color = frontAccent(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 26.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    KeyHint("A", "Change")
                    KeyHint("B", "Back")
                }
            }
        }
    }
}

@Composable
private fun TitleLine(text: String) {
    Text(
        text, color = Color(0xFFCFC8BC), fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 7.dp, horizontal = 0.dp).padding(end = 24.dp),
    )
}

@Composable
private fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).border(1.5.dp, Color(0xFF4A4540), CircleShape), contentAlignment = Alignment.Center) {
            Text(key, color = BookColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(7.dp))
        Text(label, color = BookColors.TextMuted, fontSize = 13.sp)
    }
}
