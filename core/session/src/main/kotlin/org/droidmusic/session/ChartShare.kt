package org.droidmusic.session

import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.Setlist

/**
 * Deciding what travels between a leader and a follower, and what does not.
 *
 * Every rule here is a rule about somebody else's device writing a file onto
 * yours, so all of it lives in the core with tests around it rather than inside
 * the socket code where none of it could be run without two phones and a pub.
 *
 * The protocol this rides on is deliberately unauthenticated - it was designed
 * to carry a page number, and anyone on the network can join a session. Moving
 * files across it is a different proposition, and it is only defensible because
 * of the bounds in this file:
 *
 *  - A follower only ever fetches charts **it asked for**, worked out from a set
 *    list it was sent. A leader cannot push a file at somebody.
 *  - The bytes are checked against the hash that was offered, so what arrives is
 *    what was described.
 *  - Sizes and counts are capped, so a session cannot fill a phone.
 *  - The name is rebuilt here rather than trusted, so no sender chooses a path.
 *  - Everything lands in the app's own storage, never in the user's folders.
 *
 * None of that makes an open venue network safe. It makes the damage bounded,
 * which is the most a protocol with no identity can offer. See docs/PROTOCOL.md.
 */
object ChartShare {

    /**
     * The largest single chart that will be fetched.
     *
     * A scanned songbook is the reason this is generous rather than small, and
     * the reason it exists at all: without a ceiling one file can fill a phone
     * during a soundcheck.
     */
    const val MAX_CHART_BYTES = 64L * 1024 * 1024

    /** The most that will be fetched in one session, across all charts. */
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /** The most charts one session will fetch, whatever they weigh. */
    const val MAX_CHARTS = 60

    /** How long a name may be once it has been made safe. */
    const val MAX_NAME_LENGTH = 120

