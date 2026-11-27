package com.nerfherder.walkthor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nerfherder.walkthor.data.GameArt
import com.nerfherder.walkthor.data.GamePlays
import com.nerfherder.walkthor.data.Rom
import com.nerfherder.walkthor.data.RomLibrary
import kotlinx.coroutines.delay

/**
 * Home while a game you started from WalkThor plays: Home as always, over the game's hero
 * picture (darkened so everything reads), with its logo at the top and how long you've played.
 * [bar] is the tab bar; [home] is Home itself, drawn see-through.
 */
@Composable
fun GameHome(rom: Rom, bar: @Composable () -> Unit, home: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    val art = remember { GameArt.get(context) }
    LaunchedEffect(Unit) { art.load() }
    val artVersion by art.version.collectAsState()
    val hero = remember(rom.id, artVersion) { art.picture(rom, GameArt.Kind.HERO) ?: art.picture(rom, GameArt.Kind.COVER) }
    val logo = remember(rom.id, artVersion) { art.picture(rom, GameArt.Kind.LOGO) }

    // How long this game has been playing, and in all: looked at again each half minute.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val plays by GamePlays.changed.collectAsState()
    val line = remember(rom.id, now, plays) {
        val started = GamePlays.lastPlayed(context, rom)
        val session = if (started > 0) GamePlays.timeText((now - started).coerceAtLeast(0)) else null
        val total = GamePlays.timeText(GamePlays.playTime(context, rom))
        listOfNotNull(
            session?.let { "Playing for $it" },
            total?.let { "$it in all" },
            RomLibrary.system(rom.system)?.name,
        ).joinToString(" · ")
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hero != null) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(context).data(hero).crossfade(220).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PlaceholderArt(rom.title, Modifier.fillMaxSize(), showTitle = false, big = true)
        }
        // Darkened, more so at the top (the tabs) and the bottom (the dock).
        Box(Modifier.fillMaxSize().background(Color(0x8C060608)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0xB3060608), 0.18f to Color.Transparent, 0.75f to Color.Transparent, 1f to Color(0xCC060608)),
            ),
        )
        CompositionLocalProvider(LocalBackdrop provides true) {
            Column(Modifier.fillMaxSize()) {
                bar()
                // The game's logo, or its name when it has none.
                Box(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 40.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    if (logo != null) {
                        AsyncImage(
                            model = coil.request.ImageRequest.Builder(context).data(logo).crossfade(220).build(),
                            contentDescription = rom.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(0.6f).fillMaxHeight(),
                        )
                    } else {
                        Text(
                            rom.title, color = Color.White, fontFamily = DisplayFont, fontWeight = FontWeight.Bold,
                            fontSize = if (rom.title.length > 24) 26.sp else 34.sp, lineHeight = 36.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        )
                    }
                }
                if (line.isNotEmpty()) Text(
                    line, color = Color(0xFFE9F0F6), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
                home()
            }
        }
    }
}
