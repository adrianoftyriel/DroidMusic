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
