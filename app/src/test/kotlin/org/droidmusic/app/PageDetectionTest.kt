package org.droidmusic.app

import org.droidmusic.app.capture.Otsu
import org.droidmusic.app.capture.PageDetector
import org.droidmusic.app.capture.PageQuad
import org.droidmusic.app.capture.QuadCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding the page in a photograph of one.
 *
 * The cases worth having are the ones where a scanner quietly does the wrong
 * thing: a lamp in the corner of the shot, a photograph of nothing, a page too
 * far away to be a page. Each is a few lines of pixels here; each is a page that
 * turns up cropped through the middle at a rehearsal otherwise.
 */
class PageDetectionTest {

    private val paper = 0xFFFFFFFF.toInt()
    private val desk = 0xFF202020.toInt()
    private val dim = 0xFF808080.toInt()

    private val size = 200

    private fun frame(fill: Int) = IntArray(size * size) { fill }

    private fun IntArray.rect(x0: Int, y0: Int, x1: Int, y1: Int, colour: Int) {
        for (y in y0..y1) for (x in x0..x1) this[y * size + x] = colour
    }

    /** Scanline fill, for the one case that is not axis aligned. */
    private fun IntArray.quad(points: List<Pair<Int, Int>>, colour: Int) {
        for (y in 0 until size) {
            val crossings = mutableListOf<Double>()
            for (i in points.indices) {
                val (x1, y1) = points[i]
                val (x2, y2) = points[(i + 1) % points.size]
                if ((y1 <= y && y < y2) || (y2 <= y && y < y1)) {
                    crossings += x1 + (y - y1).toDouble() * (x2 - x1) / (y2 - y1)
                }
            }
            if (crossings.size < 2) continue
            val from = Math.round(crossings.min()).toInt()
            val to = Math.round(crossings.max()).toInt()
            for (x in from..to) if (x in 0 until size) this[y * size + x] = colour
        }
    }

    private fun assertCorners(
        quad: PageQuad?,
        topLeft: Pair<Int, Int>,
        topRight: Pair<Int, Int>,
        bottomRight: Pair<Int, Int>,
        bottomLeft: Pair<Int, Int>,
        tolerance: Float = 0f,
    ) {
        assertNotNull(quad)
        quad!!
        fun check(name: String, corner: QuadCorner, expected: Pair<Int, Int>) {
            assertEquals("$name x", expected.first.toFloat(), corner.x, tolerance)
            assertEquals("$name y", expected.second.toFloat(), corner.y, tolerance)
        }
        check("top left", quad.topLeft, topLeft)
        check("top right", quad.topRight, topRight)
        check("bottom right", quad.bottomRight, bottomRight)
        check("bottom left", quad.bottomLeft, bottomLeft)
    }

    @Test
    fun `a page squared up on a dark surface is found`() {
        val pixels = frame(desk)
        pixels.rect(40, 30, 159, 168, paper)

        assertCorners(
            PageDetector.detect(pixels, size, size),
            topLeft = 40 to 30,
            topRight = 159 to 30,
            bottomRight = 159 to 168,
            bottomLeft = 40 to 168,
        )
    }

    @Test
    fun `a page photographed at an angle keeps its four corners`() {
        // The whole reason this returns a quadrilateral rather than a rectangle
        // and an angle: a page shot from slightly above is a trapezium, and
        // de-rotating a trapezium leaves a trapezium.
        val pixels = frame(desk)
        pixels.quad(listOf(50 to 25, 170 to 45, 150 to 175, 30 to 150), paper)

        assertCorners(
            PageDetector.detect(pixels, size, size),
            topLeft = 50 to 25,
            topRight = 170 to 45,
            bottomRight = 150 to 174,
            bottomLeft = 30 to 150,
            tolerance = 2f,
        )
    }

    @Test
    fun `a lamp in the corner of the shot is not mistaken for the page`() {
        val pixels = frame(desk)
        pixels.rect(40, 30, 159, 168, paper)
        pixels.rect(0, 0, 15, 15, paper)

        assertCorners(
            PageDetector.detect(pixels, size, size),
            topLeft = 40 to 30,
            topRight = 159 to 30,
            bottomRight = 159 to 168,
            bottomLeft = 40 to 168,
        )
    }

    @Test
    fun `the page is whatever the camera was pointed at, not whatever is brightest`() {
        // Dark music on a white table. Always taking the brighter class would
        // find the table and crop the page out of its own photograph.
        val pixels = frame(paper)
        pixels.rect(40, 30, 159, 168, desk)

        assertCorners(
            PageDetector.detect(pixels, size, size),
            topLeft = 40 to 30,
            topRight = 159 to 30,
            bottomRight = 159 to 168,
            bottomLeft = 40 to 168,
        )
    }

