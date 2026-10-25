package com.nerfherder.walkthor.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
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
import com.nerfherder.walkthor.data.RomLibrary
import com.nerfherder.walkthor.data.Scraper
import com.nerfherder.walkthor.data.ScraperPrefs

/** Reading the ROMs folder, or scraping artwork: a thin progress bar under the shelf's header. */
@Composable
fun ScrapeProgress() {
    val context = LocalContext.current
    val reading by RomLibrary.get(context).scanning.collectAsState()
    val state by Scraper.state.collectAsState()
    val s = state
    when {
        reading != null -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = BookColors.Brass, trackColor = BookColors.Hairline)
            Text(reading ?: "", fontSize = 11.sp, color = BookColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
        }
        s != null -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (s.running) {
                    LinearProgressIndicator(
                        progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = BookColors.Brass,
                        trackColor = BookColors.Hairline,
                    )
                }
                Text(
                    if (s.running) "Scraping artwork: ${s.done + 1} of ${s.total}" + (s.current?.let { " · $it" } ?: "")
                    else s.message ?: "",
                    fontSize = 11.sp,
                    color = BookColors.TextMuted,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = { if (s.running) Scraper.cancel() else Scraper.dismiss() }) {
                Text(if (s.running) "Stop" else "OK", fontSize = 12.sp)
            }
        }
    }
}

/**
 * Your scraper accounts. SteamGridDB: an API key (steamgriddb.com → Preferences → API).
 * ScreenScraper: your screenscraper.fr username and password, plus developer credentials unless
 * this copy of WalkThor has them built in.
 */
@Composable
fun ScraperAccountsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var sgdb by remember { mutableStateOf(ScraperPrefs.sgdbKey(context)) }
    var ssUser by remember { mutableStateOf(ScraperPrefs.ssUser(context)) }
    var ssPass by remember { mutableStateOf(ScraperPrefs.ssPassword(context)) }
    var devId by remember { mutableStateOf(ScraperPrefs.ssDevIdOwn(context)) }
    var devPass by remember { mutableStateOf(ScraperPrefs.ssDevPasswordOwn(context)) }
    val romsLinked = RomLibrary.get(context).folder.collectAsState().value != null
    fun save() = ScraperPrefs.save(context, ssUser, ssPass, devId, devPass, sgdb)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BookColors.Surface,
        title = { Text("Scraper accounts", fontFamily = DisplayFont) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "WalkThor fetches box art, wide art and logos for the games in your ROMs folder. It only goes " +
                        "online when you scrape, and only sends each game's file name, its system and these details. " +
                        "Everything here stays on this device.",
                    color = BookColors.TextMuted, fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text("SteamGridDB", style = EyebrowStyle)
                Text("Wide art and logos. Get a key at steamgriddb.com → Preferences → API.", color = BookColors.TextMuted, fontSize = 12.sp)
                Field("API key", sgdb, { sgdb = it }, secret = true)
                Spacer(Modifier.height(14.dp))
                Text("ScreenScraper", style = EyebrowStyle)
                Text("Box art, matched by system and ROM name. Your screenscraper.fr account:", color = BookColors.TextMuted, fontSize = 12.sp)
                Field("Username", ssUser, { ssUser = it })
                Field("Password", ssPass, { ssPass = it }, secret = true)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (ScraperPrefs.builtInDev) "Developer credentials are built into this WalkThor; only fill these in to use your own."
                    else "ScreenScraper also needs developer credentials (ask for them on ScreenScraper's forum).",
                    color = BookColors.TextMuted, fontSize = 12.sp,
                )
                Field("Developer ID", devId, { devId = it })
                Field("Developer password", devPass, { devPass = it }, secret = true)
            }
        },
        confirmButton = {
            Row {
                if (romsLinked) {
                    TextButton(onClick = {
                        save()
                        if (ScraperPrefs.hasAny(context)) Scraper.scrapeAll(context)
                        onDismiss()
                    }) { Text("Save & scrape") }
                }
                TextButton(onClick = { save(); onDismiss() }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = BookColors.TextMuted) } },
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, autoCorrect = false),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}
