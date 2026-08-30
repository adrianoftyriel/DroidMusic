package org.droidmusic.library

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.droidmusic.music.ChartFormat
import org.droidmusic.music.SongParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What has to survive the trip out of a Word document.
 *
 * These are all string-in, string-out: the reader is deliberately free of
 * Android and of any document library, so the interesting cases can be written
 * as literal markup rather than as fixtures somebody has to open in Word to
 * understand.
 */
class DocxTest {

    private fun document(body: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
           <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
             <w:body>$body</w:body>
           </w:document>"""

    private fun paragraph(vararg runs: String): String =
        "<w:p>" + runs.joinToString("") { "<w:r><w:t xml:space=\"preserve\">$it</w:t></w:r>" } + "</w:p>"

    @Test
    fun `each paragraph becomes a line`() {
        val text = DocxText.fromDocumentXml(
            document(paragraph("Amazing grace") + paragraph("how sweet the sound")),
        )
        assertEquals(listOf("Amazing grace", "how sweet the sound"), text.lines())
    }

    @Test
    fun `runs within a paragraph are joined without a break`() {
        // Word splits a line into a new run at every formatting change, and at
        // every spell-check boundary it feels like. A chord line arrives as a
        // dozen runs and has to come back as one line.
        val text = DocxText.fromDocumentXml(document(paragraph("G", "     ", "C", "   ", "D")))
        assertEquals("G     C   D", text)
    }

    @Test
    fun `a line break inside a paragraph starts a new line`() {
        val text = DocxText.fromDocumentXml(
            document("<w:p><w:r><w:t>G</w:t><w:br/><w:t>C</w:t></w:r></w:p>"),
        )
        assertEquals(listOf("G", "C"), text.lines())
    }

    @Test
    fun `a tab stop definition is not a tab character`() {
        // The trap this reader exists to avoid. <w:tab/> inside w:pPr defines a
        // stop on the ruler; the identical element inside a run is a tab the
        // writer typed. Reading the first as the second indents every paragraph
        // in a document that has a ruler set, which is most of them.
        val text = DocxText.fromDocumentXml(
            document(
                "<w:p><w:pPr><w:tabs><w:tab w:val=\"left\" w:pos=\"720\"/>" +
                    "<w:tab w:val=\"left\" w:pos=\"1440\"/></w:tabs></w:pPr>" +
                    "<w:r><w:t>Verse 1</w:t></w:r></w:p>",
            ),
        )
        assertEquals("Verse 1", text)
    }

    @Test
    fun `a typed tab advances to the next stop on the grid`() {
        val text = DocxText.fromDocumentXml(
            document("<w:p><w:r><w:t>G</w:t><w:tab/><w:t>C</w:t></w:r></w:p>"),
        )
        assertEquals("G   C", text)
        assertEquals(0, text.indexOf('G'))
        assertEquals(DocxText.TAB_WIDTH, text.indexOf('C'))
    }

    @Test
    fun `deleted text is left out`() {
        // w:delText is what a tracked change removed. Putting it back would show
        // the player a line somebody deliberately took out of the chart.
        val text = DocxText.fromDocumentXml(
            document(
                "<w:p><w:r><w:t>Kept</w:t></w:r>" +
                    "<w:del><w:r><w:delText> and cut</w:delText></w:r></w:del></w:p>",
            ),
        )
        assertEquals("Kept", text)
    }

    @Test
    fun `field codes are left out`() {
        val text = DocxText.fromDocumentXml(
            document(
                "<w:p><w:r><w:instrText>PAGE \\* MERGEFORMAT</w:instrText></w:r>" +
                    "<w:r><w:t>Chorus</w:t></w:r></w:p>",
            ),
        )
        assertEquals("Chorus", text)
    }

    @Test
    fun `xml escapes come back as characters`() {
        val text = DocxText.fromDocumentXml(
            document(paragraph("Rock &amp; Roll &lt;fast&gt; &#65;")),
        )
        assertEquals("Rock & Roll <fast> A", text)
    }

    @Test
    fun `a table row becomes one line of cells`() {
        // Charts drawn as a grid of bars are usually tables. A cell per line
        // would take the grid apart.
        val text = DocxText.fromDocumentXml(
            document(
                "<w:tbl><w:tr>" +
                    "<w:tc>${paragraph("G")}</w:tc>" +
                    "<w:tc>${paragraph("C")}</w:tc>" +
                    "<w:tc>${paragraph("D")}</w:tc>" +
                    "</w:tr></w:tbl>",
            ),
        )
        assertEquals(1, text.lines().size)
        assertEquals(listOf("G", "C", "D"), text.trim().split(Regex(" +")))
    }

    @Test
    fun `trailing empty paragraphs are dropped`() {
        val text = DocxText.fromDocumentXml(
            document(paragraph("Last line") + "<w:p/><w:p/><w:p/>"),
        )
        assertEquals("Last line", text)
    }

    @Test
    fun `a blank paragraph in the middle is kept`() {
        // It is the gap between two verses, and the layout engine reads it that
        // way. Collapsing it would run the verses together.
        val text = DocxText.fromDocumentXml(
            document(paragraph("Verse 1") + "<w:p/>" + paragraph("Verse 2")),
        )
        assertEquals(listOf("Verse 1", "", "Verse 2"), text.lines())
    }

    @Test
    fun `non-breaking spaces become ordinary spaces so the chords still parse`() {
        // The failure this prevents is silent. Word writes U+00A0 wherever it
        // decides a gap should not be broken; Java does not count it as
        // whitespace, so a chord line padded with them is one enormous token,
        // fails the "every token is a chord" test, and the chart quietly stops
        // being transposable.
        val chords = "G" + "\u00A0".repeat(12) + "C"
        val text = DocxText.fromDocumentXml(
            document(paragraph(chords) + paragraph("Amazing grace how sweet the sound")),
        )

        assertTrue("no-break spaces left in the text", '\u00A0' !in text)
        assertEquals(ChartFormat.CHORDS_OVER_LYRICS, SongParser.detectFormat(text))
        assertTrue(SongParser.parse(text).hasChords())
    }

    @Test
    fun `a whole document reads out of the zip`() {
        val chart = document(
            paragraph("{title: Wagon Wheel}") +
                paragraph("[G]Heading down south to the [D]land of the pines"),
        )
        val song = SongParser.parse(DocxText.extract(ByteArrayInputStream(docxOf(chart)))!!)

        assertEquals("Wagon Wheel", song.meta.title)
        assertTrue(song.hasChords())
    }

    @Test
    fun `something that is not a zip is not a Word document`() {
        // A `.doc` renamed to `.docx` lands here, and so does anything else with
        // the wrong extension. Returning null lets the caller say so rather than
        // showing a page of mojibake.
        assertNull(DocxText.extract(ByteArrayInputStream("Just a text file".toByteArray())))
    }

    @Test
    fun `a zip with no document in it is not a Word document`() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.putNextEntry(ZipEntry("hello.txt"))
            out.write("hello".toByteArray())
            out.closeEntry()
        }
        assertNull(DocxText.extract(ByteArrayInputStream(zip.toByteArray())))
    }

    @Test
    fun `docx is a transposable chart`() {
        val ref = SongRef("i", "s", "u", "Wagon Wheel.docx", SongRef.kindOf("Wagon Wheel.docx"))
        assertEquals(FileKind.DOCX, ref.kind)
        assertTrue(ref.isTransposable)
    }

    @Test
    fun `docx is recognised by mime type when the name has no extension`() {
        // Some providers hand over a display name with no extension on it, and
        // the mime type is then the only thing that says what the file is.
        assertEquals(FileKind.DOCX, SongRef.kindOf("document", SongRef.DOCX_MIME_TYPE))
    }

    /** The smallest thing Word will still open: the body, in a zip. */
    private fun docxOf(documentXml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
