package org.droidmusic.music

enum class RowKind {
    CHORDS,
    LYRIC,
    CHORD_AND_LYRIC,
    COMMENT,
    TAB,
    GRID,
    HEADER,
    BREAK,

    /** The song's title, drawn once at the head of the first page. */
    TITLE,

    /** Artist and key, under the title. See [ChartLayout.titleRows]. */
    CREDIT,

    BLANK,
}

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
    val height: Int get() = when (kind) {
        // The title is drawn larger than the body, so it costs two lines of the
        // page budget rather than one. Getting this wrong would let the last
        // line of the first page fall off the bottom of the screen.
        RowKind.CHORD_AND_LYRIC, RowKind.TITLE -> 2
        RowKind.BREAK -> 0
        else -> 1
    }

    /**
     * Longest line in the row, for working out how wide the chart needs to be.
     *
     * The title block is excluded: it is drawn in a proportional face at its own
     * size, so its character count says nothing about how many monospaced
     * columns the music needs, and a long title would otherwise widen the
     * horizontal scroll of every page for no reason.
     */
    val width: Int get() = when (kind) {
        RowKind.TITLE, RowKind.CREDIT -> 0
        else -> maxOf(chordText.length, text.length)
    }
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
        titleRows(song, unicodeAccidentals) + song.lines.mapNotNull { line ->
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

                // A `{chorus}` says "play the chorus here". Drawn like a comment
                // because that is what it is to somebody reading off a stand -
                // an instruction, not a line to sing.
                is Line.ChorusRecall -> ChartRow(RowKind.COMMENT, text = line.label ?: "Chorus")

                is Line.Tab -> ChartRow(RowKind.TAB, text = line.text)
                is Line.Grid -> ChartRow(RowKind.GRID, text = line.plainText)
                is Line.SectionHeader -> ChartRow(RowKind.HEADER, text = line.displayLabel)
                is Line.Break -> ChartRow(RowKind.BREAK)

                // A section end draws nothing, so it gets no row at all. Giving
                // it an empty one would open a gap in the middle of the chart
                // wherever a verse finished.
                is Line.SectionEnd -> null

                Line.Blank -> ChartRow(RowKind.BLANK)
            }
        }

    /**
     * The title, artist and key, as rows at the head of the chart.
     *
     * They are rows rather than a banner drawn by the viewer, which is what puts
     * them on the first page and only the first page: pagination pushes the
     * music down by exactly the space they take, and page two starts with music
     * rather than with the title again.
     *
     * Only for ChordPro, because ChordPro is the only format that *declares*
     * this. A chart in chords-over-lyrics has its title in the text already, at
     * the top of the file where its author put it, and printing it a second time
     * above itself would be a bug wearing a feature's clothes.
     *
     * The key shown is the key the chart is currently *in*, not the key the file
     * was written in - these rows are built from the transposed song, so a
     * player who moves a chart to B flat sees B flat.
     */
    fun titleRows(song: Song, unicodeAccidentals: Boolean = true): List<ChartRow> {
        if (song.meta.format != ChartFormat.CHORDPRO) return emptyList()

        val title = song.meta.title?.trim()?.takeIf { it.isNotEmpty() }
        val credit = creditLine(song.meta, unicodeAccidentals)
        if (title == null && credit == null) return emptyList()

        return buildList {
            if (title != null) add(ChartRow(RowKind.TITLE, text = title))
            if (credit != null) add(ChartRow(RowKind.CREDIT, text = credit))
            // Air between the heading and the first line. A trailing blank is
            // dropped by [paginate] if the break happens to fall here, so this
            // cannot leave a gap at the top of page two.
            add(ChartRow(RowKind.BLANK))
        }
    }

    /** "Bob Dylan - Key of G - capo 3", with whichever parts the file knows. */
    private fun creditLine(meta: SongMeta, unicodeAccidentals: Boolean): String? {
        val artist = meta.artist?.trim()?.takeIf { it.isNotEmpty() }
        // A subtitle is where plenty of files put the artist, so it is shown -
        // unless it is saying the same thing the artist field already said.
        val subtitle = meta.subtitle?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(artist, ignoreCase = true) }
        val key = meta.key?.let { key ->
            val tonic = if (unicodeAccidentals) key.tonic.toUnicode() else key.tonic.toString()
            "Key of $tonic" + if (key.mode == Mode.MINOR) " minor" else ""
        }
        val capo = if (meta.capo > 0) "capo ${meta.capo}" else null

        val parts = listOfNotNull(artist, subtitle, key, capo)
        return if (parts.isEmpty()) null else parts.joinToString("   -   ")
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

            // A chart that asked for a page break gets one. The chart's author
            // knows something the arithmetic does not - that this is where the
            // player has a free hand to turn.
            if (row.kind == RowKind.BREAK) {
                index++
                if (used > 0) flush()
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
