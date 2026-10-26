package com.nerfherder.walkthor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The sections of the settings screen, in the order of the list on its left. */
enum class SettingsSection(val label: String) {
    GENERAL("General"),
    ARTWORK("Artwork"),
    EMULATORS("Emulators"),
    COCOON("Cocoon"),
    ABOUT("About"),
}

/**
 * The settings screen: a header, the sections down the left, and the chosen section's settings on
 * the right. It covers the shelf; back (or the arrow) returns to it.
 */
@Composable
fun SettingsFrame(
    section: SettingsSection,
    onSection: (SettingsSection) -> Unit,
    version: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val narrow = LocalConfiguration.current.screenWidthDp < 520
    Column(
        Modifier
            .fillMaxSize()
            .background(BookColors.Ink)
            // Taps on empty space shouldn't fall through to the shelf underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Row(
            Modifier.fillMaxWidth().height(if (narrow) 48.dp else 56.dp).padding(start = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = BookColors.Text)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Settings",
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (narrow) 18.sp else 20.sp,
                color = BookColors.Text,
                modifier = Modifier.weight(1f),
            )
            Text("WalkThor $version", color = BookColors.TextMuted, fontSize = 11.sp, maxLines = 1)
        }
        HorizontalDivider(color = BookColors.Hairline)
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .width(if (narrow) 118.dp else 165.dp)
                    .fillMaxHeight()
                    .padding(horizontal = if (narrow) 6.dp else 9.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                SettingsSection.entries.forEach { s ->
                    val on = s == section
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) BookColors.SurfaceHigh else Color.Transparent)
                            .clickable { onSection(s) }
                            .padding(horizontal = 11.dp, vertical = if (narrow) 8.dp else 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .height(14.dp)
                                .background(if (on) BookColors.Text else Color.Transparent, RoundedCornerShape(1.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            s.label,
                            fontSize = if (narrow) 13.sp else 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (on) BookColors.Text else BookColors.TextMuted,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (narrow) "Close" else "Close WalkThor",
                    maxLines = 1,
                    fontSize = 11.sp,
                    color = BookColors.TextMuted.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                )
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(BookColors.Hairline))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (narrow) 12.dp else 20.dp, vertical = 15.dp),
                content = content,
            )
        }
    }
}

/** A titled card of settings rows. */
@Composable
fun SettingsGroup(title: String?, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) {
        Text(title.uppercase(), style = EyebrowStyle, modifier = Modifier.padding(start = 2.dp, bottom = 7.dp))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(BookColors.Surface)
            .border(1.dp, BookColors.Hairline, RoundedCornerShape(11.dp)),
        content = content,
    )
    Spacer(Modifier.height(13.dp))
}

/** A short note under a group (what a section needs before its settings appear). */
@Composable
fun SettingsNote(text: String) {
    Text(text, color = BookColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp, bottom = 13.dp))
}

/**
 * One row: a title, an optional line under it, and whatever goes on the right. Buttons that
 * don't fit beside the title (on the small screen) drop below it.
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    if (divider) HorizontalDivider(color = BookColors.Hairline.copy(alpha = 0.6f))
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        val stack = buttons != null && maxWidth < 380.dp
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = BookColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, fontSize = 11.sp, color = BookColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (buttons != null && !stack) {
                    Spacer(Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = buttons)
                }
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
                }
            }
            if (buttons != null && stack) {
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
    }
}

/** An on/off setting: the whole row toggles it. */
@Composable
fun SettingSwitch(title: String, subtitle: String? = null, checked: Boolean, divider: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, onClick = { onChange(!checked) }, divider = divider) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(
                checkedTrackColor = BookColors.Text,
                checkedThumbColor = Color.Black,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = BookColors.Hairline,
                uncheckedThumbColor = BookColors.TextMuted,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** A row that opens something: an optional value, then a chevron. */
@Composable
fun SettingLink(title: String, subtitle: String? = null, value: String? = null, divider: Boolean = true, onClick: () -> Unit) {
    SettingRow(title, subtitle, onClick = onClick, divider = divider) {
        if (value != null) Text(value, fontSize = 12.sp, color = BookColors.TextMuted, maxLines = 1)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = BookColors.TextMuted.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/** A small rounded button inside a row; [quiet] for the less common choice (Unlink). */
@Composable
fun PillButton(text: String, quiet: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = if (quiet) BookColors.TextMuted else BookColors.Text,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (quiet) Color.Transparent else BookColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

/** "Linked" / "Not linked" status tag. */
@Composable
fun StatusTag(text: String, ok: Boolean) {
    Text(
        text,
        fontSize = 10.sp,
        color = if (ok) BookColors.Text else BookColors.TextMuted,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, if (ok) BookColors.Brass else BookColors.Hairline, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
