package org.droidmusic.music

/**
 * The lexical layer of ChordPro: everything that happens to a chart's text
 * before anybody asks what a line *means*.
 *
 * This is separated from the parser because the ChordPro specification itself
 * separates them. A chart is first turned into logical lines - continuations
 * joined, escapes resolved, tabs flattened, `#` remarks dropped - and only then
 * is each line asked whether it is a directive, a lyric or tablature. Doing it
 * in one pass is how you end up with a parser that handles `{title: x}` but not
 * `{title: x\` followed by `y}`.
 *
 * Where the specification is silent or self-contradictory, the behaviour here
 * follows the [reference implementation](https://github.com/ChordPro/chordpro),
 * which is the tie-breaker the specification itself points at. The one place
 * that matters in practice is the abbreviation table: the published cheat sheet
 * lists `cb` as short for `column_break`, while the reference implementation
 * reads it as `comment_box` - and since `cb` in real charts is overwhelmingly a
 * boxed comment, the reference wins.
 */
internal object ChordProLex {

    /**
     * Turns raw chart text into the logical lines the parser sees.
     *
     * Four things happen here, in this order, because the specification's order
     * is observable: a `\uXXXX` escape written across a continuation only
     * resolves if the join happens first, and a tab inside a `#` remark must not
     * keep the remark alive.
     *
     *  1. A line ending in a backslash absorbs the next one, discarding the
     *     backslash and the leading whitespace of what follows.
     *  2. Unicode escapes become the characters they name.
     *  3. Tabs become a single space each - not a jump to the next tab stop.
     *     Wrong in a chart aligned with tabs, but it is what every other
     *     ChordPro tool does, and a chart that renders differently here than
     *     everywhere else is the worse failure.
     *  4. Lines beginning with `#` are dropped.
     *
     * The `#` test is deliberately anchored at column zero. An indented `#` is
     * *not* a remark - it is a lyric that happens to start with a hash, and
     * tablature is full of lines that would be destroyed by the looser reading.
     */
    fun logicalLines(text: String): List<String> {
        // A file ending in a newline has as many lines as it has newlines, not
        // one more. Without this every round trip through the writer - which
        // ends its output with a newline - grows another blank line at the foot
        // of the chart.
        val raw = text.lines().let { if (text.endsWith("\n")) it.dropLast(1) else it }
        val out = mutableListOf<String>()
        var i = 0

        while (i < raw.size) {
            var line = raw[i]
            i++
            // A trailing backslash means "continued", so keep swallowing lines
            // until one of them does not ask for another.
            while (line.endsWith("\\") && i < raw.size) {
                line = line.dropLast(1) + raw[i].trimStart()
                i++
            }
            line = unescape(line).replace('\t', ' ')
            if (line.startsWith("#")) continue
            out += line
        }
        return out
    }

    /**
     * Resolves the three escape forms ChordPro 6.01 added: a surrogate pair, a
     * bare four-digit `\uXXXX`, and the braced `\u{...}` for anything above the
     * basic plane.
     *
     * Surrogates are handled first and as a pair. Taken one at a time they
     * would each resolve to an unpaired surrogate, which is not a character any
     * font can draw and not what the file meant.
     */
    fun unescape(s: String): String {
        if (!s.contains("\\u", ignoreCase = true)) return s
        var out = SURROGATE_PAIR.replace(s) { m ->
            val hi = m.groupValues[1].toInt(16)
            val lo = m.groupValues[2].toInt(16)
            String(Character.toChars(0x10000 + (hi - 0xD800) * 0x400 + (lo - 0xDC00)))
        }
        out = BRACED_ESCAPE.replace(out) { m -> codePointOrLiteral(m.groupValues[1], m.value) }
        out = SHORT_ESCAPE.replace(out) { m -> codePointOrLiteral(m.groupValues[1], m.value) }
        return out
    }

    /**
     * A `\u{...}` naming something that is not a character is left exactly as
     * written rather than replaced with a replacement character. It is far more
     * likely to be a Windows path or a regular expression than a mistyped
     * escape, and mangling it would be silent.
     */
    private fun codePointOrLiteral(hex: String, original: String): String {
        val value = hex.toIntOrNull(16) ?: return original
        if (value > Character.MAX_CODE_POINT) return original
        return String(Character.toChars(value))
    }

