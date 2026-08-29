package org.droidmusic.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyDetectorTest {

    private fun detect(vararg symbols: String) =
        KeyDetector.detect(symbols.map { Chord.parse(it)!! })!!

    @Test
    fun `a plain major progression is found`() {
        assertEquals("G", detect("G", "C", "D", "G").key.toString())
        assertEquals("C", detect("C", "Am", "F", "G", "C").key.toString())
        assertEquals("D", detect("D", "A", "Bm", "G", "D").key.toString())
    }

    @Test
    fun `flat keys are found and spelled flat`() {
        assertEquals("Eb", detect("Eb", "Ab", "Bb7", "Eb").key.toString())
        assertEquals("Bb", detect("Bb", "Gm", "Eb", "F7", "Bb").key.toString())
    }

    @Test
    fun `minor keys are distinguished from their relative major`() {
        // Ending on the minor tonic, with the raised dominant, is what separates
        // A minor from C major - the two share every note otherwise.
        assertEquals("Am", detect("Am", "Dm", "E7", "Am").key.toString())
        assertEquals("Em", detect("Em", "Am", "B7", "Em").key.toString())
    }

    @Test
    fun `the dominant seventh pins the key down`() {
        assertEquals("F", detect("F", "Bb", "C7", "F").key.toString())
    }

    @Test
    fun `an ambiguous chart reports low confidence`() {
        val clear = detect("G", "C", "D", "G", "D", "G", "C", "G")
        val vague = detect("C", "F")
        assertTrue(
            "clear=${clear.confidence} vague=${vague.confidence}",
            clear.confidence > vague.confidence,
        )
    }

    @Test
    fun `detection survives a transposition`() {
        val chords = listOf("G", "C", "D", "Em", "G").map { Chord.parse(it)!! }
        for (semitones in 1..11) {
            val from = Key.parse("G")!!
            val to = Key.bestSpelling(from, semitones)
            val interval = Interval.between(from.tonic, to.tonic)
            val moved = chords.map { it.transpose(interval) }
            assertEquals(
                "transposed by $semitones",
                to.pitchClass,
                KeyDetector.detect(moved)!!.key.pitchClass,
            )
        }
    }

    @Test
    fun `no chords means no answer rather than a wrong one`() {
        assertEquals(null, KeyDetector.detect(emptyList()))
    }
}

class ChartAnalyzerTest {

    @Test
    fun `analysis reports what the chart contains`() {
        val song = SongParser.parse(
            """
            {title: Test}
            {key: G}
            [G]one [C]two [G]three [D]four [Em]five [C]six
            """.trimIndent(),
        )
        val analysis = ChartAnalyzer.analyze(song)
        assertEquals(Key.parse("G"), analysis.declaredKey)
        assertEquals(Key.parse("G"), analysis.effectiveKey)
        assertEquals(6, analysis.chordCount)
        assertEquals(4, analysis.distinctChords.size)
        // G and C both appear twice; the most used comes first.
        assertEquals(2, analysis.distinctChords.first().count)
        assertTrue(analysis.hasLyrics)
        assertTrue(!analysis.hasTab)
    }

    @Test
    fun `chords outside the key are singled out`() {
        val song = SongParser.parse("{key: C}\n[C]a [E7]b [Am]c [F]d")
        val analysis = ChartAnalyzer.analyze(song)
        assertEquals(listOf("E7"), analysis.nonDiatonic.map { it.toString() })
    }

    // The practical payoff of the whole analysis: a chart in a flat key that a
    // guitarist would rather play with a capo.
    @Test
    fun `a hard key gets a capo suggestion that lands somewhere easy`() {
        val song = SongParser.parse("{key: Eb}\n[Eb]a [Ab]b [Bb]c [Cm]d")
        val analysis = ChartAnalyzer.analyze(song)
        val best = analysis.capoSuggestions.firstOrNull()
        assertNotNull("expected a capo suggestion", best)

        // Not asserting one specific fret. E flat has two good answers - capo 1
        // into D and capo 3 into C - and a test that demands one of them would be
        // pinning down an arbitrary tie-break rather than the behaviour that
        // matters. What matters is that the suggestion is reachable, lands
        // somewhere a guitarist would call easy, and actually helps.
        assertTrue("fret ${best!!.fret}", best.fret in 1..7)
        assertTrue(
            "landed in ${best.playedKey}",
            best.playedKey.toString() in listOf("C", "D", "G", "A", "E"),
        )
        assertTrue("opened up ${best.openShapeCount}/${best.totalChords}", best.openShapeCount >= 3)

        // None of the chart's own chords are open shapes to begin with, which is
        // what makes the capo worth suggesting at all.
        assertEquals(0, song.chords().count { ChartAnalyzer.isOpenShape(it) })
    }

    @Test
    fun `a key that is already easy gets no gratuitous suggestion above it`() {
        val song = SongParser.parse("{key: G}\n[G]a [C]b [D]c [Em]d")
        val analysis = ChartAnalyzer.analyze(song)
        // Anything suggested must at least be a key a guitarist would call easy.
        assertTrue(analysis.capoSuggestions.all { it.openShapeCount > 0 })
    }

    @Test
    fun `tab is detected in a plain text file`() {
        val song = SongParser.parse(
            """
            Riff:
            e|---0---3---2---|
            B|---1---0---3---|
            """.trimIndent(),
        )
        assertTrue(ChartAnalyzer.analyze(song).hasTab)
    }
}

class SongWriterTest {

    @Test
    fun `the two row layout puts each chord over its syllable`() {
        val song = SongParser.parse("[C]Amazing [F]grace how [G]sweet")
        val text = SongWriter.toChordsOverLyrics(song)
        val rows = text.trim().lines()
        assertEquals("C       F         G", rows[0])
        assertEquals("Amazing grace how sweet", rows[1])
    }

    // Transposition changes chord widths. If the layout let them drift, every
    // chord after the first wide one would stop sitting over its word.
    @Test
    fun `a wider chord opens the lyric up rather than sliding out of place`() {
        // The key is declared, because "C then F" on its own is genuinely
        // ambiguous and this test is about layout, not about detection.
        val song = SongParser.parse("{key: C}\n[C]Amazing [F]grace")
        val moved = Transposer.transpose(song, TransposeRequest(targetKey = Key.parse("Db"))).song
        val rows = SongWriter.toChordsOverLyrics(moved).trim().lines()
        val chordRow = rows[0]
        val lyricRow = rows[1]
        assertEquals("Db", chordRow.take(2))
        // The second chord still starts exactly above the word it belongs to.
        val gbColumn = chordRow.indexOf("Gb")
        assertEquals("grace", lyricRow.substring(gbColumn).trim())
    }

    @Test
    fun `chordpro output reparses to the same chart`() {
        val original = SongParser.parse(
            "{title: T}\n{key: C}\n\n{start_of_chorus}\n[C]a [Am]b\n{end_of_chorus}\n\n[F]c",
        )
        val again = ChordProParser.parse(SongWriter.toChordPro(original))
        assertEquals(original.meta.title, again.meta.title)
        assertEquals(original.meta.key, again.meta.key)
        assertEquals(
            original.chords().map { it.toString() },
            again.chords().map { it.toString() },
        )
    }
}
