package org.droidmusic.app.net

import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.droidmusic.app.data.DocumentSources
import org.droidmusic.app.diag.Area
import org.droidmusic.app.diag.Diagnostics
import org.droidmusic.app.data.LibraryRepository
import org.droidmusic.library.Setlist
import org.droidmusic.session.ChartShare
import org.droidmusic.session.ChartsOffered
import org.droidmusic.session.ChartsWanted
import org.droidmusic.session.CheckReport
import org.droidmusic.session.CheckRequest
import org.droidmusic.session.Goodbye
import org.droidmusic.session.Hello
import org.droidmusic.session.LeaderSession
import org.droidmusic.session.LeaderState
import org.droidmusic.session.Message
import org.droidmusic.session.Ping
import org.droidmusic.session.Position
import org.droidmusic.session.FollowerStatus
import org.droidmusic.session.SetlistPush
import org.droidmusic.session.Welcome
import org.droidmusic.session.Wire
import org.droidmusic.session.PROTOCOL_VERSION

/**
 * The band leader's side of a session.
 *
 * One thread-per-follower over blocking sockets, rather than NIO or a framework.
 * A session is a handful of devices in one room for a few hours; the simplest
 * thing that cannot go wrong is worth more here than throughput that will never
 * be needed.
 *
 * Every write is best-effort. A follower whose phone has gone into a pocket must
 * never be able to stall the leader's page turn, so a failed send drops that
 * connection and moves on rather than propagating an error upward.
 */
