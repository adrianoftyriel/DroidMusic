package org.droidmusic.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordsOverLyricsTest {

    private val sample = """
        Amazing Grace

        G                   C         G
        Amazing grace how sweet the sound
                      Em        D
        That saved a wretch like me
    """.trimIndent()

    @Test
    fun `chord lines are detected and lyric lines are not`() {
        assertTrue(ChordsOverLyricsParser.isChordLine("G       C       D"))
        assertTrue(ChordsOverLyricsParser.isChordLine("| Am  F | C  G |"))
        assertTrue(ChordsOverLyricsParser.isChordLine("Bm7b5  E7  Am"))
        assertFalse(ChordsOverLyricsParser.isChordLine("Amazing grace how sweet the sound"))
        assertFalse(ChordsOverLyricsParser.isChordLine("And can it be that I should gain"))
        assertFalse(ChordsOverLyricsParser.isChordLine(""))
        // Bar furniture alone is not a chord line; there is nothing to transpose.
        assertFalse(ChordsOverLyricsParser.isChordLine("|  |  |  |"))
    }

    @Test
    fun `tab lines are not mistaken for chord lines`() {
        assertTrue(ChordsOverLyricsParser.isTabLine("e|---0---3---2---|"))
        assertTrue(ChordsOverLyricsParser.isTabLine("E|-----------------|"))
        assertFalse(ChordsOverLyricsParser.isChordLine("e|---0---3---2---|"))
    }

    @Test
    fun `chords attach to the syllable underneath them`() {
        val song = ChordsOverLyricsParser.parse(sample)
        val line = song.lines.filterIsInstance<Line.Lyric>()
            .first { l -> l.segments.any { it.chord != null } }
        assertEquals(listOf("G", "C", "G"), line.segments.mapNotNull { it.chord?.toString() })
        assertEquals("Amazing grace how sweet the sound", line.plainText)

        // Segments run from one chord's column to the next one's, so the split
        // falls wherever the chart put the chord - mid-word included, which is
        // exactly what a chart means when it puts a chord mid-word.
        assertEquals(
            listOf("Amazing grace how sw", "eet the so", "und"),
            line.segments.map { it.text },
        )
    }

    @Test
    fun `a chord line with no lyric under it becomes an instrumental line`() {
        val song = ChordsOverLyricsParser.parse("Am   F    C    G\n\nnext")
        val line = song.lines.filterIsInstance<Line.Lyric>().first()
        assertTrue(line.isChordsOnly)
        assertEquals(listOf("Am", "F", "C", "G"), line.segments.mapNotNull { it.chord?.toString() })
    }

    @Test
    fun `format sniffing picks the right parser`() {
        assertEquals(ChartFormat.CHORDPRO, SongParser.detectFormat("{title: X}\nhello"))
        assertEquals(ChartFormat.CHORDPRO, SongParser.detectFormat("[C]hello there"))
        assertEquals(ChartFormat.CHORDS_OVER_LYRICS, SongParser.detectFormat(sample))
        assertEquals(ChartFormat.PLAIN_TEXT, SongParser.detectFormat("Just some prose.\nAnd more of it."))
    }

    @Test
    fun `section headers are recognised`() {
        val song = ChordsOverLyricsParser.parse("[Chorus]\nC   G\nsome words")
        assertEquals(
            SectionKind.CHORUS,
            song.lines.filterIsInstance<Line.SectionHeader>().first().kind,
        )
    }
}