    /**
     * The body of a directive line, or null when the line is not one.
     *
     * A directive must be alone on its line, though it may be indented. The
     * match is greedy on purpose, matching the reference implementation: in
     * `{a} {b}` the body is `a} {b`, which then fails to be a directive anybody
     * recognises. That is the right outcome - the alternative reading silently
     * honours the first half of a line somebody typed wrongly.
     */
    fun directiveBody(line: String): String? =
        DIRECTIVE_LINE.matchEntire(line)?.groupValues?.get(1)

    /**
     * Splits a directive body into its name, its argument and any conditional
     * selector.
     *
     * The name is separated from the argument by a colon *or* whitespace,
     * whichever comes first, which is why this cannot be a single regular
     * expression with a `:` in it. `{title: Twinkle}`, `{title Twinkle}` and
     * `{title:Twinkle}` are the same directive, and the third form is common
     * enough in hand-written charts that treating it as unknown would be
     * noticed immediately.
     */
    fun directive(body: String): Directive {
        // Leading colons and spaces are noise. The reference implementation
        // warns about them and then carries on, so carrying on is the compatible
        // behaviour.
        var d = body.trimStart(':', ' ').trimEnd()
        if (d.isEmpty()) return Directive("", "", null, false)

        var name = d
        var arg = ""
        val cut = d.indexOfFirst { it == ':' || it == ' ' }
        if (cut >= 0) {
            name = d.substring(0, cut)
            var rest = d.substring(cut + 1)
            // `{title : Twinkle}` splits on the space, which leaves the colon at
            // the head of the argument. The reference implementation keeps it,
            // and the result is a title that begins with a colon - so one
            // leading colon is dropped here, but only when the split was on a
            // space, so that `{comment: :-)}` keeps its face.
            if (d[cut] == ' ') rest = rest.trimStart(' ').removePrefix(":")
            arg = rest.trimStart()
        }
        name = name.trimEnd(':', ' ').lowercase()

        // A conditional selector is a dash and a name appended to the directive.
        // Only split on it when the part in front is a directive that exists;
        // otherwise `x_my-plugin` would lose half its name, and a custom
        // directive is precisely the kind that contains dashes.
        var selector: String? = null
        var negated = false
        val dash = name.indexOf('-')
        if (dash > 0) {
            val head = name.substring(0, dash)
            val tail = name.substring(dash + 1)
            if (tail.isNotEmpty() && isKnownDirective(head)) {
                name = head
                selector = tail
                if (selector.endsWith("!")) {
                    negated = true
                    selector = selector.dropLast(1)
                }
            }
        }

        return Directive(ABBREVIATIONS[name] ?: name, arg, selector, negated)
    }

    /**
     * Whether a name is one the format defines, used only to decide whether a
     * dash in a directive name is a selector or part of the name.
     */
    private fun isKnownDirective(name: String): Boolean =
        name in ABBREVIATIONS ||
            name in KNOWN_DIRECTIVES ||
            ENVIRONMENT_NAME.matches(name)

    /**
     * Reads the HTML-flavoured attribute syntax directives use for their second
     * and later arguments, e.g. `src="cover.jpg" scale="50%"`.
     *
     * Returns the attributes found and whatever text was not an attribute, since
     * several directives accept either a bare value or a named one -
     * `{start_of_verse: Verse 1}` and `{start_of_verse: label="Verse 1"}` mean
     * the same thing, and a chart may use both in the same file.
     */
    fun attributes(arg: String): Attributes {
        if (arg.isEmpty()) return Attributes(emptyMap(), "")
        val found = mutableMapOf<String, String>()
        val rest = StringBuilder()
        var i = 0

        while (i < arg.length) {
            val m = ATTRIBUTE.find(arg, i)
            if (m == null || m.range.first != i) {
                // Not an attribute here; keep the character as positional text
                // and try again from the next one.
                rest.append(arg[i])
                i++
                continue
            }
            val quoted = m.groupValues[2].ifEmpty { m.groupValues[3] }
            found[m.groupValues[1].lowercase()] = quoted
            i = m.range.last + 1
        }

        return Attributes(found, rest.toString().trim())
    }