    @Test
    fun `a dim photograph is split where the picture says, not at a fixed value`() {
        // Paper at mid grey under a pub light. A fixed threshold picked for a
        // well-lit page finds nothing here.
        val pixels = frame(desk)
        pixels.rect(40, 30, 159, 168, dim)

        assertCorners(
            PageDetector.detect(pixels, size, size),
            topLeft = 40 to 30,
            topRight = 159 to 30,
            bottomRight = 159 to 168,
            bottomLeft = 40 to 168,
        )
    }

    @Test
    fun `the larger of two pages in shot is the one taken`() {
        val pixels = frame(desk)
        pixels.rect(20, 40, 90, 159, paper)
        pixels.rect(110, 60, 140, 120, paper)

        assertCorners(
            PageDetector.detect(pixels, size, size),
            topLeft = 20 to 40,
            topRight = 90 to 40,
            bottomRight = 90 to 159,
            bottomLeft = 20 to 159,
        )
    }

    @Test
    fun `a page too small to be one is refused`() {
        val pixels = frame(desk)
        pixels.rect(90, 90, 115, 115, paper)
        assertNull(PageDetector.detect(pixels, size, size))
    }

    @Test
    fun `a photograph of nothing is refused`() {
        assertNull(PageDetector.detect(frame(desk), size, size))
        assertNull(PageDetector.detect(frame(paper), size, size))
    }

    @Test
    fun `a shape that is not a page is refused`() {
        // An L of bright surfaces - a desk edge and a wall, say. Its corners
        // enclose a great deal of nothing, which is exactly the shape that
        // produces a confident, wrong crop.
        val pixels = frame(desk)
        pixels.rect(20, 20, 185, 49, paper)
        pixels.rect(20, 20, 49, 184, paper)
        assertNull(PageDetector.detect(pixels, size, size))
    }

    @Test
    fun `nonsense in gives nothing out rather than a crash`() {
        assertNull(PageDetector.detect(IntArray(0), 0, 0))
        assertNull(PageDetector.detect(IntArray(10), 200, 200))
        assertNull(PageDetector.detect(frame(desk), 1, 1))
    }

    @Test
    fun `Otsu finds the valley between two populations`() {
        val histogram = IntArray(256)
        for (value in 20..40) histogram[value] = 500
        for (value in 200..230) histogram[value] = 500

        val threshold = Otsu.threshold(histogram)
        assertTrue("threshold $threshold should sit between the two humps", threshold in 40..200)
    }

    @Test
    fun `Otsu copes with an empty histogram`() {
        assertEquals(128, Otsu.threshold(IntArray(256)))
    }

    @Test
    fun `the output keeps the longer of each pair of opposite sides`() {
        // The near edge of a page shot at an angle is longer than the far edge.
        // Scaling to the shorter one throws away detail the camera captured.
        val quad = PageQuad(
            topLeft = QuadCorner(0f, 0f),
            topRight = QuadCorner(100f, 0f),
            bottomRight = QuadCorner(120f, 200f),
            bottomLeft = QuadCorner(-20f, 200f),
        )
        val (width, height) = quad.outputSize(maxEdge = 10_000)
        assertEquals(140, width)
        assertEquals(200, height)
    }

    @Test
    fun `the output is capped so a page never becomes an enormous bitmap`() {
        val quad = PageQuad(
            topLeft = QuadCorner(0f, 0f),
            topRight = QuadCorner(4000f, 0f),
            bottomRight = QuadCorner(4000f, 8000f),
            bottomLeft = QuadCorner(0f, 8000f),
        )
        val (width, height) = quad.outputSize(maxEdge = 2000)
        assertEquals(1000, width)
        assertEquals(2000, height)
    }

    @Test
    fun `a quad reports its own area`() {
        val quad = PageQuad(
            topLeft = QuadCorner(0f, 0f),
            topRight = QuadCorner(10f, 0f),
            bottomRight = QuadCorner(10f, 20f),
            bottomLeft = QuadCorner(0f, 20f),
        )
        assertEquals(200f, quad.area, 0.01f)
        assertEquals(10f, quad.shortestSide, 0.01f)
    }

    @Test
    fun `scaling moves a quad from the analysed image to the full photograph`() {
        val quad = PageQuad(
            topLeft = QuadCorner(10f, 20f),
            topRight = QuadCorner(30f, 20f),
            bottomRight = QuadCorner(30f, 60f),
            bottomLeft = QuadCorner(10f, 60f),
        ).scaled(4f, 4f)

        assertEquals(40f, quad.topLeft.x, 0.01f)
        assertEquals(80f, quad.topLeft.y, 0.01f)
        assertEquals(120f, quad.bottomRight.x, 0.01f)
        assertEquals(240f, quad.bottomRight.y, 0.01f)
    }
}
