package org.droidmusic.library

import org.droidmusic.music.ChartFormat
import org.droidmusic.music.SongParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The charts here are written for the test, not taken from the site.
 *
 * What is being checked is the *shape* of a page and the *mechanics* of the
 * conversion - where the JSON lives, what the markers do to alignment, which
 * directives come out - and none of that needs somebody's transcription of a
 * real song in the repository. A fixture that is written here can also be made
 * to hold the awkward cases on purpose, which a real page cannot.
 */
class UltimateGuitarTest {

    // ---- recognising the link ----------------------------------------------

    @Test
    fun `recognises a chart url`() {
        assertTrue(
            UltimateGuitar.isChartUrl(
                "https://tabs.ultimate-guitar.com/tab/some-band/some-song-chords-123456",
            ),
        )
        assertTrue(UltimateGuitar.isChartUrl("https://www.ultimate-guitar.com/tab/x/y-chords-1"))
    }

    @Test
    fun `refuses pages that are not charts`() {
        assertFalse(UltimateGuitar.isChartUrl("https://www.ultimate-guitar.com/explore"))
        assertFalse(UltimateGuitar.isChartUrl("https://example.com/tab/x/y-chords-1"))
    }

    /** A lookalike domain must not be taken for the site itself. */
    @Test
    fun `refuses a host that only contains the site name`() {
        assertFalse(
            UltimateGuitar.isChartUrl("https://ultimate-guitar.com.example.net/tab/x/y-chords-1"),
        )
        assertFalse(UltimateGuitar.isChartUrl("https://notultimate-guitar.com/tab/x/y-chords-1"))
    }

    /** Browsers share the page title and the address together, not a bare URL. */
    @Test
    fun `finds the link inside shared text`() {
        val shared = "Some Song CHORDS\n" +
            "https://tabs.ultimate-guitar.com/tab/some-band/some-song-chords-123456"
        assertEquals(
            "https://tabs.ultimate-guitar.com/tab/some-band/some-song-chords-123456",
            UltimateGuitar.chartUrlIn(shared),
        )
    }

    @Test
    fun `trims punctuation from a link at the end of a sentence`() {
        val shared = "Have a look at (https://tabs.ultimate-guitar.com/tab/b/s-chords-9)."
        assertEquals(
            "https://tabs.ultimate-guitar.com/tab/b/s-chords-9",
            UltimateGuitar.chartUrlIn(shared),
        )
    }

    @Test
    fun `ignores links to somewhere else`() {
        assertNull(UltimateGuitar.chartUrlIn("https://example.com/song and nothing else"))
        assertNull(UltimateGuitar.chartUrlIn("no link here at all"))
    }

    // ---- reading the page ---------------------------------------------------

    @Test
    fun `reads the chart and its metadata out of the page`() {
        val chart = requireNotNull(UltimateGuitar.parsePage(page(BODY), SOURCE_URL))

        assertEquals("Test Song", chart.title)
        assertEquals("The Testers", chart.artist)
        assertEquals("G", chart.keyText)
        assertEquals(2, chart.capo)
        assertEquals("Standard", chart.tuning)
        assertEquals(SOURCE_URL, chart.sourceUrl)
        assertTrue(chart.body.contains("[ch]"))
    }

    /**
     * The page carries the chart, its revision history and suggestions beside
     * it, all under a `content` key. The chart is the one with chord markers.
     */
    @Test
    fun `takes the chart rather than the prose next to it`() {
        val html = pageWithDecoy(BODY, decoy = "A paragraph about the song, with no chords in it.")
        val chart = requireNotNull(UltimateGuitar.parsePage(html, SOURCE_URL))
        assertTrue(chart.body.contains("[ch]G[/ch]"))
        assertFalse(chart.body.contains("A paragraph about"))
    }

    /** An interactive Pro tab has no text chart behind it to import. */
    @Test
    fun `returns nothing when the page carries no chart`() {
        assertNull(UltimateGuitar.parsePage("<html><body><p>Nothing here</p></body></html>"))
    }

    @Test
    fun `survives a page whose json will not parse`() {
        assertNull(UltimateGuitar.parsePage("<div class=\"js-store\" data-content=\"{tab_view\"></div>"))
    }

