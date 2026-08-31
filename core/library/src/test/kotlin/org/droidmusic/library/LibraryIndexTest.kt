package org.droidmusic.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a press and hold on a chart does to the library, tested where it can be
 * tested: the bookkeeping, not the menu.
 *
 * All three of these actions are the kind whose failure is silent. A rename that
 * did not survive a rescan, a removed chart that came back the next time a folder
 * was read, a corrected key quietly dropped by a merge - none of them look like
 * an error, they look like the app forgetting things, which is the reason to have
 * the rule in the core with a test around it rather than in the repository that
 * happens to call it.
 */
class LibraryIndexTest {

    private fun song(
        id: String,
        uri: String = "content://tree/$id",
        displayName: String = "$id.cho",
        title: String? = null,
        sourceId: String = "folder",
    ) = SongRef(
        id = id,
        sourceId = sourceId,
        uri = uri,
        displayName = displayName,
        kind = FileKind.CHORDPRO,
        title = title,
    )

    // ---- What a chart is called ------------------------------------------

    @Test
    fun `a title comes from the user, then the file, then the filename`() {
        val plain = song("a", displayName = "everlong-v2.cho")
        assertEquals("everlong-v2", plain.bestTitle)

        val declared = plain.copy(title = "Everlong")
        assertEquals("Everlong", declared.bestTitle)

        val renamed = declared.copy(userTitle = "Everlong (acoustic)")
        assertEquals("Everlong (acoustic)", renamed.bestTitle)
    }

    @Test
    fun `the detected title ignores a rename, so the rename dialog can offer it`() {
        val song = song("a", displayName = "ev.cho", title = "Everlong")
            .copy(userTitle = "Something else")
        assertEquals("Everlong", song.detectedTitle)
    }

    @Test
    fun `a blank rename clears the override rather than storing it`() {
        val song = song("a", title = "Everlong").copy(userTitle = "Wrong")
        assertNull(song.withRename("").userTitle)
        assertNull(song.withRename("   ").userTitle)
        assertEquals("Everlong", song.withRename("").bestTitle)
    }

    @Test
    fun `renaming a chart to the name it already has clears the override`() {
        // Otherwise the redundant override outlives every later correction to the
        // file, and fixing a typo in the chart's own title appears to do nothing.
        val song = song("a", title = "Everlong")
        assertNull(song.withRename("Everlong").userTitle)
    }

    @Test
    fun `a rename is trimmed`() {
        assertEquals("Everlong", song("a").withRename("  Everlong  ").userTitle)
    }

    // ---- Removing a chart from the library --------------------------------

    @Test
    fun `a removed chart is kept in the index but not listed`() {
        val index = LibraryIndex(songs = listOf(song("a"), song("b").copy(hidden = true)))
        assertEquals(listOf("a"), index.visible.map { it.id })
        assertEquals(2, index.songs.size)
        assertEquals(1, index.hiddenCount)
    }

    @Test
    fun `a removed chart cannot be reached by id or by matching`() {
        // The library list is not the only door. A set list entry arrives by id,
        // and a follower in a band session arrives by hash or title.
        val hidden = song("b", title = "Everlong").copy(hidden = true, contentHash = "abc")
        val index = LibraryIndex(songs = listOf(song("a"), hidden))

        assertNull(index.findById("b"))
        assertNull(index.match("abc", "Everlong"))
        assertNull(index.match(null, "Everlong"))
    }

    @Test
    fun `a chart that is not removed is still reachable`() {
        val song = song("a", title = "Everlong").copy(contentHash = "abc")
        val index = LibraryIndex(songs = listOf(song))
        assertEquals("a", index.findById("a")?.id)
        assertEquals("a", index.match("abc", "nothing like it")?.id)
        assertEquals("a", index.match(null, "everlong")?.id)
    }

    // ---- Surviving a rescan ----------------------------------------------

    @Test
    fun `a rescan keeps everything the user set`() {
        val before = song("a", title = "Everlong").copy(
            userKeyText = "Db",
            userTitle = "Everlong (capo 2)",
            hidden = true,
            favourite = true,
            tags = listOf("rock"),
        )
        val index = LibraryIndex(songs = listOf(before))

        // The scan returns a fresh, unadorned reading of the same file.
        val fresh = song("a-new-id", uri = before.uri, title = "Everlong")
        val after = index.withSongsFrom("folder", listOf(fresh), now = 99L).songs.single()

        assertEquals("Db", after.userKeyText)
        assertEquals("Everlong (capo 2)", after.userTitle)
        assertTrue(after.hidden)
        assertTrue(after.favourite)
        assertEquals(listOf("rock"), after.tags)
        assertEquals(99L, index.withSongsFrom("folder", listOf(fresh), 99L).updatedAt)
    }

    @Test
    fun `a rescan does not bring back a chart the user removed`() {
        // The reason `hidden` is remembered in the index instead of the chart
        // simply being dropped from it: dropping it works until the next rescan
        // walks the folder and finds the file again.
        val index = LibraryIndex(songs = listOf(song("a").copy(hidden = true)))
        val rescanned = index.withSongsFrom("folder", listOf(song("a")), now = 1L)
        assertTrue(rescanned.visible.isEmpty())
        assertEquals(1, rescanned.hiddenCount)
    }

    @Test
    fun `a rescan matches by uri, not by id`() {
        // A provider is free to hand out a different document id for the same
        // file; the URI is what the app opened last time and will open next time.
        val before = song("old-id", uri = "content://tree/x").copy(userTitle = "Mine")
        val index = LibraryIndex(songs = listOf(before))
        val fresh = song("new-id", uri = "content://tree/x")
        assertEquals("Mine", index.withSongsFrom("folder", listOf(fresh), 1L).songs.single().userTitle)
    }

    @Test
    fun `a rescan forgets a file that is no longer there and keeps other sources`() {
        val index = LibraryIndex(
            songs = listOf(
                song("gone"),
                song("kept"),
                song("elsewhere", sourceId = "other"),
            ),
        )
        val after = index.withSongsFrom("folder", listOf(song("kept")), now = 1L)
        assertEquals(setOf("kept", "elsewhere"), after.songs.map { it.id }.toSet())
    }

    @Test
    fun `a rescan of an empty folder leaves other sources alone`() {
        val index = LibraryIndex(songs = listOf(song("a"), song("b", sourceId = "other")))
        val after = index.withSongsFrom("folder", emptyList(), now = 1L)
        assertEquals(listOf("b"), after.songs.map { it.id })
    }
}
