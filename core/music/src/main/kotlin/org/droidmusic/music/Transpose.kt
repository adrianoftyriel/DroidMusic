package org.droidmusic.music

/**
 * What the caller wants done. Either [semitones] or [targetKey] is given, never
 * both; [targetKey] is the more precise request because it also fixes the
 * spelling, and it is what the UI sends when the user picks a key by name.
 */
data class TransposeRequest(
    val semitones: Int? = null,
    val targetKey: Key? = null,
    /**
     * Which way to break a spelling tie. Null means "let the key signature
     * decide", which is right almost always; the UI exposes it for the case
     * where a band has agreed to read something as F sharp rather than G flat.
     */
    val preferSharps: Boolean? = null,
    /**
     * Fret the capo is on. Chords are shown in the shape the player fingers, not
     * the key the audience hears, so a capo of 3 shows the chart three semitones
     * *lower* than it sounds.
     */
    val capo: Int = 0,
    /** Tablature is left alone unless this is set; see [TabTransposer]. */
    val includeTab: Boolean = false,
)

data class TransposeResult(
    val song: Song,
    /** The key the chart was in before, as declared or as detected. */
    val fromKey: Key?,
    /** The key it now sounds in. Unchanged by a capo, which is the point of one. */
    val soundingKey: Key?,
    /** The key whose shapes the player actually fingers. Differs when a capo is set. */
    val playedKey: Key?,
    val interval: Interval,
    /** Things the user needs to be told, such as tablature having been left alone. */
    val notes: List<String> = emptyList(),
)

object Transposer {

    /**
     * The one entry point. Resolves the request against whatever the song knows
     * or can be made to admit about its own key, then applies a single interval
     * to every chord.
     *
     * Note that the interval is computed once, from key to key, and then applied
     * uniformly. Transposing each chord independently by "n semitones, then pick
     * a spelling" is the obvious alternative and it is wrong: it spells each
     * chord in isolation, so a chart in E flat comes back as a mixture of D sharp
     * and E flat depending on which accidental looked cheaper for each chord.
     */
    fun transpose(song: Song, request: TransposeRequest): TransposeResult {
        val notes = mutableListOf<String>()
        val fromKey = song.meta.key
            ?: KeyDetector.detect(song)?.key
            ?: Key(Note(0, 0), Mode.MAJOR).also {
                if (song.hasChords()) notes += "Could not work out the original key; assumed C major."
            }

        val preferSharps = request.preferSharps ?: (fromKey.fifths > 0)

        val soundingKey = when {
            request.targetKey != null -> request.targetKey
            request.semitones != null ->
                Key.bestSpelling(fromKey, request.semitones, preferSharps)
            else -> fromKey
        }

        // A capo shifts what is fingered without shifting what is heard.
        val playedKey =
            if (request.capo != 0) Key.bestSpelling(soundingKey, -request.capo, preferSharps)
            else soundingKey

        val interval = Interval.between(fromKey.tonic, playedKey.tonic)

        val transposedLines = song.lines.map { line ->
            when (line) {
                is Line.Lyric -> Line.Lyric(
                    line.segments.map { seg ->
                        // The annotation rides along untouched. `[*Coda]` is a
                        // direction to the player, not a pitch, and transposing
                        // it would be nonsense.
                        Segment(seg.chord?.transpose(interval), seg.text, seg.annotation)
                    },
                )

                // Grid chords transpose with the song, which the ChordPro
                // specification requires. Each token keeps the text it was
                // written as, so the writer can tell how far the columns moved
                // and put them back.
                is Line.Grid -> Line.Grid(
                    line.tokens.map { token ->
                        when (token) {
                            is GridToken.Chord ->
                                GridToken.Chord(token.chord.transpose(interval), token.text)
                            is GridToken.Symbol -> token
                        }
                    },
                )

                is Line.Tab -> if (request.includeTab) {
                    val shifted = TabTransposer.shift(line.text, semitonesOf(interval))
                    if (shifted == null) line else Line.Tab(shifted)
                } else {
                    line
                }

                else -> line
            }
        }

        if (song.hasTab()) {
            notes += if (request.includeTab) {
                "Tablature was shifted by fret. Check it against your tuning."
            } else {
                "Tablature was left at its original pitch; fret numbers are not transposed."
            }
        }

        val meta = song.meta.copy(key = soundingKey, capo = request.capo)
        return TransposeResult(
            song = Song(meta, transposedLines),
            fromKey = fromKey,
            soundingKey = soundingKey,
            playedKey = playedKey,
            interval = interval,
            notes = notes,
        )
    }

    /** Signed semitone distance of an interval, in the range -11..11. */
    fun semitonesOf(interval: Interval): Int {
        val up = Math.floorMod(interval.semitones, 12)
        return if (up > 6) up - 12 else up
    }
}

/**
 * Shifts ASCII tablature by fret.
 *
 * This is offered but not switched on by default, and the reason is worth being
 * explicit about. Adding *n* to every fret number does produce the same music a
 * semitone higher, but it produces it in a completely different position on the
 * neck, with different open strings and often a fingering nobody would choose.
 * It is genuinely useful for a small shift on a riff and genuinely useless for
 * moving a fingerstyle arrangement up a fourth, so the user asks for it
 * explicitly and gets told when it will not fit.
 */
object TabTransposer {

    const val MAX_FRET = 24

    /**
     * Returns the shifted line, or null if the shift would run off the neck in
     * either direction - in which case the caller should keep the original and
     * say so rather than emit a fret of -2.
     */
    fun shift(line: String, semitones: Int): String? {
        if (semitones == 0) return line
        val out = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (!c.isDigit()) {
                out.append(c)
                i++
                continue
            }
            // Frets above 9 are written as two digits, so consume the whole run.
            var j = i
            while (j < line.length && line[j].isDigit()) j++
            val fret = line.substring(i, j).toIntOrNull() ?: return null
            val shifted = fret + semitones
            if (shifted < 0 || shifted > MAX_FRET) return null
            val text = shifted.toString()
            out.append(text)
            // Keep the column count identical where we can, by trimming or padding
            // the dashes that follow. Tab that loses its alignment is unreadable.
            val widthChange = text.length - (j - i)
            i = j
            if (widthChange > 0) {
                var trimmed = 0
                while (trimmed < widthChange && i < line.length && line[i] == '-') {
                    i++
                    trimmed++
                }
            } else if (widthChange < 0) {
                out.append("-".repeat(-widthChange))
            }
        }
        return out.toString()
    }

    /** Whether every fret on these lines survives the shift. */
    fun canShift(lines: List<String>, semitones: Int): Boolean =
        lines.all { shift(it, semitones) != null }
}
