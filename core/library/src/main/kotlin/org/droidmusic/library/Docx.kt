package org.droidmusic.library

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Getting a chord chart back out of a Word document.
 *
 * **Why this is here rather than behind a library.** A `.docx` is a zip with an
 * XML file in it, and the only thing this app wants from it is the characters in
 * the order they were typed. Apache POI would do that, and would also bring
 * several megabytes of spreadsheet, presentation and OLE2 code into an APK that
 * has to open a chart on a phone at a gig. The whole reader is a couple of
 * hundred lines, has no dependencies, and runs on a plain JVM - which is also
 * what lets it be tested without a device.
 *
 * **Why plain text and not formatting.** A chart's meaning is in its characters
 * and their columns: a chord sits above a syllable because it is in that column.
 * Bold and italic carry nothing the transposer or the layout engine can use, so
 * they are dropped rather than half-preserved.
 *
 * **What that costs**, stated here and in docs/FORMATS.md rather than left to be
 * discovered:
 *
 * - A chart typed in a proportional font and aligned by eye will not line up,
 *   because it never lined up in characters - only in millimetres. Nothing short
 *   of laying out the font would fix that, and such a file usually wants to be a
 *   PDF instead.
 * - Tab stops become spaces on a fixed grid ([TAB_WIDTH]), because a tab in Word
 *   means "as far as the next stop, wherever the ruler puts it", and this app has
 *   no ruler.
 *
 * The old binary `.doc` is not read. It is not a zip, it is not XML, and it is
 * not worth the parser.
 */
object DocxText {

    /**
     * Columns a tab advances to. Four rather than the usual eight: a tab in a
     * chord chart is nearly always nudging one chord along a line, and
     * eight-column stops push it much further than the writer meant.
     */
    const val TAB_WIDTH = 4

    /** The zip entry holding the body of the document. */
    private const val DOCUMENT_ENTRY = "word/document.xml"

    /**
     * Reads a `.docx` from a stream.
     *
     * Returns null when it is not a Word document at all - which includes a
     * `.doc` renamed to `.docx`, since that is not a zip and there will be no
     * entry to find.
     *
     * [maxXmlBytes] caps the *decompressed* body. A zip is free to claim that
     * eight kilobytes expand into a gigabyte, and this runs over whatever
     * happens to be in a folder the user pointed at.
     */
    fun extract(input: InputStream, maxXmlBytes: Int = 8 * 1024 * 1024): String? {
        val xml = documentXml(input, maxXmlBytes) ?: return null
        return fromDocumentXml(xml)
    }

