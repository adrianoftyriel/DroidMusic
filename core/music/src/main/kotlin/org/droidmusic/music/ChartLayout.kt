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
    /**
     * True when this row is the tail of a line that was too wide for the screen
     * and had to be wrapped - see [ChartLayout.wrap].
     *
     * Pagination uses it to keep the halves of a wrapped line together, which is
     * the difference between a line that reads on two rows and one whose second
     * half is overleaf.
     */
    val continuation: Boolean = false,
    /**
     * Which line of the [Song] this row came from, or -1 when it came from none
     * of them - the title block, or a row built by hand in a test.
     *
     * This is the chart's durable coordinate. Row indexes are not: wrapping
     * turns one line into a different number of rows at every width, so the row
     * the reader was on has a different index at every font size. The song line
     * does not move, so it is what the viewer anchors to when the chart reflows.
     */
    val sourceLine: Int = -1,
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
 * Turns a parsed song into rows, rows into wrapped rows, and rows into pages.
 *
 * Kept here rather than in the renderer because it is arithmetic, and arithmetic
 * that decides where page breaks and line breaks land is worth testing without a
 * device attached. The renderer's job is reduced to drawing a list of strings.
 */
object ChartLayout {

    /** Never wrap to fewer columns than this; below it the result is confetti. */
    const val MIN_COLUMNS = 12

    /** Never paginate to fewer lines than this, however small the viewport. */
    const val MIN_LINES = 4

