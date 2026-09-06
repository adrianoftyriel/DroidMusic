package org.droidmusic.app

import org.droidmusic.app.capture.PageEnhancement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brightness and contrast for a photographed page.
 *
 * The matrix is what the sliders and the saved page are both drawn through, so
 * these check the two properties that decide whether a page comes out readable:
 * that asking for nothing changes nothing, and that contrast pivots about mid
 * grey rather than about black - the difference between a harder page and a
 * brighter one wearing contrast's name.
 */
class PageEnhancementTest {

    /** What the matrix does to one channel value, ignoring clipping. */
    private fun applied(enhancement: PageEnhancement, value: Float): Float {
        val matrix = enhancement.matrix()
        return matrix[0] * value + matrix[4]
    }

    @Test
    fun `no adjustment is the identity matrix`() {
        assertTrue(PageEnhancement.NONE.isNeutral)

        val expected = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        assertEquals(expected.toList(), PageEnhancement.NONE.matrix().toList())
    }

    @Test
    fun `any adjustment at all is not neutral`() {
        assertFalse(PageEnhancement(brightness = 0.1f).isNeutral)
        assertFalse(PageEnhancement(contrast = -0.1f).isNeutral)
    }

    @Test
    fun `brightness shifts every channel by the same amount`() {
        val matrix = PageEnhancement(brightness = 0.5f).matrix()
        val offset = 0.5f * PageEnhancement.BRIGHTNESS_RANGE

        assertEquals(offset, matrix[4], 0.001f)
        assertEquals(offset, matrix[9], 0.001f)
        assertEquals(offset, matrix[14], 0.001f)
        // Scale untouched: this is a shift, not a stretch.
        assertEquals(1f, matrix[0], 0.001f)
    }

    @Test
    fun `contrast pivots about mid grey`() {
        val harder = PageEnhancement(contrast = 0.5f)
        val flatter = PageEnhancement(contrast = -0.5f)

        assertEquals(
            PageEnhancement.MID_GREY,
            applied(harder, PageEnhancement.MID_GREY),
            0.001f,
        )
        assertEquals(
            PageEnhancement.MID_GREY,
            applied(flatter, PageEnhancement.MID_GREY),
            0.001f,
        )
    }

    @Test
    fun `more contrast drives paper up and ink down`() {
        val harder = PageEnhancement(contrast = 0.5f)
        val paper = applied(harder, 200f)
        val ink = applied(harder, 60f)

        assertTrue("paper should lighten, was $paper", paper > 200f)
        assertTrue("ink should darken, was $ink", ink < 60f)
    }

    @Test
    fun `less contrast pulls both ends towards mid grey`() {
        val flatter = PageEnhancement(contrast = -0.5f)
        assertTrue(applied(flatter, 200f) < 200f)
        assertTrue(applied(flatter, 60f) > 60f)
    }

    @Test
    fun `alpha is never touched`() {
        val matrix = PageEnhancement(brightness = 1f, contrast = 1f).matrix()
        assertEquals(1f, matrix[18], 0.001f)
        assertEquals(0f, matrix[19], 0.001f)
        // ...and nothing else leaks into the alpha column either.
        assertEquals(0f, matrix[3], 0.001f)
        assertEquals(0f, matrix[8], 0.001f)
        assertEquals(0f, matrix[13], 0.001f)
    }

    @Test
    fun `a slider dragged past its range is pulled back into it`() {
        val wild = PageEnhancement(brightness = 4f, contrast = -9f)

        assertEquals(1f, wild.coerced().brightness, 0.001f)
        assertEquals(-1f, wild.coerced().contrast, 0.001f)
        // The matrix uses the coerced values, so contrast cannot go negative and
        // hand back a page with its blacks and whites swapped.
        assertEquals(0f, wild.matrix()[0], 0.001f)
    }
}
