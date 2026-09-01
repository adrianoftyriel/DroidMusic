package org.droidmusic.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartLayoutTest {

    private fun rows(vararg kinds: RowKind) = kinds.map { kind ->
        when (kind) {
            RowKind.CHORD_AND_LYRIC -> ChartRow(kind, "C  G", "words here")
            RowKind.BLANK -> ChartRow(kind)
            RowKind.HEADER -> ChartRow(kind, text = "Chorus")
            else -> ChartRow(kind, text = "some text")
        }
    }

    @Test
    fun `a chord and lyric pair counts as two lines but never splits`() {
        val row = ChartRow(RowKind.CHORD_AND_LYRIC, "C  G", "words")
        assertEquals(2, row.height)

        val pages = ChartLayout.paginate(rows(RowKind.CHORD_AND_LYRIC, RowKind.CHORD_AND_LYRIC), 3)
        // Three lines of room, two rows of two lines: the second row moves whole.
        assertEquals(2, pages.size)
        assertEquals(1, pages[0].size)
        assertEquals(1, pages[1].size)
    }

    @Test
    fun `pages fill up to the limit`() {
        val pages = ChartLayout.paginate(rows(*Array(10) { RowKind.LYRIC }), 4)
        assertEquals(3, pages.size)
        assertEquals(4, pages[0].size)
        assertEquals(4, pages[1].size)
        assertEquals(2, pages[2].size)
    }

    // A "Chorus" heading stranded at the foot of a page, with the chorus itself
    // overleaf, is the specific failure this guards against.
    @Test
    fun `a section header is not orphaned at the foot of a page`() {
        val input = listOf(
            ChartRow(RowKind.LYRIC, text = "a"),
            ChartRow(RowKind.LYRIC, text = "b"),
            ChartRow(RowKind.HEADER, text = "Chorus"),
            ChartRow(RowKind.CHORD_AND_LYRIC, "C", "the chorus"),
        )
        val pages = ChartLayout.paginate(input, 4)
        val headerPage = pages.indexOfFirst { page -> page.any { it.kind == RowKind.HEADER } }
        val header = pages[headerPage]
        assertTrue("header must not be last on its page", header.last().kind != RowKind.HEADER)
        assertEquals("the chorus", header.last().text)
    }

    @Test
    fun `blank lines are absorbed by the page break rather than shown as a gap`() {
        val input = listOf(
            ChartRow(RowKind.LYRIC, text = "a"),
            ChartRow(RowKind.BLANK),
            ChartRow(RowKind.BLANK),
            ChartRow(RowKind.LYRIC, text = "b"),
        )
        val pages = ChartLayout.paginate(input, 2)
        assertTrue(pages.none { it.first().kind == RowKind.BLANK })
        assertTrue(pages.none { it.last().kind == RowKind.BLANK })
    }

    @Test
    fun `every row survives pagination exactly once`() {
        val song = SongParser.parse(
            buildString {
                appendLine("{title: Long One}")
                appendLine("{key: G}")
                repeat(40) { i ->
                    if (i % 8 == 0) appendLine("{start_of_verse: Verse ${i / 8}}")
                    appendLine("[G]line $i with [C]chords in [D]it")
                    if (i % 4 == 3) appendLine()
                }
            },
        )
        val all = ChartLayout.rows(song)
        val meaningful = all.filter { it.kind != RowKind.BLANK }
        for (perPage in listOf(3, 5, 8, 12, 30)) {
            val flattened = ChartLayout.paginate(all, perPage).flatten().filter { it.kind != RowKind.BLANK }
            assertEquals("at $perPage lines per page", meaningful, flattened)
        }
    }

    @Test
    fun `no page exceeds its line budget`() {
        val song = SongParser.parse(
            buildString {
                repeat(30) { appendLine("[C]a line of words number $it here") }
            },
        )
        val all = ChartLayout.rows(song)
        for (perPage in 2..20) {
            for (page in ChartLayout.paginate(all, perPage)) {
                assertEquals(
                    "page over budget at $perPage",
                    true,
                    page.sumOf { it.height } <= perPage,
                )
            }
        }
    }

    @Test
    fun `rows carry the chord row and the lyric row separately`() {
        val song = SongParser.parse("{key: C}\n[C]Amazing [F]grace")
        val row = ChartLayout.rows(song).first { it.kind == RowKind.CHORD_AND_LYRIC }
        assertEquals("C       F", row.chordText)
        assertEquals("Amazing grace", row.text)
        assertEquals(13, row.width)
    }

    @Test
    fun `a chordpro chart opens with its title, artist and key`() {
        val song = SongParser.parse(
            """
            {title: Wagon Wheel}
            {artist: Old Crow Medicine Show}
            {key: A}
            [A]Heading down [E]south
            """.trimIndent(),
        )
        val rows = ChartLayout.rows(song)
        assertEquals(RowKind.TITLE, rows[0].kind)
        assertEquals("Wagon Wheel", rows[0].text)
        assertEquals(RowKind.CREDIT, rows[1].kind)
        assertTrue(rows[1].text.contains("Old Crow Medicine Show"))
        assertTrue(rows[1].text.contains("Key of A"))
        // Once, at the top, and nowhere else.
        assertEquals(1, rows.count { it.kind == RowKind.TITLE })
    }

    @Test
    fun `the title block lands on the first page and never on a later one`() {
        val song = SongParser.parse(
            buildString {
                appendLine("{title: Long One}")
                appendLine("{artist: Somebody}")
                repeat(30) { appendLine("[C]line $it") }
            },
        )
        val pages = ChartLayout.paginate(ChartLayout.rows(song), 6)
        assertTrue(pages.size > 1)
        assertEquals(RowKind.TITLE, pages.first().first().kind)
        for (page in pages.drop(1)) {
            assertTrue(page.none { it.kind == RowKind.TITLE || it.kind == RowKind.CREDIT })
        }
    }

    // The key in the heading has to be the key on the page, or it is worse than
    // no heading at all.
    @Test
    fun `the key in the heading follows a transposition`() {
        val song = SongParser.parse("{title: Test}\n{key: C}\n[C]Words [G]here")
        val moved = Transposer.transpose(song, TransposeRequest(semitones = 2)).song
        val credit = ChartLayout.rows(moved).first { it.kind == RowKind.CREDIT }
        assertTrue(credit.text, credit.text.contains("Key of D"))
    }

    @Test
    fun `a capo is named in the heading`() {
        val song = SongParser.parse("{title: Test}\n{key: G}\n[G]Words [C]here")
        val moved = Transposer.transpose(song, TransposeRequest(capo = 3)).song
        val credit = ChartLayout.rows(moved).first { it.kind == RowKind.CREDIT }
        assertTrue(credit.text, credit.text.contains("capo 3"))
        // A capo does not change what the audience hears, so the key does not
        // change either.
        assertTrue(credit.text, credit.text.contains("Key of G"))
    }

    // A plain chart has its title in the text where its author typed it.
    // Printing ours above it would show the same line twice.
    @Test
    fun `a chords over lyrics chart gets no title block`() {
        val song = SongParser.parse("Wagon Wheel\n\nC       G\nHeading down south")
        assertEquals(ChartFormat.CHORDS_OVER_LYRICS, song.meta.format)
        assertTrue(ChartLayout.rows(song).none { it.kind == RowKind.TITLE })
    }

    @Test
    fun `a chordpro chart that says nothing about itself gets no title block`() {
        val song = SongParser.parse("[C]Just some [G]chords")
        assertEquals(ChartFormat.CHORDPRO, song.meta.format)
        assertEquals(RowKind.CHORD_AND_LYRIC, ChartLayout.rows(song).first().kind)
    }

    // The title is drawn proportionally at its own size, so its length says
    // nothing about how many monospaced columns the music needs.
    @Test
    fun `a long title does not widen the chart`() {
        val song = SongParser.parse(
            "{title: A Title That Is Very Much Longer Than Any Line Of This Song}\n[C]Short",
        )
        assertEquals(5, ChartLayout.widestRow(ChartLayout.rows(song)))
    }

    @Test
    fun `font size is fitted to the widest row and clamped`() {
        // 40 characters into 400px at 0.6px per character per sp.
        assertEquals(16.6f, ChartLayout.fitFontSize(40, 400f, 0.6f), 0.1f)
        // A very wide chart is clamped at the readable minimum rather than
        // shrinking to nothing.
        assertEquals(8f, ChartLayout.fitFontSize(400, 400f, 0.6f), 0.001f)
        // A very narrow one does not blow up to fill the page.
        assertEquals(28f, ChartLayout.fitFontSize(3, 4000f, 0.6f), 0.001f)
        assertEquals(28f, ChartLayout.fitFontSize(0, 400f, 0.6f), 0.001f)
    }
}