    /**
     * What a follower is missing out of a set list it has just been sent.
     *
     * Resolution is [LibraryIndex.match]'s job - hash first, then title - so a
     * chart the follower already has under a different name, or as a different
     * file, is not asked for. Which is the common case: two people rarely have
     * byte-identical copies of the same song.
     */
    fun wanted(setlist: Setlist, library: LibraryIndex): List<ChartWant> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ChartWant>()
        for (entry in setlist.entries) {
            if (library.match(entry.contentHash, entry.title) != null) continue
            // One want per chart, however many times the set list names it - an
            // encore is the same file.
            val key = entry.contentHash ?: entry.title.lowercase()
            if (!seen.add(key)) continue
            out += ChartWant(contentHash = entry.contentHash, title = entry.title)
        }
        return out
    }

    /**
     * What the leader can actually supply of what was asked for.
     *
     * A chart with no content hash is not offered. The hash is what the follower
     * checks the bytes against, and something that cannot be checked on arrival
     * should not be sent at all.
     */
    fun offers(wants: List<ChartWant>, library: LibraryIndex): List<ChartOffer> {
        val out = mutableListOf<ChartOffer>()
        val sent = mutableSetOf<String>()
        for (want in wants) {
            val song = library.match(want.contentHash, want.title) ?: continue
            val hash = song.contentHash ?: continue
            if (!sent.add(hash)) continue
            out += ChartOffer(
                contentHash = hash,
                title = song.bestTitle,
                displayName = song.displayName,
                kind = song.kind,
                sizeBytes = song.sizeBytes,
                artist = song.artist,
                keyText = song.soundingKey?.toString(),
            )
        }
        return out
    }

    /**
     * A whole library as a catalogue, for the aggregated view.
     *
     * The same shape [offers] produces, built without anybody having asked for
     * anything - which is the difference between the two: an offer answers a
     * request, a catalogue is a standing statement of what this device has.
     *
     * A chart with no content hash is left out. The hash is how a chart is
     * asked for and how the bytes are checked on arrival, so one without it
     * could be listed and never fetched, and a row that fails when tapped is
     * worse than a row that is not there.
     */
    fun catalogueOf(library: LibraryIndex): List<ChartOffer> =
        library.visible
            .filter { it.contentHash != null }
            .distinctBy { it.contentHash }
            .map { song ->
                ChartOffer(
                    contentHash = song.contentHash.orEmpty(),
                    title = song.bestTitle,
                    displayName = song.displayName,
                    kind = song.kind,
                    sizeBytes = song.sizeBytes,
                    artist = song.artist,
                    keyText = song.soundingKey?.toString(),
                )
            }

    /** An offer that was turned down, and something to tell the player. */
    data class Refusal(val offer: ChartOffer, val reason: String)

    data class Acceptance(val accepted: List<ChartOffer>, val refused: List<Refusal>) {
        val totalBytes: Long get() = accepted.sumOf { it.sizeBytes }
    }

    /**
     * Applies the caps to what was offered.
     *
     * Refusals are kept with a reason rather than dropped, because a chart that
     * silently did not arrive is the failure this whole feature exists to
     * prevent. Being told "that one is too big, get it another way" is a
     * different evening from finding out on stage.
     *
     * Offers are taken in the order they were made, which is set list order, so
     * a cap that bites takes the end of the set rather than an arbitrary
     * selection.
     */
    fun accept(offers: List<ChartOffer>): Acceptance {
        val accepted = mutableListOf<ChartOffer>()
        val refused = mutableListOf<Refusal>()
        var total = 0L

        for (offer in offers) {
            when {
                offer.sizeBytes > MAX_CHART_BYTES -> refused += Refusal(
                    offer,
                    "too big to send (${megabytes(offer.sizeBytes)}, limit " +
                        "${megabytes(MAX_CHART_BYTES)})",
                )

                accepted.size >= MAX_CHARTS -> refused += Refusal(
                    offer,
                    "more than $MAX_CHARTS charts in one session",
                )

                total + offer.sizeBytes > MAX_TOTAL_BYTES -> refused += Refusal(
                    offer,
                    "would take the session over ${megabytes(MAX_TOTAL_BYTES)}",
                )

                else -> {
                    accepted += offer
                    total += offer.sizeBytes
                }
            }
        }
        return Acceptance(accepted, refused)
    }

    /** "12 MB", for a sentence somebody reads before agreeing to it. */
    fun megabytes(bytes: Long): String = when {
        bytes <= 0 -> "unknown size"
        bytes < 1024L * 1024 -> "${(bytes + 1023) / 1024} KB"
        else -> "${(bytes + 1024L * 1024 - 1) / (1024L * 1024)} MB"
    }

    /**
     * A filename that arrived from another device, rebuilt into one that is safe
     * to create.
     *
     * Not sanitised so much as reconstructed: the extension is taken, the stem is
     * taken, and everything that is not a plain character in either is dropped.
     * A name is chosen by whoever is at the other end of an unauthenticated
     * socket, so `../../databases/library.json` has to be impossible rather than
     * unlikely, and on a case-insensitive filesystem so does `..`.
     */
    fun safeFileName(displayName: String, fallback: String = "chart"): String {
        val trimmed = displayName.trim().substringAfterLast('/').substringAfterLast('\\')
        val extension = trimmed.substringAfterLast('.', "").filter { it.isLetterOrDigit() }.take(8)
        val stem = trimmed.substringBeforeLast('.', trimmed)
            .map { if (it.isLetterOrDigit() || it in " -_()&,'" ) it else ' ' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(MAX_NAME_LENGTH)

        val safeStem = stem.ifEmpty { fallback }
        return if (extension.isEmpty()) safeStem else "$safeStem.$extension"
    }

    /**
     * Whether a chart that has arrived is the chart that was offered.
     *
     * Checked before it is installed, never after. The hash is the same one the
     * library computes for its own files, so this is the receiving device asking
     * its own indexer whether it got what it was promised, rather than trusting
     * the sender's description of it.
     */
    fun matchesOffer(offer: ChartOffer, receivedHash: String?, receivedBytes: Long): Boolean {
        if (receivedHash == null) return false
        if (receivedHash != offer.contentHash) return false
        // A declared size of zero means the provider never said, which is common
        // enough for a cloud file that it cannot be treated as a mismatch.
        return offer.sizeBytes <= 0 || receivedBytes == offer.sizeBytes
    }

    /** What to call the file a fetched chart is stored as. */
    fun storedName(offer: ChartOffer): String =
        safeFileName(offer.displayName, fallback = safeFileName(offer.title, "chart"))
}

/** A chart the follower is fetching, and how far it has got. */
data class ChartTransfer(
    val offer: ChartOffer,
    val receivedBytes: Long = 0,
    val done: Boolean = false,
    val failed: String? = null,
) {
    val fraction: Float
        get() = if (offer.sizeBytes <= 0) 0f else (receivedBytes.toFloat() / offer.sizeBytes).coerceIn(0f, 1f)
}

/** Everything the follower knows about charts coming from the leader. */
data class ChartSharing(
    /** Offered and not yet answered, so the player can be asked once. */
    val pending: List<ChartOffer> = emptyList(),
    val refused: List<ChartShare.Refusal> = emptyList(),
    val transfers: List<ChartTransfer> = emptyList(),
    /** Set once the player has answered, so they are not asked twice in a session. */
    val answered: Boolean = false,
) {
    val active: List<ChartTransfer> get() = transfers.filter { !it.done && it.failed == null }
    val arrived: Int get() = transfers.count { it.done }
    val failed: List<ChartTransfer> get() = transfers.filter { it.failed != null }
}