    /** Pulls `word/document.xml` out of the zip, or null if it is not in there. */
    private fun documentXml(input: InputStream, maxXmlBytes: Int): String? = runCatching {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.equals(DOCUMENT_ENTRY, ignoreCase = true)) {
                    return@use readEntry(zip, maxXmlBytes)
                }
                entry = zip.nextEntry
            }
            null
        }
    }.getOrNull()

    private fun readEntry(zip: ZipInputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (total < maxBytes) {
            val read = zip.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return String(out.toByteArray(), Charsets.UTF_8).removePrefix("\uFEFF")
    }

    /**
     * Turns the body of a Word document into lines of text.
     *
     * Written as a scan over tags rather than with an XML parser, because the
     * only questions being asked of the markup are "is this a run of text" and
     * "does this end a line". A scanner answers both without dragging a parser
     * onto the device, and without caring whether the file declares its
     * namespaces the way the specification says it should.
     *
     * Split out from [extract] so it can be tested without building a zip.
     */
    fun fromDocumentXml(xml: String): String {
        val out = StringBuilder()

        // Property elements - w:pPr, w:rPr, w:sectPr, w:tblPr and the rest - are
        // skipped whole. They matter because <w:tab/> means two different things
        // depending on where it sits: a tab character when it is inside a run,
        // and the *definition of a tab stop* when it is inside w:tabs inside
        // w:pPr. Reading the second as the first indents every paragraph that has
        // a ruler set on it. Every one of these elements ends in "Pr", which is
        // the whole rule.
        var skipping: String? = null
        var skipDepth = 0

        var i = 0
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break

            if (xml.startsWith("<!--", lt)) {
                val end = xml.indexOf("-->", lt)
                i = if (end < 0) xml.length else end + 3
                continue
            }

            val gt = xml.indexOf('>', lt + 1)
            if (gt < 0) break
            val tag = xml.substring(lt + 1, gt)
            i = gt + 1

            if (tag.startsWith("?") || tag.startsWith("!")) continue

            val closing = tag.startsWith("/")
            val selfClosing = tag.endsWith("/")
            val name = localName(tag)

            if (skipping != null) {
                if (name == skipping) {
                    if (closing) {
                        skipDepth--
                        if (skipDepth == 0) skipping = null
                    } else if (!selfClosing) {
                        skipDepth++
                    }
                }
                continue
            }

            if (!closing && !selfClosing && name.length > 2 && name.endsWith("Pr")) {
                skipping = name
                skipDepth = 1
                continue
            }

            when {
                // A run of text. w:t has no child elements, so everything up to
                // the next tag is the text itself.
                //
                // w:delText - the other element that holds characters - is
                // deliberately not read. It is what a tracked change deleted, and
                // putting it back would show the player a line somebody took out.
                // w:instrText, which holds field codes rather than words, is
                // ignored for the same reason.
                name == "t" && !closing && !selfClosing -> {
                    val close = xml.indexOf('<', i)
                    if (close < 0) break
                    out.append(unescape(xml.substring(i, close)))
                    val closeEnd = xml.indexOf('>', close)
                    i = if (closeEnd < 0) xml.length else closeEnd + 1
                }

                name == "tab" && !closing -> out.append('\t')
                (name == "br" || name == "cr") && !closing -> out.append('\n')
                name == "noBreakHyphen" && !closing -> out.append('-')
                name == "p" && (closing || selfClosing) -> out.append('\n')

                // A table row becomes one line with its cells separated by tabs.
                // Charts drawn as a grid of bars are usually tables, and a cell
                // per line would take the grid apart.
                name == "tc" && closing -> {
                    out.dropTrailing('\n')
                    out.append('\t')
                }
                name == "tr" && closing -> {
                    out.dropTrailing('\t')
                    out.append('\n')
                }
            }
        }

        return tidy(out.toString())
    }

    /**
     * `w:t xml:space="preserve"` becomes `t`: attributes, the namespace prefix
     * and the slashes at either end all go.
     */
    private fun localName(tag: String): String {
        var start = 0
        var end = tag.length
        if (start < end && tag[start] == '/') start++
        if (end > start && tag[end - 1] == '/') end--
        var cut = start
        while (cut < end && !tag[cut].isWhitespace()) cut++
        return tag.substring(start, cut).substringAfterLast(':')
    }

    private fun unescape(text: String): String {
        if ('&' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = text.indexOf(';', i + 1)
            if (end < 0 || end - i > 12) {
                out.append(c)
                i++
                continue
            }
            when (val entity = text.substring(i + 1, end)) {
                "amp" -> out.append('&')
                "lt" -> out.append('<')
                "gt" -> out.append('>')
                "quot" -> out.append('"')
                "apos" -> out.append('\'')
                else -> {
                    val code = when {
                        entity.startsWith("#x") || entity.startsWith("#X") ->
                            entity.drop(2).toIntOrNull(16)
                        entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                        else -> null
                    }
                    if (code != null && code in 1..0x10FFFF) {
                        out.appendCodePoint(code)
                    } else {
                        out.append(text, i, end + 1)
                    }
                }
            }
            i = end + 1
        }
        return out.toString()
    }

    /**
     * Fixed-width cleanup, line by line.
     *
     * The non-breaking spaces are not a detail. Word writes them wherever it
     * decides a gap should not be broken, and `Character.isWhitespace` says they
     * are not whitespace - so a chord line padded with them splits into one
     * enormous token, fails the "every token on the line is a chord" test, and
     * the chart silently stops being transposable. Turning them back into
     * ordinary spaces costs nothing here and is invisible everywhere else.
     */
    private fun tidy(raw: String): String {
        val lines = raw
            .replace('\u00A0', ' ') // no-break space
            .replace('\u2007', ' ') // figure space
            .replace('\u202F', ' ') // narrow no-break space
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { expandTabs(it).trimEnd() }

        // Word ends nearly every document with an empty paragraph, and leaves a
        // run of them wherever somebody pressed return a few times.
        return lines.dropLastWhile { it.isEmpty() }.joinToString("\n")
    }

    private fun expandTabs(line: String): String {
        if ('\t' !in line) return line
        val out = StringBuilder(line.length + TAB_WIDTH)
        for (c in line) {
            if (c == '\t') {
                do {
                    out.append(' ')
                } while (out.length % TAB_WIDTH != 0)
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }

    private fun StringBuilder.dropTrailing(ch: Char) {
        while (isNotEmpty() && last() == ch) setLength(length - 1)
    }
}
