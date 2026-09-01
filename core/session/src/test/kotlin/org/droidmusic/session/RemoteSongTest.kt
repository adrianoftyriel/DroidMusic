package org.droidmusic.session

import org.droidmusic.library.FileKind
import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.SongRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Finding the leader's song in this device's library.
 *
 * The case that made this necessary: a leader's song id is derived from the id
 * of the source it was indexed from, and that is a UUID generated on the device
 * that added the folder. It therefore matches nothing on any other device, and a
 * follower that looked up only the id followed the leader's page turns
 * perfectly while never once opening the song they were on.
 */
class RemoteSongTest {

    private fun song(id: String, name: String, hash: String?) = SongRef(
        id = id,
        sourceId = "src",
        uri = "content://x/$id",
        displayName = name,
        kind = FileKind.CHORDPRO,
        contentHash = hash,
        title = name,
    )

    private val library = LibraryIndex(
        songs = listOf(
            song("local-1", "Wagon Wheel", "hash-wagon"),
            song("local-2", "The Jolene", null),
        ),
    )

    private fun position(
        songId: String? = "their-1",
        title: String? = "Wagon Wheel",
        hash: String? = "hash-wagon",
    ) = Position(
        seq = 1,
        setlistIndex = 0,
        songId = songId,
        songTitle = title,
        contentHash = hash,
        page = 0,
    )

    @Test
    fun `a song id that means something here wins`() {
        assertEquals("local-1", library.songFor(position(songId = "local-1"))?.id)
    }

    @Test
    fun `an id from another device falls through to the content hash`() {
        assertEquals("local-1", library.songFor(position())?.id)
    }

    @Test
    fun `without a hash in common the title is enough`() {
        val found = library.songFor(position(songId = "their-9", title = "Jolene", hash = null))
        assertEquals("local-2", found?.id)
    }

    // A hash that matches nothing must not stop the title from being tried: two
    // people's copies of the same chart are rarely the same bytes.
    @Test
    fun `a hash nobody has still finds the song by title`() {
        val found = library.songFor(position(songId = "their-9", title = "Wagon Wheel", hash = "hash-other"))
        assertEquals("local-1", found?.id)
    }

    @Test
    fun `a song this device has not got is not guessed at`() {
        assertNull(library.songFor(position(songId = "their-9", title = "Copperhead Road", hash = "x")))
    }

    @Test
    fun `a position with no song at all resolves to nothing`() {
        assertNull(library.songFor(position(songId = null, title = null, hash = null)))
    }
}
