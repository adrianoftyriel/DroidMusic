package org.droidmusic.app

import org.droidmusic.app.ui.viewer.ContentRect
import org.droidmusic.app.ui.viewer.InkBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding the music on a page of mostly paper.
 *
 * Written against pixel arrays rather than real scans, because the cases that
 * decide whether this works are the awkward ones - a speck of dust in the
 * margin, a page printed light on dark, a page with no margin to trim - and each
 * of those is three lines here against a trip to a rehearsal to find out.
 */
class PageZoomTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** A warm off-white, which is what a scan of real paper actually looks like. */
    private val paper = 0xFFEDE8DE.toInt()

    private fun page(width: Int, height: Int, fill: Int) = IntArray(width * height) { fill }

    private fun IntArray.ink(width: Int, x0: Int, y0: Int, x1: Int, y1: Int, colour: Int) {
        for (y in y0..y1) for (x in x0..x1) this[y * width + x] = colour
    }

    @Test
    fun `a blank page has no content`() {
        assertNull(InkBounds.of(page(100, 100, white), 100, 100))
    }

    @Test
    fun `content in the middle is found, with a little room left around it`() {
        val pixels = page(100, 100, white)
        pixels.ink(100, x0 = 20, y0 = 30, x1 = 79, y1 = 69, colour = black)

        val rect = InkBounds.of(pixels, 100, 100)
        assertNotNull(rect)
        rect!!

        // Ink runs 0.20..0.80 across and 0.30..0.70 down. Each edge is then
        // opened by a further 1.2%, so the outermost notehead is not pressed
        // flat against the edge of the screen.
        assertEquals(0.188f, rect.left, 0.001f)
        assertEquals(0.812f, rect.right, 0.001f)
        assertEquals(0.288f, rect.top, 0.001f)
        assertEquals(0.712f, rect.bottom, 0.001f)
    }

    @Test
    fun `a speck of dust in the margin does not defeat the crop`() {
        // This failure is total rather than partial: one dark pixel out at the
        // edge pushes the box back to the whole page, and the zoom then silently
        // does nothing at all, on every page of the scan.
        val pixels = page(100, 100, white)
        pixels.ink(100, x0 = 40, y0 = 40, x1 = 59, y1 = 59, colour = black)
        pixels.ink(100, x0 = 3, y0 = 3, x1 = 3, y1 = 3, colour = black)
        pixels.ink(100, x0 = 95, y0 = 96, x1 = 96, y1 = 97, colour = black)

        val rect = InkBounds.of(pixels, 100, 100)!!
        assertEquals(0.388f, rect.left, 0.001f)
        assertEquals(0.612f, rect.right, 0.001f)
        assertEquals(0.388f, rect.top, 0.001f)
        assertEquals(0.612f, rect.bottom, 0.001f)
    }

    @Test
    fun `a page printed light on dark is measured the same way`() {
        // The background is whatever the page is mostly made of, not white.
        val pixels = page(100, 100, black)
        pixels.ink(100, x0 = 25, y0 = 25, x1 = 74, y1 = 74, colour = white)

        val rect = InkBounds.of(pixels, 100, 100)!!
        assertEquals(0.238f, rect.left, 0.001f)
        assertEquals(0.762f, rect.right, 0.001f)
    }

    @Test
    fun `a scan with a warm cast is still mostly paper`() {
        // "Crop the white" would find nothing here. Measuring the background
        // rather than assuming it is what makes a real scan work.
        val pixels = page(100, 100, paper)
        pixels.ink(100, x0 = 20, y0 = 20, x1 = 69, y1 = 69, colour = black)

        val rect = InkBounds.of(pixels, 100, 100)!!
        assertEquals(0.188f, rect.left, 0.001f)
        assertEquals(0.712f, rect.right, 0.001f)
    }

    @Test
    fun `content that runs to the edge is not cropped outside the page`() {
        val pixels = page(100, 100, white)
        pixels.ink(100, x0 = 0, y0 = 0, x1 = 99, y1 = 39, colour = black)

        val rect = InkBounds.of(pixels, 100, 100)!!
        assertEquals(0f, rect.left, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(1f, rect.right, 0.0001f)
        assertEquals(0.412f, rect.bottom, 0.001f)
    }

    @Test
    fun `a page already printed to its edges is not worth zooming`() {
        assertFalse(ContentRect.FULL.trimsEnoughToZoom())

        // A printed border a hair inside the page edge: there is nothing to
        // gain, and cropping it would shift the music by a pixel or two, which
        // on a stand reads as the app twitching rather than as a feature.
        val pixels = page(100, 100, white)
        pixels.ink(100, x0 = 1, y0 = 1, x1 = 98, y1 = 1, colour = black)
        pixels.ink(100, x0 = 1, y0 = 98, x1 = 98, y1 = 98, colour = black)
        pixels.ink(100, x0 = 1, y0 = 1, x1 = 1, y1 = 98, colour = black)
        pixels.ink(100, x0 = 98, y0 = 1, x1 = 98, y1 = 98, colour = black)

        assertFalse(InkBounds.of(pixels, 100, 100)!!.trimsEnoughToZoom())
    }

    @Test
    fun `a page with real margins is worth zooming`() {
        val pixels = page(100, 100, white)
        pixels.ink(100, x0 = 20, y0 = 20, x1 = 79, y1 = 79, colour = black)
        assertTrue(InkBounds.of(pixels, 100, 100)!!.trimsEnoughToZoom())
    }

    @Test
    fun `trimming only one dimension is still worth zooming`() {
        // A wide system on a tall page: nothing to gain across, plenty down.
        val pixels = page(100, 100, white)
        pixels.ink(100, x0 = 0, y0 = 30, x1 = 99, y1 = 69, colour = black)

        val rect = InkBounds.of(pixels, 100, 100)!!
        assertEquals(1f, rect.width, 0.0001f)
        assertEquals(0.424f, rect.height, 0.001f)
        assertTrue(rect.trimsEnoughToZoom())
    }

    @Test
    fun `nonsense in gives nothing out rather than a crash`() {
        assertNull(InkBounds.of(IntArray(0), 0, 0))
        assertNull(InkBounds.of(IntArray(10), 100, 100))
        assertNull(InkBounds.of(page(10, 10, white), -1, 10))
    }

    @Test
    fun `the rectangle reports its own size`() {
        val rect = ContentRect(0.25f, 0.1f, 0.75f, 0.9f)
        assertEquals(0.5f, rect.width, 0.0001f)
        assertEquals(0.8f, rect.height, 0.0001f)
    }
}
