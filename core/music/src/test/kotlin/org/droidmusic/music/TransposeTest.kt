package org.droidmusic.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransposeTest {

    private fun song(text: String) = SongParser.parse(text)

    private fun chordsOf(result: TransposeResult) = result.song.chords().map { it.toString() }

    @Test
    fun `transposing to a key spells every chord in that key`() {
        val s = song("{key: C}\n[C]one [F]two [G7]three [Am]four")
        val result = Transposer.transpose(s, TransposeRequest(targetKey = Key.parse("Eb")))
        assertEquals(listOf("Eb", "Ab", "Bb7", "Cm"), chordsOf(result))
    }

    // The failure this guards against: spelling each chord independently gives a
    // chart that mixes sharps and flats. Transposing C-F-G up three has to give
    // Eb-Ab-Bb, all flat, not Eb-G#-A#.
    @Test
    fun `a whole chart is spelled consistently`() {
        val s = song("{key: C}\n[C]a [F]b [G]c [Dm]d [Em]e [Am]f")
        val result = Transposer.transpose(s, TransposeRequest(semitones = 3))
        assertEquals(listOf("Eb", "Ab", "Bb", "Fm", "Gm", "Cm"), chordsOf(result))
        assertEquals("Eb", result.soundingKey.toString())
    }

    @Test
    fun `transposing up then back down is the identity`() {
        val original = "{key: A}\n[A]x [D]y [E7]z [F#m]w"
        for (semitones in 1..11) {
            val up = Transposer.transpose(song(original), TransposeRequest(semitones = semitones))
            val down = Transposer.transpose(up.song, TransposeRequest(semitones = -semitones))
            assertEquals(
                "round trip by $semitones",
                song(original).chords().map { it.pitchClassSignature() },
                down.song.chords().map { it.pitchClassSignature() },
            )
        }
    }

    private fun Chord.pitchClassSignature() = "${root.pitchClass}:$quality:${bass?.pitchClass}"

    @Test
    fun `slash bass notes move with the chord`() {
        val s = song("{key: G}\n[G]a [D/F#]b [Em]c")
        val result = Transposer.transpose(s, TransposeRequest(targetKey = Key.parse("A")))
        assertEquals(listOf("A", "E/G#", "F#m"), chordsOf(result))
    }

    // A capo changes the shapes without changing the sound. This is the property
    // that makes the feature worth having and the one easiest to get backwards.
    @Test
    fun `a capo lowers the shapes but not the sounding key`() {
        val s = song("{key: Bb}\n[Bb]a [Eb]b [F]c")
        val result = Transposer.transpose(s, TransposeRequest(capo = 3))
        assertEquals("Bb", result.soundingKey.toString())
        assertEquals("G", result.playedKey.toString())
        assertEquals(listOf("G", "C", "D"), chordsOf(result))
    }

    @Test
    fun `a capo composes with a transposition`() {
        val s = song("{key: C}\n[C]a [F]b [G]c")
        // Sound in D, but finger it in C with the capo on 2.
        val result = Transposer.transpose(
            s,
            TransposeRequest(targetKey = Key.parse("D"), capo = 2),
        )
        assertEquals("D", result.soundingKey.toString())
        assertEquals("C", result.playedKey.toString())
        assertEquals(listOf("C", "F", "G"), chordsOf(result))
    }

    @Test
    fun `lyrics are untouched`() {
        val s = song("{key: C}\n[C]Amazing [F]grace how [G]sweet the sound")
        val result = Transposer.transpose(s, TransposeRequest(semitones = 5))
        assertEquals(
            "Amazing grace how sweet the sound",
            result.song.lines.filterIsInstance<Line.Lyric>().first().plainText,
        )
    }

    @Test
    fun `the key is detected when the file does not declare one`() {
        val s = song("[G]a [C]b [D]c [G]d")
        val result = Transposer.transpose(s, TransposeRequest(semitones = 2))
        assertEquals("G", result.fromKey.toString())
        assertEquals("A", result.soundingKey.toString())
    }

    @Test
    fun `tab is left alone by default and reported`() {
        val s = song("{key: C}\n{start_of_tab}\ne|---0---3---|\n{end_of_tab}\n[C]after")
        val result = Transposer.transpose(s, TransposeRequest(semitones = 2))
        assertEquals("e|---0---3---|", result.song.lines.filterIsInstance<Line.Tab>().first().text)
        assertTrue(result.notes.any { it.contains("not transposed") })
    }

    @Test
    fun `tab is shifted when asked for`() {
        val s = song("{key: C}\n{start_of_tab}\ne|---0---3---|\n{end_of_tab}\n[C]after")
        val result = Transposer.transpose(s, TransposeRequest(semitones = 2, includeTab = true))
        assertEquals("e|---2---5---|", result.song.lines.filterIsInstance<Line.Tab>().first().text)
    }
}

class TabTransposerTest {

    @Test
    fun `frets move and columns are preserved`() {
        assertEquals("e|---2---5---|", TabTransposer.shift("e|---0---3---|", 2))
        // Two digits where there was one: the dash following the number is eaten,
        // so the bar comes out exactly as wide as it went in.
        assertEquals("e|---10--12--|", TabTransposer.shift("e|---8---10--|", 2))
        assertEquals(
            "e|---8---10--|".length,
            TabTransposer.shift("e|---8---10--|", 2)!!.length,
        )
    }

    @Test
    fun `a shift off the neck is refused rather than fudged`() {
        assertNull(TabTransposer.shift("e|---0---3---|", -2))
        assertNull(TabTransposer.shift("e|--22--24---|", 3))
        assertNotNull(TabTransposer.shift("e|---5---7---|", -3))
    }

    @Test
    fun `zero is a no-op`() {
        assertEquals("e|---0---3---|", TabTransposer.shift("e|---0---3---|", 0))
    }

    @Test
    fun `a whole block is checked together`() {
        val block = listOf("e|---0---3---|", "B|---1---0---|", "G|---0---0---|")
        assertTrue(TabTransposer.canShift(block, 5))
        assertTrue(!TabTransposer.canShift(block, -1))
    }
}
