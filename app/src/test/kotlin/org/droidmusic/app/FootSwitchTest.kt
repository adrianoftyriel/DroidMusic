package org.droidmusic.app

import android.view.KeyEvent
import org.droidmusic.app.input.FootSwitchMap
import org.droidmusic.app.input.FootSwitchReader
import org.droidmusic.app.input.PageAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FootSwitchMapTest {

    private val map = FootSwitchMap()

    @Test
    fun `the pedals people own work out of the box`() {
        for (code in listOf(
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
        )) {
            assertEquals(PageAction.NEXT_PAGE, map.actionFor(code))
        }
        for (code in listOf(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT)) {
            assertEquals(PageAction.PREVIOUS_PAGE, map.actionFor(code))
        }
    }

    // Stealing the volume rocker from a player running a backing track would be
    // a bad trade for supporting the minority of pedals that send volume keys.
    @Test
    fun `volume keys are left alone unless asked for`() {
        assertEquals(PageAction.NONE, map.actionFor(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(PageAction.NONE, map.actionFor(KeyEvent.KEYCODE_VOLUME_DOWN))

        val volumePedal = map.copy(allowVolumeKeys = true)
        assertEquals(PageAction.NEXT_PAGE, volumePedal.actionFor(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(PageAction.PREVIOUS_PAGE, volumePedal.actionFor(KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun `unmapped keys mean nothing`() {
        assertEquals(PageAction.NONE, map.actionFor(KeyEvent.KEYCODE_Q))
        assertEquals(PageAction.NONE, map.actionFor(KeyEvent.KEYCODE_BACK))
    }

    // Learning a key that is already bound elsewhere has to move it, not
    // duplicate it - otherwise a pedal ends up doing two things at once.
    @Test
    fun `learning a key unbinds it from wherever it was`() {
        val learned = map.bind(KeyEvent.KEYCODE_SPACE, PageAction.PREVIOUS_PAGE)
        assertEquals(PageAction.PREVIOUS_PAGE, learned.actionFor(KeyEvent.KEYCODE_SPACE))
        assertTrue(KeyEvent.KEYCODE_SPACE !in learned.next)

        val moved = learned.bind(KeyEvent.KEYCODE_SPACE, PageAction.NEXT_SONG)
        assertEquals(PageAction.NEXT_SONG, moved.actionFor(KeyEvent.KEYCODE_SPACE))
        assertTrue(KeyEvent.KEYCODE_SPACE !in moved.previous)
    }

    @Test
    fun `an unrecognised pedal key can be learned from scratch`() {
        val exotic = 0x2000
        assertEquals(PageAction.NONE, map.actionFor(exotic))
        assertEquals(
            PageAction.NEXT_PAGE,
            map.bind(exotic, PageAction.NEXT_PAGE).actionFor(exotic),
        )
    }
}

class FootSwitchReaderTest {

    private fun reader() = FootSwitchReader(FootSwitchMap())

    @Test
    fun `a press turns a page`() {
        assertEquals(
            PageAction.NEXT_PAGE,
            reader().onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, repeatCount = 0, eventTimeMs = 0),
        )
    }

    // The worst thing a page turner can do: thirty pages because somebody left
    // their foot on the pedal.
    @Test
    fun `auto repeat is dropped`() {
        val reader = reader()
        assertEquals(
            PageAction.NEXT_PAGE,
            reader.onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, 0, 0),
        )
        for (repeat in 1..30) {
            assertEquals(
                PageAction.NONE,
                reader.onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, repeat, repeat * 50L),
            )
        }
    }

    @Test
    fun `a bouncing switch only turns one page`() {
        val reader = reader()
        assertEquals(PageAction.NEXT_PAGE, reader.onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, 0, 1000))
        // A contact bounce, milliseconds later.
        assertEquals(PageAction.NONE, reader.onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, 0, 1008))
        assertEquals(PageAction.NONE, reader.onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, 0, 1100))
        // A deliberate second press, well after the guard window.
        assertEquals(PageAction.NEXT_PAGE, reader.onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, 0, 1400))
    }

    @Test
    fun `a fast two footed turn is not debounced away`() {
        val reader = reader()
        assertEquals(PageAction.NEXT_PAGE, reader.onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, 0, 0))
        // A different switch, immediately: the guard is per key, not global, so
        // going forward then straight back still works.
        assertEquals(PageAction.PREVIOUS_PAGE, reader.onKeyDown(KeyEvent.KEYCODE_PAGE_UP, 0, 10))
    }

    // Null and NONE mean different things and the activity depends on it: null
    // hands the key back to the system, NONE swallows it.
    @Test
    fun `an unmapped key is handed back rather than swallowed`() {
        assertNull(reader().onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, 0, 0))
        assertNull(reader().onKeyDown(KeyEvent.KEYCODE_BACK, 0, 0))
        assertEquals(
            PageAction.NONE,
            reader().onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, repeatCount = 3, eventTimeMs = 0),
        )
    }

    @Test
    fun `a changed mapping takes effect immediately`() {
        val reader = reader()
        assertNull(reader.onKeyDown(KeyEvent.KEYCODE_F1, 0, 0))
        reader.updateMap(FootSwitchMap().bind(KeyEvent.KEYCODE_F1, PageAction.NEXT_PAGE))
        assertEquals(PageAction.NEXT_PAGE, reader.onKeyDown(KeyEvent.KEYCODE_F1, 0, 500))
    }
}