    // ---- converting ---------------------------------------------------------

    @Test
    fun `writes the metadata as directives`() {
        val chordPro = convert(BODY)

        assertTrue(chordPro.contains("{title: Test Song}"))
        assertTrue(chordPro.contains("{artist: The Testers}"))
        assertTrue(chordPro.contains("{key: G}"))
        assertTrue(chordPro.contains("{capo: 2}"))
        assertTrue(chordPro.contains("{tuning: Standard}"))
        assertTrue(chordPro.contains("{source: $SOURCE_URL}"))
    }

    /**
     * The point of the whole exercise: a chord that sat above a syllable in the
     * page's columns has to arrive attached to that same syllable.
     */
    @Test
    fun `puts each chord back on the syllable it sat above`() {
        val chordPro = convert(BODY)

        assertTrue(chordPro.contains("[G]Counting all the [D]rows of empty chairs"))
        assertTrue(chordPro.contains("[Am]Waiting for the [C]lights to fade"))
        assertTrue(chordPro.contains("[C]Sing [G]it [D]out again"))
    }

    @Test
    fun `keeps section headings`() {
        val chordPro = convert(BODY)

        assertTrue(chordPro.contains("{start_of_verse: Verse 1}"))
        assertTrue(chordPro.contains("{start_of_chorus: Chorus}"))
        assertTrue(chordPro.contains("{comment: Intro}"))
    }

    /**
     * The general parser only trusts a bracketed line whose first word it knows,
     * which is right for a text file of unknown provenance and too cautious for
     * this site, where a bracketed line on its own is always a heading.
     */
    @Test
    fun `keeps a heading the general parser does not know`() {
        val chordPro = convert(BODY)
        assertTrue(chordPro.contains("{comment: Guitar Solo}"))
    }

    /** A repeat marker is the one bracketed line here that is not a heading. */
    @Test
    fun `does not mistake a repeat marker for a heading`() {
        val chordPro = convert("[Verse 1]\n[tab][ch]C[/ch]\nWords under a chord[/tab]\n[x2]\n")
        assertFalse(chordPro.contains("{comment: x2}"))
    }

    /** An instrumental line has no words to hang chords on, and keeps its spacing. */
    @Test
    fun `keeps an instrumental line`() {
        val chordPro = convert(BODY)
        assertTrue(chordPro.contains("[G] [D] [Am] [C]"))
    }

    @Test
    fun `reads a chart that uses windows line endings`() {
        val chordPro = convert(BODY.replace("\n", "\r\n"))
        assertTrue(chordPro.contains("[G]Counting all the [D]rows of empty chairs"))
    }

    /**
     * A chart with no chords above its first line has that line taken for a
     * title by the ordinary parser. Here the page has already said what the song
     * is called, so its answer wins.
     */
    @Test
    fun `does not let a lyric become the title`() {
        val chordPro = convert("A line of words with no chords over it\n")
        assertTrue(chordPro.contains("{title: Test Song}"))
        assertFalse(chordPro.contains("{title: A line of words"))
    }

    /** What comes out has to go back in - it is filed as ChordPro. */
    @Test
    fun `round trips through the chordpro parser`() {
        val chordPro = convert(BODY)
        assertEquals(ChartFormat.CHORDPRO, SongParser.detectFormat(chordPro))

        val song = SongParser.parse(chordPro)
        assertEquals("Test Song", song.meta.title)
        assertEquals("The Testers", song.meta.artist)
        assertEquals(2, song.meta.capo)
        assertEquals(SOURCE_URL, song.meta.extra["source"])
        assertEquals(
            listOf("G", "D", "Am", "C", "G", "D", "Am", "C", "C", "G", "D", "G", "D"),
            song.chords().map { it.toString() },
        )
    }

    // ---- naming the file ----------------------------------------------------

    @Test
    fun `names the file after the song and the artist`() {
        val chart = UltimateGuitarChart(title = "Test Song", artist = "The Testers")
        assertEquals("Test Song - The Testers.chopro", UltimateGuitar.fileNameFor(chart))
    }

    @Test
    fun `keeps a file name that other systems will also accept`() {
        val chart = UltimateGuitarChart(title = "What/Ever: A Song?", artist = "A|B")
        val name = UltimateGuitar.fileNameFor(chart)
        assertTrue(name, name.none { it in "/\\:*?\"<>|" })
        assertTrue(name.endsWith(".chopro"))
    }

