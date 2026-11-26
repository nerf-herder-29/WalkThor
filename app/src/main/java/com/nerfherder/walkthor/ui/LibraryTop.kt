package com.nerfherder.walkthor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nerfherder.walkthor.data.Book
import com.nerfherder.walkthor.data.GameArt
import com.nerfherder.walkthor.data.GamePlays
import com.nerfherder.walkthor.data.Rom
import com.nerfherder.walkthor.data.RomLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** The game you're on in the library, with its guide. */
data class LibrarySpot(val rom: Rom, val guide: Book?, val cover: File?)

/** The library across both screens: the top screen shows the game you're on and its guide. */
object LibraryTop {
    /** Whether the library's game list is up (with no game running). */
    val active = MutableStateFlow(false)
    val current = MutableStateFlow<LibrarySpot?>(null)
}

/**
 * The top screen while you're in the library: the game you're on, big (its picture, system and
 * play time), with its guide beside it: the guide's cover, where you left off and how far
 * through you are. A opens the guide there.
 */
@Composable
fun LibrarySpotlight(spot: LibrarySpot?) {
    val context = LocalContext.current
    val accent = frontAccent()
    Box(Modifier.fillMaxSize().background(Color(0xFF0C0B0E))) {
        if (spot == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Library", color = Color.White, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 40.sp)
                Text("Pick a game on the bottom screen", color = BookColors.TextMuted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            }
            return@Box
        }
        val art = remember { GameArt.get(context) }
        val picture = remember(spot.rom.id) { art.picture(spot.rom, GameArt.Kind.HERO) }
        if (picture != null) {
            AsyncImage(model = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            PlaceholderArt(spot.rom.title, Modifier.fillMaxSize(), showTitle = false, big = true)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF008080A), 0.55f to Color(0x9908080A), 1f to Color(0x6608080A))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE608080A))))
        Text(
            "Library", color = BookColors.TextMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 55.dp, top = 40.dp),
        )
        // The guide's cover, on the right.
        spot.guide?.let { book ->
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 70.dp, bottom = 40.dp)
                    .size(220.dp),
            ) { GuideTile(book, spot.cover, Modifier.fillMaxSize()) }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 55.dp, bottom = 64.dp).fillMaxWidth(0.58f)) {
            val played = GamePlays.timeText(GamePlays.playTime(context, spot.rom))
            val eyebrow = listOfNotNull(RomLibrary.system(spot.rom.system)?.name ?: spot.rom.system, played?.let { "$it played" })
                .joinToString(" · ").uppercase()
            Text(eyebrow, color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            Text(
                spot.rom.title,
                color = Color.White, fontFamily = DisplayFont, fontWeight = FontWeight.Bold,
                fontSize = if (spot.rom.title.length > 22) 44.sp else 60.sp,
                lineHeight = if (spot.rom.title.length > 22) 46.sp else 60.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
            )
            val book = spot.guide
            if (book == null) {
                Text("No guide yet", color = Color(0xFFCFC8BC), fontSize = 17.sp)
            } else {
                val where = when {
                    book.pageCount > 0 && book.lastPage > 0 -> "you stopped on page ${book.lastPage + 1} of ${book.pageCount}"
                    book.pageCount > 0 -> "${book.pageCount} pages, not started"
                    book.lastOffset > 0 -> "part way through"
                    else -> "not started"
                }
                Text(
                    "Guide: ${book.title} · $where",
                    color = Color(0xFFCFC8BC), fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (book.pageCount > 0) {
                    val f = ((book.lastPage + 1).toFloat() / book.pageCount).coerceIn(0f, 1f)
                    Box(Modifier.padding(top = 12.dp).width(380.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2A272E))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(if (book.lastPage > 0) f else 0f).background(accent))
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 55.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (spot.guide != null) {
                TopHint("A", "Open guide")
                TopHint("Y", "Other guides")
            } else {
                TopHint("A", "Add a guide")
            }
        }
    }
}

@Composable
private fun TopHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).border(1.5.dp, Color(0xFF4A4540), CircleShape), contentAlignment = Alignment.Center) {
            Text(key, color = BookColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(7.dp))
        Text(label, color = BookColors.TextMuted, fontSize = 13.sp)
    }
}

/** Apps across both screens: the top screen shows the app you're on. */
object AppsTop {
    /** Whether the Apps screen is up (from your games, with no game running). */
    val active = MutableStateFlow(false)
    val current = MutableStateFlow<InstalledApp?>(null)
}

/**
 * The top screen while Apps is open: laid out like Settings' top screen, with the app you're on
 * (its icon and name, big) and what you can do with it.
 */
@Composable
fun AppsExplainer(app: InstalledApp?, count: Int) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0C0B0E))) {
        Column(Modifier.fillMaxSize().padding(start = 55.dp, end = 55.dp, top = 40.dp, bottom = 40.dp)) {
            Text(
                listOfNotNull("Apps", app?.label).joinToString("  ›  "),
                color = BookColors.TextMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(36.dp))
            if (app == null) {
                Text("Apps", color = Color.White, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 56.sp)
                Text(
                    if (count > 0) "$count apps. Touch one or move to it to see it here." else "Touch an app or move to it to see it here.",
                    color = Color(0xFFCFC8BC), fontSize = 22.sp, modifier = Modifier.padding(top = 22.dp),
                )
                Spacer(Modifier.weight(1f))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = app.icon
                    if (icon != null) {
                        androidx.compose.foundation.Image(icon, contentDescription = null, modifier = Modifier.size(110.dp))
                    } else {
                        Box(Modifier.size(110.dp).background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(26.dp)))
                    }
                    Spacer(Modifier.width(30.dp))
                    Text(
                        app.label,
                        color = Color.White, fontFamily = DisplayFont, fontWeight = FontWeight.Bold,
                        fontSize = if (app.label.length > 24) 44.sp else 56.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "Tap it to open it on the bottom screen. Hold it to open it on the top screen instead, pin it to the top of Apps, or see Android's info for it.",
                    color = Color(0xFFCFC8BC), fontSize = 22.sp, lineHeight = 32.sp,
                    modifier = Modifier.padding(top = 30.dp).fillMaxWidth(0.8f),
                )
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    TopHint("A", "Open")
                    TopHint("B", "Back")
                }
            }
        }
    }
}
