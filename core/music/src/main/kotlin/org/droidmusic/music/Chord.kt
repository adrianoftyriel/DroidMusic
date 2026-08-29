package org.droidmusic.music

/**
 * A chord symbol, split into the three parts that behave differently under
 * transposition: the root moves, the bass moves, and the quality is carried
 * across untouched.
 *
 * The quality is kept as the original text rather than being parsed into a set
 * of intervals. That is a deliberate limit. Chart notation is not a closed
 * vocabulary - `sus4`, `add9`, `-7b5`, `maj7#11`, `(no3)` and a dozen house
 * styles all appear in real charts - and a parser that only understands the
 * qualities someone thought of will mangle the ones they did not. Since
 * transposition never needs to know what the quality means, the safest thing to
 * do with it is nothing at all.
 */
data class Chord(
    val root: Note,
    val quality: String,
    val bass: Note? = null,
) {
    override fun toString(): String = buildString {
        append(root.toString())
        append(quality)
        if (bass != null) {
            append('/')
            append(bass.toString())
        }
    }

    fun toUnicode(): String = buildString {
        append(root.toUnicode())
        append(quality)
        if (bass != null) {
            append('/')
            append(bass.toUnicode())
        }
    }

    fun transpose(interval: Interval): Chord =
        Chord(root.transpose(interval), quality, bass?.transpose(interval))

    /** True for the qualities that make this a minor triad family chord. */
    val isMinor: Boolean
        get() {
            val q = quality
            if (q.startsWith("maj", ignoreCase = true)) return false
            if (q.startsWith("m", ignoreCase = false) && !q.startsWith("maj")) return true
            return q.startsWith("min", ignoreCase = true) || q.startsWith("-")
        }

    val isDiminished: Boolean
        get() = quality.startsWith("dim") || quality.startsWith("o") || quality.startsWith("°")

    companion object {
        /**
         * What may follow the root, expressed as the vocabulary a quality is
         * actually built from rather than as a list of characters.
         *
         * The difference matters more than it looks. A character allowlist
         * accepts any word made of the right letters, so "Add", "And" and "Are"
         * all become chords and a lyric line gets silently rewritten by the
         * transposer. Requiring the tail to be a sequence of real quality tokens
         * rejects all three - "dd", "nd" and "re" are not things a chord quality
         * is made of - while still accepting the open-ended real-world
         * vocabulary, including forms nobody enumerated in advance like
         * `maj7#11`, `m7b5`, `-9`, `sus2`, `6/9` and `(no3)`.
         */
        private val QUALITY = Regex(
            "^(?:maj|min|mi|m|M|dim|aug|sus|add|alt|dom|no|\\u00b0|\\u00f8|\\u0394" +
                "|\\+|-|\\^|\\(|\\)|/|[0-9]|#|b|\\u266d|\\u266f)*$",
        )

        /**
         * Parses a complete chord symbol, or returns null if [text] is not one.
         * The whole string must be consumed, so this doubles as a predicate.
         */
        fun parse(text: String): Chord? {
            val s = text.trim()
            if (s.isEmpty()) return null
            if (s.any { it.isWhitespace() }) return null

            val slash = s.lastIndexOf('/')
            val headPart: String
            var bass: Note? = null
            if (slash > 0 && slash < s.length - 1) {
                val bassParsed = Note.parse(s.substring(slash + 1))
                // Only treat the slash as a bass separator when what follows is
                // exactly a note and nothing else. `6/9` is a quality, not an
                // inversion, and `and/or` is not a chord at all.
                if (bassParsed != null && bassParsed.length == s.length - slash - 1) {
                    bass = bassParsed.note
                    headPart = s.substring(0, slash)
                } else {
                    headPart = s
                }
            } else {
                headPart = s
            }

            val rootParsed = Note.parse(headPart) ?: return null
            val quality = headPart.substring(rootParsed.length)
            if (!QUALITY.matches(quality)) return null
            return Chord(rootParsed.note, quality, bass)
        }

        /**
         * Tokens that appear on a chord line without being chords: bar lines,
         * repeats, and the "no chord" marker. Recognised so that a line like
         * `| C  Am | N.C. |` is still seen as a chord line and gets transposed.
         */
        val CHORD_LINE_PASSTHROUGH = setOf(
            "|", "||", "|.", ".|", "|:", ":|", "%", "/", "//", "///", "////",
            "N.C.", "NC", "(N.C.)", "-", "x2", "x3", "x4", "X2", "X3", "X4",
        )
    }
}
