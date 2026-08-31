package org.droidmusic.music

/**
 * The parsed form of a chart. Everything the viewer draws, transposes or
 * analyses comes from here, whatever the file on disk looked like.
 *
 * A song is a flat list of lines rather than a tree of nested sections. Nesting
 * would be the tidier model, but the viewer's job is to lay lines out in a
 * column and break them into pages, and a flat list is what that wants. Section
 * starts and ends are lines in their own right, so a page break can fall between
 * them and their contents without any special handling.
 */
data class Song(
    val meta: SongMeta = SongMeta(),
    val lines: List<Line> = emptyList(),
) {
    /** Every chord in the song, in order, including repeats. */
    fun chords(): List<Chord> = lines.flatMap { line ->
        when (line) {
            is Line.Lyric -> line.segments.mapNotNull { it.chord }
            is Line.Grid -> line.chords()
            else -> emptyList()
        }
    }

    fun hasChords(): Boolean = chords().isNotEmpty()

    fun hasTab(): Boolean = lines.any { it is Line.Tab }

    fun hasGrid(): Boolean = lines.any { it is Line.Grid }
}

/**
 * A song's metadata.
 *
 * The fields named here are the ones the app itself does something with - shows
 * in a list, detects a key against, transposes by. The rest of what a chart
 * declares lives in [extra], which is a multi-map because ChordPro says so: two
 * `{composer:}` directives mean two composers, not one overwriting the other.
 */
data class SongMeta(
    val title: String? = null,
    val sortTitle: String? = null,
    val subtitle: String? = null,
    val artist: String? = null,
    val sortArtist: String? = null,
    val composer: String? = null,
    val lyricist: String? = null,
    val arranger: String? = null,
    val album: String? = null,
    val year: String? = null,
    val copyright: String? = null,
    /** The key as declared in the file, if it declared one. */
    val key: Key? = null,
    /** Capo position as declared in the file. */
    val capo: Int = 0,
    val tempo: Int? = null,
    val time: String? = null,
    /** Duration in seconds, however the file spelled it. */
    val duration: Int? = null,
    /** Semitones the file itself asked for, via `{transpose}`. */
    val transpose: Int = 0,
    val format: ChartFormat = ChartFormat.CHORDPRO,
    /**
     * Everything else the chart declared, in the order it was declared, kept so
     * that export can round-trip a file it does not fully understand. Holds
     * non-standard metadata, `x_` custom directives and any directive this app
     * has no use for.
     */
    val extra: Map<String, List<String>> = emptyMap(),
) {
    /** The first value declared for [name], for the common single-valued case. */
    fun first(name: String): String? = extra[name]?.firstOrNull()
}

enum class ChartFormat {
    /** Braces and inline `[C]` brackets. */
    CHORDPRO,

    /** Chords on their own line, sitting above the lyric line. */
    CHORDS_OVER_LYRICS,

    /** No chords found; treated as plain text and laid out as-is. */
    PLAIN_TEXT,
}

/**
 * The environments ChordPro singles out for special treatment, plus [OTHER] for
 * the arbitrary ones.
 *
 * ChordPro 6 lets a chart name a section anything it likes - `{start_of_part:
 * Solo}`, `{start_of_intro}` - and says only that `chorus`, `tab` and `grid`
 * behave specially and that unknown sections are ordinary lyrics. So this enum
 * is not a list of the sections that exist; it is a list of the ones that change
 * how a line is handled. The section's real name travels alongside it in
 * [Line.SectionHeader.name] so it can be written back out as itself.
 */
enum class SectionKind(val environment: String) {
    VERSE("verse"),
    CHORUS("chorus"),
    BRIDGE("bridge"),
    TAB("tab"),
    GRID("grid"),

    /**
     * Any other section. Deliberately carries no environment name of its own:
     * a section this app invented by recognising a heading has no ChordPro
     * environment behind it, and guessing one would put a `{start_of_part}` into
     * an exported file that the original never had.
     */
    OTHER(""),
    ;

    companion object {
        /** The kind an environment name gets, defaulting to [OTHER]. */
        fun of(environment: String): SectionKind =
            entries.firstOrNull { it != OTHER && it.environment == environment } ?: OTHER
    }
}

sealed interface Line {

    /**
     * A line of lyrics with chords attached at points within it. This is the
     * only line type that transposes.
     */
    data class Lyric(val segments: List<Segment>) : Line {
        /** True when the line carries chords but no words - an instrumental bar. */
        val isChordsOnly: Boolean
            get() = segments.any { it.chord != null } && segments.all { it.text.isBlank() }

