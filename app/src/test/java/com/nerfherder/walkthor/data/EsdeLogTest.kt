package com.nerfherder.walkthor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class EsdeLogTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 27, 18, 0, 0) }.timeInMillis

    @Test
    fun readsTheNewestGameStart() {
        val log = """
            Oct 27 17:40:02 Debug:  Scripting::fireEvent(): game-select "/storage/emulated/0/ROMs/gba/Other.gba" "Other" "gba" "Nintendo Game Boy Advance"
            Oct 27 17:41:10 Debug:  Scripting::fireEvent(): game-start "/storage/emulated/0/ROMs/gc/Lanterns of Emberfall (USA).rvz" "Lanterns of Emberfall" "gc" "Nintendo GameCube"
            Oct 27 17:41:11 Info:   Launching game...
        """.trimIndent()
        val g = EsdeLog.parseLast(log, now)!!
        assertEquals("Lanterns of Emberfall", g.title)
        assertEquals("Lanterns of Emberfall (USA).rvz", g.file)
        assertEquals("Nintendo GameCube", g.player)
        assertFalse(g.ended)
        val cal = Calendar.getInstance().apply { timeInMillis = g.at }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(17, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun gameEndMarksItEnded() {
        val log = """
            Oct 27 17:41:10 Debug:  Scripting::fireEvent(): game-start "/r/gc/A.rvz" "A" "gc" "Nintendo GameCube"
            Oct 27 17:55:00 Debug:  Scripting::fireEvent(): game-end "/r/gc/A.rvz" "A" "gc" "Nintendo GameCube"
        """.trimIndent()
        assertTrue(EsdeLog.parseLast(log, now)!!.ended)
    }

    @Test
    fun nothingWithoutEvents() {
        assertNull(EsdeLog.parseLast("Oct 27 17:00:00 Info: ES-DE started", now))
    }

    @Test
    fun lastYearWhenTheDateIsAhead() {
        val jan = Calendar.getInstance().apply { set(2027, Calendar.JANUARY, 2, 9, 0, 0) }.timeInMillis
        val g = EsdeLog.parseLast("""Dec 31 23:10:00 Debug:  Scripting::fireEvent(): game-start "/r/a.iso" "A" "ps2" "Sony PlayStation 2"""", jan)!!
        assertEquals(2026, Calendar.getInstance().apply { timeInMillis = g.at }.get(Calendar.YEAR))
    }
}
