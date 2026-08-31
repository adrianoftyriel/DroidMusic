package org.droidmusic.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetlistTest {

    private fun entry(id: String, title: String, hash: String? = null) =
        SetlistEntry(songId = id, title = title, contentHash = hash)

    private val set = Setlist(
        id = "s1",
        name = "Friday at the Anchor",
        entries = listOf(entry("a", "Wagon Wheel"), entry("b", "Folsom Prison"), entry("c", "Jolene")),
    )

    @Test
    fun `reordering moves one entry and keeps the rest`() {
        assertEquals(
            listOf("Jolene", "Wagon Wheel", "Folsom Prison"),
            set.moved(2, 0).entries.map { it.title },
        )
        assertEquals(
            listOf("Folsom Prison", "Jolene", "Wagon Wheel"),
            set.moved(0, 2).entries.map { it.title },
        )
    }

    @Test
    fun `an out of range move is ignored rather than throwing`() {
        assertEquals(set, set.moved(0, 9))
        assertEquals(set, set.moved(-1, 0))
        assertEquals(set, set.moved(1, 1))
    }

    @Test
    fun `removal and per entry edits work by index`() {
        assertEquals(2, set.removedAt(1).size)
        assertEquals(set, set.removedAt(7))
        val transposed = set.withEntryAt(1) { it.copy(transposeSemitones = 2) }
        assertEquals(2, transposed.entries[1].transposeSemitones)
        assertEquals(0, transposed.entries[0].transposeSemitones)
    }

    @Test
    fun `a bundle round trips through json`() {
        val bundle = SetlistCodec.bundle(set, exportedBy = "Jim", producer = "DroidMusic 0.1.0", now = 1000L)
        val decoded = SetlistCodec.decode(SetlistCodec.encode(bundle))
        assertNotNull(decoded)
        assertEquals(bundle, decoded)
        assertEquals(set.entries.size, decoded!!.setlist.entries.size)
    }

    // The set list arrives from someone else's phone. It must not be able to
    // crash this one.
    @Test
    fun `malformed input decodes to null instead of throwing`() {
        assertNull(SetlistCodec.decode(""))
        assertNull(SetlistCodec.decode("not json at all"))
        assertNull(SetlistCodec.decode("{\"formatVersion\": 1}"))
        assertNull(SetlistCodec.decode("[1,2,3]"))
    }

    @Test
    fun `unknown fields from a newer writer are tolerated`() {
        val text = """
            {"formatVersion":1,"setlist":{"id":"s1","name":"X","entries":[]},"somethingNew":42}
        """.trimIndent()
        assertNotNull(SetlistCodec.decode(text))
    }

    @Test
    fun `a newer format version is refused rather than half read`() {
        val bundle = SetlistBundle(formatVersion = 99, setlist = set)
        assertFalse(SetlistCodec.canRead(bundle))
        assertTrue(SetlistCodec.canRead(SetlistBundle(setlist = set)))
    }

    @Test
    fun `exported file names are safe`() {
        assertEquals(
            "Friday-at-the-Anchor.dmset",
            SetlistCodec.fileName(set),
        )
        assertEquals("setlist.dmset", SetlistCodec.fileName(set.copy(name = "///")))
        assertEquals("AC-DC-night.dmset", SetlistCodec.fileName(set.copy(name = "AC/DC night")))
    }
}

class LibraryMatchingTest {

    private fun song(id: String, name: String, hash: String?, title: String? = null) = SongRef(
        id = id,
        sourceId = "src",
        uri = "content://x/$id",
        displayName = name,
        kind = SongRef.kindOf(name),
        contentHash = hash,
        title = title,
    )

    private val library = LibraryIndex(
        songs = listOf(
            song("1", "wagon-wheel.pro", "hash-ww", "Wagon Wheel"),
            song("2", "Folsom Prison Blues.pdf", "hash-fp"),
            song("3", "the jolene chart.txt", "hash-j", "The Jolene"),
        ),
    )

