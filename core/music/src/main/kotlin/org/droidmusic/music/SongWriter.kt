package org.droidmusic.music

/**
 * Renders a [Song] back out as text.
 *
 * Needed for two things: exporting a transposed chart so it can be shared or
 * printed, and drawing the chart on screen, since the viewer lays out the same
 * two-row chord-over-lyric shape the text form describes.
 */
object SongWriter {

    /** Canonical ChordPro. Round-trips through [ChordProParser]. */
    fun toChordPro(song: Song, unicodeAccidentals: Boolean = false): String = buildString {
        val m = song.meta
        m.title?.let { appendLine("{title: $it}") }
        m.subtitle?.let { appendLine("{subtitle: $it}") }
        m.artist?.let { appendLine("{artist: $it}") }
        m.key?.let { appendLine("{key: $it}") }
        if (m.capo != 0) appendLine("{capo: ${m.capo}}")
        m.tempo?.let { appendLine("{tempo: $it}") }
        m.time?.let { appendLine("{time: $it}") }
        for ((k, v) in m.extra) appendLine("{$k: $v}")
        if (length > 0) appendLine()

        var inTab = false
        for (line in song.lines) {
            if (inTab && line !is Line.Tab) {
                appendLine("{end_of_tab}")
                inTab = false
            }
            when (line) {
                is Line.Lyric -> appendLine(
                    line.segments.joinToString("") { seg ->
                        val chord = seg.chord?.let { c ->
                            "[" + (if (unicodeAccidentals) c.toUnicode() else c.toString()) + "]"
                        } ?: ""
                        chord + seg.text
                    },
                )

                is Line.Comment -> appendLine("{comment: ${line.text}}")

                is Line.SectionHeader -> when (line.kind) {
                    SectionKind.CHORUS -> appendLine("{start_of_chorus: ${line.label}}")
                    SectionKind.VERSE -> appendLine("{start_of_verse: ${line.label}}")
                    SectionKind.BRIDGE -> appendLine("{start_of_bridge: ${line.label}}")
                    SectionKind.GRID -> appendLine("{start_of_grid: ${line.label}}")
                    SectionKind.TAB -> {
                        appendLine("{start_of_tab: ${line.label}}")
                        inTab = true
                    }
                    SectionKind.OTHER -> appendLine("{comment: ${line.label}}")
                }

                is Line.Tab -> {
                    if (!inTab) {
                        appendLine("{start_of_tab}")
                        inTab = true
                    }
                    appendLine(line.text)
                }

                Line.Blank -> appendLine()
            }
        }
        if (inTab) appendLine("{end_of_tab}")
    }

    /**
     * The two-row form: a line of chords above a line of words.
     *
     * The interesting part is what happens when transposition changes a chord's
     * width - C becoming D flat, or B flat becoming B. Left alone, every chord
     * after it on the line slides out of position and stops sitting over its
     * syllable. Rather than let the chords drift, this pads the *lyric* to make
     * room, so a chord always starts exactly above the syllable it belongs to and
     * the words open up a little where a wide chord needed the space.
     */
    fun toChordsOverLyrics(song: Song, unicodeAccidentals: Boolean = false): String = buildString {
        for (line in song.lines) {
            when (line) {
                is Line.Lyric -> {
                    val (chordRow, lyricRow) = layoutLyricLine(line, unicodeAccidentals)
                    if (chordRow.isNotBlank()) appendLine(chordRow.trimEnd())
                    if (lyricRow.isNotBlank() || chordRow.isBlank()) appendLine(lyricRow.trimEnd())
                }
                is Line.Comment -> appendLine(line.text)
                is Line.Tab -> appendLine(line.text)
                is Line.SectionHeader -> appendLine("[${line.label}]")
                Line.Blank -> appendLine()
            }
        }
    }

    /**
     * Lays one lyric line out as a chord row and a lyric row of equal alignment.
     * Exposed because the on-screen renderer needs exactly the same two strings.
     */
    fun layoutLyricLine(line: Line.Lyric, unicodeAccidentals: Boolean = false): Pair<String, String> {
        val chordRow = StringBuilder()
        val lyricRow = StringBuilder()

        for (seg in line.segments) {
            val chord = seg.chord
            if (chord != null) {
                // Chords need a space between them or they read as one symbol.
                if (chordRow.isNotEmpty() && !chordRow.endsWith(" ")) chordRow.append(' ')
                val column = maxOf(chordRow.length, lyricRow.length)
                padTo(chordRow, column)
                padTo(lyricRow, column)
                chordRow.append(if (unicodeAccidentals) chord.toUnicode() else chord.toString())
            }
            lyricRow.append(seg.text)
        }
        return chordRow.toString() to lyricRow.toString()
    }

    private fun padTo(sb: StringBuilder, column: Int) {
        while (sb.length < column) sb.append(' ')
    }
}
