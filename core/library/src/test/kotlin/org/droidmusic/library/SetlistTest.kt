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
        assertEquals(FileKind.UNKNOWN, SongRef.kindOf("notes.docx"))
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
        assertFalse(ref("song.pdf").isTransposable)
        assertFalse(ref("song.png").isTransposable)
    }

    @Test
    fun `the best title falls back to the file name without its extension`() {
        val untitled = SongRef("i", "s", "u", "Wagon Wheel.pdf", FileKind.PDF)
        assertEquals("Wagon Wheel", untitled.bestTitle)
        assertEquals("Real Title", untitled.copy(title = "Real Title").bestTitle)
    }
}
