package org.droidmusic.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ChordPro format, rule by rule, against
 * [the specification](https://www.chordpro.org/chordpro/).
 *
 * Organised by the part of the format each rule belongs to rather than by the
 * function that implements it, so that a rule which moves between the lexer and
 * the parser does not lose its test.
 */
class ChordProLexTest {

    @Test
    fun `a line starting with a hash is a remark`() {
        val song = ChordProParser.parse("# just a note to self\n[C]Words")
        assertEquals(1, song.lines.size)
        assertEquals("Words", (song.lines[0] as Line.Lyric).plainText)
    }

    @Test
    fun `an indented hash is lyrics, not a remark`() {
        // The specification anchors the remark at the start of the line, and
        // tablature is full of lines that a looser reading would delete.
        val song = ChordProParser.parse("  # 4 sharps")
        assertEquals("  # 4 sharps", (song.lines[0] as Line.Lyric).plainText)
    }

    @Test
    fun `a trailing backslash continues the line`() {
        val song = ChordProParser.parse("{title: A very \\\n   long title}")
        assertEquals("A very long title", song.meta.title)
    }

    @Test
    fun `a continued lyric line keeps its chords`() {
        val song = ChordProParser.parse("[C]Half a line \\\n[G]and the rest")
        val line = song.lines.filterIsInstance<Line.Lyric>().single()
        assertEquals(listOf("C", "G"), line.segments.mapNotNull { it.chord?.toString() })
        assertEquals("Half a line and the rest", line.plainText)
    }

    @Test
    fun `unicode escapes become their characters`() {
        assertEquals("ABC", ChordProLex.unescape("A\\u0042C"))
        assertEquals("A\u2665C", ChordProLex.unescape("A\\u2665C"))
        assertEquals("\uD83C\uDFB8", ChordProLex.unescape("\\u{1F3B8}"))
        // A surrogate pair has to be read as a pair; taken singly each half is
        // not a character anything can draw.
        assertEquals("\uD83C\uDFB8", ChordProLex.unescape("\\ud83c\\udfb8"))
    }

    @Test
    fun `something that is not an escape is left alone`() {
        assertEquals("C:\\users\\bob", ChordProLex.unescape("C:\\users\\bob"))
        assertEquals("\\u{ffffffff}", ChordProLex.unescape("\\u{ffffffff}"))
    }

    @Test
    fun `a tab becomes a single space`() {
        // Not a jump to the next tab stop. Wrong for a chart aligned with tabs,
        // but it is what every other ChordPro tool does.
        assertEquals(listOf("a b"), ChordProLex.logicalLines("a\tb"))
    }

    @Test
    fun `a directive argument may be separated by a colon, a space, or neither`() {
        for (form in listOf("{title: Twinkle}", "{title Twinkle}", "{title:Twinkle}")) {
            assertEquals(form, "Twinkle", ChordProParser.parse(form).meta.title)
        }
    }

    @Test
    fun `leading colons and trailing whitespace are tolerated`() {
        assertEquals("Twinkle", ChordProParser.parse("{ : title : Twinkle  }").meta.title)
    }

    @Test
    fun `abbreviations expand to their full names`() {
        assertEquals("Short", ChordProParser.parse("{t: Short}").meta.title)
        assertEquals("Sub", ChordProParser.parse("{st: Sub}").meta.subtitle)
        assertEquals(
            SectionKind.CHORUS,
            ChordProParser.parse("{soc}").lines.filterIsInstance<Line.SectionHeader>().single().kind,
        )
    }

    @Test
    fun `cb is a boxed comment and colb is a column break`() {
        // The published cheat sheet lists `cb` as short for `column_break`; the
        // reference implementation reads it as `comment_box`, and since `cb` in
        // real charts is overwhelmingly a boxed comment, the reference wins.
        val boxed = ChordProParser.parse("{cb: Watch the repeat}").lines.single()
        assertEquals(Line.Comment("Watch the repeat", CommentStyle.BOX), boxed)
        assertEquals(Line.Break(BreakKind.COLUMN), ChordProParser.parse("{colb}").lines.single())
    }

    @Test
    fun `a directive must be alone on its line`() {
        // The match is greedy, matching the reference implementation, so the
        // body is the whole of `title: a} {artist: b`. The first directive
        // swallows the rest of the line as its argument and the second is never
        // seen - which is ugly, but it is what every other ChordPro tool does
        // with this input, and agreeing with them matters more than being tidy.
        val song = ChordProParser.parse("{title: a} {artist: b}")
        assertEquals("a} {artist: b", song.meta.title)
        assertNull(song.meta.artist)
    }

    @Test
    fun `attributes may use either kind of quote`() {
        val double = ChordProLex.attributes("""src="a.jpg" scale="50%"""")
        assertEquals(mapOf("src" to "a.jpg", "scale" to "50%"), double.values)
        assertEquals(mapOf("src" to "a.jpg"), ChordProLex.attributes("src='a.jpg'").values)
    }
}

class ChordProDirectiveTest {

    @Test
    fun `standard metadata lands in typed fields`() {
        val song = ChordProParser.parse(
            """
            {title: The Middle}
            {subtitle: from Bleed American}
            {artist: Jimmy Eat World}
            {composer: Jim Adkins}
            {lyricist: Jim Adkins}
            {album: Bleed American}
            {year: 2001}
            {copyright: 2001 DreamWorks}
            {key: D}
            {capo: 2}
            {tempo: 162}
            {time: 4/4}
            {duration: 2:45}
            """.trimIndent(),
        )
        assertEquals("The Middle", song.meta.title)
        assertEquals("from Bleed American", song.meta.subtitle)
        assertEquals("Jimmy Eat World", song.meta.artist)
        assertEquals("Jim Adkins", song.meta.composer)
        assertEquals("Bleed American", song.meta.album)
        assertEquals("2001", song.meta.year)
        assertEquals(Key.parse("D"), song.meta.key)
        assertEquals(2, song.meta.capo)
        assertEquals(162, song.meta.tempo)
        assertEquals("4/4", song.meta.time)
        assertEquals(165, song.meta.duration)
    }

    @Test
    fun `the meta directive is the general form of every metadata directive`() {
        val song = ChordProParser.parse("{meta: artist The Beatles}\n{meta: key G}")
        assertEquals("The Beatles", song.meta.artist)
        assertEquals(Key.parse("G"), song.meta.key)
    }

    @Test
    fun `a metadata item declared twice keeps both values`() {
        val song = ChordProParser.parse(
            "{meta: arranger John Lennon}\n{meta: arranger Paul McCartney}",
        )
        // The first goes in the typed field, and both survive for export.
        assertEquals("Paul McCartney", song.meta.arranger)
    }

    @Test
    fun `a non-standard metadata item keeps every value in order`() {
        val song = ChordProParser.parse("{meta: voice alto}\n{meta: voice tenor}")
        assertEquals(listOf("alto", "tenor"), song.meta.extra["voice"])
        assertEquals("alto", song.meta.first("voice"))
    }

    @Test
    fun `a tempo written loosely still gives a number`() {
        assertEquals(120, ChordProParser.parse("{tempo: 120 bpm}").meta.tempo)
        assertEquals(120, ChordProParser.parse("{tempo: ~120}").meta.tempo)
    }

    @Test
    fun `a duration may be seconds or minutes and seconds`() {
        assertEquals(165, ChordProParser.parse("{duration: 2:45}").meta.duration)
        assertEquals(165, ChordProParser.parse("{duration: 165}").meta.duration)
        assertEquals(3765, ChordProParser.parse("{duration: 1:02:45}").meta.duration)
        assertNull(ChordProParser.parse("{duration: about three minutes}").meta.duration)
    }

    @Test
    fun `comment styles are kept apart`() {
        assertEquals(
            listOf(CommentStyle.PLAIN, CommentStyle.ITALIC, CommentStyle.BOX, CommentStyle.HIGHLIGHT),
            ChordProParser.parse(
                "{comment: a}\n{comment_italic: b}\n{comment_box: c}\n{highlight: d}",
            ).lines.filterIsInstance<Line.Comment>().map { it.style },
        )
    }

    @Test
    fun `a chorus directive recalls the chorus rather than becoming a comment`() {
        val lines = ChordProParser.parse("{chorus}\n{chorus: Final}").lines
        assertEquals(
            listOf(Line.ChorusRecall(null), Line.ChorusRecall("Final")),
            lines,
        )
    }

    @Test
    fun `a transpose directive is recorded`() {
        assertEquals(2, ChordProParser.parse("{transpose: 2}").meta.transpose)
        assertEquals(-1, ChordProParser.parse("{transpose: -1}").meta.transpose)
    }

    @Test
    fun `page and column breaks become breaks`() {
        assertEquals(
            listOf(Line.Break(BreakKind.PAGE), Line.Break(BreakKind.PAGE), Line.Break(BreakKind.COLUMN)),
            ChordProParser.parse("{new_page}\n{npp}\n{column_break}").lines,
        )
    }

    @Test
    fun `an unknown directive is kept so export can round-trip it`() {
        val song = ChordProParser.parse("{x_mspro_pedal: 3}\n{tuning: E A D G B E}")
        assertEquals(listOf("3"), song.meta.extra["x_mspro_pedal"])
        assertEquals(listOf("E A D G B E"), song.meta.extra["tuning"])
    }

    @Test
    fun `an x_ directive with no value is still kept`() {
        assertEquals(listOf(""), ChordProParser.parse("{x_someapp_flag}").meta.extra["x_someapp_flag"])
    }
}

class ChordProConditionalTest {

    @Test
    fun `a selector this chart does not satisfy drops the directive`() {
        // With no instrument and no user configured, only the metadata test can
        // succeed - so the alto's note does not reach the tenor's stand.
        assertTrue(ChordProParser.parse("{comment-alto: Very softly}").lines.isEmpty())
    }

    @Test
    fun `a selector naming a metadata item that is set keeps the directive`() {
        val song = ChordProParser.parse("{meta: alto yes}\n{comment-alto: Very softly}")
        assertEquals("Very softly", song.lines.filterIsInstance<Line.Comment>().single().text)
    }

    @Test
    fun `a bang reverses the selection`() {
        val song = ChordProParser.parse("{comment-alto!: Everyone else}")
        assertEquals("Everyone else", song.lines.filterIsInstance<Line.Comment>().single().text)
    }

    @Test
    fun `a false-looking metadata value does not select`() {
        for (value in listOf("0", "false", "null")) {
            val song = ChordProParser.parse("{meta: alto $value}\n{comment-alto: hidden}")
            assertTrue(value, song.lines.filterIsInstance<Line.Comment>().isEmpty())
        }
    }

    @Test
    fun `a deselected section takes its whole contents with it`() {
        val song = ChordProParser.parse(
            """
            {start_of_verse-soprano}
            {comment: this should not appear}
            High notes only
            {end_of_verse}
            Everybody
            """.trimIndent(),
        )
        assertTrue(song.lines.filterIsInstance<Line.Comment>().isEmpty())
        assertEquals(
            listOf("Everybody"),
            song.lines.filterIsInstance<Line.Lyric>().map { it.plainText },
        )
    }

    @Test
    fun `a hyphen in a custom directive name is not mistaken for a selector`() {
        val song = ChordProParser.parse("{x_my-plugin: on}")
        assertEquals(listOf("on"), song.meta.extra["x_my-plugin"])
    }
}

class ChordProEnvironmentTest {

    @Test
    fun `a section may be named anything`() {
        val header = ChordProParser.parse("{start_of_part: Solo}\n[A]\n{end_of_part}")
            .lines.filterIsInstance<Line.SectionHeader>().single()
        assertEquals("part", header.name)
        assertEquals("Solo", header.label)
        assertEquals(SectionKind.OTHER, header.kind)
    }

    @Test
    fun `the environments with special treatment are recognised as such`() {
        for ((directive, kind) in listOf(
            "verse" to SectionKind.VERSE,
            "chorus" to SectionKind.CHORUS,
            "bridge" to SectionKind.BRIDGE,
            "tab" to SectionKind.TAB,
            "grid" to SectionKind.GRID,
        )) {
            val header = ChordProParser.parse("{start_of_$directive}\n{end_of_$directive}")
                .lines.filterIsInstance<Line.SectionHeader>().single()
            assertEquals(directive, kind, header.kind)
            assertEquals(directive, header.name)
        }
    }

    @Test
    fun `an unlabelled section shows its own name and stays unlabelled`() {
        val verse = ChordProParser.parse("{start_of_verse}").lines
            .filterIsInstance<Line.SectionHeader>().single()
        assertNull(verse.label)
        assertEquals("Verse", verse.displayLabel)

        val riff = ChordProParser.parse("{start_of_intro_riff}").lines
            .filterIsInstance<Line.SectionHeader>().single()
        assertEquals("Intro Riff", riff.displayLabel)

        // Unlabelled going in, unlabelled coming out. Exporting must not put a
        // label on a line that never had one.
        assertTrue(SongWriter.toChordPro(ChordProParser.parse("{start_of_verse}"))
            .contains("{start_of_verse}"))
    }

    @Test
    fun `a label may be given positionally or as an attribute`() {
        for (form in listOf("{start_of_verse: Verse 1}", """{start_of_verse: label="Verse 1"}""")) {
            assertEquals(
                form,
                "Verse 1",
                ChordProParser.parse(form).lines
                    .filterIsInstance<Line.SectionHeader>().single().label,
            )
        }
    }

    @Test
    fun `a label may run to two lines`() {
        val header = ChordProParser.parse("""{start_of_verse: label="Verse 1\nAll"}""")
            .lines.filterIsInstance<Line.SectionHeader>().single()
        assertEquals("Verse 1\nAll", header.label)
    }

    @Test
    fun `a section end is a line of its own`() {
        val lines = ChordProParser.parse("{start_of_verse}\nwords\n{end_of_verse}").lines
        assertEquals(Line.SectionEnd("verse"), lines.last())
    }

    @Test
    fun `a section left open is closed at the end of the chart`() {
        // The intent is obvious and refusing to draw the last verse over a
        // missing end directive would be a strange thing to do on a stand.
        val lines = ChordProParser.parse("{start_of_verse}\nwords").lines
        assertEquals(Line.SectionEnd("verse"), lines.last())
    }

    @Test
    fun `an end directive that closes nothing is ignored`() {
        assertTrue(ChordProParser.parse("{end_of_chorus}").lines.isEmpty())
    }

    @Test
    fun `tab content is literal, directives included`() {
        val song = ChordProParser.parse(
            """
            {start_of_tab}
            e|---0---3---|
            {comment: not a comment in here}
            {end_of_tab}
            {comment: but this one is}
            """.trimIndent(),
        )
        assertEquals(
            listOf("e|---0---3---|", "{comment: not a comment in here}"),
            song.lines.filterIsInstance<Line.Tab>().map { it.text },
        )
        assertEquals("but this one is", song.lines.filterIsInstance<Line.Comment>().single().text)
    }

    @Test
    fun `tab keeps its own spacing`() {
        val song = ChordProParser.parse("{start_of_tab}\n  e|--0--|  \n{end_of_tab}")
        assertEquals("  e|--0--|  ", song.lines.filterIsInstance<Line.Tab>().single().text)
    }
}

class ChordProChordTest {

    @Test
    fun `chords split the line at the syllable they sit on`() {
        val line = ChordProParser.parse("A[G]mazing grace how [C]sweet the [G]sound")
            .lines.filterIsInstance<Line.Lyric>().single()
        assertEquals(listOf(null, "G", "C", "G"), line.segments.map { it.chord?.toString() })
        assertEquals("Amazing grace how sweet the sound", line.plainText)
    }

    @Test
    fun `a starred bracket is an annotation`() {
        val line = ChordProParser.parse("[*Coda]Last time").lines.filterIsInstance<Line.Lyric>().single()
        assertEquals("Coda", line.segments.single().annotation)
        assertNull(line.segments.single().chord)
    }

    @Test
    fun `a bracket that is not a chord is an annotation, not lyrics`() {
        // The old behaviour pushed `[x2]` into the middle of the lyric, where it
        // reads as something to sing. The specification puts it above the line,
        // in the column it was written in, which is where the writer meant it.
        val line = ChordProParser.parse("[C]Hello [x2] there")
            .lines.filterIsInstance<Line.Lyric>().single()
        assertEquals("Hello  there", line.plainText)
        assertEquals(listOf("C"), line.segments.mapNotNull { it.chord?.toString() })
        assertEquals(listOf("x2"), line.segments.mapNotNull { it.annotation })
    }

    @Test
    fun `a bar line or blank bracket is an annotation`() {
        assertEquals(
            "|",
            ChordProParser.parse("[|]bar").lines.filterIsInstance<Line.Lyric>()
                .single().segments.first().annotation,
        )
        assertNotNull(
            ChordProParser.parse("[ ]gap").lines.filterIsInstance<Line.Lyric>()
                .single().segments.first().annotation,
        )
    }

    @Test
    fun `parentheses around a chord are not part of its name`() {
        val line = ChordProParser.parse("[(C)]Optional").lines.filterIsInstance<Line.Lyric>().single()
        assertEquals("C", line.segments.single().chord?.toString())
    }

    @Test
    fun `a line of nothing but chords is an instrumental line`() {
        val line = ChordProParser.parse("[A][D]").lines.filterIsInstance<Line.Lyric>().single()
        assertTrue(line.isChordsOnly)
        assertEquals(listOf("A", "D"), line.segments.mapNotNull { it.chord?.toString() })
    }

    @Test
    fun `an annotation does not transpose`() {
        val song = ChordProParser.parse("{key: C}\n[C]Play [*Rit.]slowly")
        val result = Transposer.transpose(song, TransposeRequest(semitones = 2))
        val line = result.song.lines.filterIsInstance<Line.Lyric>().single()
        assertEquals(listOf("D"), line.segments.mapNotNull { it.chord?.toString() })
        assertEquals(listOf("Rit."), line.segments.mapNotNull { it.annotation })
    }
}

class ChordProGridTest {

    @Test
    fun `a grid keeps its furniture and finds its chords`() {
        val song = ChordProParser.parse(
            """
            {start_of_grid}
            || Am . . . | C . . . | D . . . |
            {end_of_grid}
            """.trimIndent(),
        )
        val grid = song.lines.filterIsInstance<Line.Grid>().single()
        assertEquals("|| Am . . . | C . . . | D . . . |", grid.plainText)
        assertEquals(listOf("Am", "C", "D"), grid.chords().map { it.toString() })
    }

    @Test
    fun `grid chords transpose with the song`() {
        val song = ChordProParser.parse(
            "{key: Am}\n{start_of_grid}\n| Am . | C . |\n{end_of_grid}",
        )
        val result = Transposer.transpose(song, TransposeRequest(semitones = 2))
        val grid = result.song.lines.filterIsInstance<Line.Grid>().single()
        assertEquals(listOf("Bm", "D"), grid.chords().map { it.toString() })
    }

    @Test
    fun `a widened grid chord gives the space back to keep the columns`() {
        val song = ChordProParser.parse("{key: C}\n{start_of_grid}\n| C  . | G  . |\n{end_of_grid}")
        val result = Transposer.transpose(song, TransposeRequest(semitones = 1))
        val written = SongWriter.toChordPro(result.song)
        val gridLine = written.lines().single { it.trimStart().startsWith("|") }
        // C became Db and G became Ab - one character wider each - and the bar
        // lines have not moved.
        assertEquals(
            song.lines.filterIsInstance<Line.Grid>().single().plainText.length,
            gridLine.length,
        )
    }

    @Test
    fun `strum arrows and margin notes are not mistaken for chords`() {
        val grid = ChordProParser.parse(
            "{start_of_grid}\nCoda |s dn~up dn~up | D7 . |\n{end_of_grid}",
        ).lines.filterIsInstance<Line.Grid>().single()
        assertEquals(listOf("D7"), grid.chords().map { it.toString() })
        assertEquals("Coda |s dn~up dn~up | D7 . |", grid.plainText)
    }
}

class ChordProBareTabTest {

    @Test
    fun `tablature nobody wrapped in a directive is still tablature`() {
        // A documented departure from the specification, which has no notion of
        // detecting tab. Charts from the usual places look like this constantly,
        // and reading these as lyrics leaves them at the mercy of any future
        // reflow.
        val song = ChordProParser.parse(
            """
            {title: Riff}
            e|-----------------|
            A|-9-9-5-5-5-5-5-5-|
            """.trimIndent(),
        )
        assertEquals(
            listOf("e|-----------------|", "A|-9-9-5-5-5-5-5-5-|"),
            song.lines.filterIsInstance<Line.Tab>().map { it.text },
        )
        assertTrue(song.lines.filterIsInstance<Line.Lyric>().isEmpty())
    }

    @Test
    fun `a lyric with a dash in it is not tablature`() {
        val song = ChordProParser.parse("Well - and this is a lyric - it really is")
        assertTrue(song.lines.filterIsInstance<Line.Tab>().isEmpty())
    }

    @Test
    fun `exporting wraps sniffed tablature in the directive it was missing`() {
        val written = SongWriter.toChordPro(ChordProParser.parse("e|--0--3--|\nA|--2--0--|"))
        assertTrue(written, written.contains("{start_of_tab}"))
        assertTrue(written, written.contains("{end_of_tab}"))
        // And it reads back as the same tablature.
        assertEquals(
            listOf("e|--0--3--|", "A|--2--0--|"),
            ChordProParser.parse(written).lines.filterIsInstance<Line.Tab>().map { it.text },
        )
    }
}

class ChordProRoundTripTest {

    private val chart = """
        {title: Amazing Grace}
        {artist: John Newton}
        {composer: Traditional}
        {key: G}
        {capo: 2}
        {tuning: E A D G B E}
        {x_someapp_flag: 7}

        {start_of_verse: Verse 1}
        A[G]mazing grace how [C]sweet the [G]sound
        That saved a [Em]wretch like [D]me [*Rit.]
        {end_of_verse}

        {start_of_chorus}
        [G]Praise him
        {end_of_chorus}

        {chorus: Final}

        {start_of_part: Solo}
        [A][D]
        {end_of_part}

        {start_of_tab: Riff}
        e|---0---3---|
        {end_of_tab}

        {start_of_grid}
        || Am . . . | C . . . |
        {end_of_grid}
    """.trimIndent()

    @Test
    fun `writing what was parsed and parsing it again gives the same song`() {
        val once = ChordProParser.parse(chart)
        val twice = ChordProParser.parse(SongWriter.toChordPro(once))
        assertEquals(once.meta, twice.meta)
        assertEquals(once.lines, twice.lines)
    }

    @Test
    fun `writing is stable, so exporting twice changes nothing`() {
        val first = SongWriter.toChordPro(ChordProParser.parse(chart))
        val second = SongWriter.toChordPro(ChordProParser.parse(first))
        assertEquals(first, second)
    }

    @Test
    fun `nothing the chart declared is lost`() {
        val written = SongWriter.toChordPro(ChordProParser.parse(chart))
        for (fragment in listOf(
            "{title: Amazing Grace}", "{composer: Traditional}", "{tuning: E A D G B E}",
            "{x_someapp_flag: 7}", "{start_of_verse: Verse 1}", "{start_of_part: Solo}",
            "{start_of_tab: Riff}", "{chorus: Final}", "{start_of_grid}",
        )) {
            assertTrue(fragment, written.contains(fragment))
        }
    }

    @Test
    fun `an annotation is only starred when leaving the star off would change it`() {
        // `[x2]` is already an annotation to any reader, so it stays as written.
        assertTrue(SongWriter.toChordPro(ChordProParser.parse("[x2]a")).contains("[x2]"))
        // `[*C]` has to keep its star or it becomes the chord C.
        assertTrue(SongWriter.toChordPro(ChordProParser.parse("[*C]a")).contains("[*C]"))
    }
}

/**
 * The format as it actually arrives: a chart downloaded from the internet, with
 * unwrapped tablature at the top, labelled sections, mid-word chords and a solo
 * written as bare chords.
 */
class TheMiddleFixtureTest {

    private val song: Song = ChordProParser.parse(
        checkNotNull(javaClass.getResourceAsStream("/the-middle.chordpro")) {
            "the-middle.chordpro is missing from the test resources"
        }.reader().readText(),
    )

    @Test
    fun `the metadata is read`() {
        assertEquals("The Middle", song.meta.title)
        assertEquals("Jimmy Eat World", song.meta.artist)
    }

    @Test
    fun `the unwrapped tablature at the top is held as tablature`() {
        val tab = song.lines.filterIsInstance<Line.Tab>()
        assertEquals(12, tab.size)
        assertEquals("A|-9-9-5-5-5-5-5-5-|-5-5-5-9-9-9-9-9-|-7-7-4-4-4-4-4-4-|-4-4-4-4-4-4-4-4-|", tab[4].text)
    }

    @Test
    fun `every section is found with its label`() {
        val headers = song.lines.filterIsInstance<Line.SectionHeader>()
        assertEquals(
            listOf(
                "verse" to "Verse", "chorus" to "Chorus", "verse" to "Verse",
                "chorus" to "Chorus", "part" to "Solo", "verse" to "Verse",
                "chorus" to "Chorus",
            ),
            headers.map { it.name to it.label },
        )
    }

    @Test
    fun `the solo is a run of chord-only lines`() {
        val solo = song.lines
            .dropWhile { !(it is Line.SectionHeader && it.name == "part") }
            .drop(1)
            .takeWhile { it !is Line.SectionEnd }
            .filterIsInstance<Line.Lyric>()
        assertEquals(4, solo.size)
        assertTrue(solo.all { it.isChordsOnly })
        assertEquals(
            listOf(listOf("A", "D"), listOf("A", "D"), listOf("G", "D"), listOf("A")),
            solo.map { line -> line.segments.mapNotNull { it.chord?.toString() } },
        )
    }

    @Test
    fun `a chord written mid-word stays mid-word`() {
        val line = song.lines.filterIsInstance<Line.Lyric>()
            .first { it.plainText.startsWith("Hey, don't write") }
        assertEquals("Hey, don't write yourself off yet.", line.plainText)
        assertEquals(listOf("D", "A"), line.segments.mapNotNull { it.chord?.toString() })
        // The A lands inside "yet", which is what the chart means by writing it there.
        assertEquals(listOf("Hey, don't write yourself off y", "et."), line.segments.map { it.text })
    }

    @Test
    fun `the whole chart round-trips`() {
        // Not line-for-line identical, and deliberately so: the chart's
        // tablature arrived without a `{start_of_tab}` around it, and exporting
        // supplies the one it should have had. So what is asserted here is that
        // nothing was lost, not that nothing changed.
        val written = SongWriter.toChordPro(song)
        val again = ChordProParser.parse(written)

        assertEquals(song.meta, again.meta)
        assertEquals(song.chords().map { it.toString() }, again.chords().map { it.toString() })
        assertEquals(
            song.lines.filterIsInstance<Line.Lyric>().map { it.plainText },
            again.lines.filterIsInstance<Line.Lyric>().map { it.plainText },
        )
        assertEquals(
            song.lines.filterIsInstance<Line.Tab>().map { it.text },
            again.lines.filterIsInstance<Line.Tab>().map { it.text },
        )
        assertEquals(
            listOf("verse", "chorus", "verse", "chorus", "part", "verse", "chorus"),
            again.lines.filterIsInstance<Line.SectionHeader>()
                .filter { it.name != "tab" }.map { it.name },
        )
    }

    @Test
    fun `exporting the chart twice changes nothing the second time`() {
        val first = SongWriter.toChordPro(song)
        val second = SongWriter.toChordPro(ChordProParser.parse(first))
        assertEquals(first, second)
    }

    @Test
    fun `the chart transposes without losing its tablature or its shape`() {
        val result = Transposer.transpose(song, TransposeRequest(semitones = 2))
        assertEquals(song.lines.size, result.song.lines.size)
        assertEquals(
            song.lines.filterIsInstance<Line.Tab>().size,
            result.song.lines.filterIsInstance<Line.Tab>().size,
        )
        // The song is in D; two semitones up is E.
        assertEquals(listOf("E", "B"), result.song.lines.filterIsInstance<Line.Lyric>()
            .first { it.plainText.startsWith("Hey, don't write") }
            .segments.mapNotNull { it.chord?.toString() },)
    }
}
