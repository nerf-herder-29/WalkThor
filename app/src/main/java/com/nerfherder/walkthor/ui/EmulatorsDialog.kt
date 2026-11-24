package com.nerfherder.walkthor.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Which apps count as games (emulators): WalkThor's guess, ticked, and
 * any app you tick or untick yourself (for an emulator it doesn't know, like a new one).
 */
@Composable
fun EmulatorsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps by AppCatalog.apps.collectAsState()
    // Loaded again if it's let go while open (it's dropped to save memory when a game starts).
    androidx.compose.runtime.LaunchedEffect(apps == null) { if (AppCatalog.apps.value == null) AppCatalog.refresh(context) }
    var query by remember { mutableStateOf("") }
    // Bumped on each tick so the list re-reads the choices.
    var version by remember { mutableStateOf(0) }
    val list = remember(apps, version) {
        apps.orEmpty().map { it to com.nerfherder.walkthor.EmulatorWatch.isEmulator(context, it.packageName) }
            .sortedWith(compareBy({ !it.second }, { it.first.label.lowercase() }))
    }
    val shown = if (query.isBlank()) list else list.filter { it.first.label.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BookColors.Surface,
        title = { Text("Emulators", fontFamily = DisplayFont) },
        text = {
            Column {
                Text(
                    "These count as games (the drawer's tabs, play time, and opening WalkThor when one starts). Tick any it missed. Both screens: for emulators that draw on the bottom screen too, so WalkThor stays off it while they play.",
                    color = BookColors.TextMuted, fontSize = 13.sp,
                )
                SearchBox(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
                if (apps == null) Text("Loading your apps…", color = BookColors.TextMuted)
                // The list always keeps room on a short screen, however the text above wraps.
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 160.dp, max = 340.dp)) {
                    items(shown.size) { i ->
                        val (app, on) = shown[i]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    com.nerfherder.walkthor.EmulatorWatch.setChosen(context, app.packageName, !on)
                                    version++
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = on,
                                onCheckedChange = null,
                                colors = androidx.compose.material3.CheckboxDefaults.colors(
                                    checkedColor = BookColors.Text, checkmarkColor = androidx.compose.ui.graphics.Color.Black,
                                    uncheckedColor = BookColors.TextMuted,
                                ),
                            )
                            Spacer(Modifier.padding(start = 8.dp))
                            app.icon?.let {
                                androidx.compose.foundation.Image(it, contentDescription = null, modifier = Modifier.padding(end = 10.dp).size(28.dp))
                            }
                            Text(app.label, color = BookColors.Text, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            if (on) {
                                // Uses both screens: tap to switch (its own tap, not the row's).
                                val both = remember(app.packageName, version) { com.nerfherder.walkthor.EmulatorWatch.usesBothScreens(context, app.packageName) }
                                Text(
                                    "Both screens",
                                    color = if (both) androidx.compose.ui.graphics.Color.Black else BookColors.TextMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                                        .background(if (both) BookColors.Text else BookColors.SurfaceHigh)
                                        .clickable {
                                            com.nerfherder.walkthor.EmulatorWatch.setBothScreens(context, app.packageName, !both)
                                            version++
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