    /**
     * The label of a section, taken from a `label` attribute if there is one and
     * from the positional argument otherwise.
     *
     * `\n` in a label is a line break rather than the two characters, so a
     * two-line margin label survives. This is unrelated to the `\uXXXX` escapes
     * resolved earlier - the specification defines this one only for labels.
     */
    fun label(arg: String): String? {
        val attrs = attributes(arg)
        val raw = attrs.values["label"] ?: attrs.positional.ifEmpty { null } ?: return null
        return raw.replace("\\n", "\n").ifEmpty { null }
    }

    private val DIRECTIVE_LINE = Regex("^\\s*\\{(.*)}\\s*$")
    private val SURROGATE_PAIR =
        Regex("\\\\u(d[89ab][0-9a-f]{2})\\\\u(d[cdef][0-9a-f]{2})", RegexOption.IGNORE_CASE)
    private val SHORT_ESCAPE = Regex("\\\\u([0-9a-f]{4})", RegexOption.IGNORE_CASE)
    private val BRACED_ESCAPE = Regex("\\\\u\\{([0-9a-f]+)}", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

    /** Environment names may hold letters, digits and underscores, and nothing else. */
    private val ENVIRONMENT_NAME = Regex("^(?:start|end)_of_[a-zA-Z0-9_]+$")

    /**
     * The abbreviation table, taken verbatim from the reference implementation
     * rather than from the documentation, which disagrees with itself about
     * `cb`.
     */
    val ABBREVIATIONS = mapOf(
        "c" to "comment",
        "cb" to "comment_box",
        "cf" to "chordfont",
        "ci" to "comment_italic",
        "col" to "columns",
        "colb" to "column_break",
        "cs" to "chordsize",
        "eob" to "end_of_bridge",
        "eoc" to "end_of_chorus",
        "eog" to "end_of_grid",
        "eot" to "end_of_tab",
        "eov" to "end_of_verse",
        "g" to "diagrams",
        "ng" to "no_grid",
        "np" to "new_page",
        "npp" to "new_physical_page",
        "ns" to "new_song",
        "sob" to "start_of_bridge",
        "soc" to "start_of_chorus",
        "sog" to "start_of_grid",
        "sot" to "start_of_tab",
        "sov" to "start_of_verse",
        "st" to "subtitle",
        "t" to "title",
        "tf" to "textfont",
        "ts" to "textsize",
    )

    /**
     * Every directive name the format defines. Used to tell a selector from a
     * hyphenated name, and to decide whether an unknown directive is worth
     * keeping quiet about.
     */
    val KNOWN_DIRECTIVES = setOf(
        // Metadata.
        "title", "sorttitle", "subtitle", "artist", "sortartist", "composer",
        "lyricist", "arranger", "copyright", "album", "year", "key", "time",
        "tempo", "duration", "capo", "tag", "meta",
        // Formatting and structure.
        "comment", "comment_italic", "comment_box", "highlight", "image",
        "chorus", "transpose", "define", "chord", "diagrams", "no_grid", "grid",
        // Output.
        "new_song", "new_page", "new_physical_page", "column_break", "columns",
        "pagetype", "titles",
        // Fonts, sizes and colours, which this app has no use for but must not
        // mistake for something else.
        "chordfont", "chordsize", "chordcolour", "chordcolor",
        "chorusfont", "chorussize", "choruscolour", "choruscolor",
        "footerfont", "footersize", "footercolour", "footercolor",
        "gridfont", "gridsize", "gridcolour", "gridcolor",
        "tabfont", "tabsize", "tabcolour", "tabcolor",
        "labelfont", "labelsize", "labelcolour", "labelcolor",
        "tocfont", "tocsize", "toccolour", "toccolor",
        "textfont", "textsize", "textcolour", "textcolor",
        "titlefont", "titlesize", "titlecolour", "titlecolor",
    )
}

/**
 * A directive, with its abbreviation expanded and its conditional selector - if
 * it had one - split off.
 */
internal data class Directive(
    val name: String,
    val arg: String,
    val selector: String?,
    val negated: Boolean,
) {
    val isConditional: Boolean get() = selector != null

    /** The environment this starts, or null when it is not a start directive. */
    val startsEnvironment: String?
        get() = if (name.startsWith("start_of_")) name.removePrefix("start_of_") else null

    /** The environment this ends, or null when it is not an end directive. */
    val endsEnvironment: String?
        get() = if (name.startsWith("end_of_")) name.removePrefix("end_of_") else null
}

/** The named attributes of a directive, and whatever text was not one. */
internal data class Attributes(
    val values: Map<String, String>,
    val positional: String,
)
