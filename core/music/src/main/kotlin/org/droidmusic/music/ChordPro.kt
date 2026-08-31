package org.droidmusic.music

/**
 * Reads ChordPro, as specified at [chordpro.org](https://www.chordpro.org/chordpro/).
 *
 * The format is small: lyrics with `[C]` chords in them, `{directives}` in
 * braces, blank lines, and `#` remarks. Nearly all of the work is in the
 * directives, and nearly all of the difficulty is that a chart in the wild was
 * written by a person rather than a generator - so this reads the permissive
 * forms the specification allows and the reference implementation accepts,
 * rather than only the tidy ones the examples show.
 *
 * Two deliberate departures from the letter of the specification, both because
 * the specification is describing a typesetter and this is a thing people read
 * off a stand:
 *
 *  - **Bare tablature is recognised.** A run of `e|---9-7--|` lines that nobody
 *    wrapped in `{start_of_tab}` is still tablature, and is held verbatim rather
 *    than treated as lyrics. Charts downloaded from the usual places look like
 *    this constantly.
 *  - **A chart is one song.** `{new_song}` is preserved so exporting does not
 *    destroy it, but a file holding a songbook is read as its first song. The
 *    library is a list of charts, and splitting one file into several entries
 *    behind the user's back would be worse than not reading the rest.
 *
 * Everything a chart declares that this app has no use for - fonts, colours,
 * page sizes, chord diagrams - is kept in [SongMeta.extra] so that exporting a
 * transposed chart does not quietly throw away the parts of it that were never
 * about transposition.
 */
object ChordProParser {

    fun parse(text: String): Song = Reader(ChordProLex.logicalLines(text)).read()

    /**
     * One pass over a chart's logical lines.
     *
     * Written as a class with fields rather than a fold because the parse is
     * genuinely stateful - an environment is open or it is not, and what a line
     * means depends on which - and threading that through a fold makes it harder
     * to see, not easier.
     */
    private class Reader(private val lines: List<String>) {

        private var meta = SongMeta(format = ChartFormat.CHORDPRO)
        private val extra = linkedMapOf<String, MutableList<String>>()
        private val out = mutableListOf<Line>()

        /** The environments currently open, innermost last. */
        private val open = ArrayDeque<String>()

        /**
         * The environment whose contents are being skipped because its
         * `start_of` directive was conditional and not selected, or null.
         */
        private var skipping: String? = null

        fun read(): Song {
            var i = 0
            while (i < lines.size) {
                i += consume(i)
            }
            // A chart that forgot to close its last section still has to render.
            // Closing it silently is right: the file's intent is obvious, and
            // refusing to draw the last verse over a missing `{end_of_verse}`
            // would be a strange thing to do on a stand.
            while (open.isNotEmpty()) out += Line.SectionEnd(open.removeLast())
            return Song(meta.copy(extra = extra.mapValues { it.value.toList() }), out)
        }

        /** Handles the line at [index], returning how many lines it used. */
        private fun consume(index: Int): Int {
            val raw = lines[index]
            val body = ChordProLex.directiveBody(raw)
            val directive = body?.let { ChordProLex.directive(it) }

            // A section that was conditionally skipped swallows everything up to
            // and including its own end directive - including other directives,
            // which is what the specification means by selection applying to
            // "everything in the section".
            skipping?.let { env ->
                if (directive?.endsEnvironment == env) skipping = null
                return 1
            }

            // Tab content is literal. The specification is explicit that inside
            // a tab block even a directive is just text, with the single
            // exception of the one that ends the block - which is what lets a
            // tab hold a line like `{riff}` without it disappearing.
            if (open.lastOrNull() == "tab") {
                if (directive?.endsEnvironment == "tab") return closeEnvironment(directive)
                out += Line.Tab(raw)
                return 1
            }

            if (directive != null && directive.name.isNotEmpty()) {
                return handle(directive)
            }

            return when {
                raw.isBlank() -> {
                    out += Line.Blank
                    1
                }

                open.lastOrNull() == "grid" -> {
                    out += Line.Grid(gridTokens(raw))
                    1
                }

                // Bare tablature: only when the line is unmistakably tab and no
                // section has claimed it. Inside a verse a `|`-heavy line is far
                // more likely to be a bar-line annotation over lyrics.
                ChordsOverLyricsParser.isTabLine(raw) -> {
                    out += Line.Tab(raw)
                    1
                }

                else -> {
                    out += Line.Lyric(segments(raw))
                    1
                }
            }
        }

