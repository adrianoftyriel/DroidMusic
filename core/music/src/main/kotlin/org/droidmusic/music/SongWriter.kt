package org.droidmusic.music

/**
 * Renders a [Song] back out as text.
 *
 * Needed for two things: exporting a transposed chart so it can be shared or
 * printed, and drawing the chart on screen, since the viewer lays out the same
 * two-row chord-over-lyric shape the text form describes.
 */
object SongWriter {

    /**
     * Canonical ChordPro. Round-trips through [ChordProParser]: parsing what
     * this writes gives back the song it was given.
     *
     * "Canonical" is doing some work in that sentence. The writer does not
     * reproduce the input byte for byte - it normalises the metadata order, and
     * it wraps bare tablature in the `{start_of_tab}` the original was missing.
     * What it does guarantee is that nothing is lost, including the directives
     * this app has no opinion about.
     */
    fun toChordPro(song: Song, unicodeAccidentals: Boolean = false): String = buildString {
        val m = song.meta
        m.title?.let { appendLine("{title: $it}") }
        m.sortTitle?.let { appendLine("{sorttitle: $it}") }
        m.subtitle?.let { appendLine("{subtitle: $it}") }
        m.artist?.let { appendLine("{artist: $it}") }
        m.sortArtist?.let { appendLine("{sortartist: $it}") }
        m.composer?.let { appendLine("{composer: $it}") }
        m.lyricist?.let { appendLine("{lyricist: $it}") }
        m.arranger?.let { appendLine("{arranger: $it}") }
        m.album?.let { appendLine("{album: $it}") }
        m.year?.let { appendLine("{year: $it}") }
        m.copyright?.let { appendLine("{copyright: $it}") }
        m.key?.let { appendLine("{key: $it}") }
        if (m.capo != 0) appendLine("{capo: ${m.capo}}")
        m.tempo?.let { appendLine("{tempo: $it}") }
        m.time?.let { appendLine("{time: $it}") }
        m.duration?.let { appendLine("{duration: $it}") }
        if (m.transpose != 0) appendLine("{transpose: ${m.transpose}}")
        for ((name, values) in m.extra) {
            for (value in values) {
                if (value.isEmpty()) appendLine("{$name}") else appendLine("{$name: $value}")
            }
        }
        // One blank line between the metadata and the song, unless the chart
        // already begins with one. Adding it unconditionally means every export
        // opens a slightly bigger gap than the last.
        if (length > 0 && song.lines.firstOrNull() != Line.Blank) appendLine()

        // Tablature that arrived without a `{start_of_tab}` around it - sniffed
        // out of a chart that never had one - is written with the directive it
        // should have had. Exporting is the one moment where tidying up somebody
        // else's file is welcome, since the result is what every other ChordPro
        // tool needs in order to read it the same way.
        var impliedTab = false
        var declaredTab = false

        fun closeImpliedTab() {
            if (impliedTab) {
                appendLine("{end_of_tab}")
                impliedTab = false
            }
        }

        for (line in song.lines) {
            if (line !is Line.Tab) closeImpliedTab()
            when {
                line is Line.SectionHeader && line.name == "tab" -> declaredTab = true
                line is Line.SectionEnd && line.name == "tab" -> declaredTab = false
            }

            when (line) {
                is Line.Lyric -> appendLine(
                    line.segments.joinToString("") { seg -> mark(seg, unicodeAccidentals) + seg.text },
                )

                is Line.Comment -> appendLine("{${commentDirective(line.style)}: ${line.text}}")

                is Line.ChorusRecall ->
                    appendLine(if (line.label == null) "{chorus}" else "{chorus: ${escapeLabel(line.label)}}")

                is Line.Break -> appendLine(
                    when (line.kind) {
                        BreakKind.PAGE -> "{new_page}"
                        BreakKind.COLUMN -> "{column_break}"
                    },
                )

                is Line.SectionHeader -> appendLine(startDirective(line))

                is Line.SectionEnd -> appendLine("{end_of_${line.name}}")

                is Line.Grid -> appendLine(gridText(line, unicodeAccidentals))

                is Line.Tab -> {
                    if (!declaredTab && !impliedTab) {
                        appendLine("{start_of_tab}")
                        impliedTab = true
                    }
                    appendLine(line.text)
                }

                Line.Blank -> appendLine()
            }
        }
        closeImpliedTab()
    }

    private fun commentDirective(style: CommentStyle): String = when (style) {
        CommentStyle.PLAIN -> "comment"
        CommentStyle.ITALIC -> "comment_italic"
        CommentStyle.BOX -> "comment_box"
        CommentStyle.HIGHLIGHT -> "highlight"
    }

