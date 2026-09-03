package org.droidmusic.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wrapping a chord chart to the width of a screen.
 *
 * The failure this file exists to prevent is not an ugly line break. It is a
 * chord that ends up over the wrong word after a wrap, because that is wrong in
 * a way the player will believe and play. So most of what is tested here is one
 * property: **every chord is still attached to the syllable it was attached to
 * before.**
 */
class ChartWrapTest {

    /**
     * Which word each chord sits over, read off the two rows the way a player
     * reads them: by column.
     */
    private fun attachments(chords: String, words: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var index = 0
        while (index < chords.length) {
            if (chords[index] == ' ') {
                index++
                continue
            }
            val end = chords.indexOf(' ', index).let { if (it < 0) chords.length else it }
            out += chords.substring(index, end) to wordAt(words, index)
            index = end
        }
        return out
    }

    private fun wordAt(words: String, column: Int): String {
        if (column >= words.length || words[column] == ' ') return ""
        var end = column
        while (end < words.length && words[end] != ' ') end++
        return words.substring(column, end)
    }

    private fun ChartRow.attachments() = attachments(chordText, text)

    private fun words(rows: List<ChartRow>) =
        rows.joinToString(" ") { it.text }.split(" ").filter { it.isNotEmpty() }

    @Test
    fun `a chart that already fits is left exactly as it was`() {
        val rows = ChartLayout.rows(SongParser.parse("{key: C}\n[C]Amazing [F]grace"))
        assertSame(rows, ChartLayout.wrap(rows, 40))
    }

    @Test
    fun `every chord keeps its syllable across a wrap`() {
        val song = SongParser.parse(
            "{key: C}\n[C]Amazing [F]grace, how [G]sweet the [Am]sound that " +
                "[F]saved a [C]wretch like [G7]me",
        )
        val row = ChartLayout.rows(song).first { it.kind == RowKind.CHORD_AND_LYRIC }
        val before = row.attachments()

        for (columns in 12..row.width) {
            val wrapped = ChartLayout.wrapRow(row, columns)
            assertEquals(
                "chords moved when wrapped to $columns columns",
                before,
                wrapped.flatMap { it.attachments() },
            )
        }
    }

    @Test
    fun `no wrapped row is wider than the screen`() {
        val song = SongParser.parse(
            buildString {
                appendLine("{title: Wide}")
                appendLine("[Cmaj7]a line of words that runs [F#m7b5]well past the edge of a phone [G]screen")
                appendLine("a plain lyric line with no chords on it at all, which is also far too long")
                appendLine("{comment: a comment long enough that it too has to be broken somewhere}")
            },
        )
        val rows = ChartLayout.rows(song)
        for (columns in 12..30) {
            for (row in ChartLayout.wrap(rows, columns)) {
                assertTrue(
                    "row of ${row.width} columns at a $columns column limit: '${row.text}'",
                    row.width <= columns,
                )
            }
        }
    }

    @Test
    fun `not one word is lost or duplicated`() {
        val song = SongParser.parse(
            "[C]the quick brown fox [G]jumps over the lazy dog and keeps [Am]running",
        )
        val rows = ChartLayout.rows(song)
        val expected = words(rows)
        for (columns in 12..60) {
            assertEquals("at $columns columns", expected, words(ChartLayout.wrap(rows, columns)))
        }
    }

    @Test
    fun `a chord symbol is never split in half`() {
        val song = SongParser.parse("[Cmaj7]one [F#m7b5]two [Bbsus4]three [G7]four [Dm9]five")
        val row = ChartLayout.rows(song).first { it.kind == RowKind.CHORD_AND_LYRIC }
        val whole = row.chordText.split(" ").filter { it.isNotEmpty() }
        for (columns in 12..row.width) {
            val seen = ChartLayout.wrapRow(row, columns)
                .flatMap { it.chordText.split(" ") }
                .filter { it.isNotEmpty() }
            assertEquals("at $columns columns", whole, seen)
        }
    }

