package org.droidmusic.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One song as it appears in a set list.
 *
 * The per-entry [transposeSemitones] and [capo] are the reason a set list is not
 * just a list of song ids: the same chart is sung in different keys by different
 * singers, and the set list is where that decision belongs. Leaving it on the
 * song would mean a chart could only ever be in one key.
 *
 * [title] and [contentHash] are carried alongside [songId] so the entry can
 * still be resolved on a device where that id means nothing - which is every
 * device except the one that made the list.
 */
@Serializable
data class SetlistEntry(
    val songId: String,
    val title: String,
    val contentHash: String? = null,
    val artist: String? = null,
    val transposeSemitones: Int = 0,
    val capo: Int = 0,
    val targetKeyText: String? = null,
    /** Shown to the band, not the audience: "segue into next", "capo 3, watch the tag". */
    val note: String? = null,
)

@Serializable
data class Setlist(
    val id: String,
    val name: String,
    val entries: List<SetlistEntry> = emptyList(),
    val venue: String? = null,
    val date: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /**
     * Where this list came from, when it came from somewhere else.
     *
     * The id of the copy on the device that first made it. It exists so that a
     * set list arriving twice is recognised as the same set list: a leader
     * pushes the running order when the set is started and again when a check is
     * run, a follower reconnecting is sent it again, and somebody who was mailed
     * the file opens it twice. Without an identity that survives the crossing,
     * each of those was a fresh copy, and a band ended a rehearsal with five
     * identical lists.
     *
     * Null on a list this device made itself.
     */
    val originId: String? = null,
) {
    val size: Int get() = entries.size

    /**
     * What this list *is*, across devices. Its origin if it has one, otherwise
     * its own id - which is what a list being pushed on by a follower who
     * adopted it should carry, so a third device recognises it as one list
     * rather than as two.
     */
    val identity: String get() = originId ?: id

    fun withEntryAt(index: Int, transform: (SetlistEntry) -> SetlistEntry): Setlist =
        copy(entries = entries.mapIndexed { i, e -> if (i == index) transform(e) else e })

    fun moved(from: Int, to: Int): Setlist {
        if (from !in entries.indices || to !in entries.indices || from == to) return this
        val list = entries.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(entries = list)
    }

    fun removedAt(index: Int): Setlist =
        if (index !in entries.indices) this
        else copy(entries = entries.filterIndexed { i, _ -> i != index })
}

/**
 * A set list packaged for another device.
 *
 * This is the file that gets shared, and it is plain JSON on purpose. A band
 * mate on a phone that has never run this app should still be able to open the
 * attachment and see what the set is; a binary format would buy nothing and cost
 * that.
 *
 * [formatVersion] is checked on import. An older app meeting a newer file needs
 * to say so plainly rather than silently drop the fields it does not know about,
 * which for a set list would mean quietly losing somebody's transpositions.
 */
@Serializable
data class SetlistBundle(
    val formatVersion: Int = FORMAT_VERSION,
    val setlist: Setlist,
    val exportedBy: String? = null,
    val exportedAt: Long = 0L,
    /** App version that wrote the file, for support rather than for logic. */
    val producer: String? = null,
) {
    companion object {
        const val FORMAT_VERSION = 1

        /** The extension and MIME type a shared set list uses. */
        const val EXTENSION = "dmset"
        const val MIME_TYPE = "application/json"
    }
}

/** What happened when a shared set list met this device's library. */
data class SetlistImport(
    val setlist: Setlist,
    val resolved: List<ResolvedEntry>,
    /** True when this replaced a copy of the same list already on the device. */
    val replaced: Boolean = false,
) {
    val missing: List<ResolvedEntry> get() = resolved.filter { it.localSongId == null }
    val allPresent: Boolean get() = missing.isEmpty()
}

data class ResolvedEntry(val entry: SetlistEntry, val localSongId: String?)

object SetlistCodec {

    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(bundle: SetlistBundle): String =
        json.encodeToString(SetlistBundle.serializer(), bundle)

    /**
     * Reads a shared set list. Returns null rather than throwing on anything
     * malformed, because this is fed by files from other people's devices and a
     * crash on a bad attachment is not an acceptable failure mode.
     */
    fun decode(text: String): SetlistBundle? = runCatching {
        json.decodeFromString(SetlistBundle.serializer(), text)
    }.getOrNull()

    /**
     * True if this build can be trusted to read the file without losing
     * information. A newer major format is refused rather than half-read.
     */
    fun canRead(bundle: SetlistBundle): Boolean =
        bundle.formatVersion <= SetlistBundle.FORMAT_VERSION

    /**
     * Matches every entry in a shared set list against the local library, by
     * content hash first and title second.
     */
    fun resolve(setlist: Setlist, library: LibraryIndex): SetlistImport = SetlistImport(
        setlist = setlist,
        resolved = setlist.entries.map { entry ->
            ResolvedEntry(entry, library.match(entry.contentHash, entry.title)?.id)
        },
    )

    /**
     * Takes a set list from elsewhere and makes it this device's own.
     *
     * Two jobs, and the second is the one that was missing. Song ids are
     * rewritten to point at local copies, by content hash and then title, so the
     * entries resolve here. And the list is matched against what this device has
     * already adopted, by [Setlist.identity], so that the same running order
     * arriving a second time *replaces* the copy already here instead of sitting
     * beside it.
     *
     * Replacing rather than merging is deliberate. An incoming push is the
     * leader saying what the band is playing tonight; if it disagrees with a
     * local edit, the leader is right, and a set list that quietly kept a
     * follower's older order would be worse than one that changed under them.
     *
     * [newId] is passed in rather than generated here so the rule stays a pure
     * function - the same inputs give the same list, which is what makes it
     * worth testing.
     */
    fun adopt(
        incoming: Setlist,
        existing: List<Setlist>,
        library: LibraryIndex,
        now: Long,
        newId: () -> String,
    ): SetlistImport {
        val resolution = resolve(incoming, library)
        val identity = incoming.identity
        val alreadyHere = existing.firstOrNull { it.identity == identity }

        val localised = incoming.copy(
            id = alreadyHere?.id ?: newId(),
            originId = identity,
            entries = resolution.resolved.map { resolved ->
                resolved.localSongId?.let { resolved.entry.copy(songId = it) } ?: resolved.entry
            },
            // Kept from the copy already here, so a list adopted at the start of
            // a rehearsal does not claim to have been created at the moment of
            // its third push.
            createdAt = alreadyHere?.createdAt ?: incoming.createdAt.takeIf { it > 0L } ?: now,
            updatedAt = now,
        )
        return SetlistImport(localised, resolution.resolved, replaced = alreadyHere != null)
    }

    /** Builds the shareable form of a set list held on this device. */
    fun bundle(setlist: Setlist, exportedBy: String?, producer: String?, now: Long): SetlistBundle =
        SetlistBundle(
            setlist = setlist,
            exportedBy = exportedBy,
            exportedAt = now,
            producer = producer,
        )

    /** A filesystem-safe name for the exported file. */
    fun fileName(setlist: Setlist): String {
        // Disallowed characters become a word break rather than vanishing, so
        // "AC/DC night" exports as AC-DC-night and not ACDC-night.
        val base = setlist.name
            .replace(Regex("[^A-Za-z0-9 _-]"), " ")
            .trim()
            .replace(Regex("[\\s_-]+"), "-")
            .ifEmpty { "setlist" }
        return "$base.${SetlistBundle.EXTENSION}"
    }
}
