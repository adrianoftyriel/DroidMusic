package org.droidmusic.music

data class KeyEstimate(
    val key: Key,
    /** 0.0 to 1.0. Below about 0.5 the answer is a guess worth showing as one. */
    val confidence: Double,
    val runnerUp: Key? = null,
)

/**
 * Works out what key a chart is in from the chords it uses.
 *
 * This is a scoring problem rather than a solved one - plenty of songs are
 * genuinely ambiguous, and a chart that is all C, F and G is equally at home in
 * C major and, less likely but not impossibly, F major. So the detector scores
 * all 24 candidates and reports how far ahead the winner was, and the UI shows a
 * low-confidence answer as a suggestion the user can override rather than as a
 * fact.
 *
 * The signals, in rough order of how much work they do:
 *
 *  - Chords that are diatonic to the candidate key, with a bonus when the chord
 *    quality is the one that degree should have. A major chord on the fourth
 *    degree is strong evidence; a major chord on the third is evidence against.
 *  - The last chord of the chart, which lands on the tonic far more often than
 *    chance, and the first, which does so somewhat more often.
 *  - The presence of the dominant, especially as a seventh, which is the single
 *    most key-defining chord there is.
 *  - A small preference for simpler key signatures, purely to break ties the way
 *    a human would.
 */
object KeyDetector {

    internal val MAJOR_QUALITIES = arrayOf(
        Triad.MAJOR, Triad.MINOR, Triad.MINOR, Triad.MAJOR,
        Triad.MAJOR, Triad.MINOR, Triad.DIMINISHED,
    )
    internal val MINOR_QUALITIES = arrayOf(
        Triad.MINOR, Triad.DIMINISHED, Triad.MAJOR, Triad.MINOR,
        Triad.MINOR, Triad.MAJOR, Triad.MAJOR,
    )

    /**
     * Chosen so that a three-point gap - one chord fitting one key and not the
     * other - moves confidence noticeably without a single chord dominating.
     */
    private const val SOFTMAX_TEMPERATURE = 2.0

    internal enum class Triad { MAJOR, MINOR, DIMINISHED }

    internal fun triadOf(chord: Chord): Triad = when {
        chord.isDiminished -> Triad.DIMINISHED
        chord.isMinor -> Triad.MINOR
        else -> Triad.MAJOR
    }

    fun detect(song: Song): KeyEstimate? = detect(song.chords())

    fun detect(chords: List<Chord>): KeyEstimate? {
        if (chords.isEmpty()) return null

        val scored = candidates().map { key -> key to score(key, chords) }
            .sortedByDescending { it.second }

        val (best, bestScore) = scored[0]
        val (runnerUp, runnerScore) = scored.getOrNull(1) ?: (null to 0.0)

        // Confidence is the winner's share of a softmax over all 24 candidates.
        //
        // The obvious alternative - the margin over the runner-up, divided by the
        // winner's own score - looks reasonable and is not, because both terms
        // grow with the length of the chart. An eight-chord chart with an
        // unmistakable key scores it no more confidently than a two-chord chart
        // that could be either of two keys, which is precisely backwards. A
        // softmax has no such scale dependence: what it measures is how far the
        // field is behind, which is the thing being asked about.
        val confidence = if (bestScore <= 0.0) {
            0.0
        } else {
            val mass = scored.sumOf { kotlin.math.exp((it.second - bestScore) / SOFTMAX_TEMPERATURE) }
            (1.0 / mass).coerceIn(0.0, 1.0)
        }
        return KeyEstimate(best, confidence, runnerUp)
    }

    /**
     * The 24 keys, each spelled the way it is normally written.
     *
     * `Mode.values()` rather than `Mode.entries`, and that is not a style
     * preference. `entries` is compiled into a call on the Kotlin enum-entries
     * runtime, which R8 shrank out of the release build - so every chart without
     * a declared key threw NoClassDefFoundError here, in the release APK only,
     * where no test could see it. This runs on the path that opens *every* text
     * chart, because the transposer asks for the key before it can transpose
     * anything, so the whole feature failed on a class nobody meant to depend on.
     *
     * Two enum constants do not need a runtime to iterate. See also the keep
     * rule in app/proguard-rules.pro, which covers the case if this comes back.
     */
    fun candidates(): List<Key> = buildList {
        for (pc in 0..11) {
            for (mode in Mode.values()) {
                val spellings = Key.enumerateSpellings(pc)
                    .map { Key(it, mode) }
                    .filter { it.isPractical }
                add(spellings.minByOrNull { it.accidentalCount } ?: continue)
            }
        }
    }

    private fun score(key: Key, chords: List<Chord>): Double {
        val scale = key.scalePitchClasses()
        val expected = if (key.mode == Mode.MAJOR) MAJOR_QUALITIES else MINOR_QUALITIES
        var total = 0.0

        for (chord in chords) {
            val degree = scale.indexOf(chord.root.pitchClass)
            if (degree < 0) {
                // Chromatic. Common enough not to be fatal, but it is evidence.
                total -= 1.5
                continue
            }
            total += if (triadOf(chord) == expected[degree]) 3.0 else 1.0

            // The dominant, and above all the dominant seventh, points at exactly
            // one key. In minor it is usually raised, so accept a major V there.
            if (degree == 4) {
                total += 1.0
                if (chord.quality.contains("7") && !chord.quality.contains("maj")) total += 1.5
            }
            if (key.mode == Mode.MINOR && degree == 4 && triadOf(chord) == Triad.MAJOR) total += 1.5
        }

        if (chords.first().root.pitchClass == key.pitchClass &&
            triadOf(chords.first()) == expected[0]
        ) {
            total += 2.5
        }
        if (chords.last().root.pitchClass == key.pitchClass &&
            triadOf(chords.last()) == expected[0]
        ) {
            total += 5.0
        }

        // Tie-break towards the simpler signature, at a weight small enough that
        // it never outvotes actual harmonic evidence.
        total -= key.accidentalCount * 0.1
        return total
    }
}