    @Test
    fun `tab is never reflowed, because its columns are the notation`() {
        val tab = ChartRow(
            RowKind.TAB,
            text = "e|--0---3---5---7---5---3---0---3---5---7---5---3---0---|",
            sourceLine = 0,
        )
        assertEquals(listOf(tab), ChartLayout.wrapRow(tab, 20))
    }

    @Test
    fun `a word longer than the screen is broken rather than lost`() {
        val row = ChartRow(RowKind.LYRIC, text = "a" + "b".repeat(60), sourceLine = 0)
        val wrapped = ChartLayout.wrapRow(row, 16)
        assertTrue(wrapped.size > 1)
        assertTrue(wrapped.all { it.width <= 16 })
        assertEquals(row.text, wrapped.joinToString("") { it.text })
    }

    @Test
    fun `fragments say which line they came from and which of them is a tail`() {
        val song = SongParser.parse("[C]a line of words long enough to need breaking up\nsecond")
        val wrapped = ChartLayout.wrap(ChartLayout.rows(song), 16)
        val first = wrapped.first { it.sourceLine == 0 }
        assertFalse("the head of a line is not a continuation", first.continuation)
        val tails = wrapped.filter { it.sourceLine == 0 && it !== first }
        assertTrue("a line this long has tails", tails.isNotEmpty())
        assertTrue("every tail is marked", tails.all { it.continuation })
    }

    @Test
    fun `a fragment with no chords over it does not waste a line on an empty chord row`() {
        // Chords on the first few words only: the tail is words alone.
        val song = SongParser.parse("[C]one [G]two three four five six seven eight nine ten")
        val wrapped = ChartLayout.wrap(ChartLayout.rows(song), 14)
        val tails = wrapped.filter { it.continuation }
        assertTrue(tails.isNotEmpty())
        assertTrue(
            "a chordless tail is a lyric row, not a two-line row with a blank above it",
            tails.filter { it.chordText.isBlank() }.all { it.kind == RowKind.LYRIC },
        )
    }

    @Test
    fun `a wrapped line is not split across a page break`() {
        val song = SongParser.parse(
            buildString {
                appendLine("short one")
                appendLine("[C]a much longer line that will certainly have to be wrapped twice over")
                appendLine("short two")
            },
        )
        val rows = ChartLayout.wrap(ChartLayout.rows(song), 20)
        val wrappedLine = rows.filter { it.sourceLine == 1 }
        assertTrue("the fixture must actually wrap", wrappedLine.size > 1)
        val needed = wrappedLine.sumOf { it.height }

        for (perPage in needed..needed + 4) {
            val pages = ChartLayout.paginate(rows, perPage)
            val landedOn = pages.count { page -> page.any { it.sourceLine == 1 } }
            assertEquals("split at $perPage lines per page", 1, landedOn)
            for (page in pages) {
                assertTrue("page over budget at $perPage", page.sumOf { it.height } <= perPage)
            }
        }
    }

    @Test
    fun `a line too long for any page is split rather than dropped`() {
        val rows = ChartLayout.wrap(
            ChartLayout.rows(SongParser.parse("[C]" + (1..40).joinToString(" ") { "word$it" })),
            20,
        )
        val pages = ChartLayout.paginate(rows, 2)
        assertEquals(rows.filter { it.kind != RowKind.BLANK }, pages.flatten())
    }

    @Test
    fun `the page holding a line is found again after a reflow`() {
        val song = SongParser.parse(
            buildString {
                repeat(30) { appendLine("[G]line $it of a chart with words enough to wrap on a phone") }
            },
        )
        val rows = ChartLayout.rows(song)

        // The reader is on song line 17. Whatever the width and height of the
        // screen, they should land on a page that reaches that line.
        for (columns in listOf(16, 24, 40, 80)) {
            for (perPage in listOf(4, 7, 12, 30)) {
                val pages = ChartLayout.paginate(ChartLayout.wrap(rows, columns), perPage)
                val page = ChartLayout.pageContainingSourceLine(pages, 17)
                assertTrue(
                    "$columns x $perPage landed on page $page of ${pages.size}",
                    pages[page].any { it.sourceLine == 17 },
                )
            }
        }
    }