class SessionServer(
    private val scope: CoroutineScope,
    private val sessionName: String,
    private val leaderName: String,
    private val discovery: SessionDiscovery?,
    /**
     * The library charts are offered out of, and the socket they travel on.
     *
     * Both optional, and a leader without them simply never offers a chart: the
     * port it advertises stays zero, which is exactly what a follower sees from
     * a build that predates chart sharing.
     */
    private val library: LibraryRepository? = null,
    private val chartServer: ChartServer? = null,
    /** Needed only to re-read a chart when offering it; see [onWanted]. */
    private val context: android.content.Context? = null,
) {
    private val connections = ConcurrentHashMap<String, Connection>()
    private var serverSocket: ServerSocket? = null
    private var registration: SessionDiscovery.Registration? = null
    private var acceptJob: Job? = null
    private var heartbeatJob: Job? = null

    /**
     * What this session is playing, and whether the band has been asked to
     * check it.
     *
     * Kept so that a device joining late is caught up rather than left staring
     * at an empty screen. Ten minutes into a soundcheck is exactly when
     * somebody's phone finally connects, and until now the leader would have
     * had to remember to push the running order again for them - which is to
     * say, it did not happen.
     */
    @Volatile
    private var running: Setlist? = null

    @Volatile
    private var checkAsked: Setlist? = null

    private val _state = MutableStateFlow(LeaderState(sessionName, leaderName))
    val state: StateFlow<LeaderState> = _state.asStateFlow()

    private val _port = MutableStateFlow(0)
    val port: StateFlow<Int> = _port.asStateFlow()

    /**
     * One follower's socket, and the queue everything sent to it goes through.
     *
     * The queue is the point. Sending used to launch a coroutine per message,
     * which meant two announcements could reach the socket in either order and a
     * set list could arrive after the position that referred to it. Ordering is
     * the one thing a stream protocol is supposed to give you for free, and
     * launching a coroutine per write throws it away. One writer per connection,
     * fed by a channel, puts it back.
     *
     * [UNLIMITED] because a full queue must never suspend the caller: the caller
     * is a page turn. A follower slow enough to build a backlog is a follower
     * about to be dropped by its own socket timeout anyway.
     */
    private class Connection(
        val socket: Socket,
        val reader: BufferedReader,
        val writer: BufferedWriter,
        var deviceId: String? = null,
        val outbound: Channel<Message> = Channel(Channel.UNLIMITED),
    )

    /** Binds an ephemeral port, advertises it, and starts accepting followers. */
    suspend fun start(): Int = withContext(Dispatchers.IO) {
        // Port 0 lets the OS pick a free one. A fixed port would collide the
        // moment two people in the same band both tapped "start" by mistake.
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0))
        serverSocket = socket
        _port.value = socket.localPort

        // Started before the session is advertised, so the port is real by the
        // time the first follower is welcomed.
        runCatching { chartServer?.start() }

        registration = discovery?.advertise(sessionName, leaderName, socket.localPort)
        Diagnostics.log(
            Area.LEADER,
            "session \"$sessionName\" listening on ${socket.localPort}, " +
                "charts on ${chartServer?.port?.value ?: 0}",
        )

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { handle(client) }
            }
        }

        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(LeaderSession.HEARTBEAT_INTERVAL_MS)
                val (next, seq) = LeaderSession.nextSeq(_state.value)
                val before = next.followers.map { it.deviceId }.toSet()
                _state.value = LeaderSession.evictStale(next, System.currentTimeMillis())
                for (gone in before - _state.value.followers.map { it.deviceId }.toSet()) {
                    Diagnostics.log(
                        Area.LEADER,
                        "dropped ${Diagnostics.short(gone)}: nothing heard for " +
                            "${LeaderSession.FOLLOWER_TIMEOUT_MS / 1000}s",
                    )
                }
                broadcast(Ping(seq, System.currentTimeMillis()))
            }
        }

        socket.localPort
    }

    private fun handle(client: Socket) {
        // A follower that connects and then says nothing must not hold a thread
        // for ever; a leader that stops sending must not look alive.
        client.soTimeout = READ_TIMEOUT_MS
        client.tcpNoDelay = true

        val reader = client.getInputStream().bufferedReader()
        val writer = client.getOutputStream().bufferedWriter()
        val connection = Connection(client, reader, writer)
        val writerJob = scope.launch(Dispatchers.IO) { pump(connection) }

        try {
            while (!client.isClosed) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break

                // Anything at all counts as a sign of life, and the heartbeat
                // reply is usually the only thing there is: a follower quietly
                // doing as it is told sends no news for minutes together. See
                // LeaderSession.seen.
                connection.deviceId?.let { id ->
                    _state.value = LeaderSession.seen(_state.value, id, System.currentTimeMillis())
                }

                when (val message = Wire.decode(line)) {
                    is Hello -> onHello(message, connection)
                    is ChartsWanted -> onWanted(message, connection)
                    is FollowerStatus -> {
                        _state.value = LeaderSession.withStatus(
                            _state.value,
                            message,
                            System.currentTimeMillis(),
                        )
                    }
                    is CheckReport -> {
                        _state.value = LeaderSession.withReport(_state.value, message.report)
                        Diagnostics.log(
                            Area.LEADER,
                            "report from ${message.report.deviceName}: " +
                                "${message.report.problems.size} of " +
                                "${message.report.checks.size} need attention",
                        )
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            // Any socket problem is the same problem: this follower is gone.
            Diagnostics.log(
                Area.LEADER,
                "${Diagnostics.short(connection.deviceId)} read failed: ${reason(e)}",
            )
        } finally {
            connection.outbound.close()
            writerJob.cancel()
            connection.deviceId?.let { id ->
                // Only if this connection is still the one on file. A device
                // that reconnected has a newer socket under the same id, and
                // tearing the new one down as the old one finishes is how a
                // reconnect turns into a disconnect.
                if (connections.remove(id, connection)) {
                    _state.value = LeaderSession.withoutFollower(_state.value, id)
                    Diagnostics.log(Area.LEADER, "${Diagnostics.short(id)} disconnected")
                }
            }
            runCatching { client.close() }
        }
    }

    /** A socket failure in a few words, for the log. */
    private fun reason(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName

    /**
     * Writes one connection's queue, in order, until the socket dies.
     *
     * A failed write drops the follower rather than propagating: a phone that
     * went into a pocket must never be able to stall the leader's page turn.
     */
    private suspend fun pump(connection: Connection) {
        for (message in connection.outbound) {
            val written = runCatching {
                connection.writer.write(Wire.encode(message))
                connection.writer.flush()
            }
            if (written.isFailure) {
                Diagnostics.log(
                    Area.LEADER,
                    "${Diagnostics.short(connection.deviceId)} write failed, dropping",
                )
                connection.deviceId?.let { id ->
                    if (connections.remove(id, connection)) {
                        _state.value = LeaderSession.withoutFollower(_state.value, id)
                    }
                }
                runCatching { connection.socket.close() }
                return
            }
        }
    }

    private fun onHello(hello: Hello, connection: Connection) {
        if (!Wire.isCompatible(hello.protocolVersion)) {
            // Refusing clearly beats half-talking to a build that means something
            // different by the same message.
            send(
                connection,
                Welcome(
                    seq = 0,
                    accepted = false,
                    sessionName = sessionName,
                    leaderName = leaderName,
                    reason = "This session needs DroidMusic protocol $PROTOCOL_VERSION; " +
                        "that device speaks ${hello.protocolVersion}.",
                ),
            )
            Diagnostics.log(
                Area.LEADER,
                "refused ${hello.deviceName}: speaks protocol ${hello.protocolVersion}, " +
                    "this build speaks $PROTOCOL_VERSION",
            )
            runCatching { connection.socket.close() }
            return
        }

        connection.deviceId = hello.deviceId
        // A device reconnecting replaces its own stale connection, so a phone
        // that dropped does not leave a ghost in the list.
        connections.put(hello.deviceId, connection)?.let { previous ->
            if (previous !== connection) {
                previous.outbound.close()
                runCatching { previous.socket.close() }
            }
        }

        _state.value = LeaderSession.withFollower(_state.value, hello, System.currentTimeMillis())
        Diagnostics.log(
            Area.LEADER,
            "joined: ${hello.deviceName} (${Diagnostics.short(hello.deviceId)}) " +
                "on ${connection.socket.inetAddress?.hostAddress ?: "?"}, " +
                "app ${hello.appVersion ?: "?"}",
        )

        send(
            connection,
            Welcome(
                seq = 0,
                accepted = true,
                sessionName = sessionName,
                leaderName = leaderName,
                filePort = chartServer?.port?.value ?: 0,
            ),
        )
        // Catch the newcomer up: the running order first, so the check that
        // follows has something to be about, and the position last so they land
        // on the song the band is actually on.
        //
        // Sent to this one connection rather than broadcast. Everybody else
        // already has all of it, and re-pushing a set list to a player mid-song
        // is a screen change they did not ask for.
        running?.let { setlist ->
            val (afterPush, pushSeq) = LeaderSession.nextSeq(_state.value)
            _state.value = afterPush
            send(connection, SetlistPush(pushSeq, setlist))
            Diagnostics.log(
                Area.SETLIST,
                "caught up ${hello.deviceName} with \"${setlist.name}\"",
            )
        }
        checkAsked?.let { setlist ->
            val (afterCheck, checkSeq) = LeaderSession.nextSeq(_state.value)
            _state.value = afterCheck
            send(connection, CheckRequest(checkSeq, setlist))
        }
        _state.value.position?.let { send(connection, it) }
    }

    /**
     * Answers a follower asking which of the charts it is missing this leader
     * can supply.
     *
     * Only an answer, never an offer made first. The leader does not decide what
     * another device is short of - the follower works that out from the set list
     * it was sent and asks, which is what keeps a leader from being able to push
     * a file at anybody. See [ChartShare].
     */
    private fun onWanted(wanted: ChartsWanted, connection: Connection) {
        val repository = library ?: return
        if ((chartServer?.port?.value ?: 0) == 0) return

        scope.launch(Dispatchers.IO) {
            val index = repository.index.value
            val candidates = ChartShare.offers(wanted.wanted, index)
            if (candidates.isEmpty()) return@launch

            // Each chart is re-read before it is offered, and what this leader
            // knows about it is brought up to date.
            //
            // Not belt and braces. A content hash sitting in an index was
            // computed by whatever version of the app last scanned that folder,
            // and the rule changed - it used to leave the file's length out for
            // anything past a megabyte. Offering a hash this leader's own code
            // would no longer produce means the follower checks the bytes
            // against a number nothing can reproduce, and every large chart
            // fails as corrupt. Refreshing here fixes that for the charts it
            // matters for, and writing it back means the set list matching that
            // depends on the same hash stops being wrong too.
            val offers = candidates.mapNotNull { offer ->
                val song = index.songs.firstOrNull { it.contentHash == offer.contentHash }
                    ?: return@mapNotNull offer
                val fresh = context
                    ?.let { DocumentSources.enrich(it, listOf(song), parseContents = true) }
                    ?.firstOrNull()
                    ?: return@mapNotNull offer
                val hash = fresh.contentHash ?: return@mapNotNull null

                if (hash != song.contentHash) {
                    repository.updateSong(song.id) { it.copy(contentHash = hash) }
                }
                offer.copy(contentHash = hash, sizeBytes = fresh.sizeBytes)
            }

            val (next, seq) = LeaderSession.nextSeq(_state.value)
            _state.value = next
            send(connection, ChartsOffered(seq, offers))
        }
    }

    /** Moves the session to a new position and tells everyone. */
    fun announce(
        setlistIndex: Int,
        songId: String?,
        songTitle: String?,
        contentHash: String?,
        page: Int,
        transposeSemitones: Int = 0,
        capo: Int = 0,
    ): Position {
        val (next, position) = LeaderSession.announce(
            _state.value,
            setlistIndex,
            songId,
            songTitle,
            contentHash,
            page,
            transposeSemitones,
            capo,
        )
        _state.value = next
        Diagnostics.log(
            Area.LEADER,
            "position #${position.seq}: ${songTitle ?: "no song"} p${page + 1}" +
                (if (setlistIndex >= 0) " (set ${setlistIndex + 1})" else "") +
                " to ${connections.size}",
        )
        broadcast(position)
        return position
    }

    /**
     * Asks every follower whether they can open tonight's charts.
     *
     * The answers already on screen are thrown away first. They are about
     * whatever was checked last, and a stale "all present" is the one thing this
     * screen must never show.
     */
    fun requestCheck(setlist: Setlist) {
        checkAsked = setlist
        val (next, seq) = LeaderSession.nextSeq(_state.value)
        _state.value = LeaderSession.clearReports(next)
        Diagnostics.log(
            Area.LEADER,
            "check asked of ${connections.size} for \"${setlist.name}\" (${setlist.size} songs)",
        )
        broadcast(CheckRequest(seq, setlist))
    }

    fun pushSetlist(setlist: Setlist) {
        running = setlist
        val (next, seq) = LeaderSession.nextSeq(_state.value)
        _state.value = next
        Diagnostics.log(
            Area.SETLIST,
            "pushed \"${setlist.name}\" (${setlist.size} songs, id ${Diagnostics.short(setlist.id)}) " +
                "to ${connections.size}",
        )
        broadcast(SetlistPush(seq, setlist))
    }

    private fun broadcast(message: Message) {
        for (connection in connections.values) send(connection, message)
    }

    /**
     * Queues a message. Never blocks and never throws - see [Connection] for why
     * the ordering this preserves matters.
     */
    private fun send(connection: Connection, message: Message) {
        connection.outbound.trySend(message)
    }

    /** Closes the session, telling followers rather than just vanishing. */
    fun stop() {
        val (next, seq) = LeaderSession.nextSeq(_state.value)
        _state.value = next
        broadcast(Goodbye(seq, "Session ended"))

        // The goodbye is queued, not written, so the sockets cannot be torn
        // down in the same breath - a follower would read the close before the
        // reason and report the leader as vanished rather than finished.
        val leaving = connections.values.toList()
        Diagnostics.log(Area.LEADER, "session ended, ${leaving.size} told")
        chartServer?.stop()
        registration?.stop()
        registration = null
        acceptJob?.cancel()
        heartbeatJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        connections.clear()
        _state.value = LeaderState(sessionName, leaderName)

        scope.launch(Dispatchers.IO) {
            delay(GOODBYE_GRACE_MS)
            leaving.forEach {
                it.outbound.close()
                runCatching { it.socket.close() }
            }
        }
    }

    companion object {
        /**
         * Comfortably longer than the heartbeat interval, so a quiet but healthy
         * connection is never mistaken for a dead one.
         */
        val READ_TIMEOUT_MS = (LeaderSession.HEARTBEAT_INTERVAL_MS * 6).toInt()

        /** Long enough for a queued goodbye to reach the wire, short enough to be over. */
        const val GOODBYE_GRACE_MS = 250L
    }
}
