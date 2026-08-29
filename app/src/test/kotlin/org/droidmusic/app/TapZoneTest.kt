package org.droidmusic.app

import org.droidmusic.app.input.PageAction
import org.droidmusic.app.ui.viewer.PageLayoutRules
import org.droidmusic.app.ui.viewer.PageMode
import org.droidmusic.app.ui.viewer.TapZoneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapZoneTest {

    private val zones = TapZoneConfig()
    private val width = 1200f
    private val height = 1800f

    /** Below the controls band, so these taps are unambiguously page turns. */
    private fun at(x: Float) = zones.actionAt(x, height * 0.8f, width, height)

    @Test
    fun `the left third goes back and the right two thirds go forward`() {
        assertEquals(PageAction.PREVIOUS_PAGE, at(1f))
        assertEquals(PageAction.PREVIOUS_PAGE, at(width / 3f - 1f))
        assertEquals(PageAction.NEXT_PAGE, at(width / 3f + 1f))
        assertEquals(PageAction.NEXT_PAGE, at(width / 2f))
        assertEquals(PageAction.NEXT_PAGE, at(width - 1f))
    }

    @Test
    fun `forward is twice the target that back is`() {
        val samples = (0 until 300).map { at(it * width / 300f) }
        val forward = samples.count { it == PageAction.NEXT_PAGE }
        val back = samples.count { it == PageAction.PREVIOUS_PAGE }
        assertEquals(2.0, forward.toDouble() / back, 0.05)
    }

    @Test
    fun `mirroring swaps the sides`() {
        val mirrored = zones.copy(mirrored = true)
        assertEquals(PageAction.NEXT_PAGE, mirrored.actionAt(1f, height * 0.8f, width, height))
        assertEquals(
            PageAction.PREVIOUS_PAGE,
            mirrored.actionAt(width - 1f, height * 0.8f, width, height),
        )
    }

    @Test
    fun `the top centre opens the controls instead of turning a page`() {
        assertEquals(PageAction.TOGGLE_CONTROLS, zones.actionAt(width / 2f, 4f, width, height))
        // Just outside the band horizontally is still a page turn.
        assertEquals(PageAction.NEXT_PAGE, zones.actionAt(width * 0.95f, 4f, width, height))
        // And below it vertically.
        assertEquals(
            PageAction.NEXT_PAGE,
            zones.actionAt(width / 2f, height * 0.5f, width, height),
        )
    }

    @Test
    fun `tapping does nothing when tap to turn is off, except the controls`() {
        val pedalOnly = zones.copy(tapToTurnEnabled = false)
        assertEquals(PageAction.NONE, pedalOnly.actionAt(width - 1f, height * 0.8f, width, height))
        assertEquals(
            PageAction.TOGGLE_CONTROLS,
            pedalOnly.actionAt(width / 2f, 4f, width, height),
        )
    }

    @Test
    fun `a zero sized viewport does not crash or turn a page`() {
        assertEquals(PageAction.NONE, zones.actionAt(0f, 0f, 0f, 0f))
    }
}

class PageLayoutTest {

    @Test
    fun `two pages only on a screen wide enough to read them`() {
        // A tablet in landscape.
        assertEquals(PageMode.SPREAD, PageLayoutRules.modeFor(1280, 800))
        // The same tablet held upright.
        assertEquals(PageMode.SINGLE, PageLayoutRules.modeFor(800, 1280))
        // A phone in landscape: wide, but not wide enough for two readable pages.
        assertEquals(PageMode.SINGLE, PageLayoutRules.modeFor(780, 360))
        // A phone upright.
        assertEquals(PageMode.SINGLE, PageLayoutRules.modeFor(400, 880))
    }

    @Test
    fun `the override wins over the screen`() {
        assertEquals(PageMode.SPREAD, PageLayoutRules.modeFor(400, 880, PageMode.SPREAD))
        assertEquals(PageMode.SINGLE, PageLayoutRules.modeFor(1280, 800, PageMode.SINGLE))
    }

    @Test
    fun `single mode steps one page at a time and clamps at both ends`() {
        assertEquals(1, PageLayoutRules.advance(0, 10, PageMode.SINGLE, forward = true))
        assertEquals(0, PageLayoutRules.advance(1, 10, PageMode.SINGLE, forward = false))
        assertEquals(0, PageLayoutRules.advance(0, 10, PageMode.SINGLE, forward = false))
        assertEquals(9, PageLayoutRules.advance(9, 10, PageMode.SINGLE, forward = true))
    }

    // Turning back and then forward again has to land where it started. If the
    // left page were allowed to become odd, the whole document would silently
    // re-pair and every page after it would face the wrong partner.
    @Test
    fun `spread mode keeps the left page even`() {
        var page = 0
        for (turn in 1..4) {
            page = PageLayoutRules.advance(page, 10, PageMode.SPREAD, forward = true)
            assertEquals("after $turn turns", 0, page % 2)
        }
        for (turn in 1..4) {
            page = PageLayoutRules.advance(page, 10, PageMode.SPREAD, forward = false)
            assertEquals("after $turn turns back", 0, page % 2)
        }
        assertEquals(0, page)
    }

    @Test
    fun `spread mode shows two facing pages and one at a ragged end`() {
        assertEquals(listOf(0, 1), PageLayoutRules.visiblePages(0, 10, PageMode.SPREAD))
        assertEquals(listOf(2, 3), PageLayoutRules.visiblePages(3, 10, PageMode.SPREAD))
        // An odd page count leaves the last page on its own rather than blank.
        assertEquals(listOf(4), PageLayoutRules.visiblePages(4, 5, PageMode.SPREAD))
        assertEquals(listOf(3), PageLayoutRules.visiblePages(3, 10, PageMode.SINGLE))
    }

    @Test
    fun `the end is where the last visible page is the last page`() {
        assertTrue(PageLayoutRules.isAtEnd(9, 10, PageMode.SINGLE))
        assertFalse(PageLayoutRules.isAtEnd(8, 10, PageMode.SINGLE))
        // In a spread, being on page 8 of 10 means 8 and 9 are both showing.
        assertTrue(PageLayoutRules.isAtEnd(8, 10, PageMode.SPREAD))
        assertFalse(PageLayoutRules.isAtEnd(6, 10, PageMode.SPREAD))
        assertTrue(PageLayoutRules.isAtEnd(0, 0, PageMode.SINGLE))
    }

    @Test
    fun `an empty document has no pages and does not crash`() {
        assertEquals(emptyList<Int>(), PageLayoutRules.visiblePages(0, 0, PageMode.SINGLE))
        assertEquals(0, PageLayoutRules.advance(0, 0, PageMode.SINGLE, forward = true))
    }
}
