package org.droidmusic.app.net

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.droidmusic.session.BackstageReport
import org.droidmusic.session.CheckReport
import org.droidmusic.session.CheckRequest
import org.droidmusic.session.FollowerEvent
import org.droidmusic.session.FollowerMachine
import org.droidmusic.session.FollowerState
import org.droidmusic.session.FollowerStatus
import org.droidmusic.session.Goodbye
import org.droidmusic.session.Hello
import org.droidmusic.session.LinkState
import org.droidmusic.session.Message
import org.droidmusic.session.Ping
import org.droidmusic.session.Pong
import org.droidmusic.session.Position
import org.droidmusic.session.ChartWant
import org.droidmusic.session.ChartsOffered
import org.droidmusic.session.ChartsWanted
import org.droidmusic.session.SetlistPush
import org.droidmusic.session.Welcome
import org.droidmusic.session.Wire
import org.droidmusic.session.FollowerEffect
import org.droidmusic.session.LocalPosition

/**
 * A player's side of a session.
 *
 * The socket handling here is deliberately dull, because all the decisions live
 * in [FollowerMachine] where they can be tested. What this class adds is the
 * reconnection loop, and the one property that matters about it: **losing the
 * connection never blocks the player**. The reconnect runs in the background and
 * the viewer keeps working the whole time, which is the requirement that made
 * this feature worth building rather than a nice idea.
 */
/**
 * Where a leader is serving charts, once it has said that it is.
 *
 * A separate address from the control connection's on purpose - see
 * [ChartServer] for why a transfer must not share the socket a page turn
 * travels on.
 */
data class ChartSource(val host: String, val port: Int)

