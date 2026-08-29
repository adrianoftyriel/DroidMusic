package org.droidmusic.music

/**
 * Reads the plain-text format where a line of chords sits above the line it
 * belongs to, aligned by column.
 *
 * The whole problem is deciding which lines are chord lines. Get it wrong in one
 * direction and chords are shown as lyrics; get it wrong in the other and the
 * transposer rewrites somebody's words. The rule used here is that *every*
 * whitespace-separated token on the line has to parse as a chord (or as a bar
 * line or similar), which combined with the quality grammar in [Chord] makes a
 * false positive on real prose very unlikely: an English sentence would have to
 * consist entirely of words that are chord symbols.
 */
object ChordsOverLyricsParser {

    fun parse(text: String): Song {
        val raw = text.lines()
        val lines = mutableListOf<Line>()
        var title: String? = null
        var sawChord = false
        var i = 0

        while (i < raw.size) {
            val line = raw[i]

            when {
                isTabLine(line) -> {
                    lines += Line.Tab(line)
                    i++
                }

                isChordLine(line) -> {
                    sawChord = true
                    val next = raw.getOrNull(i + 1)
                    // A chord line pairs with the line below only if that line is
                    // lyrics. Two chord lines in a row are two instrumental bars.
                    if (next != null && next.isNotBlank() &&
                        !isChordLine(next) && !isTabLine(next) && !isSectionHeader(next)
                    ) {
                        lines += Line.Lyric(align(line, next))
                        i += 2
                    } else {
                        lines += Line.Lyric(align(line, ""))
                        i++
                    }
                }

                isSectionHeader(line) -> {
                    lines += Line.SectionHeader(line.trim().trim('[', ']', '(', ')', ':'), sectionKind(line))
                    i++
                }

                line.isBlank() -> {
                    lines += Line.Blank
                    i++
                }

                else -> {
                    // The first non-blank line of a chart with no chords above it
                    // is conventionally the title.
                    if (title == null && lines.none { it is Line.Lyric }) {
                        title = line.trim()
                    }
                    lines += Line.Lyric(listOf(Segment(null, line)))
                    i++
                }
            }
        }

        val format = when {
            sawChord -> ChartFormat.CHORDS_OVER_LYRICS
            else -> ChartFormat.PLAIN_TEXT
        }
        return Song(SongMeta(title = title, format = format), lines)
    }

    /**
     * True when the line consists only of chord symbols and bar-line furniture.
     *
     * Requires at least one real chord, so a line of `| | |` on its own is not
     * mistaken for one.
     */
    fun isChordLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (isTabLine(line)) return false
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        var chords = 0
        for (token in tokens) {
            if (token in Chord.CHORD_LINE_PASSTHROUGH) continue
            // Bar lines often abut the chord: "|C" or "Am|".
            val stripped = token.trim('|', ':')
            if (stripped.isEmpty()) continue
            if (Chord.parse(stripped) == null) return false
            chords++
        }
        return chords > 0
    }

    /**
     * ASCII tablature: a string label and a run of dashes. Checked before chord
     * detection because a tab line like `E|-----------|` would otherwise look
     * like the chord E followed by bar furniture.
     */
    fun isTabLine(line: String): Boolean {
        val dashes = line.count { it == '-' }
        return dashes >= 4 && dashes >= line.length / 4 && line.contains(Regex("[-|]{4,}"))
    }

    private fun isSectionHeader(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > 40) return false
        val inner = t.trim('[', ']', '(', ')').trim().removeSuffix(":").trim()
        if (inner.isEmpty()) return false
        val bracketed = (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("(") && t.endsWith(")"))
        val colon = t.endsWith(":")
        if (!bracketed && !colon) return false
        return SECTION_WORDS.any { inner.lowercase().startsWith(it) }
    }

    private fun sectionKind(line: String): SectionKind {
        val l = line.lowercase()
        return when {
            l.contains("chorus") -> SectionKind.CHORUS
            l.contains("bridge") -> SectionKind.BRIDGE
            l.contains("verse") -> SectionKind.VERSE
            l.contains("tab") -> SectionKind.TAB
            else -> SectionKind.OTHER
        }
    }

    private val SECTION_WORDS = listOf(
        "verse", "chorus", "bridge", "intro", "outro", "solo", "instrumental",
        "pre-chorus", "prechorus", "refrain", "interlude", "coda", "tag", "vamp",
        "ending", "break", "hook",
    )

    /**
     * Pairs a chord line with its lyric line by column position.
     *
     * The chord at column *n* belongs to whatever syllable begins at column *n*,
     * so each segment runs from one chord's column to the next one's. Where the
     * lyric line runs out before the chords do - a chord hanging past the end of
     * the words - the remaining segments get the spacing from the chord line
     * instead, which is what keeps an instrumental bar looking like bars.
     */
    internal fun align(chordLine: String, lyricLine: String): List<Segment> {
        val placements = chordPlacements(chordLine)
        if (placements.isEmpty()) return listOf(Segment(null, lyricLine))

        val segments = mutableListOf<Segment>()
        val firstColumn = placements.first().column
        if (firstColumn > 0) {
            segments += Segment(null, lyricLine.take(firstColumn))
        }

        for ((index, placement) in placements.withIndex()) {
            val start = placement.column
            val end = placements.getOrNull(index + 1)?.column ?: Int.MAX_VALUE
            val text = when {
                start >= lyricLine.length -> {
                    // Past the end of the lyric: fall back to the chord line's own
                    // spacing so the horizontal rhythm is preserved.
                    val stop = minOf(end, chordLine.length)
                    if (stop > start) chordLine.substring(start, stop).drop(placement.text.length) else ""
                }
                else -> lyricLine.substring(start, minOf(end, lyricLine.length))
            }
            segments += Segment(placement.chord, text)
        }
        return segments
    }

    private data class Placement(val column: Int, val text: String, val chord: Chord)

    /** Finds each chord on a chord line together with the column it starts in. */
    private fun chordPlacements(chordLine: String): List<Placement> {
        val out = mutableListOf<Placement>()
        var i = 0
        while (i < chordLine.length) {
            if (chordLine[i].isWhitespace()) { i++; continue }
            var j = i
            while (j < chordLine.length && !chordLine[j].isWhitespace()) j++
            val token = chordLine.substring(i, j)
            val stripped = token.trim('|', ':')
            val chord = if (stripped.isEmpty()) null else Chord.parse(stripped)
            if (chord != null) {
                val offset = token.indexOf(stripped)
                out += Placement(i + offset, stripped, chord)
            }
            i = j
        }
        return out
    }
}
