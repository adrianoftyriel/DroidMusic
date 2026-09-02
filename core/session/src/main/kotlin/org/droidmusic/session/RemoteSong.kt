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

/**
 * The arrangement a follower plays when the leader announces a position.
 *
 * Two numbers that look alike and are not the same kind of thing at all.
 *
 * **The key is the band's.** Transposing is the singer saying tonight this one
 * is in B flat, and a band where that reached only one phone is a band playing
 * two different songs. So a leader's transposition applies everywhere, and
 * arrives the moment they choose it rather than at the next page turn.
 *
 * **The capo is one player's.** It changes nothing anybody hears - it is how a
 * guitarist chooses to finger the same key, and it means nothing at all to the
 * keyboard player, the horn player, or the guitarist who capos somewhere else.
 * A leader's capo travelling with the position put the leader's fingering on
 * everybody's screen, which for half a band is shapes for an instrument they are
 * not holding.
 *
 * So the capo in a [Position] is advisory: it says what the leader is fingering,
 * and every device keeps its own.
 */
data class Arrangement(val transposeSemitones: Int, val capo: Int)

/** What this device should play, given [localCapo] as it already had it. */
fun Position.arrangementFor(localCapo: Int): Arrangement =
    Arrangement(transposeSemitones = transposeSemitones, capo = localCapo)
