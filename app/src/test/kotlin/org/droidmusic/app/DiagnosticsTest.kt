package org.droidmusic.app

import java.time.ZoneId
import org.droidmusic.app.diag.Area
import org.droidmusic.app.diag.Diagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsTest {

    @Before
    fun clear() {
        Diagnostics.clear()
    }

    @Test
    fun `lines come back in the order they happened`() {
        Diagnostics.log(Area.LEADER, "one", at = 1_000)
        Diagnostics.log(Area.FOLLOWER, "two", at = 2_000)

        assertEquals(listOf("one", "two"), Diagnostics.entries().map { it.message })
    }

    // The buffer is what makes this safe to leave switched on: it cannot grow,
    // and the newest lines are the ones somebody wants.
    @Test
    fun `the oldest lines fall off the end rather than the newest`() {
        repeat(Diagnostics.CAPACITY + 50) { Diagnostics.log(Area.SESSION, "line $it", at = it.toLong()) }

        val kept = Diagnostics.entries()
        assertEquals(Diagnostics.CAPACITY, kept.size)
        assertEquals("line 50", kept.first().message)
        assertEquals("line ${Diagnostics.CAPACITY + 49}", kept.last().message)
    }

    @Test
    fun `the rendered log carries the header, the warning and the lines`() {
        Diagnostics.log(Area.FOLLOWER, "connected to \"Anchor\"", at = 0)

        val text = Diagnostics.render(
            about = listOf("App" to "0.1.0", "Device" to "Jim's Pixel"),
            zone = ZoneId.of("UTC"),
        )

        assertTrue(text.startsWith("DroidMusic diagnostic log"))
        assertTrue(text.contains("App: 0.1.0"))
        assertTrue(text.contains("Device: Jim's Pixel"))
        assertTrue("says what is in it", text.contains("names your device"))
        assertTrue(text.contains("00:00:00.000  FOLLOWER connected to \"Anchor\""))
    }

    @Test
    fun `an empty log says so rather than rendering nothing`() {
        val text = Diagnostics.render(zone = ZoneId.of("UTC"))
        assertTrue(Diagnostics.isEmpty)
        assertTrue(text.contains("(nothing recorded)"))
    }

    @Test
    fun `a full log says that the oldest lines were dropped`() {
        repeat(Diagnostics.CAPACITY) { Diagnostics.log(Area.SESSION, "line $it", at = 0) }
        assertTrue(Diagnostics.render(zone = ZoneId.of("UTC")).contains("(oldest dropped)"))
    }

    @Test
    fun `clearing empties it`() {
        Diagnostics.log(Area.CHART, "something", at = 0)
        assertFalse(Diagnostics.isEmpty)
        Diagnostics.clear()
        assertTrue(Diagnostics.isEmpty)
    }

    // Device ids are UUIDs. Printed whole they are 36 characters of noise on
    // every line, and six is enough to tell two phones apart in one log.
    @Test
    fun `device ids are shortened for reading`() {
        assertEquals("a1b2c3", Diagnostics.short("a1b2c3d4-e5f6-0000-1111-222222222222"))
        assertEquals("short", Diagnostics.short("short"))
        assertEquals("?", Diagnostics.short(null))
        assertEquals("?", Diagnostics.short("  "))
    }
}
