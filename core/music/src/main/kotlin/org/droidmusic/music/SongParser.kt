package org.droidmusic.music

/**
 * Turns chart text into a [Song].
 *
 * Three notations turn up in the wild and the app is expected to open all of
 * them without being told which is which:
 *
 *  - ChordPro, with `{directives}` and inline `[C]hords`.
 *  - Chords over lyrics, the plain-text format where a line of chords sits above
 *    the line it belongs to, aligned by column.
 *  - Plain text with no chords at all, including ASCII tablature.
 *
 * Sniffing between them is cheap and reliable, so the app never asks.
 */
object SongParser {

    fun parse(text: String): Song = when (detectFormat(text)) {
        ChartFormat.CHORDPRO -> ChordProParser.parse(text)
        else -> ChordsOverLyricsParser.parse(text)
    }

    /**
     * ChordPro is identified by its own syntax rather than by file extension,
     * because plenty of ChordPro lives in files called `.txt`.
     *
     * A recognised directive is proof on its own - no other format uses braces
     * on a line of their own. An inline bracket is weaker evidence, since a
     * chords-over-lyrics chart may have `[Chorus]` as a section heading, so a
     * bracket only counts when it holds something that is actually a chord or an
     * explicit `[*annotation]`.
     */
    fun detectFormat(text: String): ChartFormat {
        val head = ChordProLex.logicalLines(text).take(400)

        for (line in head) {
            val body = ChordProLex.directiveBody(line) ?: continue
            val name = ChordProLex.directive(body).name
            if (name.isEmpty()) continue
            if (name in ChordProLex.KNOWN_DIRECTIVES ||
                name.startsWith("start_of_") || name.startsWith("end_of_") ||
                name.startsWith("x_")
            ) {
                return ChartFormat.CHORDPRO
            }
        }

        if (head.any { hasInlineChord(it) }) return ChartFormat.CHORDPRO

        val chordLines = head.count { ChordsOverLyricsParser.isChordLine(it) }
        return if (chordLines >= 1) ChartFormat.CHORDS_OVER_LYRICS else ChartFormat.PLAIN_TEXT
    }

    /**
     * Whether a line carries a bracket that means what ChordPro means by one.
     *
     * The bracket has to hold a real chord, or be an explicit annotation. A
     * heading like `[Chorus]` on its own line does not count - reading that as
     * ChordPro is how a whole chart's chords get lost, because the chords in a
     * chords-over-lyrics file are on the line *above* the words and the ChordPro
     * parser would find none of them.
     */
    private fun hasInlineChord(line: String): Boolean {
        var i = 0
        while (i < line.length) {
            if (line[i] == '[') {
                val close = line.indexOf(']', i + 1)
                if (close > i) {
                    val inner = line.substring(i + 1, close)
                    if (inner.startsWith("*")) return true
                    if (Chord.parse(inner.removeSurrounding("(", ")")) != null) return true
                    i = close + 1
                    continue
                }
            }
            i++
        }
        return false
    }
}
