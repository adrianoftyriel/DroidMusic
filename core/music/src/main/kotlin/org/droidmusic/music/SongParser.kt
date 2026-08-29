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
     */
    fun detectFormat(text: String): ChartFormat {
        val head = text.lineSequence().take(400).toList()
        val directive = head.any { DIRECTIVE.matches(it.trim()) }
        val inlineChord = head.any { INLINE_CHORD.containsMatchIn(it) }
        if (directive || inlineChord) return ChartFormat.CHORDPRO
        val chordLines = head.count { ChordsOverLyricsParser.isChordLine(it) }
        return if (chordLines >= 1) ChartFormat.CHORDS_OVER_LYRICS else ChartFormat.PLAIN_TEXT
    }

    internal val DIRECTIVE = Regex("^\\{\\s*([a-zA-Z_]+)\\s*(?::\\s*(.*?))?\\s*}$")
    internal val INLINE_CHORD = Regex("\\[[A-G][^\\]]{0,12}]")
}

object ChordProParser {

    fun parse(text: String): Song {
        var meta = SongMeta(format = ChartFormat.CHORDPRO)
        val extra = mutableMapOf<String, String>()
        val lines = mutableListOf<Line>()
        var inTab = false

        for (raw in text.lines()) {
            val trimmed = raw.trim()
            val directive = SongParser.DIRECTIVE.matchEntire(trimmed)

            if (directive != null) {
                val name = directive.groupValues[1].lowercase()
                val value = directive.groupValues[2].trim()
                when (name) {
                    "title", "t" -> meta = meta.copy(title = value)
                    "subtitle", "st" -> meta = meta.copy(subtitle = value)
                    "artist", "composer" -> meta = meta.copy(artist = value)
                    "key" -> Key.parse(value)?.let { meta = meta.copy(key = it) }
                    "capo" -> value.toIntOrNull()?.let { meta = meta.copy(capo = it) }
                    "tempo", "bpm" -> value.toIntOrNull()?.let { meta = meta.copy(tempo = it) }
                    "time", "meter" -> meta = meta.copy(time = value)
                    "comment", "c", "comment_italic", "ci", "comment_box", "cb" ->
                        lines += Line.Comment(value)
                    "start_of_tab", "sot" -> {
                        inTab = true
                        lines += Line.SectionHeader(value.ifEmpty { "Tab" }, SectionKind.TAB)
                    }
                    "end_of_tab", "eot" -> inTab = false
                    "start_of_chorus", "soc" ->
                        lines += Line.SectionHeader(value.ifEmpty { "Chorus" }, SectionKind.CHORUS)
                    "start_of_verse", "sov" ->
                        lines += Line.SectionHeader(value.ifEmpty { "Verse" }, SectionKind.VERSE)
                    "start_of_bridge", "sob" ->
                        lines += Line.SectionHeader(value.ifEmpty { "Bridge" }, SectionKind.BRIDGE)
                    "start_of_grid", "sog" ->
                        lines += Line.SectionHeader(value.ifEmpty { "Grid" }, SectionKind.GRID)
                    "end_of_chorus", "eoc", "end_of_verse", "eov",
                    "end_of_bridge", "eob", "end_of_grid", "eog",
                    -> Unit
                    else -> if (value.isNotEmpty()) extra[name] = value
                }
                continue
            }

            when {
                inTab -> lines += Line.Tab(raw)
                raw.isBlank() -> lines += Line.Blank
                else -> lines += Line.Lyric(parseInline(raw))
            }
        }

        return Song(meta.copy(extra = extra), lines)
    }

    /**
     * Splits a ChordPro line into segments at each `[...]`.
     *
     * Bracket contents that do not parse as a chord are kept as literal text
     * rather than dropped. Charts use brackets for annotations - `[x2]`,
     * `[slowly]` - and silently deleting them would be worse than showing them.
     */
    internal fun parseInline(raw: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val current = StringBuilder()
        var pendingChord: Chord? = null
        var i = 0

        while (i < raw.length) {
            val c = raw[i]
            if (c == '[') {
                val close = raw.indexOf(']', i + 1)
                if (close > 0) {
                    val inner = raw.substring(i + 1, close)
                    val chord = Chord.parse(inner)
                    if (chord != null) {
                        segments += Segment(pendingChord, current.toString())
                        current.setLength(0)
                        pendingChord = chord
                    } else {
                        current.append('[').append(inner).append(']')
                    }
                    i = close + 1
                    continue
                }
            }
            current.append(c)
            i++
        }
        segments += Segment(pendingChord, current.toString())

        // A leading empty segment carries no information; drop it so a line that
        // starts on a chord does not render with a phantom gap in front of it.
        return if (segments.size > 1 && segments[0].chord == null && segments[0].text.isEmpty()) {
            segments.drop(1)
        } else {
            segments
        }
    }
}
