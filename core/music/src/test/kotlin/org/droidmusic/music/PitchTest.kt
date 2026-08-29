package org.droidmusic.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