    // The two halves of the zoom have to agree: if fitting the width says the
    // font can be this big, wrapping must not then decide the chart is one
    // character too wide for it.
    @Test
    fun `at a fit-to-width zoom nothing wraps`() {
        val song = SongParser.parse("[C]a line of a chart with a few words in it")
        val rows = ChartLayout.rows(song)
        val widest = ChartLayout.widestRow(rows)

        for (screenWidth in listOf(300f, 640f, 801f, 1234.5f)) {
            for (charWidth in listOf(6f, 7.3f, 11f)) {
                val scale = ChartZoom.fitWidthScale(widest, screenWidth, charWidth)
                val columns = ChartLayout.columnsThatFit(screenWidth, charWidth * scale)
                assertTrue(
                    "$screenWidth px at $charWidth px per character left $columns columns " +
                        "for a $widest character chart",
                    columns >= widest,
                )
                assertEquals(rows, ChartLayout.wrap(rows, columns))
            }
        }
    }

    @Test
    fun `columns and lines are worked out from the viewport and floored`() {
        assertEquals(40, ChartLayout.columnsThatFit(400f, 10f))
        assertEquals(ChartLayout.MIN_COLUMNS, ChartLayout.columnsThatFit(40f, 10f))
        assertEquals(ChartLayout.MIN_COLUMNS, ChartLayout.columnsThatFit(400f, 0f))
        assertEquals(25, ChartLayout.linesThatFit(500f, 20f))
        assertEquals(ChartLayout.MIN_LINES, ChartLayout.linesThatFit(10f, 20f))
    }
}

/**
 * The font-size arithmetic behind pinch and double tap on a chart.
 */
class ChartZoomTest {

    @Test
    fun `fitting the width is a multiplier of the size on screen`() {
        // 40 characters of 10px each is 400px of chart in 800px of screen.
        assertEquals(2f, ChartZoom.fitWidthScale(40, 800f, 10f), 0.001f)
        assertEquals(0.5f, ChartZoom.fitWidthScale(40, 200f, 10f), 0.001f)
    }

    @Test
    fun `a chart with nothing to gain is not zoomed at all`() {
        // Already wider than the screen: fitting the width would shrink it,
        // which is not what a double tap is asking for.
        assertFalse(ChartZoom.worthZooming(ChartZoom.fitWidthScale(100, 400f, 10f)))
        // And a couple of percent is not worth the movement.
        assertFalse(ChartZoom.worthZooming(1.02f))
        assertTrue(ChartZoom.worthZooming(1.4f))
    }

    @Test
    fun `an unmeasurable chart asks for no change rather than for infinity`() {
        assertEquals(1f, ChartZoom.fitWidthScale(0, 400f, 10f), 0.001f)
        assertEquals(1f, ChartZoom.fitWidthScale(40, 400f, 0f), 0.001f)
        // A nonsense scale asks for no change. Both of these can only come out
        // of a division by a zero-sized viewport, and "leave it alone" is the
        // only answer that cannot put the chart into a state nobody asked for.
        assertEquals(1f, ChartZoom.clamp(Float.NaN), 0.001f)
        assertEquals(1f, ChartZoom.clamp(Float.POSITIVE_INFINITY), 0.001f)
    }

    @Test
    fun `zoom is clamped at both ends`() {
        assertEquals(ChartZoom.MAX, ChartZoom.clamp(50f), 0.001f)
        assertEquals(ChartZoom.MIN, ChartZoom.clamp(0.01f), 0.001f)
        var scale = 1f
        repeat(40) { scale = ChartZoom.step(scale, larger = true) }
        assertEquals(ChartZoom.MAX, scale, 0.001f)
        repeat(80) { scale = ChartZoom.step(scale, larger = false) }
        assertEquals(ChartZoom.MIN, scale, 0.001f)
    }
}
