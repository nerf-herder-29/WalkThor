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
        // The systems WalkThor learned from ES-DE: families share their colour.
        "fds" to 0xFFC62828, "sgb" to 0xFF8BAC0F, "satellaview" to 0xFFA99BDB, "sufami" to 0xFFA99BDB, "snesna" to 0xFFA99BDB,
        "n64dd" to 0xFF1FA34A, "pokemini" to 0xFFF2C230, "gameandwatch" to 0xFFB9BEC6,
        "megadrivejp" to 0xFF2E6FD8, "megacdjp" to 0xFF4C86E0, "sega32xjp" to 0xFFD9A31E, "sega32xna" to 0xFFD9A31E,
        "saturnjp" to 0xFF5566C4, "sg1000" to 0xFFC62828, "mark3" to 0xFFC62828,
        "naomi" to 0xFFF0662A, "naomi2" to 0xFFF0662A, "naomigd" to 0xFFF0662A, "atomiswave" to 0xFFF0662A,
        "model2" to 0xFFFF4FA3, "model3" to 0xFFFF4FA3, "cps" to 0xFFFF4FA3, "cps1" to 0xFFFF4FA3, "cps2" to 0xFFFF4FA3, "cps3" to 0xFFFF4FA3,
        "stv" to 0xFFFF4FA3, "triforce" to 0xFF7A4FD6, "mame" to 0xFFFF4FA3, "fbneo" to 0xFFFF4FA3,
        "ps4" to 0xFF2A5BD7, "neogeocd" to 0xFFD4A017, "neogeocdjp" to 0xFFD4A017,
        "pcenginecd" to 0xFFF39800, "tgcd" to 0xFFF39800, "supergrafx" to 0xFFF39800, "pcfx" to 0xFFF39800,
        "atari5200" to 0xFFB5651D, "atari7800" to 0xFFB5651D, "atari800" to 0xFFB5651D, "atarixe" to 0xFFB5651D,
        "atarijaguar" to 0xFFC0392B, "atarijaguarcd" to 0xFFC0392B, "atarist" to 0xFF9B9B9B,
        "c64" to 0xFF6C5EB5, "vic20" to 0xFF6C5EB5, "plus4" to 0xFF6C5EB5,
        "amiga" to 0xFFE0702A, "amiga600" to 0xFFE0702A, "amiga1200" to 0xFFE0702A, "amigacd32" to 0xFFE0702A, "cdtv" to 0xFFE0702A,
        "msx" to 0xFF4A90D9, "msx1" to 0xFF4A90D9, "msx2" to 0xFF4A90D9, "msxturbor" to 0xFF4A90D9,
        "zxspectrum" to 0xFFE53935, "zx81" to 0xFFE53935, "amstradcpc" to 0xFF2E7D32,
        "colecovision" to 0xFF5D6D7E, "intellivision" to 0xFFB08D57, "vectrex" to 0xFF7A7F87, "3do" to 0xFFD4A017,
        "dos" to 0xFF8AA4BF, "windows3x" to 0xFF8AA4BF, "windows9x" to 0xFF8AA4BF, "ports" to 0xFF8AA4BF,
        "scummvm" to 0xFF2FA36B, "pico8" to 0xFFFF77A8, "openbor" to 0xFFB0413E,
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
        "fds" to "DISK SYSTEM", "sgb" to "SUPER GB", "satellaview" to "SATELLAVIEW", "sufami" to "SUFAMI", "snesna" to "SNES",
        "n64dd" to "64DD", "pokemini" to "POKÉMON MINI", "gameandwatch" to "GAME & WATCH",
        "megadrivejp" to "MEGA DRIVE", "megacdjp" to "MEGA-CD", "sega32xjp" to "32X", "sega32xna" to "32X", "saturnjp" to "SATURN",
        "sg1000" to "SG-1000", "mark3" to "MARK III", "naomi" to "NAOMI", "naomi2" to "NAOMI 2", "naomigd" to "NAOMI GD",
        "atomiswave" to "ATOMISWAVE", "model2" to "MODEL 2", "model3" to "MODEL 3", "cps" to "CPS", "cps1" to "CPS-1",
        "cps2" to "CPS-2", "cps3" to "CPS-3", "stv" to "ST-V", "triforce" to "TRIFORCE", "ps4" to "PS4",
        "neogeocd" to "NEO GEO CD", "neogeocdjp" to "NEO GEO CD", "pcenginecd" to "PC ENGINE CD", "tgcd" to "TG-CD",
        "supergrafx" to "SUPERGRAFX", "pcfx" to "PC-FX", "atari5200" to "ATARI 5200", "atari7800" to "ATARI 7800",
        "atari800" to "ATARI 800", "atarixe" to "ATARI XE", "atarijaguar" to "JAGUAR", "atarijaguarcd" to "JAGUAR CD",
        "atarist" to "ATARI ST", "c64" to "C64", "vic20" to "VIC-20", "plus4" to "PLUS/4", "amiga" to "AMIGA",
        "amiga600" to "AMIGA 600", "amiga1200" to "AMIGA 1200", "amigacd32" to "CD32", "cdtv" to "CDTV", "msx" to "MSX",
        "msx1" to "MSX", "msx2" to "MSX2", "msxturbor" to "MSX TURBO R", "zxspectrum" to "ZX SPECTRUM", "zx81" to "ZX81",
        "amstradcpc" to "CPC", "colecovision" to "COLECOVISION", "intellivision" to "INTELLIVISION", "vectrex" to "VECTREX",
        "3do" to "3DO", "dos" to "DOS", "windows3x" to "WINDOWS 3.X", "windows9x" to "WINDOWS 9X", "ports" to "PORTS",
        "scummvm" to "SCUMMVM", "pico8" to "PICO-8", "openbor" to "OPENBOR",
        com.nerfherder.walkthor.data.PinnedShortcuts.SYSTEM to "SHORTCUT",
    )

    /** Android's green, for the ring around apps. */
    val ANDROID = Color(0xFF3DDC84)

    fun color(system: String): Color = Color(COLORS[system] ?: 0xFF8A8378)
    fun label(system: String): String = LABELS[system]
        ?: com.nerfherder.walkthor.data.RomLibrary.system(system)?.name?.uppercase()?.takeIf { it.length <= 14 }
        ?: system.uppercase()
}