    /**
     * A section's start directive, with its label.
     *
     * The label is always written, even when it says no more than the directive
     * name already does. Dropping a redundant `{start_of_chorus: Chorus}` down to
     * `{start_of_chorus}` loses nothing that can be read back - the label comes
     * out the same either way - but it does mean exporting a chart quietly
     * rewrites lines the author typed, and a chart is somebody's document before
     * it is this app's data.
     *
     * A label with a line break in it has to use the attribute form; the
     * positional form has nowhere to put one.
     */
    private fun startDirective(header: Line.SectionHeader): String = when {
        // A heading with no ChordPro environment behind it - one this app worked
        // out by recognising the word "Intro" on a line of its own - is written
        // as a comment. ChordPro would allow `{start_of_part: Intro}`, but a
        // section has to be closed and nothing in the original says where its
        // contents stop: the next heading might begin a new section or might not.
        // A comment claims exactly as much as the source did.
        header.name == null -> "{comment: ${header.displayLabel}}"
        header.label.isNullOrEmpty() -> "{start_of_${header.name}}"
        header.label.contains('\n') || header.label.contains('"') ->
            "{start_of_${header.name}: label=\"${escapeLabel(header.label)}\"}"
        else -> "{start_of_${header.name}: ${header.label}}"
    }

    private fun escapeLabel(label: String): String = label.replace("\n", "\\n")

    /**
     * The bracketed mark in front of a segment: a chord, an annotation, or
     * nothing.
     *
     * An annotation is only starred when it has to be. `[x2]` is already an
     * annotation to any reader, because it does not parse as a chord, so writing
     * it back as `[x2]` keeps the file looking like the one somebody wrote. The
     * star is reserved for the cases where leaving it off would change the
     * meaning - an annotation that happens to spell a chord, or one that starts
     * with a star of its own.
     */
    private fun mark(seg: Segment, unicodeAccidentals: Boolean): String {
        seg.chord?.let { return "[" + (if (unicodeAccidentals) it.toUnicode() else it.toString()) + "]" }
        val annotation = seg.annotation ?: return ""
        val needsStar = annotation.startsWith("*") ||
            (annotation.isNotBlank() && annotation.trim() != "|" && Chord.parse(annotation) != null)
        return if (needsStar) "[*$annotation]" else "[$annotation]"
    }

    /**
     * A grid line as text, keeping its columns where transposition changed a
     * chord's width.
     *
     * A grid is a rectangle, and a chord that grew a character pushes everything
     * after it one column right - which is exactly the alignment the grid exists
     * to show. So the space that follows a chord absorbs the change where it can,
     * and only where it can: eating the last space between two chords would run
     * them together into one unreadable symbol.
     */
    private fun gridText(grid: Line.Grid, unicodeAccidentals: Boolean): String {
        val out = StringBuilder()
        var i = 0
        while (i < grid.tokens.size) {
            when (val token = grid.tokens[i]) {
                is GridToken.Symbol -> out.append(token.text)
                is GridToken.Chord -> {
                    val printed =
                        if (unicodeAccidentals) token.chord.toUnicode() else token.chord.toString()
                    out.append(printed)
                    val drift = printed.length - token.text.length
                    val next = grid.tokens.getOrNull(i + 1)
                    if (drift != 0 && next is GridToken.Symbol) {
                        out.append(absorb(next.text, drift))
                        i += 2
                        continue
                    }
                }
            }
            i++
        }
        return out.toString()
    }

    /**
     * Absorbs a chord's change of width into the spacing that follows it.
     *
     * A widened chord takes the space back out of the gap after it and a narrowed
     * one gives it back, which keeps the next bar line in the column it was
     * written in. The gap is never closed completely: two grid symbols with no
     * space between them read as one symbol.
     */
    private fun absorb(spacing: String, drift: Int): String {
        val leading = spacing.takeWhile { it == ' ' }.length
        val rest = spacing.substring(leading)
        val floor = if (rest.isEmpty()) 0 else 1
        return " ".repeat((leading - drift).coerceAtLeast(floor)) + rest
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
                is Line.ChorusRecall -> appendLine(line.label ?: "Chorus")
                is Line.Tab -> appendLine(line.text)
                is Line.Grid -> appendLine(gridText(line, unicodeAccidentals))
                is Line.SectionHeader -> appendLine("[${line.displayLabel}]")
                is Line.SectionEnd -> Unit
                is Line.Break -> appendLine()
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
            val markText = seg.markText(unicodeAccidentals)
            if (markText != null) {
                // Marks need a space between them or they read as one symbol.
                if (chordRow.isNotEmpty() && !chordRow.endsWith(" ")) chordRow.append(' ')
                val column = maxOf(chordRow.length, lyricRow.length)
                padTo(chordRow, column)
                padTo(lyricRow, column)
                chordRow.append(markText)
            }
            lyricRow.append(seg.text)
        }
        return chordRow.toString() to lyricRow.toString()
    }

    private fun padTo(sb: StringBuilder, column: Int) {
        while (sb.length < column) sb.append(' ')
    }
}
