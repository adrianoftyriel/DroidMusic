package org.droidmusic.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taking a set list from another device.
 *
 * The rule that matters most here is that the same running order arriving twice
 * is one set list. A leader pushes when the set starts and again when a check is
 * run; a follower that reconnects is sent it again; somebody mailed the file
 * opens it twice. Every one of those used to make another copy, and a band
 * finished a rehearsal with five identical lists.
 */
class AdoptTest {

    private fun song(id: String, name: String, hash: String) = SongRef(
        id = id,
        sourceId = "src",
        uri = "content://x/$id",
        displayName = name,
        kind = FileKind.CHORDPRO,
        contentHash = hash,
        title = name.substringBeforeLast('.'),
    )

    private val library = LibraryIndex(
        sources = listOf(SourceRef("src", SourceKind.MANAGED, "file:///", "On this device")),
        songs = listOf(
            song("local-1", "wagon-wheel.cho", "hash-wagon"),
            song("local-2", "jolene.cho", "hash-jolene"),
        ),
    )

    /** As the leader's device holds it: their ids, their hashes. */
    private val fromLeader = Setlist(
        id = "leader-list",
        name = "Friday at the Anchor",
        entries = listOf(
            SetlistEntry(songId = "their-1", title = "Wagon Wheel", contentHash = "hash-wagon"),
            SetlistEntry(songId = "their-2", title = "Jolene", contentHash = "hash-jolene"),
            SetlistEntry(songId = "their-3", title = "Copperhead Road", contentHash = "hash-copper"),
        ),
    )

    private var made = 0
    private fun newId(): String = "local-list-${++made}"

    @Test
    fun `entries are rewritten to point at this device's own charts`() {
        val import = SetlistCodec.adopt(fromLeader, emptyList(), library, now = 10, newId = ::newId)

        assertEquals(
            listOf("local-1", "local-2", "their-3"),
            import.setlist.entries.map { it.songId },
        )
        assertEquals(listOf("Copperhead Road"), import.missing.map { it.entry.title })
        assertFalse(import.allPresent)
    }

    @Test
    fun `the same list pushed twice is one list, not two`() {
        val first = SetlistCodec.adopt(fromLeader, emptyList(), library, now = 10, newId = ::newId)
        val second = SetlistCodec.adopt(
            fromLeader,
            listOf(first.setlist),
            library,
            now = 20,
            newId = ::newId,
        )

        assertTrue(second.replaced)
        assertEquals(first.setlist.id, second.setlist.id)
        assertEquals("leader-list", second.setlist.originId)
        // Created when it first arrived, updated when it arrived again.
        assertEquals(10L, second.setlist.createdAt)
        assertEquals(20L, second.setlist.updatedAt)
    }

    // A follower who adopted the list and then leads the next rehearsal pushes
    // its own copy on. The third device has to see one list, not a second one.
    @Test
    fun `a list passed on through a second device keeps its identity`() {
        val onSecondDevice = SetlistCodec
            .adopt(fromLeader, emptyList(), library, now = 10, newId = ::newId)
            .setlist

        val onThird = SetlistCodec
            .adopt(onSecondDevice, emptyList(), library, now = 30, newId = ::newId)
            .setlist
        val onThirdAgain = SetlistCodec
            .adopt(fromLeader, listOf(onThird), library, now = 40, newId = ::newId)

        assertEquals("leader-list", onThird.originId)
        assertTrue("the original and the relayed copy are one list", onThirdAgain.replaced)
        assertEquals(onThird.id, onThirdAgain.setlist.id)
    }

    @Test
    fun `a different list is a different list`() {
        val first = SetlistCodec.adopt(fromLeader, emptyList(), library, now = 10, newId = ::newId)
        val other = SetlistCodec.adopt(
            fromLeader.copy(id = "another-list", name = "Saturday"),
            listOf(first.setlist),
            library,
            now = 20,
            newId = ::newId,
        )

        assertFalse(other.replaced)
        assertNotEquals(first.setlist.id, other.setlist.id)
    }

    // A list this device made has no origin, and adopting something else must
    // never land on top of it.
    @Test
    fun `a local list of this device's own is left alone`() {
        val mine = Setlist(id = "mine", name = "My own", entries = emptyList())
        val import = SetlistCodec.adopt(fromLeader, listOf(mine), library, now = 10, newId = ::newId)

        assertFalse(import.replaced)
        assertNotEquals("mine", import.setlist.id)
        assertEquals("mine", mine.identity)
    }

    // The key is the band's and the capo is not. A leader who plays everything
    // with a capo on 3 must not put third-fret shapes on the keyboard player's
    // screen.
    @Test
    fun `an incoming list brings the key and leaves the capo alone`() {
        val withCapo = fromLeader.copy(
            entries = fromLeader.entries.map { it.copy(transposeSemitones = 2, capo = 3) },
        )
        val here = library.copy(
            songs = library.songs.map { if (it.id == "local-1") it.copy(userCapo = 5) else it },
        )

        val taken = SetlistCodec.adopt(withCapo, emptyList(), here, now = 10, newId = ::newId)

        assertEquals(listOf(2, 2, 2), taken.setlist.entries.map { it.transposeSemitones })
        // This player's own capo for the chart they have; nothing for the one
        // they have never played, and never the sender's.
        assertEquals(listOf(5, 0, 0), taken.setlist.entries.map { it.capo })
    }

    @Test
    fun `an adopted list keeps the leader's running order, not a local edit`() {
        val first = SetlistCodec.adopt(fromLeader, emptyList(), library, now = 10, newId = ::newId)
        val edited = first.setlist.moved(0, 2)

        val second = SetlistCodec.adopt(
            fromLeader,
            listOf(edited),
            library,
            now = 20,
            newId = ::newId,
        )
        assertEquals(
            listOf("Wagon Wheel", "Jolene", "Copperhead Road"),
            second.setlist.entries.map { it.title },
        )
    }
}
