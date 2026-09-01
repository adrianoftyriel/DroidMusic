package org.droidmusic.app.net

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.droidmusic.app.data.DocumentSources
import org.droidmusic.app.data.LibraryRepository
import org.droidmusic.library.SongRef
import org.droidmusic.session.ChartFetch
import org.droidmusic.session.ChartFetchHeader
import org.droidmusic.session.ChartOffer
import org.droidmusic.session.ChartShare
import org.droidmusic.session.Wire

/**
 * The follower collecting a chart it has not got.
 *
 * Pulled rather than pushed: the leader never writes anything onto a follower's
 * device on its own initiative, it only answers requests for charts the follower
 * worked out it was missing. On a protocol with no identity that distinction is
 * most of the safety - see [ChartShare], which holds the rest of it.
 *
 * A part-finished chart is kept in the cache directory and resumed from where it
 * stopped, because the network this runs on is a pub's and a forty-megabyte scan
 * will not always arrive first time. Starting again from nothing on every drop is
 * how it never arrives at all.
 */
class ChartFetcher(
    private val context: Context,
    private val library: LibraryRepository,
) {

    sealed interface Outcome {
        data class Installed(val song: SongRef) : Outcome
        data class Failed(val reason: String, val partial: Boolean) : Outcome
    }

    /**
     * Fetches one chart, reporting how many bytes have arrived as they do.
     *
     * Returns rather than throws. A chart that did not arrive is a thing to tell
     * the player about, not an exception to unwind a session over.
     */
    suspend fun fetch(
        host: String,
        port: Int,
        offer: ChartOffer,
        onProgress: (Long) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val partial = partFile(offer)
        val alreadyHave = if (partial.isFile) partial.length() else 0L

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            val request = ChartFetch(offer.contentHash, alreadyHave)
            output.write((Wire.json.encodeToString(ChartFetch.serializer(), request) + "\n").toByteArray())
            output.flush()

            val header = readHeader(input)
                ?: return@withContext Outcome.Failed("No answer from the leader", partial = true)
            if (!header.ok) {
                // A refusal is the leader's final word on this chart, so the part
                // file goes; keeping it would resume a transfer that will never
                // be offered again.
                partial.delete()
                return@withContext Outcome.Failed(header.reason ?: "The leader would not send it", partial = false)
            }
            if (header.offset != alreadyHave) {
                // The leader is sending from somewhere other than where this
                // stopped. Rather than splice two halves that may not meet,
                // start again.
                partial.delete()
                return@withContext Outcome.Failed("Could not pick up where that left off", partial = true)
            }

            partial.parentFile?.mkdirs()
            // Appending, not truncating: this may be the second half of a chart
            // whose first half survived a dropped connection.
            val received = FileOutputStream(partial, alreadyHave > 0).use { sink ->
                copyBody(input, sink, header, alreadyHave, onProgress)
            }
            if (received < 0) {
                return@withContext Outcome.Failed("That chart is larger than a session will carry", partial = false)
            }

            if (header.length >= 0 && received < alreadyHave + header.length) {
                return@withContext Outcome.Failed("The chart stopped arriving partway", partial = true)
            }

            install(offer, partial)
        } catch (_: Exception) {
            Outcome.Failed("Lost the connection while fetching that chart", partial = true)
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Reads the JSON header line a byte at a time.
     *
     * Deliberately not a `BufferedReader`. One of those would read ahead past the
     * newline and swallow the first few kilobytes of the chart into a buffer this
     * code cannot get at, which presents as every transfer arriving corrupt for
     * reasons nothing in the log explains.
     */
    private fun readHeader(input: InputStream): ChartFetchHeader? {
        val line = StringBuilder()
        while (line.length < MAX_HEADER_CHARS) {
            val byte = input.read()
            if (byte < 0) return null
            if (byte == '\n'.code) {
                return runCatching {
                    Wire.json.decodeFromString(ChartFetchHeader.serializer(), line.toString().trim())
                }.getOrNull()
            }
            line.append(byte.toChar())
        }
        return null
    }

    /**
     * Copies the body, stopping at the declared length or at the end of the
     * connection when the leader could not say how long it was.
     *
     * Returns the number of bytes now held, or -1 if the chart turned out to be
     * over the cap - which is checked here as well as when the offer was
     * accepted, because the size in an offer is a claim and this is the fact.
     */
    private fun copyBody(
        input: InputStream,
        sink: OutputStream,
        header: ChartFetchHeader,
        alreadyHave: Long,
        onProgress: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var held = alreadyHave
        var remaining = if (header.length >= 0) header.length else Long.MAX_VALUE

        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, want)
            if (read <= 0) break
            if (held + read > ChartShare.MAX_CHART_BYTES) return -1
            sink.write(buffer, 0, read)
            held += read
            remaining -= read
            onProgress(held)
        }
        sink.flush()
        return held
    }

    /**
     * Checks what arrived against the offer and, only then, files it.
     *
     * The hash is **not** computed here. It is computed by handing the file to
     * this device's own indexer and reading back what it called it, which is the
     * only way the two ends can be sure of agreeing.
     *
     * That is not fastidiousness, it is the bug this method shipped with. The
     * library hashes a chart in one of two ways depending on its kind - a text
     * chart from the characters it parses out of it, a PDF from its first
     * megabyte and its length - and this code reimplemented the second rule and
     * applied it to everything. Every ChordPro chart therefore compared a hash
     * of its text against a hash of its bytes, which cannot match, and every
     * transfer was rejected as corrupt while being perfectly intact.
     *
     * Nothing is copied anywhere until the check has passed. The part file is
     * what gets indexed, so a chart that fails never reaches the directory the
     * library reads.
     */
    private suspend fun install(offer: ChartOffer, partial: File): Outcome {
        val probe = SongRef(
            id = "probe",
            sourceId = DocumentSources.MANAGED_SOURCE_ID,
            uri = Uri.fromFile(partial).toString(),
            displayName = ChartShare.storedName(offer),
            kind = offer.kind,
            sizeBytes = partial.length(),
        )
        // parseContents = true is required: without it the indexer hands the song
        // straight back and there is no hash to check against anything.
        val indexed = DocumentSources.enrich(context, listOf(probe), parseContents = true)
            .firstOrNull()

        if (!ChartShare.matchesOffer(offer, indexed?.contentHash, partial.length())) {
            partial.delete()
            return Outcome.Failed(
                "That chart did not match what the leader offered. If this keeps " +
                    "happening, the leader's library may need a rescan.",
                partial = false,
            )
        }

        val directory = File(context.filesDir, MANAGED_DIRECTORY).apply { mkdirs() }
        val target = File(directory, uniqueName(directory, ChartShare.storedName(offer)))
        if (!runCatching { partial.copyTo(target, overwrite = true); true }.getOrDefault(false)) {
            return Outcome.Failed("Could not save that chart on this device", partial = true)
        }
        partial.delete()

        // The metadata comes from the indexer's reading of the file rather than
        // from the offer: the title on an offer is what the *leader* calls the
        // chart, which may be a rename only they made.
        val stored = SongRef(
            id = DocumentSources.stableId(DocumentSources.MANAGED_SOURCE_ID, target.name),
            sourceId = DocumentSources.MANAGED_SOURCE_ID,
            uri = Uri.fromFile(target).toString(),
            displayName = target.name,
            kind = offer.kind,
            sizeBytes = target.length(),
            modifiedAt = target.lastModified(),
            contentHash = indexed?.contentHash,
            title = indexed?.title,
            artist = indexed?.artist,
            keyText = indexed?.keyText,
        )

        fileManaged(stored)
        return Outcome.Installed(stored)
    }

    /** Adds a fetched chart to the managed source, making that source if it is not there. */
    private suspend fun fileManaged(song: SongRef) {
        val source = DocumentSources.managedSource()
        if (library.index.value.sources.none { it.id == source.id }) {
            library.addSource(source)
        }
        val existing = library.index.value.songsFrom(source.id).filterNot { it.id == song.id }
        library.replaceSongsFrom(source.id, existing + song, System.currentTimeMillis())
    }

    /**
     * Where a part-finished chart waits between attempts.
     *
     * Named after the hash rather than the sender's filename, so that nothing a
     * peer chose is ever used as a path - not even a temporary one.
     */
    private fun partFile(offer: ChartOffer): File {
        val safe = offer.contentHash.filter { it.isLetterOrDigit() }.take(64).ifEmpty { "chart" }
        return File(File(context.cacheDir, PART_DIRECTORY), "$safe.part")
    }

    private fun uniqueName(directory: File, name: String): String {
        if (!File(directory, name).exists()) return name
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var suffix = 2
        while (true) {
            val candidate = if (extension.isEmpty()) "$stem $suffix" else "$stem $suffix.$extension"
            if (!File(directory, candidate).exists()) return candidate
            suffix++
        }
    }

    private companion object {
        const val MANAGED_DIRECTORY = "managed"
        const val PART_DIRECTORY = "chart-sync"
        const val CONNECT_TIMEOUT_MS = 6_000
        const val READ_TIMEOUT_MS = 30_000

        /** A header line longer than this is not a header line. */
        const val MAX_HEADER_CHARS = 8 * 1024
    }
}