        private fun handle(d: Directive): Int {
            // A conditional directive this chart's metadata does not select is
            // dropped. When it opens a section, the whole section goes with it.
            if (d.isConditional && !selects(d)) {
                if (d.startsEnvironment != null) skipping = d.startsEnvironment
                return 1
            }

            d.startsEnvironment?.let { return openEnvironment(it, d) }
            d.endsEnvironment?.let { return closeEnvironment(d) }

            when (d.name) {
                "comment" -> out += Line.Comment(d.arg, CommentStyle.PLAIN)
                "comment_italic" -> out += Line.Comment(d.arg, CommentStyle.ITALIC)
                "comment_box" -> out += Line.Comment(d.arg, CommentStyle.BOX)
                "highlight" -> out += Line.Comment(d.arg, CommentStyle.HIGHLIGHT)

                "chorus" -> out += Line.ChorusRecall(ChordProLex.label(d.arg))

                "new_page", "new_physical_page" -> out += Line.Break(BreakKind.PAGE)
                "column_break" -> out += Line.Break(BreakKind.COLUMN)

                // `{meta: name value}` is the general form of every metadata
                // directive, so it is unpacked and handed to the same place the
                // standalone ones go.
                "meta" -> {
                    val cut = d.arg.indexOfFirst { it.isWhitespace() || it == ':' }
                    if (cut > 0) {
                        setMeta(
                            d.arg.substring(0, cut).lowercase(),
                            d.arg.substring(cut + 1).trim(),
                        )
                    } else if (d.arg.isNotEmpty()) {
                        setMeta(d.arg.lowercase(), "")
                    }
                }

                "transpose" ->
                    d.arg.trim().toIntOrNull()?.let { meta = meta.copy(transpose = meta.transpose + it) }

                else -> setMeta(d.name, d.arg)
            }
            return 1
        }

        private fun openEnvironment(name: String, d: Directive): Int {
            open.addLast(name)
            val kind = SectionKind.of(name)
            out += Line.SectionHeader(ChordProLex.label(d.arg), kind, name)
            return 1
        }

        private fun closeEnvironment(d: Directive): Int {
            val name = d.endsEnvironment!!
            // An `{end_of_x}` that closes nothing is ignored rather than
            // emitted. Charts collected from several sources have stray end
            // directives in them, and a phantom section end would confuse an
            // exporter into closing a section that was never opened.
            if (open.isEmpty()) return 1
            // Close the named environment, and anything left open inside it.
            if (!open.contains(name)) return 1
            while (open.isNotEmpty()) {
                val closed = open.removeLast()
                out += Line.SectionEnd(closed)
                if (closed == name) break
            }
            return 1
        }

        /**
         * Whether a conditional directive applies.
         *
         * ChordPro matches a selector against the instrument, then the user
         * name, then the song's own metadata. This app configures neither an
         * instrument nor a user, so only the metadata test can ever succeed -
         * which means a `{comment-guitar:}` is dropped rather than shown. That is
         * the specified behaviour for a processor with no guitar configured, and
         * the alternative - showing every variant of a conditional line at once -
         * would put the alto's part in front of the tenor.
         */
        private fun selects(d: Directive): Boolean {
            val name = d.selector!!.lowercase()
            val value = metaValue(name)
            val truthy = value != null && value.isNotEmpty() &&
                value != "0" && !value.equals("false", true) && !value.equals("null", true)
            return truthy != d.negated
        }

        private fun metaValue(name: String): String? = when (name) {
            "title" -> meta.title
            "subtitle" -> meta.subtitle
            "artist" -> meta.artist
            "composer" -> meta.composer
            "lyricist" -> meta.lyricist
            "arranger" -> meta.arranger
            "album" -> meta.album
            "year" -> meta.year
            "copyright" -> meta.copyright
            "key" -> meta.key?.toString()
            "capo" -> meta.capo.takeIf { it != 0 }?.toString()
            "tempo" -> meta.tempo?.toString()
            "time" -> meta.time
            else -> extra[name]?.firstOrNull()
        }

        /**
         * Records a metadata item, in a typed field when the app does something
         * with it and in [extra] otherwise.
         *
         * The `extra` fallback is not a dumping ground for things that failed to
         * parse - it is how a chart's own vocabulary survives a round trip. A
         * chart that declares `{x_mspro_pedal: 3}` should still declare it after
         * being transposed and exported.
         */
        private fun setMeta(name: String, value: String) {
            when (name) {
                "title" -> meta = meta.copy(title = value)
                "sorttitle" -> meta = meta.copy(sortTitle = value)
                "subtitle" -> meta = meta.copy(subtitle = value)
                "artist" -> meta = meta.copy(artist = value)
                "sortartist" -> meta = meta.copy(sortArtist = value)
                "composer" -> meta = meta.copy(composer = value)
                "lyricist" -> meta = meta.copy(lyricist = value)
                "arranger" -> meta = meta.copy(arranger = value)
                "album" -> meta = meta.copy(album = value)
                "year" -> meta = meta.copy(year = value)
                "copyright" -> meta = meta.copy(copyright = value)
                "key" -> Key.parse(value)?.let { meta = meta.copy(key = it) }
                    ?: append(name, value)
                "capo" -> value.trim().toIntOrNull()?.let { meta = meta.copy(capo = it) }
                "tempo" -> tempoOf(value)?.let { meta = meta.copy(tempo = it) }
                "time" -> meta = meta.copy(time = value)
                "duration" -> durationOf(value)?.let { meta = meta.copy(duration = it) }
                else -> if (value.isNotEmpty() || name.startsWith("x_")) append(name, value)
            }
        }