    fun rows(song: Song, unicodeAccidentals: Boolean = true): List<ChartRow> =
        titleRows(song, unicodeAccidentals) + song.lines.mapIndexedNotNull { index, line ->
            when (line) {
                is Line.Lyric -> {
                    val (chordRow, lyricRow) = SongWriter.layoutLyricLine(line, unicodeAccidentals)
                    val hasChords = chordRow.isNotBlank()
                    val hasWords = lyricRow.isNotBlank()
                    when {
                        hasChords && hasWords -> ChartRow(
                            kind = RowKind.CHORD_AND_LYRIC,
                            chordText = chordRow.trimEnd(),
                            text = lyricRow.trimEnd(),
                            sourceLine = index,
                        )
                        hasChords -> ChartRow(
                            kind = RowKind.CHORDS,
                            chordText = chordRow.trimEnd(),
                            sourceLine = index,
                        )
                        else -> ChartRow(
                            kind = RowKind.LYRIC,
                            text = lyricRow.trimEnd(),
                            sourceLine = index,
                        )
                    }
                }

                is Line.Comment -> ChartRow(RowKind.COMMENT, text = line.text, sourceLine = index)

                // A `{chorus}` says "play the chorus here". Drawn like a comment
                // because that is what it is to somebody reading off a stand -
                // an instruction, not a line to sing.
                is Line.ChorusRecall ->
                    ChartRow(RowKind.COMMENT, text = line.label ?: "Chorus", sourceLine = index)

                is Line.Tab -> ChartRow(RowKind.TAB, text = line.text, sourceLine = index)
                is Line.Grid ->
                    ChartRow(RowKind.GRID, text = line.plainText, sourceLine = index)
                is Line.SectionHeader ->
                    ChartRow(RowKind.HEADER, text = line.displayLabel, sourceLine = index)
                is Line.Break -> ChartRow(RowKind.BREAK, sourceLine = index)

                // A section end draws nothing, so it gets no row at all. Giving
                // it an empty one would open a gap in the middle of the chart
                // wherever a verse finished.
                is Line.SectionEnd -> null

                Line.Blank -> ChartRow(RowKind.BLANK, sourceLine = index)
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
     * Wraps rows so that nothing is wider than [columns] characters.
     *
     * **Why wrapping a chord chart is normally wrong, and why this is not.** The
     * naive version wraps the lyric and leaves the chord row alone, at which
     * point every chord after the break sits over the wrong syllable - which is
     * worse than useless, because it is wrong in a way the player will believe.
     * So both rows are cut at the *same character column* and the same number of
     * leading spaces is then removed from both. Whatever alignment the two rows
     * had, they still have: a chord that was above its syllable is above its
     * syllable on the continuation row too.
     *
     * **Where the cut is allowed to fall.** Only at a column that is a space in
     * the lyric row *and* a space in the chord row. The first condition is the
     * ordinary one - do not split a word. The second is what protects the music:
     * a column with no chord character in it cannot be the middle of a chord
     * symbol, so `Am7` is never left as `Am` on one row and `7` on the next.
     * When no such column exists - one word longer than the screen is wide, or a
     * chord row packed solid - the line is cut at the margin anyway, because a
     * hard break is still readable and running off the edge of the screen is not.
     *
     * **What is never wrapped.** Tab and grid. The column positions inside
     * tablature and the cells of a grille *are* the notation; reflowing either
     * would destroy it. Both stay whole and scroll sideways instead. Neither is
     * the title block, which is drawn in a proportional face at its own size and
     * reports no width at all.
     */
    fun wrap(rows: List<ChartRow>, columns: Int): List<ChartRow> {
        if (columns <= 0 || rows.none { it.width > columns }) return rows
        return rows.flatMap { wrapRow(it, columns) }
    }

    /** [wrap] for a single row: one row in, one or more out. */
    fun wrapRow(row: ChartRow, columns: Int): List<ChartRow> {
        if (columns <= 0 || row.width <= columns) return listOf(row)
        if (row.kind == RowKind.TAB || row.kind == RowKind.GRID) return listOf(row)

        val pieces = wrapPair(row.chordText, row.text, columns)
        return pieces.mapIndexed { index, piece ->
            val (chords, words) = piece
            row.copy(
                kind = kindOf(row.kind, chords, words),
                chordText = chords,
                text = words,
                continuation = row.continuation || index > 0,
            )
        }
    }

    /**
     * The kind a wrapped fragment should be drawn as.
     *
     * A fragment of a chord-and-lyric line that happens to carry no chords is a
     * lyric row, not a chord-and-lyric row with an empty line above it. Getting
     * this wrong does not look wrong so much as *waste a line of the screen* on
     * every wrapped row, which on a phone is a page.
     */
    private fun kindOf(kind: RowKind, chords: String, words: String): RowKind =
        if (kind != RowKind.CHORD_AND_LYRIC && kind != RowKind.CHORDS && kind != RowKind.LYRIC) {
            kind
        } else {
            when {
                chords.isNotBlank() && words.isNotBlank() -> RowKind.CHORD_AND_LYRIC
                chords.isNotBlank() -> RowKind.CHORDS
                words.isNotBlank() -> RowKind.LYRIC
                else -> kind
            }
        }

    private fun wrapPair(
        chordText: String,
        text: String,
        columns: Int,
    ): List<Pair<String, String>> {
        val pieces = mutableListOf<Pair<String, String>>()
        var chords = chordText
        var words = text
        while (true) {
            if (maxOf(chords.length, words.length) <= columns) {
                pieces += chords.trimEnd() to words.trimEnd()
                return pieces
            }
            // At least one column is always taken, so this terminates.
            val at = breakColumn(chords, words, columns)
            pieces += chords.take(at).trimEnd() to words.take(at).trimEnd()

            val chordRest = chords.drop(at)
            val wordRest = words.drop(at)
            // The same number of columns off both rows, or the chords move
            // relative to the words - which is the whole thing this must not do.
            val indent = sharedIndent(chordRest, wordRest)
            chords = chordRest.drop(indent)
            words = wordRest.drop(indent)
        }
    }

    private fun breakColumn(chords: String, words: String, columns: Int): Int {
        for (column in columns downTo 1) {
            if (breakable(words, column) && breakable(chords, column)) return column
        }
        return columns
    }

    /** A column is breakable when nothing is printed at it. */
    private fun breakable(row: String, column: Int): Boolean =
        column >= row.length || row[column] == ' '

    /**
     * How much whitespace can come off the front of both rows at once.
     *
     * A row that is entirely blank does not get a say - it would always answer
     * "all of it" and would drag the other row's first character off the line.
     */
    private fun sharedIndent(chords: String, words: String): Int {
        val chordIndent = if (chords.isBlank()) null else chords.indexOfFirst { it != ' ' }
        val wordIndent = if (words.isBlank()) null else words.indexOfFirst { it != ' ' }
        return listOfNotNull(chordIndent, wordIndent).minOrNull() ?: 0
    }

    /**
     * Splits rows into pages of at most [linesPerPage] lines of text.
     *
     * Three refinements over filling each page to the brim, all of which are the
     * difference between a chart that can be played from and one that cannot:
     *
     *  - A section header is never left alone at the foot of a page. A "Chorus"
     *    with its first line on the next page is worse than useless on stage.
     *  - A wrapped line is not split across the break. Half a line of lyric with
     *    the rest of it overleaf is the one thing wrapping must not introduce.
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
                // The row that does not fit is the continuation of a line whose
                // earlier half is already on this page: move the whole line to
                // the next page rather than break it in two. Only when the line
                // will actually fit there, and only when something else is left
                // behind - otherwise this would loop, or produce an over-budget
                // page, both of which are worse than the split.
                val group = if (row.continuation) {
                    trailingRowsOfSameLine(page, row.sourceLine)
                } else {
                    emptyList()
                }
                val groupHeight = group.sumOf { it.height }
                if (
                    group.isNotEmpty() &&
                    group.size < page.size &&
                    groupHeight + row.height <= perPage
                ) {
                    repeat(group.size) { page.removeAt(page.size - 1) }
                    used -= groupHeight
                    flush()
                    page.addAll(group)
                    used += groupHeight
                    continue
                }
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
     * The run of rows at the end of [page] that came from song line [sourceLine].
     *
     * Empty when the rows carry no source line, which is the case for rows built
     * by hand rather than by [rows]; the caller then leaves the page break where
     * it fell.
     */
    private fun trailingRowsOfSameLine(page: List<ChartRow>, sourceLine: Int): List<ChartRow> {
        if (sourceLine < 0) return emptyList()
        var start = page.size
        while (start > 0 && page[start - 1].sourceLine == sourceLine) start--
        return page.subList(start, page.size).toList()
    }

    /** The song line a page starts on, which is what a reflow is anchored to. */
    fun firstSourceLineOf(page: List<ChartRow>): Int =
        page.firstOrNull { it.sourceLine >= 0 }?.sourceLine ?: 0

    /**
     * The page holding song line [sourceLine]: the first page that reaches it.
     *
     * Used after a reflow to put the reader back on the line they were reading
     * rather than on the page number they happened to be on.
     *
     * "The first page that reaches it" rather than "the page that starts at or
     * before it" for two cases that both really happen. A line long enough to
     * wrap across a page break appears on two pages, and the reader should land
     * on the one where it starts. And a line that pagination dropped - a blank
     * absorbed into a page break, a section end - is on no page at all, in which
     * case the right answer is the page that carries on from there.
     */
    fun pageContainingSourceLine(pages: List<List<ChartRow>>, sourceLine: Int): Int {
        for ((index, page) in pages.withIndex()) {
            val reached = page.maxOfOrNull { it.sourceLine } ?: -1
            if (reached >= sourceLine) return index
        }
        return (pages.size - 1).coerceAtLeast(0)
    }

    /**
     * How many monospaced characters of [charWidthPx] fit across [widthPx].
     *
     * The nudge before truncating is not superstition. A fit-to-width zoom sets
     * the character width to exactly the width divided by the longest line, and
     * a division that lands a whisker under a whole number would then report one
     * column too few - so the chart would wrap by a single character at the very
     * size chosen to make it fit.
     */
    fun columnsThatFit(widthPx: Float, charWidthPx: Float): Int {
        if (widthPx <= 0f || charWidthPx <= 0f) return MIN_COLUMNS
        return (widthPx / charWidthPx + FLOAT_NUDGE).toInt().coerceAtLeast(MIN_COLUMNS)
    }

    /** How many lines of [lineHeightPx] fit down [heightPx]. */
    fun linesThatFit(heightPx: Float, lineHeightPx: Float): Int {
        if (heightPx <= 0f || lineHeightPx <= 0f) return MIN_LINES
        return (heightPx / lineHeightPx).toInt().coerceAtLeast(MIN_LINES)
    }

    private const val FLOAT_NUDGE = 0.001f

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
