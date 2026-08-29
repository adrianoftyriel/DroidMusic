package org.droidmusic.app.net

import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.droidmusic.library.Setlist
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
) {
    private val connections = ConcurrentHashMap<String, Connection>()
    private var serverSocket: ServerSocket? = null
    private var registration: SessionDiscovery.Registration? = null
    private var acceptJob: Job? = null
    private var heartbeatJob: Job? = null

    private val _state = MutableStateFlow(LeaderState(sessionName, leaderName))
    val state: StateFlow<LeaderState> = _state.asStateFlow()

    private val _port = MutableStateFlow(0)
    val port: StateFlow<Int> = _port.asStateFlow()

    private class Connection(
        val socket: Socket,
        val reader: BufferedReader,
        val writer: BufferedWriter,
        var deviceId: String? = null,
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

        registration = discovery?.advertise(sessionName, leaderName, socket.localPort)

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
                _state.value = LeaderSession.evictStale(next, System.currentTimeMillis())
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

        try {
            while (!client.isClosed) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                when (val message = Wire.decode(line)) {
                    is Hello -> onHello(message, connection)
                    is FollowerStatus -> {
                        _state.value = LeaderSession.withStatus(
                            _state.value,
                            message,
                            System.currentTimeMillis(),
                        )
                    }
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            // Any socket problem is the same problem: this follower is gone.
        } finally {
            connection.deviceId?.let { id ->
                connections.remove(id)
                _state.value = LeaderSession.withoutFollower(_state.value, id)
            }
            runCatching { client.close() }
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
            runCatching { connection.socket.close() }
            return
        }

        connection.deviceId = hello.deviceId
        // A device reconnecting replaces its own stale connection, so a phone
        // that dropped does not leave a ghost in the list.
        connections.put(hello.deviceId, connection)?.let { previous ->
            if (previous !== connection) runCatching { previous.socket.close() }
        }

        _state.value = LeaderSession.withFollower(_state.value, hello, System.currentTimeMillis())

        send(
            connection,
            Welcome(seq = 0, accepted = true, sessionName = sessionName, leaderName = leaderName),
        )
        // Bring the newcomer straight to where the band already is.
        _state.value.position?.let { send(connection, it) }
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
        broadcast(position)
        return position
    }

    fun pushSetlist(setlist: Setlist) {
        val (next, seq) = LeaderSession.nextSeq(_state.value)
        _state.value = next
        broadcast(SetlistPush(seq, setlist))
    }

    private fun broadcast(message: Message) {
        for (connection in connections.values) send(connection, message)
    }

    private fun send(connection: Connection, message: Message) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                synchronized(connection) {
                    connection.writer.write(Wire.encode(message))
                    connection.writer.flush()
                }
            }.onFailure {
                connection.deviceId?.let { id ->
                    connections.remove(id)
                    _state.value = LeaderSession.withoutFollower(_state.value, id)
                }
                runCatching { connection.socket.close() }
            }
        }
    }

    /** Closes the session, telling followers rather than just vanishing. */
    fun stop() {
        val (next, seq) = LeaderSession.nextSeq(_state.value)
        _state.value = next
        broadcast(Goodbye(seq, "Session ended"))

        registration?.stop()
        registration = null
        acceptJob?.cancel()
        heartbeatJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        connections.values.forEach { runCatching { it.socket.close() } }
        connections.clear()
        _state.value = LeaderState(sessionName, leaderName)
    }

    companion object {
        /**
         * Comfortably longer than the heartbeat interval, so a quiet but healthy
         * connection is never mistaken for a dead one.
         */
        val READ_TIMEOUT_MS = (LeaderSession.HEARTBEAT_INTERVAL_MS * 6).toInt()
    }
}
