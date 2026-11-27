package com.nerfherder.walkthor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
 * Home while a game you started from WalkThor plays, laid out like the top screen's spotlight:
 * the game's hero picture with its logo in the middle, the time and battery at the top left, and
 * at the bottom left its system and [emulator], how long this session has run (big) and your
 * time with it in all. [bar] is the tab bar.
 */
@Composable
fun GameHome(rom: Rom, emulator: String?, bar: @Composable () -> Unit) {
    val context = LocalContext.current
    val art = remember { GameArt.get(context) }
    LaunchedEffect(Unit) { art.load() }
    val artVersion by art.version.collectAsState()
    val hero = remember(rom.id, artVersion) { art.picture(rom, GameArt.Kind.HERO) ?: art.picture(rom, GameArt.Kind.COVER) }
    val logo = remember(rom.id, artVersion) { art.picture(rom, GameArt.Kind.LOGO) }

    // Looked at again each half minute.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val plays by GamePlays.changed.collectAsState()
    val started = remember(rom.id, plays) { GamePlays.lastPlayed(context, rom) }
    val session = if (started > 0) GamePlays.timeText((now - started).coerceAtLeast(0)) ?: "Just started" else null
    val total = remember(rom.id, plays, now) { GamePlays.timeText(GamePlays.playTime(context, rom)) }
    val eyebrow = listOfNotNull(RomLibrary.system(rom.system)?.name, emulator).joinToString(" · ").uppercase()

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
        // A little darker overall, and dark along the top (the tabs) and the bottom (the details).
        Box(Modifier.fillMaxSize().background(Color(0x33060608)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x99060608), 0.2f to Color.Transparent, 0.5f to Color.Transparent, 1f to Color(0xE6060608)),
            ),
        )
        // The game's logo in the middle, or its name when it has none.
        Box(Modifier.fillMaxSize().padding(top = 64.dp), contentAlignment = Alignment.Center) {
            if (logo != null) {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(context).data(logo).crossfade(220).build(),
                    contentDescription = rom.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(0.6f).fillMaxHeight(0.34f),
                )
            } else {
                Text(
                    rom.title, color = Color.White, fontFamily = DisplayFont, fontWeight = FontWeight.Bold,
                    fontSize = if (rom.title.length > 24) 30.sp else 40.sp, lineHeight = 42.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
        }
        CompositionLocalProvider(LocalBackdrop provides true) {
            Column(Modifier.fillMaxSize()) {
                bar()
                ClockLine(Modifier.padding(start = 28.dp, top = 10.dp))
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 28.dp, end = 28.dp, bottom = 26.dp)) {
            if (eyebrow.isNotEmpty()) Text(
                eyebrow, color = frontAccent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (session != null) Text(
                session, color = Color.White, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 50.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            val under = listOfNotNull(if (session != null) "playing now" else null, total?.let { "$it in all" }).joinToString(" · ")
            if (under.isNotEmpty()) Text(under, color = Color(0xFFCFC8BC), fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
