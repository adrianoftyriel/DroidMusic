package org.droidmusic.app

import org.droidmusic.app.ui.common.ReorderGeometry
import org.droidmusic.app.ui.common.ReorderGeometry.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The arithmetic behind dragging a song up and down a set list.
 *
 * A row that lands one place away from where the finger put it is the kind of
 * mistake nobody catches: the list still looks like a list, and the running
 * order is only wrong once somebody is on stage playing the wrong song.
 */
class ReorderGeometryTest {

    /** Five rows of even height, as a set list of five songs would lay out. */
    private val rows = (0 until 5).map { Row(index = it, offset = it * 100, size = 100) }

    private fun targetOf(dragging: Int, floatingStart: Float) =
        ReorderGeometry.targetIndex(rows, dragging, floatingStart, floatingSize = 100)

    @Test
    fun `a row that has not been moved stays where it is`() {
        assertNull(targetOf(dragging = 2, floatingStart = 200f))
    }

    @Test
    fun `a row only takes a place once it covers more than half of it`() {
        // Halfway down over the row below: its centre is still in its own slot.
        assertNull(targetOf(dragging = 2, floatingStart = 249f))
        assertEquals(3, targetOf(dragging = 2, floatingStart = 251f))
    }

    @Test
    fun `dragging upwards is the same rule in reverse`() {
        assertNull(targetOf(dragging = 2, floatingStart = 151f))
        assertEquals(1, targetOf(dragging = 2, floatingStart = 149f))
    }

    @Test
    fun `a fast drag skips straight to the row it landed on`() {
        assertEquals(0, targetOf(dragging = 4, floatingStart = 10f))
    }

    @Test
    fun `a drag past either end of the list has nowhere to go`() {
        assertNull(targetOf(dragging = 0, floatingStart = -80f))
        assertNull(targetOf(dragging = 4, floatingStart = 480f))
    }

    @Test
    fun `rows of different heights are measured as they were laid out`() {
        // A song with a note under it is taller than one without, so half of the
        // row below is not the same distance every time.
        val uneven = listOf(
            Row(index = 0, offset = 0, size = 60),
            Row(index = 1, offset = 60, size = 200),
            Row(index = 2, offset = 260, size = 60),
        )
        // The short row's centre sits 30 below its own top edge, so it keeps its
        // place until that centre crosses into the tall row beneath it - which
        // is 30 of travel, not the 100 an evenly sized list would need.
        assertNull(ReorderGeometry.targetIndex(uneven, 0, floatingStart = 20f, floatingSize = 60))
        assertEquals(1, ReorderGeometry.targetIndex(uneven, 0, floatingStart = 40f, floatingSize = 60))
    }

    @Test
    fun `a row held inside the list scrolls nothing`() {
        assertEquals(
            0f,
            ReorderGeometry.autoScroll(200f, 300f, 0f, 800f, maxStep = 18f),
            0f,
        )
    }

    @Test
    fun `a row held past the bottom pulls the list up after it`() {
        assertEquals(
            10f,
            ReorderGeometry.autoScroll(710f, 810f, 0f, 800f, maxStep = 18f),
            0f,
        )
        // However far past the edge the finger is, the list moves at a speed
        // somebody can still read.
        assertEquals(
            18f,
            ReorderGeometry.autoScroll(1200f, 1300f, 0f, 800f, maxStep = 18f),
            0f,
        )
    }

    @Test
    fun `a row held above the top scrolls the other way`() {
        assertEquals(
            -12f,
            ReorderGeometry.autoScroll(-12f, 88f, 0f, 800f, maxStep = 18f),
            0f,
        )
        assertEquals(
            -18f,
            ReorderGeometry.autoScroll(-400f, -300f, 0f, 800f, maxStep = 18f),
            0f,
        )
    }
}
