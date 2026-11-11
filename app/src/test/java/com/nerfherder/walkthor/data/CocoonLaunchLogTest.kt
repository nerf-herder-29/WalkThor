package com.nerfherder.walkthor.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CocoonLaunchLogTest {
    @Test
    fun readsThePackageFromThePlayerLine() {
        val log = """
            [2026-10-14 08:59:19.145] [----] ══════ Launch: Wind Waker ══════
            [2026-10-14 08:59:19.146] [INFO] Game: Wind Waker | File: Wind Waker (USA).rvz
            [2026-10-14 08:59:19.148] [INFO] Player: Dolphin Emulator (org.dolphinemu.dolphinemu/.ui.main.MainActivity)
        """.trimIndent()
        val g = CocoonLaunchLog.parseLast(log)!!
        assertEquals("Wind Waker", g.title)
        assertEquals("org.dolphinemu.dolphinemu", g.playerPkg)
    }

    @Test
    fun readsThePackageFromTheIntentLine() {
        val log = """
            [2026-11-11 07:22:16.500] [----] ══════ Launch: Call of Duty - Modern Warfare 2 ══════
            [2026-11-11 07:22:16.622] [INFO] {file.path} → /storage/68B3-2B35/ROMS/xbox360/Call of Duty - Modern Warfare 2 (USA, Europe).iso
            [2026-11-11 07:22:16.642] [INFO] Intent: action=android.intent.action.VIEW, data=null, component=ComponentInfo{xendroid.compose/xendroid.compose.EmulatorHostActivity}
            [2026-11-11 07:22:16.667] [INFO] Launch SUCCESS — taskId=null
        """.trimIndent()
        assertEquals("xendroid.compose", CocoonLaunchLog.parseLast(log)!!.playerPkg)
    }
}