    @Test
    fun `content hash matches exactly`() {
        assertEquals("2", library.match("hash-fp", "something else entirely")?.id)
    }

    // Two devices will have different bytes for the same song - a different scan,
    // a ChordPro against a PDF. Falling back to the title is what makes a shared
    // set list usable rather than a list of things nobody has.
    @Test
    fun `title matching is the fallback when the bytes differ`() {
        assertEquals("1", library.match("some-other-hash", "Wagon Wheel")?.id)
        assertEquals("2", library.match(null, "Folsom Prison Blues")?.id)
    }

    @Test
    fun `title matching ignores case punctuation and a leading article`() {
        assertEquals("1", library.match(null, "wagon wheel")?.id)
        assertEquals("1", library.match(null, "Wagon-Wheel")?.id)
        assertEquals("3", library.match(null, "Jolene")?.id)
        assertEquals("3", library.match(null, "The Jolene")?.id)
    }

    @Test
    fun `a song nobody has is reported missing rather than guessed at`() {
        assertNull(library.match("nope", "Something Nobody Owns"))
    }

    @Test
    fun `resolving a shared set list says exactly what is missing`() {
        val shared = Setlist(
            id = "s",
            name = "Set",
            entries = listOf(
                SetlistEntry(songId = "remote-1", title = "Wagon Wheel", contentHash = "hash-ww"),
                SetlistEntry(songId = "remote-2", title = "A Song Nobody Has"),
                SetlistEntry(songId = "remote-3", title = "Folsom Prison Blues"),
            ),
        )
        val result = SetlistCodec.resolve(shared, library)
        assertFalse(result.allPresent)
        assertEquals(listOf("A Song Nobody Has"), result.missing.map { it.entry.title })
        assertEquals(listOf("1", null, "2"), result.resolved.map { it.localSongId })
    }
}

class FileKindTest {

    @Test
    fun `extensions map to the renderer that can open them`() {
        assertEquals(FileKind.PDF, SongRef.kindOf("chart.pdf"))
        assertEquals(FileKind.IMAGE, SongRef.kindOf("page1.PNG"))
        assertEquals(FileKind.IMAGE, SongRef.kindOf("scan.jpeg"))
        assertEquals(FileKind.CHORDPRO, SongRef.kindOf("song.pro"))
        assertEquals(FileKind.CHORDPRO, SongRef.kindOf("song.cho"))
        assertEquals(FileKind.TEXT, SongRef.kindOf("riff.tab"))
        assertEquals(FileKind.DOCX, SongRef.kindOf("notes.docx"))

        // The old binary format is not read - see DocxText. Saying so here keeps
        // a later "well, .doc is nearly the same thing" from being an easy edit.
        assertEquals(FileKind.UNKNOWN, SongRef.kindOf("notes.doc"))
        assertEquals(FileKind.UNKNOWN, SongRef.kindOf("band.zip"))
    }

    @Test
    fun `mime type is used when the name has no extension`() {
        assertEquals(FileKind.PDF, SongRef.kindOf("document", "application/pdf"))
        assertEquals(FileKind.IMAGE, SongRef.kindOf("document", "image/png"))
        assertEquals(FileKind.TEXT, SongRef.kindOf("document", "text/plain"))
    }

    @Test
    fun `only text charts can be transposed`() {
        fun ref(name: String) = SongRef("i", "s", "u", name, SongRef.kindOf(name))
        assertTrue(ref("song.pro").isTransposable)
        assertTrue(ref("song.txt").isTransposable)
        assertTrue(ref("song.docx").isTransposable)
        assertFalse(ref("song.pdf").isTransposable)
        assertFalse(ref("song.png").isTransposable)
    }

