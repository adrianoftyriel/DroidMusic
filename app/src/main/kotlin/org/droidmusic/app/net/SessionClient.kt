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
import org.droidmusic.app.diag.Area
import org.droidmusic.app.diag.Diagnostics
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
import org.droidmusic.session.AggregatedChart
import org.droidmusic.session.Catalogue
import org.droidmusic.session.CatalogueDevice
import org.droidmusic.session.CatalogueGone
import org.droidmusic.session.CataloguePeer
import org.droidmusic.session.CataloguePublish
import org.droidmusic.session.ChartOffer
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
    /**
     * Looks the session up again by name. Optional so the client can still be
     * built without discovery - in tests, or against an address typed in by
     * hand - in which case a reconnect simply reuses the address it has.
     */
    private val relocate: (suspend (String) -> DiscoveredSession?)? = null,
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
    @Volatile
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

    /**
     * What every device in the session has, as the leader relays it.
     *
     * Accumulated per device rather than replaced wholesale: one device
     * changing its library should not blank the others while its pages arrive.
     */
    private val peers = java.util.concurrent.ConcurrentHashMap<String, CatalogueDevice>()
    private val peerPages = java.util.concurrent.ConcurrentHashMap<String, MutableList<ChartOffer>>()

    private val _aggregate = MutableStateFlow<List<AggregatedChart>>(emptyList())

    /** The band's charts as one library. */
    val aggregate: StateFlow<List<AggregatedChart>> = _aggregate.asStateFlow()

    /**
     * What this device will tell the session it has, and where from.
     *
     * Set by the coordinator, which owns the library and the chart server. Held
     * rather than passed so a reconnect re-announces without being asked again.
     */
    @Volatile
    var catalogue: List<ChartOffer> = emptyList()

    @Volatile
    var chartPort: Int = 0

    private val _checkRequests = MutableSharedFlow<CheckRequest>(extraBufferCapacity = 8)
    val checkRequests: SharedFlow<CheckRequest> = _checkRequests.asSharedFlow()

    private val _sessionName = MutableStateFlow<String?>(null)
    val sessionName: StateFlow<String?> = _sessionName.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun join(session: DiscoveredSession) {
        Diagnostics.log(
            Area.FOLLOWER,
            "joining \"${session.serviceName}\" at ${session.host}:${session.port}",
        )
        target = session
        job?.cancel()
        job = scope.launch(Dispatchers.IO) { connectionLoop(session) }
    }

    /**
     * Reconnects for as long as the session is meant to be joined, backing off
     * so that a leader who has genuinely gone home does not cost the followers
     * their battery for the rest of the night.
     *
     * Two things it has to get right, and neither was obvious until a band tried
     * it in a room.
     *
     * **The address is not durable.** The leader binds an ephemeral port, so a
     * leader whose app restarted is listening on a different one, and a phone
     * that dropped off the wifi and back may have a different address as well.
     * Retrying the address this device first saw would retry it all night. So
     * every reconnect asks mDNS where the session is now, and falls back to the
     * last known address when the lookup finds nothing - which is the right
     * answer on a network that blocks multicast, where the address is all there
     * ever was.
     *
     * **A connection that ended immediately is not a success.** The backoff used
     * to reset whenever a connection had been established at all, so a leader
     * that accepted and instantly closed - a refused protocol version, a socket
     * dying at the far end - produced an unpaced retry loop. It resets only
     * after a connection that lasted, and every other case waits.
     */
    private suspend fun connectionLoop(first: DiscoveredSession) {
        var session = first
        var backoff = INITIAL_BACKOFF_MS

        while (scope.isActive) {
            val startedAt = System.currentTimeMillis()
            runCatching { attempt(session) }
            val lasted = System.currentTimeMillis() - startedAt

            if (_state.value.link == LinkState.OFFLINE) return

            if (lasted >= STABLE_CONNECTION_MS) {
                backoff = INITIAL_BACKOFF_MS
            }
            Diagnostics.log(
                Area.FOLLOWER,
                "connection to ${session.host}:${session.port} lasted ${lasted / 1000}s, " +
                    "retrying in ${backoff / 1000}s",
            )
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)

            if (_state.value.link == LinkState.OFFLINE) return

            // Ask where the session is now. Keep what we have if nobody answers.
            val moved = relocate?.let { lookUp ->
                runCatching { lookUp(session.serviceName) }.getOrNull()
            }
            when {
                moved == null -> Diagnostics.log(
                    Area.FOLLOWER,
                    "mDNS found no \"${session.serviceName}\"; retrying the address we have",
                )
                moved.host != session.host || moved.port != session.port -> Diagnostics.log(
                    Area.FOLLOWER,
                    "\"${session.serviceName}\" moved to ${moved.host}:${moved.port}",
                )
            }
            session = moved ?: session
        }
    }

    /** One connection, from open to close. Returns true if it ever got going. */
    private suspend fun attempt(session: DiscoveredSession): Boolean {
        var everConnected = false
        // Kept current, because a reconnect may have found the session at a new
        // address and everything else that asks where the leader is - the chart
        // channel included - should be told the same answer.
        target = session
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
                            Diagnostics.log(
                                Area.FOLLOWER,
                                "refused by ${message.leaderName}: " +
                                    (message.reason ?: "no reason given"),
                            )
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
                        Diagnostics.log(
                            Area.FOLLOWER,
                            "connected to \"${message.sessionName}\" led by ${message.leaderName}" +
                                (if (message.filePort > 0) ", charts on ${message.filePort}" else ""),
                        )
                        dispatch(FollowerEvent.Connected(message.sessionName), ::send)
                        // What this device brings. Announced on every connect,
                        // including a reconnect, because the leader forgets a
                        // device's catalogue when it drops.
                        publishCatalogue(::send)
                    }

                    is Position -> {
                        Diagnostics.log(
                            Area.FOLLOWER,
                            "position #${message.seq}: ${message.songTitle ?: "no song"} " +
                                "p${message.page + 1}, this device is ${_state.value.mode}",
                        )
                        dispatch(FollowerEvent.LeaderPosition(message), ::send)
                    }

                    is SetlistPush -> {
                        Diagnostics.log(
                            Area.SETLIST,
                            "sent \"${message.setlist.name}\" (${message.setlist.size} songs, " +
                                "id ${Diagnostics.short(message.setlist.identity)})",
                        )
                        _setlistPushes.tryEmit(message)
                    }

                    is ChartsOffered -> _chartOffers.tryEmit(message)

                    is CataloguePeer -> onPeerCatalogue(message)

                    is CatalogueGone -> {
                        peers.remove(message.deviceId)
                        peerPages.remove(message.deviceId)
                        republishAggregate()
                    }

                    is CheckRequest -> _checkRequests.tryEmit(message)

                    is Ping -> send(Pong(nextSeq(), message.sentAt, deviceId))

                    is Goodbye -> {
                        Diagnostics.log(Area.FOLLOWER, "leader said goodbye: ${message.reason}")
                        _lastError.value = message.reason ?: "The leader ended the session."
                        dispatch(FollowerEvent.LeaveRequested)
                        return everConnected
                    }

                    else -> Unit
                }
            }
        } catch (e: Exception) {
            // Nothing to distinguish here: a refused connection, a timeout and a
            // reset all mean the same thing to a player standing on a stage. The
            // log is the one place the difference is worth keeping.
            Diagnostics.log(
                Area.FOLLOWER,
                "connection ended: ${e.message ?: e::class.java.simpleName}",
            )
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
        peers.clear()
        peerPages.clear()
        _aggregate.value = emptyList()
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

    private fun onPeerCatalogue(message: CataloguePeer) {
        val id = message.device.deviceId
        val pages = peerPages.getOrPut(id) { mutableListOf() }
        synchronized(pages) { pages += message.device.charts }
        if (!message.final) return

        val charts = synchronized(pages) { pages.toList().also { pages.clear() } }
        peerPages.remove(id)
        peers[id] = message.device.copy(
            charts = charts,
            // A blank host is the leader saying "me": it cannot know which of
            // its addresses this device reached it on, and the one already in
            // use demonstrably works.
            host = message.device.host.ifBlank { target?.host.orEmpty() },
        )
        republishAggregate()
    }

    private fun republishAggregate() {
        _aggregate.value = Catalogue.merge(peers.values.toList())
    }

    /**
     * Tells the session what this device has, in pages.
     *
     * Paged for the same reason the leader's relay is: the control socket
     * carries page turns, and a library of two thousand charts on one line
     * would sit in front of the next one.
     */
    fun publishCatalogue(send: ((Message) -> Unit)? = null) {
        val charts = catalogue
        val pages = charts.chunked(CATALOGUE_PAGE_SIZE).ifEmpty { listOf(emptyList()) }
        for ((index, page) in pages.withIndex()) {
            val message = CataloguePublish(
                seq = nextSeq(),
                deviceId = deviceId,
                deviceName = deviceName,
                filePort = chartPort,
                charts = page,
                final = index == pages.lastIndex,
            )
            if (send != null) send(message) else sendOnCurrentSocket(message)
        }
        Diagnostics.log(
            Area.FOLLOWER,
            "published ${charts.size} charts" +
                (if (chartPort > 0) " on port $chartPort" else ", cannot serve"),
        )
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
        /**
         * How long a connection has to last to count as having worked.
         *
         * Above the round trip of a refusal - hello out, welcome back, socket
         * closed - and well below the length of a song.
         */
        const val STABLE_CONNECTION_MS = 5_000L

        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 30_000
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 20_000L

        /** Charts per catalogue message; see SessionServer for the reasoning. */
        const val CATALOGUE_PAGE_SIZE = 150
    }
}
