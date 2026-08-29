package org.droidmusic.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordTest {

    @Test
    fun `qualities are carried through untouched`() {
        for (symbol in listOf(
            "C", "Am", "G7", "Dsus4", "Fmaj7", "Bm7b5", "C#m", "Ebmaj9",
            "A7sus4", "Gadd9", "F#dim", "Baug", "C6/9", "Dm7", "E-9",
            "Amaj7#11", "G(no3)", "Cø7", "F+", "Bb13",
        )) {
            val chord = Chord.parse(symbol)
            assertNotNull("should parse: $symbol", chord)
            assertEquals(symbol, chord.toString())
        }
    }

    @Test
    fun `slash chords split the bass off`() {
        val c = Chord.parse("D/F#")!!
        assertEquals("D", c.root.toString())
        assertEquals("F#", c.bass!!.toString())
        assertEquals("D/F#", c.toString())
    }

    @Test
    fun `a slash that is not a bass note stays in the quality`() {
        val c = Chord.parse("C6/9")!!
        assertNull(c.bass)
        assertEquals("6/9", c.quality)
    }

    // The whole risk of a chart transposer is rewriting words. These are the
    // English words that start with a letter A-G and most nearly look like chords.
    @Test
    fun `ordinary words are not chords`() {
        for (word in listOf(
            "Add", "And", "Are", "As", "Be", "But", "Can", "Do", "Dad", "Down",
            "Even", "Fade", "Girl", "Gone", "Ain't", "Baby", "Call", "Amen",
            "Falling", "End", "Ever", "Bass", "Cause", "Get", "Fire", "Dream",
            "Awake", "Because", "Deep", "Faith", "Grace", "Angel",
        )) {
            assertNull("should not parse as a chord: $word", Chord.parse(word))
        }
    }

    @Test
    fun `minor and diminished are recognised across notations`() {
        assertTrue(Chord.parse("Am")!!.isMinor)
        assertTrue(Chord.parse("Amin7")!!.isMinor)
        assertTrue(Chord.parse("A-7")!!.isMinor)
        assertTrue(!Chord.parse("Amaj7")!!.isMinor)
        assertTrue(!Chord.parse("AM7")!!.isMinor)
        assertTrue(Chord.parse("Bdim")!!.isDiminished)
    }

    @Test
    fun `transposition moves root and bass together`() {
        val c = Chord.parse("D/F#")!!
        // Up a minor third, spelled as one: D to F, F sharp to A.
        val moved = c.transpose(Interval(2, 3))
        assertEquals("F/A", moved.toString())
    }

    @Test
    fun `transposition leaves the quality alone`() {
        val c = Chord.parse("Amaj7#11")!!
        assertEquals("maj7#11", c.transpose(Interval(1, 2)).quality)
    }
}
