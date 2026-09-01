package org.droidmusic.session

import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.SongRef

/**
 * Finding this device's copy of the chart the leader is on.
 *
 * A [Position] names a song three ways, and only the last two mean anything
 * here. `songId` is derived from the source the chart was indexed from, and a
 * source id is a UUID generated on the device that added the folder - so the
 * leader's id for a song never matches the follower's, even when both are
 * reading byte-identical copies of the same file. A follower that looks up only
 * the id therefore fails on every song the leader opens, which presents as the
 * chart simply not arriving.
 *
 * So the id is tried first, because on the leader's own device it is exact and
 * free, and then the content hash and the title - the same two things a set list
 * entry carries across, and for the same reason.
 */
fun LibraryIndex.songFor(position: Position): SongRef? {
    val byId = position.songId?.let { findById(it) }
    if (byId != null) return byId

    val title = position.songTitle ?: return null
    return match(position.contentHash, title)
}
