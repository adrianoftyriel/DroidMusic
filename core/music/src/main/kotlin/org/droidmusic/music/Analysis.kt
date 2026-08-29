package org.droidmusic.music

/**
 * What the app can tell a player about a chart without being asked: what key it
 * is in, what it is made of, and where a capo would make it easier.
 */
data class ChartAnalysis(
    val format: ChartFormat,
    val declaredKey: Key?,
    val detectedKey: KeyEstimate?,
    /** Declared if the file said, detected otherwise. */
    val effectiveKey: Key?,
    val chordCount: Int,
    val distinctChords: List<ChordUse>,
    val hasTab: Boolean,
    val hasLyrics: Boolean,
    val lineCount: Int,
    val capoSuggestions: List<CapoSuggestion>,
    /** Chords outside the effective key, which are usually the interesting ones. */
    val nonDiatonic: List<Chord>,
)

data class ChordUse(val chord: Chord, val count: Int)

/**
 * "Put a capo on [fret] and play in [playedKey] instead."
 *
 * [openShapeCount] is how many of the chart's chords become one of the shapes a
 * guitarist can play open, which is the whole reason to reach for a capo.
 */
data class CapoSuggestion(
    val fret: Int,
    val playedKey: Key,
    val openShapeCount: Int,
    val totalChords: Int,
)

object ChartAnalyzer {

    /**
     * The keys whose chords are all open shapes on a guitar in standard tuning.
     * This is the practical, unglamorous heart of the capo suggestion: a chart in
     * E flat is hard, the same chart with a capo on 1 in D is easy, and the app
     * can just say so.
     */
    private val EASY_KEYS = listOf("G", "C", "D", "A", "E", "Em", "Am", "Dm", "Bm")

    /**
     * The chords a guitarist plays without barring. Deliberately short: F, B and
     * Bm are barre chords, and counting them as open would let the analyser
     * recommend a capo position that makes nothing easier.
     */
    private val OPEN_SHAPE_ROOTS = setOf("C", "D", "E", "G", "A", "Am", "Dm", "Em")

    fun analyze(song: Song): ChartAnalysis {
        val chords = song.chords()
        val detected = KeyDetector.detect(chords)
        val effective = song.meta.key ?: detected?.key

        val distinct = chords.groupingBy { it.toString() }.eachCount()
            .entries
            .mapNotNull { (text, count) -> Chord.parse(text)?.let { ChordUse(it, count) } }
            .sortedByDescending { it.count }

        val nonDiatonic = if (effective == null) {
            emptyList()
        } else {
            distinct.map { it.chord }.filter { !isDiatonic(it, effective) }
        }

        return ChartAnalysis(
            format = song.meta.format,
            declaredKey = song.meta.key,
            detectedKey = detected,
            effectiveKey = effective,
            chordCount = chords.size,
            distinctChords = distinct,
            hasTab = song.hasTab(),
            hasLyrics = song.lines.any { it is Line.Lyric && it.plainText.isNotBlank() },
            lineCount = song.lines.size,
            capoSuggestions = if (effective == null) emptyList() else suggestCapos(effective, distinct),
            nonDiatonic = nonDiatonic,
        )
    }

    /**
     * Every capo position from 1 to 7 that lands the chart in an easier key,
     * best first: most open shapes wins, and where two positions tie the lower
     * fret does, since a capo high on the neck costs range and tone for nothing.
     * Stops at 7 because past that there is not much neck left.
     */
    fun suggestCapos(soundingKey: Key, chords: List<ChordUse>): List<CapoSuggestion> {
        val total = chords.sumOf { it.count }
        if (total == 0) return emptyList()

        return (1..7).mapNotNull { fret ->
            val played = Key.bestSpelling(soundingKey, -fret, preferSharps = false)
            if (played.toString() !in EASY_KEYS) return@mapNotNull null
            val interval = Interval.between(soundingKey.tonic, played.tonic)
            val open = chords.sumOf { use ->
                val moved = use.chord.transpose(interval)
                if (isOpenShape(moved)) use.count else 0
            }
            CapoSuggestion(fret, played, open, total)
        }
            .filter { it.openShapeCount > 0 }
            .sortedWith(compareByDescending<CapoSuggestion> { it.openShapeCount }.thenBy { it.fret })
    }

    /**
     * Whether a chord belongs to the key, judged on quality as well as root.
     *
     * Root alone is not enough, and the case that proves it is the secondary
     * dominant: in C major, E7 has a root that is perfectly diatonic and a
     * quality that is not - the third degree should be minor. E7 is the single
     * most interesting chord in that progression and a root-only test misses it.
     */
    fun isDiatonic(chord: Chord, key: Key): Boolean {
        val degree = key.scalePitchClasses().indexOf(chord.root.pitchClass)
        if (degree < 0) return false
        val expected =
            if (key.mode == Mode.MAJOR) KeyDetector.MAJOR_QUALITIES else KeyDetector.MINOR_QUALITIES
        if (KeyDetector.triadOf(chord) == expected[degree]) return true
        // The raised dominant in a minor key is diatonic in practice, whatever
        // the natural minor scale says.
        return key.mode == Mode.MINOR && degree == 4 &&
            KeyDetector.triadOf(chord) == KeyDetector.Triad.MAJOR
    }

    /** Whether the chord has a shape playable without barring, roughly. */
    fun isOpenShape(chord: Chord): Boolean {
        val head = chord.root.toString() + if (chord.isMinor) "m" else ""
        return head in OPEN_SHAPE_ROOTS && chord.root.alter == 0
    }
}
