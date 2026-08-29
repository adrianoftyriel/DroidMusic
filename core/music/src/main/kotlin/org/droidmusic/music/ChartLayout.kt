package org.droidmusic.music

enum class RowKind { CHORDS, LYRIC, CHORD_AND_LYRIC, COMMENT, TAB, HEADER, BLANK }

/**
 * One row of the laid-out chart.
 *
 * A lyric line with chords over it is a single row that draws two lines of text,
 * not two rows, because a page break must never fall between a chord and the
 * word it sits above.
 */
data class ChartRow(
    val kind: RowKind,
    val chordText: String = "",
    val text: String = "",
) {
    /** How many lines of text this row draws. */
    val height: Int get() = if (kind == RowKind.CHORD_AND_LYRIC) 2 else 1

    /** Longest line in the row, for working out how wide the chart needs to be. */
    val width: Int get() = maxOf(chordText.length, text.length)
}

/**
 * Turns a parsed song into rows, and rows into pages.
 *
 * Kept here rather than in the renderer because it is arithmetic, and arithmetic
 * that decides where page breaks land is worth testing without a device
 * attached. The renderer's job is reduced to drawing a list of strings.
 */
object ChartLayout {

    fun rows(song: Song, unicodeAccidentals: Boolean = true): List<ChartRow> =
        song.lines.map { line ->
            when (line) {
                is Line.Lyric -> {
                    val (chordRow, lyricRow) = SongWriter.layoutLyricLine(line, unicodeAccidentals)
                    val hasChords = chordRow.isNotBlank()
                    val hasWords = lyricRow.isNotBlank()
                    when {
                        hasChords && hasWords ->
                            ChartRow(RowKind.CHORD_AND_LYRIC, chordRow.trimEnd(), lyricRow.trimEnd())
                        hasChords -> ChartRow(RowKind.CHORDS, chordRow.trimEnd())
                        else -> ChartRow(RowKind.LYRIC, text = lyricRow.trimEnd())
                    }
                }

                is Line.Comment -> ChartRow(RowKind.COMMENT, text = line.text)
                is Line.Tab -> ChartRow(RowKind.TAB, text = line.text)
                is Line.SectionHeader -> ChartRow(RowKind.HEADER, text = line.label)
                Line.Blank -> ChartRow(RowKind.BLANK)
            }
        }

    /** The width, in characters, the widest row needs. */
    fun widestRow(rows: List<ChartRow>): Int = rows.maxOfOrNull { it.width } ?: 0

    /**
     * Splits rows into pages of at most [linesPerPage] lines of text.
     *
     * Two refinements over filling each page to the brim, both of which are the
     * difference between a chart that can be played from and one that cannot:
     *
     *  - A section header is never left alone at the foot of a page. A "Chorus"
     *    with its first line on the next page is worse than useless on stage.
     *  - Trailing blank lines are absorbed into the page break rather than
     *    pushed onto the next page, where they would show as a gap at the top.
     */
    fun paginate(rows: List<ChartRow>, linesPerPage: Int): List<List<ChartRow>> {
        if (rows.isEmpty()) return listOf(emptyList())
        val perPage = linesPerPage.coerceAtLeast(1)

        val pages = mutableListOf<List<ChartRow>>()
        var page = mutableListOf<ChartRow>()
        var used = 0

        fun flush() {
            // Drop blank rows from the end; they only make the next page start low.
            while (page.isNotEmpty() && page.last().kind == RowKind.BLANK) page.removeAt(page.size - 1)
            if (page.isNotEmpty()) pages += page.toList()
            page = mutableListOf()
            used = 0
        }

        var index = 0
        while (index < rows.size) {
            val row = rows[index]

            // A blank line at the top of a page is a gap nobody asked for.
            if (used == 0 && row.kind == RowKind.BLANK) {
                index++
                continue
            }

            if (used + row.height > perPage && used > 0) {
                flush()
                continue
            }

            page += row
            used += row.height
            index++

            // Having just placed a header, check something can follow it here.
            // If not, move the header to the next page along with its first
            // line - but only if the two of them actually fit there. Without
            // that second condition the rule happily builds an over-budget page
            // on a short viewport, which is worse than the orphan it was
            // avoiding: an orphaned heading is untidy, an overflowing page hides
            // a line of the song off the bottom of the screen.
            if (row.kind == RowKind.HEADER) {
                val next = rows.getOrNull(index)
                val orphaned = next != null && next.kind != RowKind.BLANK &&
                    used + next.height > perPage
                val bothFitOnAFreshPage = next != null && row.height + next.height <= perPage
                if (orphaned && bothFitOnAFreshPage && page.size > 1) {
                    page.removeAt(page.size - 1)
                    used -= row.height
                    flush()
                    page += row
                    used += row.height
                    index++
                    page += next!!
                    used += next.height
                }
            }
        }
        flush()

        return pages.ifEmpty { listOf(emptyList()) }
    }

    /**
     * The largest font size whose characters still fit [availableWidthPx].
     *
     * Charts are laid out in a monospaced font so that the column alignment
     * between a chord and its syllable is exact, which means the whole width
     * question reduces to one multiplication.
     */
    fun fitFontSize(
        widestRowChars: Int,
        availableWidthPx: Float,
        charWidthAtOneSp: Float,
        minSp: Float = 8f,
        maxSp: Float = 28f,
    ): Float {
        if (widestRowChars <= 0 || charWidthAtOneSp <= 0f) return maxSp
        val ideal = availableWidthPx / (widestRowChars * charWidthAtOneSp)
        return ideal.coerceIn(minSp, maxSp)
    }
}