/**
 * The header-orphan rule pulls a heading forward onto the next page when its
 * first line would not fit beneath it. That move has to respect the page budget
 * as well, which is only visible at small page sizes.
 */
class ChartLayoutHeaderEdgeTest {

    @Test
    fun `pulling a header forward never overflows the page`() {
        val input = listOf(
            ChartRow(RowKind.LYRIC, text = "a"),
            ChartRow(RowKind.HEADER, text = "Chorus"),
            ChartRow(RowKind.CHORD_AND_LYRIC, "C", "the chorus"),
            ChartRow(RowKind.CHORD_AND_LYRIC, "G", "more of it"),
        )
        for (perPage in 1..8) {
            for (page in ChartLayout.paginate(input, perPage)) {
                assertTrue(
                    "perPage=$perPage produced a page of ${page.sumOf { it.height }} lines",
                    page.sumOf { it.height } <= perPage.coerceAtLeast(2),
                )
            }
        }
    }

    @Test
    fun `no row is lost when a header is pulled forward`() {
        val input = listOf(
            ChartRow(RowKind.LYRIC, text = "a"),
            ChartRow(RowKind.HEADER, text = "Chorus"),
            ChartRow(RowKind.CHORD_AND_LYRIC, "C", "the chorus"),
            ChartRow(RowKind.LYRIC, text = "b"),
            ChartRow(RowKind.HEADER, text = "Bridge"),
            ChartRow(RowKind.CHORD_AND_LYRIC, "F", "the bridge"),
        )
        for (perPage in 1..10) {
            assertEquals(
                "perPage=$perPage",
                input,
                ChartLayout.paginate(input, perPage).flatten(),
            )
        }
    }
}