    @Test
    fun `still names a file when the page said nothing`() {
        assertEquals("Imported chart.chopro", UltimateGuitar.fileNameFor(UltimateGuitarChart()))
    }

    // ---- entities -----------------------------------------------------------

    @Test
    fun `decodes the entities the page is escaped with`() {
        assertEquals(
            "\"quoted\" & <angled> 'apostrophe'",
            UltimateGuitar.decodeEntities(
                "&quot;quoted&quot; &amp; &lt;angled&gt; &#39;apostrophe&#39;",
            ),
        )
    }

    /**
     * Decoding in one pass, not a chain of replacements. `&amp;lt;` is the
     * literal text `&lt;`, and a chain that expands `&amp;` first turns it into
     * a `<` that was never in the document.
     */
    @Test
    fun `does not decode text that was never encoded`() {
        assertEquals("&lt;", UltimateGuitar.decodeEntities("&amp;lt;"))
    }

    @Test
    fun `leaves alone anything that is not an entity`() {
        assertEquals("a & b", UltimateGuitar.decodeEntities("a & b"))
        assertEquals("100 & rising;", UltimateGuitar.decodeEntities("100 & rising;"))
        assertEquals("&nosuchentity;", UltimateGuitar.decodeEntities("&nosuchentity;"))
    }

    // ---- fixtures -----------------------------------------------------------

    private fun convert(body: String): String =
        UltimateGuitar.toChordPro(requireNotNull(UltimateGuitar.parsePage(page(body), SOURCE_URL)))

    /**
     * A page in the shape the site serves: the chart lives in a JSON blob in a
     * `data-content` attribute, HTML-escaped as an attribute has to be.
     */
    private fun page(body: String): String = html(
        """{"store":{"page":{"data":{""" +
            """"tab":{"song_name":"Test Song","artist_name":"The Testers",""" +
            """"tonality_name":"G"},""" +
            """"tab_view":{"wiki_tab":{"content":${jsonString(body)}},""" +
            """"meta":{"capo":2,"tuning":{"name":"Standard"}}}}}}}""",
    )

    /** The same page with a decoy `content` ahead of the chart's own. */
    private fun pageWithDecoy(body: String, decoy: String): String = html(
        """{"store":{"page":{"data":{""" +
            """"tab":{"song_name":"Test Song","artist_name":"The Testers"},""" +
            """"suggestions":[{"content":${jsonString(decoy)}}],""" +
            """"tab_view":{"revisions":[{"content":${jsonString(decoy)}}]}}}},""" +
            """"chart":{"content":${jsonString(body)}}}""",
    )

    private fun html(json: String): String =
        "<html><body><div class=\"js-store\" data-content=\"${escapeAttribute(json)}\"></div>" +
            "</body></html>"

    /** What a browser does to an attribute value, and what the reader must undo. */
    private fun escapeAttribute(text: String): String = text
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun jsonString(text: String): String = buildString {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
        append('"')
    }

    private companion object {
        const val SOURCE_URL = "https://tabs.ultimate-guitar.com/tab/some-band/some-song-chords-1"

        /**
         * A chart written for this test, in the site's own markup.
         *
         * The chord lines are spaced so that each chord starts in the column of
         * the syllable it belongs to - which is the thing the conversion has to
         * preserve, and the thing that breaks if a marker is stripped carelessly.
         */
        val BODY = """
            |[Intro]
            |[ch]G[/ch] [ch]D[/ch] [ch]Am[/ch] [ch]C[/ch]
            |
            |[Verse 1]
            |[tab][ch]G[/ch]                [ch]D[/ch]
            |Counting all the rows of empty chairs[/tab]
            |[tab][ch]Am[/ch]              [ch]C[/ch]
            |Waiting for the lights to fade[/tab]
            |
            |[Chorus]
            |[tab][ch]C[/ch]    [ch]G[/ch]  [ch]D[/ch]
            |Sing it out again[/tab]
            |
            |[Guitar Solo]
            |[ch]G[/ch] [ch]D[/ch]
        """.trimMargin()
    }
}
