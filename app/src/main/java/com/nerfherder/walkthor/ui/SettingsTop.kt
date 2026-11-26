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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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

/**
 * The sections as tabs, drawn like the tabs during a game: a small icon over each name, and a
 * short line under the open one that glides to whichever you pick.
 */
@Composable
fun SettingsTabsRow(selected: SettingsSection, onSelect: (SettingsSection) -> Unit, modifier: Modifier = Modifier) {
    val narrow = LocalConfiguration.current.screenWidthDp < 480
    val bounds = remember { mutableStateMapOf<SettingsSection, Pair<Float, Float>>() }
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
        horizontalArrangement = Arrangement.spacedBy(if (narrow) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsSection.entries.forEach { s ->
            val on = s == selected
            val tint = if (on) BookColors.Text else Color.White.copy(alpha = 0.55f)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onGloballyPositioned { c -> bounds[s] = c.positionInParent().x to c.size.width.toFloat() }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(s) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SectionIcon(s, tint)
                Spacer(Modifier.height(3.dp))
                Text(
                    s.label,
                    color = tint,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = if (narrow) 11.sp else 12.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
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