        val plainText: String get() = segments.joinToString("") { it.text }
    }

    /** A `{comment}` directive, or a bracketed aside in a plain chart. */
    data class Comment(val text: String, val style: CommentStyle = CommentStyle.PLAIN) : Line

    /**
     * A line inside a tab block. Held verbatim, because the column positions
     * inside tablature *are* the notation - reflowing it would destroy it.
     */
    data class Tab(val text: String) : Line

    /**
     * A line inside a grid block: a jazz grille, where chords sit in a
     * rectangle of cells divided by bar lines.
     *
     * Held as tokens rather than as text because the specification is explicit
     * that grid chords transpose along with the rest of the song, and as a
     * single string they could not. The spacing between tokens is kept exactly
     * as written, so a grid that was aligned by hand stays aligned.
     */
    data class Grid(val tokens: List<GridToken>) : Line {
        fun chords(): List<Chord> = tokens.filterIsInstance<GridToken.Chord>().map { it.chord }

        val plainText: String get() = tokens.joinToString("") { it.text }
    }

    /**
     * The start of a section. [name] is the environment as the chart spelled it,
     * and [kind] is what this app does about it.
     *
     * [label] is null when the chart did not give the section one, which is a
     * different thing from a section labelled after itself. Both draw the same
     * heading - see [displayLabel] - but only one of them should get a label
     * written back when the chart is exported, and conflating the two means
     * every export rewrites lines the author never typed.
     *
     * A label may run to several lines, since ChordPro allows `\n` in one.
     */
    data class SectionHeader(
        val label: String?,
        val kind: SectionKind,
        val name: String? = kind.environment.ifEmpty { null },
    ) : Line {
        /**
         * What to draw: the chart's own label, or the environment's name made
         * readable - `start_of_intro_riff` shows as "Intro Riff".
         */
        val displayLabel: String
            get() = label
                ?: name?.split('_')
                    ?.joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
                ?: "Section"
    }

    /**
     * The end of a section. Carries no text and draws nothing; it exists so that
     * exporting a chart can close what it opened, and so a renderer that wants
     * to bracket a chorus knows where it stops.
     */
    data class SectionEnd(val name: String) : Line

    /**
     * A `{chorus}` directive: play the chorus here, without writing it out
     * again.
     *
     * Kept as its own line rather than turned into a comment so that exporting
     * writes `{chorus}` back rather than the word "Chorus", and so a renderer
     * that wants to print the chorus in full at this point still can.
     */
    data class ChorusRecall(val label: String? = null) : Line

    /** A page or column break the chart asked for. */
    data class Break(val kind: BreakKind) : Line

    data object Blank : Line
}

/** How a comment asked to be shown. None of these change what it says. */
enum class CommentStyle { PLAIN, ITALIC, BOX, HIGHLIGHT }

enum class BreakKind { PAGE, COLUMN }

/** One token of a grid line: either a chord, or the furniture around it. */
sealed interface GridToken {
    val text: String

    /** A chord in a cell. [text] is how it was written, which may not be how it prints. */
    data class Chord(val chord: org.droidmusic.music.Chord, override val text: String) : GridToken

    /** Spacing, bar lines, repeats, volta numbers, margin notes - anything not a chord. */
    data class Symbol(override val text: String) : GridToken
}

/**
 * A chord or annotation, and the text that begins underneath it.
 *
 * A segment with neither chord nor annotation is the run of lyric before the
 * first of them; a segment whose text is blank is a mark with no word under it,
 * where the text holds the original spacing so the bar spacing of an
 * instrumental line survives.
 *
 * [chord] and [annotation] are never both set. An annotation is ChordPro's
 * `[*Coda]` - something printed where a chord would go but which is not a chord
 * and does not transpose. Anything in brackets that fails to parse as a chord
 * becomes one too, which is how `[x2]` keeps its place above the line it was
 * written over instead of being dropped or shoved into the lyric.
 */
data class Segment(
    val chord: Chord? = null,
    val text: String = "",
    val annotation: String? = null,
) {
    /** What to draw above the text, whether it is a chord or an annotation. */
    fun markText(unicodeAccidentals: Boolean = false): String? = when {
        chord != null -> if (unicodeAccidentals) chord.toUnicode() else chord.toString()
        else -> annotation
    }

    val hasMark: Boolean get() = chord != null || annotation != null
}
