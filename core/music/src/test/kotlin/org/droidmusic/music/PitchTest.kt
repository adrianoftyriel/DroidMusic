package org.droidmusic.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTest {

    @Test
    fun `pitch class accounts for the accidental`() {
        assertEquals(0, Note.parse("C")!!.note.pitchClass)
        assertEquals(1, Note.parse("C#")!!.note.pitchClass)
        assertEquals(1, Note.parse("Db")!!.note.pitchClass)
        assertEquals(11, Note.parse("B")!!.note.pitchClass)
        assertEquals(0, Note.parse("B#")!!.note.pitchClass)
        assertEquals(11, Note.parse("Cb")!!.note.pitchClass)
    }

    @Test
    fun `unicode accidentals parse the same as ascii`() {
        assertEquals(Note.parse("Bb")!!.note, Note.parse("B♭")!!.note)
        assertEquals(Note.parse("F#")!!.note, Note.parse("F♯")!!.note)
    }

    @Test
    fun `non letters are not notes`() {
        assertNull(Note.parse("H"))
        assertNull(Note.parse(""))
        assertNull(Note.parse("7"))
    }

    /**
     * The crash this guards against: a chart with an E sharp in it, transposed
     * and then read behind a capo, asks for a note four sharps above B - which
     * is not a symbol, and which used to fail an assertion inside the Note
     * constructor and take the app down with it.
     */
    @Test
    fun `a spelling nobody could write falls back to one they could`() {
        val bSharp = Note.parse("B#")!!.note
        // Six semitones up with the letter staying put: the exact answer is B
        // with five flats, which the arithmetic reaches and no one can read.
        val moved = bSharp.transpose(Interval(0, 6))
        assertEquals(6, moved.pitchClass)
        assertTrue("alter was ${moved.alter}", moved.alter in -3..3)
        // Flat-wards, because that is the direction the spelling was going.
        assertEquals("Gb", moved.toString())
    }

    @Test
    fun `every note survives every interval, on the right pitch`() {
        for (letter in 0..6) {
            for (alter in -3..3) {
                val note = Note(letter, alter)
                for (letterSteps in 0..6) {
                    for (semitones in 0..11) {
                        val moved = note.transpose(Interval(letterSteps, semitones))
                        assertEquals(
                            "$note by ($letterSteps, $semitones) landed on $moved",
                            Math.floorMod(note.pitchClass + semitones, 12),
                            moved.pitchClass,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a flat chart that has to be respelled stays flat`() {
        // Three flats deep and asked for one more: the fallback picks the flat
        // side of the tie rather than flipping the chart into sharps.
        val tripleFlat = Note(0, -3)
        val moved = tripleFlat.transpose(Interval(0, 11))
        assertEquals(8, moved.pitchClass)
        assertEquals("Ab", moved.toString())
    }

    @Test
    fun `the plainest spelling of a pitch is the one with fewest accidentals`() {
        assertEquals("C", Note.spellingOf(0, preferFlats = false).toString())
        assertEquals("Bb", Note.spellingOf(10, preferFlats = true).toString())
        assertEquals("A#", Note.spellingOf(10, preferFlats = false).toString())
        // Six is a genuine tie - F sharp and G flat are both one accidental.
        assertEquals("Gb", Note.spellingOf(6, preferFlats = true).toString())
        assertEquals("F#", Note.spellingOf(6, preferFlats = false).toString())
    }

    // The point of storing a letter rather than a pitch class: a fourth above E
    // flat has to come out A flat, never G sharp.
    @Test
    fun `transposition preserves spelling`() {
        val eFlat = Note.parse("Eb")!!.note
        assertEquals("Ab", eFlat.transpose(Interval(3, 5)).toString())

        val c = Note.parse("C")!!.note
        assertEquals("Eb", c.transpose(Interval(2, 3)).toString())
        assertEquals("D#", c.transpose(Interval(1, 3)).toString())
        assertEquals("F#", c.transpose(Interval(3, 6)).toString())
        assertEquals("Gb", c.transpose(Interval(4, 6)).toString())
    }

    @Test
    fun `transposition round trips`() {
        val interval = Interval(2, 3)
        for (letter in 0..6) {
            for (alter in -1..1) {
                val note = Note(letter, alter)
                assertEquals(note, note.transpose(interval).transpose(-interval))
            }
        }
    }

    @Test
    fun `interval between two notes is spelled by those notes`() {
        val bFlat = Note.parse("Bb")!!.note
        val d = Note.parse("D")!!.note
        // B flat up to D is a major third: two letter names, four semitones.
        assertEquals(Interval(2, 4), Interval.between(bFlat, d))
    }
}

class KeyTest {

    @Test
    fun `key signatures have the right accidental counts`() {
        assertEquals(0, Key.parse("C")!!.fifths)
        assertEquals(1, Key.parse("G")!!.fifths)
        assertEquals(-4, Key.parse("Ab")!!.fifths)
        assertEquals(7, Key.parse("C#")!!.fifths)
        assertEquals(-5, Key.parse("Db")!!.fifths)
        assertEquals(0, Key.parse("Am")!!.fifths)
        assertEquals(1, Key.parse("Em")!!.fifths)
        assertEquals(3, Key.parse("F#m")!!.fifths)
        assertEquals(-3, Key.parse("Cm")!!.fifths)
    }

    @Test
    fun `best spelling picks the writable key`() {
        val c = Key.parse("C")!!
        // Three up from C is E flat (3 flats), never D sharp (9 sharps).
        assertEquals("Eb", Key.bestSpelling(c, 3).toString())
        // One up is D flat (5 flats) rather than C sharp (7 sharps).
        assertEquals("Db", Key.bestSpelling(c, 1).toString())
        assertEquals("D", Key.bestSpelling(c, 2).toString())
    }

    @Test
    fun `a tie is broken by the caller's preference`() {
        val c = Key.parse("C")!!
        // F sharp and G flat both need six accidentals.
        assertEquals("Gb", Key.bestSpelling(c, 6, preferSharps = false).toString())
        assertEquals("F#", Key.bestSpelling(c, 6, preferSharps = true).toString())
    }

    @Test
    fun `unwritable keys are never chosen`() {
        for (from in KeyDetector.candidates()) {
            for (semitones in 0..11) {
                val target = Key.bestSpelling(from, semitones)
                assert(target.isPractical) { "$from + $semitones gave $target" }
                assertEquals(
                    Math.floorMod(from.pitchClass + semitones, 12),
                    target.pitchClass,
                )
            }
        }
    }

    @Test
    fun `minor keys keep their mode through transposition`() {
        val aMinor = Key.parse("Am")!!
        assertEquals(Mode.MINOR, Key.bestSpelling(aMinor, 5).mode)
        assertEquals("Dm", Key.bestSpelling(aMinor, 5).toString())
    }
}