    @Test
    fun `the best title falls back to the file name without its extension`() {
        val untitled = SongRef("i", "s", "u", "Wagon Wheel.pdf", FileKind.PDF)
        assertEquals("Wagon Wheel", untitled.bestTitle)
        assertEquals("Real Title", untitled.copy(title = "Real Title").bestTitle)
    }

    @Test
    fun `every chordpro extension is a chart`() {
        for (extension in SongRef.CHORDPRO_EXTENSIONS) {
            assertEquals(
                "song.$extension",
                FileKind.CHORDPRO,
                SongRef.kindOf("song.$extension"),
            )
        }
    }

    /**
     * ChordPro has no registered MIME type, so every provider on the device
     * calls one an `application/octet-stream` - the same answer it gives for a
     * firmware image. The extension has to win.
     */
    @Test
    fun `a chart is still a chart when the provider calls it a blob`() {
        assertEquals(
            FileKind.CHORDPRO,
            SongRef.kindOf("Wayfaring Stranger.cho", "application/octet-stream"),
        )
    }

    @Test
    fun `a name with dots in it is judged on the last one`() {
        assertEquals(FileKind.CHORDPRO, SongRef.kindOf("Ain't No Sunshine (live).v2.cho"))
        assertEquals(FileKind.PDF, SongRef.kindOf("The Band. Best of.pdf"))
    }

    @Test
    fun `every extension the picker offers is one the app can open`() {
        for (extension in SongRef.ALL_EXTENSIONS) {
            assertTrue(
                "kindOf did not recognise .$extension",
                SongRef.kindOf("chart.$extension") != FileKind.UNKNOWN,
            )
        }
    }
}

/**
 * The last resort when a file's name says nothing: read the start of it and ask
 * whether it is text.
 *
 * This is what lets a chart called `.songbook`, or called nothing at all, still
 * open. The extension list is a convenience, not a gate - somebody else's
 * convention is not wrong, it is just not on a list this app happened to write
 * down.
 */
class TextSniffTest {

    @Test
    fun `a chordpro file reads as text`() {
        val chart = """
            {title: Wayfaring Stranger}
            {key: Am}

            [Am]I am a poor way[C]faring [G]stranger
        """.trimIndent().toByteArray()
        assertTrue(SongRef.looksLikeText(chart))
    }

    @Test
    fun `a binary file does not`() {
        // A PNG header: the NUL in the second word is the giveaway.
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        )
        assertFalse(SongRef.looksLikeText(png))
    }

    @Test
    fun `an empty file is not text`() {
        assertFalse(SongRef.looksLikeText(ByteArray(0)))
        assertFalse(SongRef.looksLikeText(ByteArray(16), length = 0))
    }

    @Test
    fun `only the bytes that were read are judged`() {
        // A 4k buffer holding a 27-byte chart is 4k of NUL after it, and reading
        // the whole buffer would call every short chart a binary.
        val buffer = ByteArray(4096)
        val chart = "{title: Short}\n[C]One line\n".toByteArray()
        chart.copyInto(buffer)
        assertTrue(SongRef.looksLikeText(buffer, chart.size))
        assertFalse(SongRef.looksLikeText(buffer))
    }

    @Test
    fun `accented lyrics and unicode accidentals are text`() {
        val chart = "{title: \u00c9t\u00e9}\n[B\u266d]O\u00f9 sont les mots\n"
            .toByteArray(Charsets.UTF_8)
        assertTrue(SongRef.looksLikeText(chart))
    }

    @Test
    fun `a stray control character does not condemn a whole chart`() {
        // A chart exported from a word processor sometimes carries one.
        val chart = ("{title: Exported}\n\u000b" + "[C]Words and more words\n".repeat(20))
            .toByteArray()
        assertTrue(SongRef.looksLikeText(chart))
    }

    @Test
    fun `a file that is mostly control bytes is not a chart`() {
        val noisy = ByteArray(200) { if (it % 2 == 0) 0x01 else 0x41 }
        assertFalse(SongRef.looksLikeText(noisy))
    }
}
