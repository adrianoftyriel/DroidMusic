package org.droidmusic.music

/**
 * The parsed form of a chart. Everything the viewer draws, transposes or
 * analyses comes from here, whatever the file on disk looked like.
 *
 * A song is a flat list of lines rather than a tree of nested sections. Nesting
 * would be the tidier model, but the viewer's job is to lay lines out in a
 * column and break them into pages, and a flat list is what that wants. Section
 * headers are lines in their own right, so a page break can fall between them
 * and their contents without any special handling.
 */
data class Song(
    val meta: SongMeta = SongMeta(),
    val lines: List<Line> = emptyList(),
) {
    /** Every chord in the song, in order, including repeats. */
    fun chords(): List<Chord> = lines.flatMap { line ->
        when (line) {
            is Line.Lyric -> line.segments.mapNotNull { it.chord }
            else -> emptyList()
        }
    }

    fun hasChords(): Boolean = lines.any { it is Line.Lyric && it.segments.any { s -> s.chord != null } }

    fun hasTab(): Boolean = lines.any { it is Line.Tab }
}

data class SongMeta(
    val title: String? = null,
    val subtitle: String? = null,
    val artist: String? = null,
    /** The key as declared in the file, if it declared one. */
    val key: Key? = null,
    /** Capo position as declared in the file. */
    val capo: Int = 0,
    val tempo: Int? = null,
    val time: String? = null,
    val format: ChartFormat = ChartFormat.CHORDPRO,
    /** Directives we understood but do not model, kept so export can round-trip. */
    val extra: Map<String, String> = emptyMap(),
)

enum class ChartFormat {
    /** Braces and inline `[C]` brackets. */
    CHORDPRO,

    /** Chords on their own line, sitting above the lyric line. */
    CHORDS_OVER_LYRICS,

    /** No chords found; treated as plain text and laid out as-is. */
    PLAIN_TEXT,
}

enum class SectionKind { VERSE, CHORUS, BRIDGE, TAB, GRID, OTHER }

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
    data class Comment(val text: String) : Line

    /**
     * A line inside a tab block. Held verbatim, because the column positions
     * inside tablature *are* the notation - reflowing it would destroy it.
     */
    data class Tab(val text: String) : Line

    data class SectionHeader(val label: String, val kind: SectionKind) : Line

    data object Blank : Line
}

/**
 * A chord and the text that begins underneath it. A segment with a null chord is
 * the run of lyric before the first chord; a segment whose text is blank is a
 * chord with no word under it, where the text holds the original spacing so the
 * bar spacing of an instrumental line survives.
 */
data class Segment(val chord: Chord?, val text: String)