class SessionClient(
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val deviceName: String,
    private val appVersion: String,
) {
    private var socket: Socket? = null

    /**
     * The writer for the live connection. Held so that a status report raised by
     * the state machine can go out on the same buffered stream the read loop
     * writes to, under the same lock, rather than a second writer over the same
     * socket.
     */
    @Volatile
    private var writer: java.io.BufferedWriter? = null

    private var job: Job? = null
    private var target: DiscoveredSession? = null
    private var seq = 0L

    private val _state = MutableStateFlow(FollowerState())
    val state: StateFlow<FollowerState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FollowerEffect>(extraBufferCapacity = 32)
    val effects: SharedFlow<FollowerEffect> = _effects.asSharedFlow()

    private val _setlistPushes = MutableSharedFlow<SetlistPush>(extraBufferCapacity = 8)
    val setlistPushes: SharedFlow<SetlistPush> = _setlistPushes.asSharedFlow()

    private val _chartOffers = MutableSharedFlow<ChartsOffered>(extraBufferCapacity = 8)

    /** What the leader says it can send of the charts this device asked for. */
    val chartOffers: SharedFlow<ChartsOffered> = _chartOffers.asSharedFlow()

    /**
     * Where charts can be fetched from, or null when this leader is not offering
     * any - which is also what an older build looks like.
     */
    private val _chartSource = MutableStateFlow<ChartSource?>(null)
    val chartSource: StateFlow<ChartSource?> = _chartSource.asStateFlow()

    private val _checkRequests = MutableSharedFlow<CheckRequest>(extraBufferCapacity = 8)
    val checkRequests: SharedFlow<CheckRequest> = _checkRequests.asSharedFlow()

    private val _sessionName = MutableStateFlow<String?>(null)
    val sessionName: StateFlow<String?> = _sessionName.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun join(session: DiscoveredSession) {
        target = session
        job?.cancel()
        job = scope.launch(Dispatchers.IO) { connectionLoop(session) }
    }

    /**
     * Reconnects for as long as the session is meant to be joined, backing off
     * so that a leader who has genuinely gone home does not cost the followers
     * their battery for the rest of the night.
     */
    private suspend fun connectionLoop(session: DiscoveredSession) {
        var backoff = INITIAL_BACKOFF_MS
        while (scope.isActive) {
            val connected = runCatching { attempt(session) }.getOrElse { false }
            if (connected) {
                backoff = INITIAL_BACKOFF_MS
            } else {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
            if (_state.value.link == LinkState.OFFLINE) return
        }
    }

    /** One connection, from open to close. Returns true if it ever got going. */
    private suspend fun attempt(session: DiscoveredSession): Boolean {
        var everConnected = false
        val client = Socket()
        try {
            client.tcpNoDelay = true
            client.connect(InetSocketAddress(session.host, session.port), CONNECT_TIMEOUT_MS)
            client.soTimeout = READ_TIMEOUT_MS
            socket = client

            val reader = client.getInputStream().bufferedReader()
            writer = client.getOutputStream().bufferedWriter()

            fun send(message: Message) = sendOn(client, message)

            send(
                Hello(
                    deviceName = deviceName,
                    deviceId = deviceId,
                    appVersion = appVersion,
                ),
            )

            while (scope.isActive && !client.isClosed) {
                val line = reader.readLine() ?: break
                when (val message = Wire.decode(line)) {
                    is Welcome -> {
                        if (!message.accepted) {
                            _lastError.value = message.reason ?: "The leader declined the connection."
                            dispatch(FollowerEvent.LeaveRequested)
                            return everConnected
                        }
                        everConnected = true
                        _lastError.value = null
                        _sessionName.value = message.sessionName
                        _chartSource.value = message.filePort
                            .takeIf { it > 0 }
                            ?.let { ChartSource(session.host, it) }
                        dispatch(FollowerEvent.Connected(message.sessionName), ::send)
                    }

                    is Position -> dispatch(FollowerEvent.LeaderPosition(message), ::send)

                    is SetlistPush -> _setlistPushes.tryEmit(message)

                    is ChartsOffered -> _chartOffers.tryEmit(message)

                    is CheckRequest -> _checkRequests.tryEmit(message)

                    is Ping -> send(Pong(nextSeq(), message.sentAt, deviceId))

                    is Goodbye -> {
                        _lastError.value = message.reason ?: "The leader ended the session."
                        dispatch(FollowerEvent.LeaveRequested)
                        return everConnected
                    }

                    else -> Unit
                }
            }
        } catch (_: Exception) {
            // Nothing to distinguish here: a refused connection, a timeout and a
            // reset all mean the same thing to a player standing on a stage.
        } finally {
            runCatching { client.close() }
            socket = null
            writer = null
            if (_state.value.link != LinkState.OFFLINE) {
                dispatch(FollowerEvent.ConnectionLost)
            }
        }
        return everConnected
    }

    /**
     * Answers the leader's backstage check.
     *
     * Best effort, like everything else sent from here: a report that does not
     * make it costs the leader one row on a screen, and must never cost the
     * player a page turn. The leader shows a device that did not answer as not
     * having answered, which is the honest reading either way.
     */
    fun sendReport(report: BackstageReport) {
        sendOnCurrentSocket(CheckReport(nextSeq(), report))
    }

    /** The player turned a page on this device. */
    fun onUserNavigated(songId: String?, page: Int, setlistIndex: Int) {
        dispatch(FollowerEvent.UserNavigated(LocalPosition(songId, page, setlistIndex)))
    }

    fun rejoin() = dispatch(FollowerEvent.RejoinRequested)

    fun leave() {
        dispatch(FollowerEvent.LeaveRequested)
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        target = null
        _sessionName.value = null
    }

    private fun dispatch(event: FollowerEvent, send: ((Message) -> Unit)? = null) {
        val transition = FollowerMachine.reduce(_state.value, event)
        _state.value = transition.state
        for (effect in transition.effects) {
            if (effect is FollowerEffect.ReportStatus) {
                val status = FollowerStatus(
                    seq = nextSeq(),
                    deviceId = deviceId,
                    deviceName = deviceName,
                    following = transition.state.mode == org.droidmusic.session.FollowMode.FOLLOWING,
                    page = transition.state.local?.page ?: 0,
                    songId = transition.state.local?.songId,
                )
                send?.invoke(status) ?: sendOnCurrentSocket(status)
            }
            _effects.tryEmit(effect)
        }
    }

    /**
     * Asks the leader which of these charts it can send.
     *
     * Best effort, like every other write here: a request that does not get out
     * because the link chose that moment to drop costs a chart that has to be
     * fetched another way, not a session.
     */
    fun requestCharts(wanted: List<ChartWant>) {
        if (wanted.isEmpty()) return
        sendOnCurrentSocket(ChartsWanted(nextSeq(), deviceId, wanted))
    }

    private fun sendOnCurrentSocket(message: Message) {
        val current = socket ?: return
        scope.launch(Dispatchers.IO) { sendOn(current, message) }
    }

    /**
     * Writes one message, locking on the socket so the read loop's sends and the
     * state machine's status reports cannot interleave mid-line. A failure is
     * swallowed: the reconnect loop is what handles a dead connection, and a
     * status report is not worth an exception on the way to it.
     */
    private fun sendOn(target: Socket, message: Message) {
        runCatching {
            synchronized(target) {
                val out = writer ?: return
                out.write(Wire.encode(message))
                out.flush()
            }
        }
    }

    private fun nextSeq(): Long = ++seq

    companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 30_000
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 20_000L
    }
}
