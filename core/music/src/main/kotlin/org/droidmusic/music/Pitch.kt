package org.droidmusic.music

/**
 * A note is a letter plus an alteration, never a bare pitch class.
 *
 * This is the single most important decision in the transposition code, so it is
 * worth stating why. If a note is stored as "one of the twelve semitones", then
 * transposing E flat up a fourth can only ever produce "the pitch class 3
 * semitones above A flat", and the code has to guess afterwards whether to print
 * that as A flat or G sharp. Every such guess is wrong roughly half the time, and
 * a chart full of G sharp major chords is a chart a musician will not read.
 *
 * Storing the letter separately makes the answer fall out of the arithmetic
 * instead. Transposition is defined by an [Interval], which carries how many
 * letter names to move as well as how many semitones; the letter is moved by
 * the first number and the alteration is then whatever it takes to satisfy the
 * second. E flat up a perfect fourth moves E to A (four letter names) and needs
 * a flat to make the distance five semitones, so it is A flat, and there was
 * never a decision to get wrong.
 */
data class Note(val letter: Int, val alter: Int) {

    init {
        require(letter in 0..6) { "letter must be 0..6 (C..B), was $letter" }
        require(alter in -3..3) { "alter must be -3..3, was $alter" }
    }

    /** Pitch class 0..11, with C = 0. */
    val pitchClass: Int get() = Math.floorMod(LETTER_SEMITONES[letter] + alter, 12)

    /** Renders as ASCII: `Bb`, `F#`, `Cx` is written `C##`. */
    override fun toString(): String = LETTER_NAMES[letter] + accidentalText(alter)

    /** Renders with real accidental glyphs, for display rather than for files. */
    fun toUnicode(): String = LETTER_NAMES[letter] + when {
        alter > 0 -> "♯".repeat(alter)
        alter < 0 -> "♭".repeat(-alter)
        else -> ""
    }

    fun transpose(interval: Interval): Note {
        val newLetter = Math.floorMod(letter + interval.letterSteps, 7)
        // How far the letter moved on its own, in semitones, ignoring octaves.
        val naturalMove = Math.floorMod(LETTER_SEMITONES[newLetter] - LETTER_SEMITONES[letter], 12)
        val wantedMove = Math.floorMod(interval.semitones, 12)
        // The alteration absorbs whatever the letter movement did not account for.
        val delta = ((wantedMove - naturalMove + 18) % 12) - 6
        return Note(newLetter, alter + delta)
    }

    companion object {
        /** Semitone offset of each natural letter above C. */
        val LETTER_SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val LETTER_NAMES = arrayOf("C", "D", "E", "F", "G", "A", "B")

        fun accidentalText(alter: Int): String = when {
            alter > 0 -> "#".repeat(alter)
            alter < 0 -> "b".repeat(-alter)
            else -> ""
        }

        fun letterIndex(c: Char): Int = LETTER_NAMES.indexOf(c.uppercaseChar().toString())

        /**
         * Parses a note head: a letter, then any run of sharps or flats in either
         * ASCII or Unicode. Returns null if [text] does not start with a letter
         * A-G, so callers can use it as a cheap "is this a chord?" test.
         */
        fun parse(text: String): ParsedNote? {
            if (text.isEmpty()) return null
            val letter = letterIndex(text[0])
            if (letter < 0) return null
            var i = 1
            var alter = 0
            while (i < text.length) {
                when (text[i]) {
                    '#', '♯' -> alter++
                    'b', '♭' -> alter--
                    // Anything else starts the chord quality, not the note head.
                    else -> break
                }
                i++
                if (alter !in -3..3) return null
            }
            return ParsedNote(Note(letter, alter), i)
        }
    }
}

/** A parsed note plus how many characters it consumed. */
data class ParsedNote(val note: Note, val length: Int)

/**
 * A directed interval, as a pair of "how many letter names" and "how many
 * semitones". Both are needed; see the note on [Note] for why.
 */
data class Interval(val letterSteps: Int, val semitones: Int) {

    operator fun unaryMinus(): Interval = Interval(-letterSteps, -semitones)

    companion object {
        /**
         * The interval that takes [from] to [to], spelled exactly as those two
         * notes imply. Always the ascending form, within one octave.
         */
        fun between(from: Note, to: Note): Interval = Interval(
            letterSteps = Math.floorMod(to.letter - from.letter, 7),
            semitones = Math.floorMod(to.pitchClass - from.pitchClass, 12),
        )
    }
}
