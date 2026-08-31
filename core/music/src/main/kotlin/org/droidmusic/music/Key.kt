package org.droidmusic.music

enum class Mode { MAJOR, MINOR }

/**
 * A key, which for our purposes is a tonic and a mode. The useful thing a key
 * knows is its position on the circle of fifths, because that single number
 * decides how a transposed chart should be spelled.
 */
data class Key(val tonic: Note, val mode: Mode) {

    /**
     * Signed count of accidentals in the key signature: positive is sharps,
     * negative is flats. A major is 3, E flat major is -3, A minor is 0.
     */
    val fifths: Int
        get() = fifthsOf(tonic) + if (mode == Mode.MINOR) -3 else 0

    /** How many accidentals the signature needs, regardless of which kind. */
    val accidentalCount: Int get() = kotlin.math.abs(fifths)

    /**
     * Keys past seven accidentals exist on paper - G sharp major has eight, one
     * of them a double sharp - but nobody writes charts in them, so the
     * transposer treats them as unavailable and picks the enharmonic instead.
     */
    val isPractical: Boolean get() = accidentalCount <= 7

    val pitchClass: Int get() = tonic.pitchClass

    override fun toString(): String = tonic.toString() + if (mode == Mode.MINOR) "m" else ""

    fun toUnicode(): String = tonic.toUnicode() + if (mode == Mode.MINOR) "m" else ""

    /** Long form, for headers: "E♭ major", "F♯ minor". */
    fun display(): String = tonic.toUnicode() + if (mode == Mode.MINOR) " minor" else " major"

    fun transpose(interval: Interval): Key = Key(tonic.transpose(interval), mode)

    /**
     * The key this one becomes [semitones] away, spelled the way [Transposer]
     * will actually spell it.
     *
     * The spelling preference is taken from this key rather than left at the
     * default, because that is what the transposer does: a chart in E goes up a
     * semitone to F, and a chart in Eb goes up to E, and picking the other
     * spelling in either case gives a key with more accidentals than the music
     * needs. Anything showing a player which key they are choosing has to agree
     * with the key they will get.
     */
    fun transposedTo(semitones: Int): Key = bestSpelling(this, semitones, fifths > 0)

    /** The scale degrees of this key as pitch classes, tonic first. */
    fun scalePitchClasses(): IntArray {
        val steps = if (mode == Mode.MAJOR) MAJOR_STEPS else MINOR_STEPS
        return IntArray(7) { Math.floorMod(pitchClass + steps[it], 12) }
    }

    companion object {
        val MAJOR_STEPS = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val MINOR_STEPS = intArrayOf(0, 2, 3, 5, 7, 8, 10)

        /** Circle-of-fifths position of each natural letter. */
        private val LETTER_FIFTHS = intArrayOf(0, 2, 4, -1, 1, 3, 5) // C D E F G A B

        fun fifthsOf(note: Note): Int = LETTER_FIFTHS[note.letter] + 7 * note.alter

        /**
         * Folds a transposition onto the twelve keys that exist, as -5..+6.
         *
         * There are twelve distinct keys, so an offer of -6..+6 is thirteen
         * choices for twelve destinations and two of them are the same place: a
         * tritone up and a tritone down are the same key. Offering both means one
         * of them can be picked and the other lights up, which looks like a bug
         * because it is one. Twelve is the honest number, and +6 is the one kept
         * because "up a tritone" is how the interval is spoken.
         *
         * Also what keeps a player who taps up repeatedly from ending at +14,
         * which is a real key written as a number nothing will display.
         */
        fun foldSemitones(semitones: Int): Int = ((semitones + 5).mod(12)) - 5

        fun parse(text: String): Key? {
            val s = text.trim()
            if (s.isEmpty()) return null
            val parsed = Note.parse(s) ?: return null
            val rest = s.substring(parsed.length).trim().lowercase()
            val mode = when {
                rest.isEmpty() -> Mode.MAJOR
                rest == "m" || rest.startsWith("min") -> Mode.MINOR
                rest.startsWith("maj") -> Mode.MAJOR
                else -> return null
            }
            return Key(parsed.note, mode)
        }

        /**
         * The best-spelled key a given number of semitones from [from].
         *
         * "Best" means the fewest accidentals in the signature, which is the rule
         * an arranger would apply by hand: three semitones up from C is E flat
         * major (3 flats), not D sharp major (9 sharps, and unwritable). Where two
         * spellings tie - F sharp against G flat, both 6 - [preferSharps] breaks
         * it, defaulting to the flat side because flat keys are the commoner
         * choice for the horn and vocal charts this is mostly used on.
         */
        fun bestSpelling(from: Key, semitones: Int, preferSharps: Boolean = false): Key {
            val target = Math.floorMod(from.pitchClass + semitones, 12)
            val candidates = enumerateSpellings(target).map { Key(it, from.mode) }
            val practical = candidates.filter { it.isPractical }.ifEmpty { candidates }
            val best = practical.minOf { it.accidentalCount }
            val tied = practical.filter { it.accidentalCount == best }
            return tied.firstOrNull { (it.fifths > 0) == preferSharps }
                ?: tied.minByOrNull { if (preferSharps) -it.fifths else it.fifths }
                ?: tied.first()
        }

        /** Every sensible way to spell a pitch class, from double flat to double sharp. */
        fun enumerateSpellings(pitchClass: Int): List<Note> = Note.spellingsOf(pitchClass)
    }
}