        private fun append(name: String, value: String) {
            extra.getOrPut(name) { mutableListOf() } += value
        }

        /**
         * A tempo is a number, but charts write `120 bpm`, `~120` and `120-130`.
         * The leading number is the useful part of all three.
         */
        private fun tempoOf(value: String): Int? =
            Regex("\\d+").find(value)?.value?.toIntOrNull()

        /** A duration is `mm:ss`, `h:mm:ss`, or a count of seconds. */
        private fun durationOf(value: String): Int? {
            val parts = value.trim().split(':')
            if (parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
            return when (parts.size) {
                1 -> parts[0].toIntOrNull()
                2 -> parts[0].toIntOrNull()?.let { m -> parts[1].toIntOrNull()?.let { m * 60 + it } }
                3 -> parts[0].toIntOrNull()?.let { h ->
                    parts[1].toIntOrNull()?.let { m ->
                        parts[2].toIntOrNull()?.let { h * 3600 + m * 60 + it }
                    }
                }
                else -> null
            }
        }
    }

    /**
     * Splits a ChordPro line into segments at each `[...]`.
     *
     * What is in the brackets is a chord if it parses as one, and an *annotation*
     * otherwise. That is the specification's rule, and it is also the right one:
     * `[x2]` and `[Coda]` are directions to the player, so they belong above the
     * line where the writer put them, in the position they were written, rather
     * than shoved into the middle of the lyric where they read as something to
     * sing.
     *
     * Three forms all mean "annotation": an explicit `[*Coda]`, a bracket
     * holding only a bar line or spaces, and anything that simply is not a
     * chord. A chord in parentheses - `[(C)]`, meaning play it if you like - is
     * the chord.
     */
    internal fun segments(raw: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val current = StringBuilder()
        var chord: Chord? = null
        var annotation: String? = null
        var i = 0

        fun flush() {
            segments += Segment(chord, current.toString(), annotation)
            current.setLength(0)
            chord = null
            annotation = null
        }

        while (i < raw.length) {
            val c = raw[i]
            if (c == '[') {
                val close = raw.indexOf(']', i + 1)
                if (close > 0) {
                    val inner = raw.substring(i + 1, close)
                    val mark = markOf(inner)
                    flush()
                    chord = mark.first
                    annotation = mark.second
                    i = close + 1
                    continue
                }
            }
            current.append(c)
            i++
        }
        flush()

        // A leading empty segment carries no information; drop it so a line that
        // starts on a chord does not render with a phantom gap in front of it.
        return if (segments.size > 1 && !segments[0].hasMark && segments[0].text.isEmpty()) {
            segments.drop(1)
        } else {
            segments
        }
    }

    /** Reads bracket contents as either a chord or an annotation. */
    private fun markOf(inner: String): Pair<Chord?, String?> {
        // An explicit annotation. The asterisk goes, whatever follows stays -
        // including the empty string, which is a bracket somebody meant to be
        // blank rather than a chord that failed to parse.
        if (inner.startsWith("*")) return null to inner.substring(1)

        // A bar line or nothing but spaces is an annotation too, per the
        // reference implementation. It is how a chart marks a bar with no chord
        // change without pretending there is a chord there.
        if (inner.isBlank() || inner.trim() == "|") return null to inner

        // Parentheses around a chord mean the chord is optional, not that the
        // parentheses are part of its name.
        val bare = PARENTHESISED.matchEntire(inner)?.groupValues?.get(1) ?: inner
        Chord.parse(bare)?.let { return it to null }

        return null to inner
    }

    private val PARENTHESISED = Regex("^\\((.*)\\)$")

    /**
     * Splits a grid line into chords and the furniture between them.
     *
     * The furniture - bar lines, repeat marks, volta numbers, `%` measure
     * repeats, strum arrows, margin notes - is kept exactly as written, spacing
     * included, because a grid is a picture of the song's structure and its
     * columns are the picture. Only the chords are picked out, and only so that
     * transposing the song transposes the grid with it, which the specification
     * requires.
     */
    internal fun gridTokens(raw: String): List<GridToken> {
        val tokens = mutableListOf<GridToken>()
        var at = 0

        for (m in GRID_CHORD.findAll(raw)) {
            val chord = Chord.parse(m.value) ?: continue
            if (m.range.first > at) tokens += GridToken.Symbol(raw.substring(at, m.range.first))
            tokens += GridToken.Chord(chord, m.value)
            at = m.range.last + 1
        }
        if (at < raw.length) tokens += GridToken.Symbol(raw.substring(at))
        return tokens
    }

    /**
     * Candidate chords in a grid. Anchored on an upper-case root so that the
     * strum vocabulary - `dn`, `up`, `ua+`, `dx` - and the lower-case note names
     * are left alone, and so that a margin note like `Coda` is only a chord if it
     * actually parses as one.
     */
    private val GRID_CHORD = Regex("[A-G][A-Za-z0-9#b♭♯+\\-^°øΔ()/]*")
}
