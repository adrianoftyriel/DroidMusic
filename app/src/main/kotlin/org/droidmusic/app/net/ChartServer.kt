package org.droidmusic.app.net

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.droidmusic.app.data.LibraryRepository
import org.droidmusic.session.ChartFetch
import org.droidmusic.session.ChartFetchHeader
import org.droidmusic.session.ChartShare
import org.droidmusic.session.Wire

/**
 * The leader handing over a chart file, on a socket of its own.
 *
 * **Why a second socket at all.** The control connection carries positions and
 * heartbeats, and a page turn must never wait behind a forty-megabyte scan. On
 * one stream it would: TCP delivers in order, so a large frame blocks everything
 * behind it, the heartbeat stops, and the follower's timeout logic starts
 * deciding the leader has gone home. Base64 in a JSON line would also mean
 * holding the whole file as one String on both devices, which a phone with
 * several followers will not survive.
 *
 * So: one connection per chart, a JSON line asking for it, a JSON line
 * describing what is coming, then the raw bytes. Nothing else shares the
 * channel, so the framing can be as simple as "the rest of this connection".
 *
 * **What it will serve.** Only a chart in the leader's own library, found by the
 * content hash it offered, and only one it has not removed from that library.
 * There is no path in the request and no way to name a file that is not indexed.
 */
class ChartServer(
    private val scope: CoroutineScope,
    private val context: Context,
    private val library: LibraryRepository,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val _port = MutableStateFlow(0)

    /** The port to advertise, or zero when nothing is being served. */
    val port: StateFlow<Int> = _port.asStateFlow()

    suspend fun start(): Int = withContext(Dispatchers.IO) {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0))
        serverSocket = socket
        _port.value = socket.localPort

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch(Dispatchers.IO) { serve(client) }
            }
        }
        socket.localPort
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _port.value = 0
    }

    private fun serve(client: Socket) {
        client.soTimeout = REQUEST_TIMEOUT_MS
        client.tcpNoDelay = true
        try {
            val reader = client.getInputStream().bufferedReader()
            val output = client.getOutputStream()

            val line = reader.readLine() ?: return
            val request = runCatching {
                Wire.json.decodeFromString(ChartFetch.serializer(), line.trim())
            }.getOrNull() ?: run {
                refuse(client, "Not a chart request")
                return
            }

            val song = library.index.value.visible
                .firstOrNull { it.contentHash == request.contentHash }
                ?: run {
                    refuse(client, "No such chart here")
                    return
                }

            val uri = Uri.parse(song.uri)
            val size = sizeOf(uri, song.sizeBytes)
            if (size > ChartShare.MAX_CHART_BYTES) {
                refuse(client, "That chart is larger than a session will carry")
                return
            }

            val offset = request.offset.coerceAtLeast(0)
            if (size >= 0 && offset > size) {
                refuse(client, "Asked to resume past the end of the chart")
                return
            }

            val stream = context.contentResolver.openInputStream(uri) ?: run {
                refuse(client, "That chart could not be opened here")
                return
            }

            stream.use { input ->
                if (offset > 0 && !skipTo(input, offset)) {
                    refuse(client, "That chart could not be resumed")
                    return
                }

                val header = ChartFetchHeader(
                    ok = true,
                    contentHash = request.contentHash,
                    displayName = song.displayName,
                    kind = song.kind,
                    sizeBytes = size,
                    offset = offset,
                    // -1 when the provider will not say how big the file is, in
                    // which case the end of the connection is the end of the
                    // chart and the receiver checks the hash to know it is whole.
                    length = if (size >= 0) size - offset else -1,
                )
                output.write((Wire.json.encodeToString(ChartFetchHeader.serializer(), header) + "\n").toByteArray())
                output.flush()
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
                output.flush()
            }
        } catch (_: Exception) {
            // A follower that walked away mid-transfer is not an error worth
            // reporting anywhere; it will ask again from where it got to.
        } finally {
            runCatching { client.close() }
        }
    }

    private fun refuse(client: Socket, reason: String) {
        runCatching {
            val header = ChartFetchHeader(ok = false, reason = reason)
            client.getOutputStream().apply {
                write((Wire.json.encodeToString(ChartFetchHeader.serializer(), header) + "\n").toByteArray())
                flush()
            }
        }
    }

    /**
     * How big the chart is, or -1 when nothing will say.
     *
     * The library's own figure first, then the provider's. A cloud provider is
     * entitled to answer neither, and a chart whose size is unknown is still
     * worth sending - it just cannot have a progress bar.
     */
    private fun sizeOf(uri: Uri, indexed: Long): Long {
        if (indexed > 0) return indexed
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                        cursor.getLong(column)
                    } else {
                        null
                    }
                }
        }.getOrNull()
        return queried?.takeIf { it > 0 } ?: -1
    }

    /**
     * Winds an input stream forward to a resume point.
     *
     * `skip` is allowed to move less than it was asked to, so it is called in a
     * loop; a stream that stops moving before the offset is reached cannot serve
     * the request and says so rather than sending the wrong bytes.
     */
    private fun skipTo(input: InputStream, offset: Long): Boolean {
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) return false
            remaining -= skipped
        }
        return true
    }

    private companion object {
        /**
         * A follower that opens a connection and then says nothing must not hold
         * a thread. Short, because the request is one line and it has already
         * decided to send it.
         */
        const val REQUEST_TIMEOUT_MS = 15_000
    }
}
