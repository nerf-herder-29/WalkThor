package com.nerfherder.walkthor.ui

import androidx.compose.ui.graphics.Color

/**
 * Each system's colour and short name, for the ring around its games' bubbles: the colours the
 * systems are known by (Nintendo's red for the Switches, the GameCube's purple, the Wii's blue,
 * PlayStation blue, Xbox green and so on).
 */
object SystemColors {
    private val COLORS = mapOf(
        "switch2" to 0xFFE60012, "switch" to 0xFFE60012,
        "3ds" to 0xFFD12A2F, "nds" to 0xFFB9BEC6,
        "wiiu" to 0xFF00A2D8, "wii" to 0xFF35A9E8,
        "gc" to 0xFF7A4FD6, "n64" to 0xFF1FA34A,
        "gba" to 0xFF4B48C8, "gbc" to 0xFFF2C230, "gb" to 0xFF8BAC0F,
        "snes" to 0xFFA99BDB, "nes" to 0xFF9B9B9B, "virtualboy" to 0xFFA50E0E,
        "ps2" to 0xFF2A5BD7, "psx" to 0xFFA7A9AE, "psp" to 0xFFC6CBD3, "psvita" to 0xFF3A8FE8, "ps3" to 0xFF7D8590,
        "xbox" to 0xFF107C10, "xbox360" to 0xFF52B043,
        "dreamcast" to 0xFFF0662A, "saturn" to 0xFF5566C4, "genesis" to 0xFF2E6FD8, "segacd" to 0xFF4C86E0,
        "sega32x" to 0xFFD9A31E, "mastersystem" to 0xFFC62828, "gamegear" to 0xFF7A7F87,
        "pcengine" to 0xFFF39800, "neogeo" to 0xFFD4A017, "ngpc" to 0xFFE8C547,
        "arcade" to 0xFFFF4FA3, "atari2600" to 0xFFB5651D, "lynx" to 0xFFE0702A, "wonderswan" to 0xFF39B0A8,
        "pc" to 0xFF8AA4BF, "windows" to 0xFF8AA4BF,
        com.nerfherder.walkthor.data.PinnedShortcuts.SYSTEM to 0xFF3DDC84,
    )
    private val LABELS = mapOf(
        "switch2" to "SWITCH 2", "switch" to "SWITCH", "3ds" to "3DS", "nds" to "DS", "wiiu" to "WII U", "wii" to "WII",
        "gc" to "GAMECUBE", "n64" to "N64", "gba" to "GBA", "gbc" to "GBC", "gb" to "GAME BOY", "snes" to "SNES", "nes" to "NES",
        "virtualboy" to "VIRTUAL BOY", "ps2" to "PS2", "psx" to "PS1", "psp" to "PSP", "psvita" to "VITA", "ps3" to "PS3",
        "xbox" to "XBOX", "xbox360" to "XBOX 360", "dreamcast" to "DREAMCAST", "saturn" to "SATURN", "genesis" to "GENESIS",
        "segacd" to "SEGA CD", "sega32x" to "32X", "mastersystem" to "MASTER SYSTEM", "gamegear" to "GAME GEAR",
        "pcengine" to "PC ENGINE", "neogeo" to "NEO GEO", "ngpc" to "NGPC", "arcade" to "ARCADE", "atari2600" to "ATARI",
        "lynx" to "LYNX", "wonderswan" to "WONDERSWAN", "pc" to "PC", "windows" to "PC",
        com.nerfherder.walkthor.data.PinnedShortcuts.SYSTEM to "SHORTCUT",
    )

    /** Android's green, for the ring around apps. */
    val ANDROID = Color(0xFF3DDC84)

    fun color(system: String): Color = Color(COLORS[system] ?: 0xFF8A8378)
    fun label(system: String): String = LABELS[system] ?: system.uppercase()
}
