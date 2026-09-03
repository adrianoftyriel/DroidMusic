package org.droidmusic.app.ui.session

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.droidmusic.app.diag.Area
import org.droidmusic.app.diag.Diagnostics
import org.droidmusic.app.data.LibraryRepository
import org.droidmusic.app.data.SettingsRepository
import org.droidmusic.app.net.ChartFetcher
import org.droidmusic.app.net.ChartServer
import org.droidmusic.app.net.DiscoveredSession
import org.droidmusic.app.net.SessionClient
import org.droidmusic.app.net.SessionDiscovery
import org.droidmusic.app.net.SessionServer
import org.droidmusic.library.Setlist
import org.droidmusic.session.BackstageReport
import org.droidmusic.session.ChartOffer
import org.droidmusic.session.ChartSharing
import org.droidmusic.session.ChartShare
import org.droidmusic.session.ChartTransfer
import org.droidmusic.session.AggregatedChart
import org.droidmusic.session.Catalogue
import org.droidmusic.session.ChartWant
import org.droidmusic.session.FollowMode
import org.droidmusic.session.FollowerState
import org.droidmusic.session.LeaderState
import org.droidmusic.session.LinkState
import org.droidmusic.session.Position

/** What this device is doing in a session, if anything. */
enum class SessionRole { NONE, LEADER, FOLLOWER }

/**
 * One chart being pulled from another device in the band.
 *
 * Separate from [org.droidmusic.session.ChartTransfer], which describes the
 * set-list flow where the leader offers and the player accepts a batch. This is
 * one chart, asked for by name off the aggregated library, from whichever device
 * has it.
 */
data class PeerFetch(
    val chart: AggregatedChart,
    val from: String = "",
    val receivedBytes: Long = 0,
    val done: Boolean = false,
    val failed: String? = null,
) {
    val inFlight: Boolean get() = !done && failed == null

    val fraction: Float
        get() = if (chart.sizeBytes <= 0) 0f
        else (receivedBytes.toFloat() / chart.sizeBytes).coerceIn(0f, 1f)
}

/**
 * The single place the rest of the app asks about band-leader mode.
 *
 * The viewer must not know whether it is leading, following or on its own. It
 * reports where the player went and asks where it should be, and this decides.
 * That is what keeps the "connectivity lost" behaviour honest: there is exactly
 * one code path for turning a page, and it works whether or not anyone is
 * listening.
 */
class SessionCoordinator(
    private val scope: CoroutineScope,
    private val discovery: SessionDiscovery,
    private val settings: SettingsRepository,
    private val appVersion: String,
    private val context: Context,
    private val library: LibraryRepository,
) {
    private val _role = MutableStateFlow(SessionRole.NONE)
    val role: StateFlow<SessionRole> = _role.asStateFlow()

    private var server: SessionServer? = null
    private var client: SessionClient? = null

    private val _leaderState = MutableStateFlow<LeaderState?>(null)
    val leaderState: StateFlow<LeaderState?> = _leaderState.asStateFlow()

    private val _followerState = MutableStateFlow<FollowerState?>(null)
    val followerState: StateFlow<FollowerState?> = _followerState.asStateFlow()

    private val _sessionLabel = MutableStateFlow<String?>(null)
    val sessionLabel: StateFlow<String?> = _sessionLabel.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * The position the viewer should be showing, when something else is deciding
     * that. Null means this device is on its own and its own page is the truth.
     */
    private val _remotePosition = MutableStateFlow<Position?>(null)
    val remotePosition: StateFlow<Position?> = _remotePosition.asStateFlow()

    private val _pushedSetlist = MutableStateFlow<Setlist?>(null)
    val pushedSetlist: StateFlow<Setlist?> = _pushedSetlist.asStateFlow()

    private val _sharing = MutableStateFlow(ChartSharing())

    /** Charts the leader has offered, and how the ones being fetched are getting on. */
    val sharing: StateFlow<ChartSharing> = _sharing.asStateFlow()

    private val fetcher by lazy { ChartFetcher(context, library) }

    /**
     * This device's own chart server, in either role.
     *
     * A follower runs one too now, because the aggregated library is only worth
     * looking at if a chart nobody but the bass player has can actually be
     * pulled from the bass player. Otherwise the list is a catalogue of things
     * the band cannot get.
     */
    private var ownChartServer: ChartServer? = null

    private val _aggregate = MutableStateFlow<List<AggregatedChart>>(emptyList())

    /**
     * Every chart the band has between them, with who holds each one.
     *
     * Sourced differently by role and deliberately the same flow: the leader
     * hears from everybody and merges, a follower is told the merge. Backstage
     * does not have to know which it is looking at.
     */
    val aggregate: StateFlow<List<AggregatedChart>> = _aggregate.asStateFlow()

    /** Charts arriving from a peer, keyed by content hash, for a progress row. */
    private val _peerFetches = MutableStateFlow<Map<String, PeerFetch>>(emptyMap())
    val peerFetches: StateFlow<Map<String, PeerFetch>> = _peerFetches.asStateFlow()

    /**
     * A set list the leader has asked everyone to check, waiting to be checked.
     *
     * The set list travels with the request, so what gets checked is the
     * leader's running order rather than whatever this device happens to have
     * adopted under the same name.
     */
    private val _requestedCheck = MutableStateFlow<Setlist?>(null)
    val requestedCheck: StateFlow<Setlist?> = _requestedCheck.asStateFlow()

    fun discoverSessions() = discovery.discover()

    /**
     * Opens a session, on a scope that outlives whatever screen asked for it.
     *
     * This exists because the obvious call site is wrong in a way that is
     * invisible until you try it. The button that starts a session is on a
     * screen that immediately navigates to Backstage, and a
     * `rememberCoroutineScope` is cancelled the moment its composable leaves the
     * composition - so `scope.launch { startLeading() }` followed by a
     * navigation cancels the start halfway through binding the socket. The
     * session is torn down before anybody can join it, and the leader is left
     * looking at an error when they come back.
     *
     * [then] runs after the session is up, for the caller that has a running
     * order to push. It is skipped if the session did not open, because pushing
     * a set list into a session that does not exist is a no-op that hides the
     * failure.
     */
    fun lead(sessionName: String, then: suspend () -> Unit = {}) {
        scope.launch {
            startLeading(sessionName)
            if (_role.value == SessionRole.LEADER) then()
        }
    }

    /**
     * Private, so [lead] is the only way in.
     *
     * Deliberately not callable from a screen. Every caller of this is a button,
     * every button is in a composable, and a composable's scope is exactly the
     * one that must not be used - so the type system says no rather than the
     * next person rediscovering it with two phones and a rehearsal to run.
     */
    private suspend fun startLeading(sessionName: String) {
        stop()
        // Whatever went wrong last time is not what is happening now, and an
        // error left over from a previous attempt sitting above a session that
        // is running is worse than no error at all.
        _message.value = null
        val current = settings.settings.value
        val leaderName = current.deviceName.ifEmpty { "Leader" }
        val charts = ChartServer(scope, context, library)
        ownChartServer = charts
        val newServer = SessionServer(
            scope = scope,
            sessionName = sessionName,
            leaderName = leaderName,
            discovery = discovery,
            library = library,
            chartServer = charts,
            context = context,
        )
        server = newServer
        _role.value = SessionRole.LEADER
        _sessionLabel.value = sessionName

        scope.launch { newServer.state.collect { _leaderState.value = it } }
        scope.launch { newServer.aggregate.collect { _aggregate.value = it } }
        try {
            newServer.start()
        } catch (cancelled: CancellationException) {
            // Not a network failure and must not be reported as one. The only
            // thing that cancels a start is the caller going away, and telling
            // the player their wifi is broken because they navigated is how a
            // real fault gets ignored later. Tear down and re-throw, so the
            // coroutine machinery sees the cancellation it asked for.
            stop()
            throw cancelled
        } catch (failed: Exception) {
            Diagnostics.log(Area.LEADER, "could not open a session: ${failed.message ?: failed}")
            _message.value = "Could not open a session on this network."
            stop()
            return
        }

        // Its own library goes into the union like anybody else's, and again
        // whenever it changes - a chart imported during a soundcheck should
        // appear on everybody's list without anyone rejoining.
        scope.launch {
            library.index.collect {
                newServer.announceOwnCatalogue(
                    deviceId = current.deviceId,
                    deviceName = leaderName,
                )
            }
        }
    }

    fun joinAsFollower(session: DiscoveredSession) {
        stop()
        _message.value = null
        val current = settings.settings.value
        val newClient = SessionClient(
            scope = scope,
            deviceId = current.deviceId,
            deviceName = current.deviceName.ifEmpty { "Player" },
            appVersion = appVersion,
            // So a reconnect can find a leader that has moved. The address a
            // session was joined on stops being true the moment the leader's app
            // restarts, because the port it binds is an ephemeral one.
            relocate = { name -> discovery.resolveOnce(name) },
        )
        client = newClient
        _role.value = SessionRole.FOLLOWER
        _sessionLabel.value = session.serviceName

        // This device serves its own charts too, so a song only it has can be
        // pulled by the rest of the band. Started before the catalogue is
        // published, because the port is part of what is published; if it will
        // not bind, the catalogue goes out with port zero and this device is
        // listed as having charts it cannot send, which is the honest answer.
        val charts = ChartServer(scope, context, library)
        ownChartServer = charts
        scope.launch {
            val port = runCatching { charts.start() }.getOrDefault(0)
            if (port == 0) {
                Diagnostics.log(
                    Area.FOLLOWER,
                    "could not open a chart port; this device will not be able to send charts",
                )
            }
            newClient.chartPort = port
            newClient.catalogue = ChartShare.catalogueOf(library.index.value)
            newClient.publishCatalogue()
        }

        scope.launch { newClient.aggregate.collect { _aggregate.value = it } }

        // Re-announced when this device's library changes, so a chart imported
        // mid-soundcheck is on offer to the band without rejoining.
        scope.launch {
            library.index.collect { index ->
                newClient.catalogue = ChartShare.catalogueOf(index)
                newClient.publishCatalogue()
            }
        }

        scope.launch { newClient.state.collect { _followerState.value = it } }
        scope.launch { newClient.lastError.collect { _message.value = it } }
        scope.launch {
            newClient.setlistPushes.collect { push ->
                _pushedSetlist.value = push.setlist
                askForMissingCharts(newClient, push.setlist)
            }
        }
        scope.launch {
            newClient.chartOffers.collect { offered -> onOffered(offered.offers) }
        }
        scope.launch {
            newClient.checkRequests.collect { _requestedCheck.value = it.setlist }
        }
        scope.launch {
            // Only a position that should actually move this device is published.
            // The state machine has already decided; this just carries the result.
            newClient.effects.collect { effect ->
                if (effect is org.droidmusic.session.FollowerEffect.ApplyPosition) {
                    _remotePosition.value = effect.position
                }
            }
        }
        newClient.join(session)
    }

    /**
     * Called by the viewer every time the player moves, whatever the reason.
     *
     * As leader this broadcasts. As follower it tells the state machine, which
     * decides whether the player has just taken control. Alone it does nothing,
     * which is the case that has to keep working when the wifi does not.
     */
    fun onLocalPosition(
        songId: String?,
        songTitle: String?,
        contentHash: String?,
        page: Int,
        setlistIndex: Int,
        transposeSemitones: Int,
        capo: Int,
        userInitiated: Boolean,
    ) {
        when (_role.value) {
            SessionRole.LEADER -> server?.announce(
                setlistIndex = setlistIndex,
                songId = songId,
                songTitle = songTitle,
                contentHash = contentHash,
                page = page,
                transposeSemitones = transposeSemitones,
                capo = capo,
            )

            SessionRole.FOLLOWER -> if (userInitiated) {
                client?.onUserNavigated(songId, page, setlistIndex)
            }

            SessionRole.NONE -> Unit
        }
    }

    /**
     * The player has chosen a key.
     *
     * As leader that is the band's key and goes out at once, rather than
     * waiting for the next page turn to carry it - the whole point of a shared
     * key is that nobody is reading the old one in the meantime. As a follower
     * it is a local override that the leader's next position will overrule, and
     * it deliberately does *not* report a status: trying a key on your own
     * screen is not the same as taking control of the set.
     */
    fun onLocalTranspose(
        songId: String?,
        songTitle: String?,
        contentHash: String?,
        page: Int,
        setlistIndex: Int,
        transposeSemitones: Int,
        capo: Int,
    ) {
        if (_role.value != SessionRole.LEADER) return
        server?.announce(
            setlistIndex = setlistIndex,
            songId = songId,
            songTitle = songTitle,
            contentHash = contentHash,
            page = page,
            transposeSemitones = transposeSemitones,
            capo = capo,
        )
    }

    fun pushSetlist(setlist: Setlist) {
        server?.pushSetlist(setlist)
    }

    /**
     * Asks the band to check they can open tonight's charts.
     *
     * Does nothing at all when this device is not leading, which is deliberate:
     * a player can still run the check on their own copy, and the Backstage
     * screen works identically whether or not anybody is listening.
     */
    fun requestCheck(setlist: Setlist) {
        server?.requestCheck(setlist)
    }

    /**
     * Sends this device's answer to the leader, when there is one to send it to.
     *
     * A leader's own report stays where it was made: the Backstage screen shows
     * this device's verdict from the check itself, and [LeaderState.reports] is
     * the followers' answers, pruned as followers come and go.
     */
    fun submitReport(report: BackstageReport) {
        if (_role.value == SessionRole.FOLLOWER) client?.sendReport(report)
    }

    fun consumeRequestedCheck() {
        _requestedCheck.value = null
    }

    /** Bring this device back into step with the leader. */
    fun rejoin() {
        client?.rejoin()
        _followerState.value?.leaderPosition?.let { _remotePosition.value = it }
    }

    // ---- Charts the follower has not got ----------------------------------

    /**
     * Works out what this device is short of and asks the leader for it.
     *
     * Only ever asked after a set list arrives, because the set list is what
     * says which songs tonight actually needs. A library missing a chart nobody
     * is going to play is not a problem worth a transfer.
     */
    private fun askForMissingCharts(client: SessionClient, setlist: Setlist) {
        requestCharts(ChartShare.wanted(setlist, library.index.value))
    }

    /**
     * Asks the leader for a named list of charts, from Backstage.
     *
     * The automatic ask above fires when a set list arrives and covers what the
     * library cannot resolve at all. This one is the player pressing the button
     * after the check, which knows more: a chart that resolves and then will not
     * open is invisible to the automatic ask and is exactly the case somebody
     * wants a fresh copy of.
     */
    fun requestCharts(wanted: List<ChartWant>) {
        val active = client ?: return
        if (active.chartSource.value == null || wanted.isEmpty()) return
        active.requestCharts(wanted)
    }

    /**
     * Holds what the leader offered until the player has answered.
     *
     * Asked once a session: the first offer raises the question, and whatever
     * comes later - a second set list, a reconnection - is taken on the answer
     * already given. Being asked again halfway through a set is worse than
     * either answer.
     */
    private fun onOffered(offers: List<ChartOffer>) {
        val already = _sharing.value
        val fresh = offers.filterNot { offer ->
            already.transfers.any { it.offer.contentHash == offer.contentHash } ||
                already.pending.any { it.contentHash == offer.contentHash }
        }
        if (fresh.isEmpty()) return

        val acceptance = ChartShare.accept(fresh)
        _sharing.value = already.copy(
            pending = already.pending + acceptance.accepted,
            refused = already.refused + acceptance.refused,
        )
        if (already.answered) startFetching()
    }

    /** The player agreeing, once, to charts arriving on this device. */
    fun acceptCharts() {
        _sharing.value = _sharing.value.copy(answered = true)
        startFetching()
    }

    /** The player declining. Nothing arrives, and nothing asks again this session. */
    fun declineCharts() {
        _sharing.value = _sharing.value.copy(answered = true, pending = emptyList())
    }

    private fun startFetching() {
        val source = client?.chartSource?.value ?: return
        val queued = _sharing.value.pending
        if (queued.isEmpty()) return

        _sharing.value = _sharing.value.copy(
            pending = emptyList(),
            transfers = _sharing.value.transfers + queued.map { ChartTransfer(it) },
        )

        scope.launch {
            // One at a time. Several large transfers at once on a pub's wifi is
            // how none of them finishes, and there is nothing to be gained by
            // racing them - the set does not start until they are all here.
            for (offer in queued) {
                val outcome = fetcher.fetch(source.host, source.port, offer) { received ->
                    updateTransfer(offer.contentHash) { it.copy(receivedBytes = received) }
                }
                when (outcome) {
                    is ChartFetcher.Outcome.Installed ->
                        updateTransfer(offer.contentHash) { it.copy(done = true) }
                    is ChartFetcher.Outcome.Failed ->
                        updateTransfer(offer.contentHash) { it.copy(failed = outcome.reason) }
                }
            }
            // Nothing is republished here on purpose. A set list entry is
            // resolved against the library each time it is read, and the library
            // index is a StateFlow, so the entries that just arrived stop showing
            // as missing the moment the charts are filed. Re-assigning the set
            // list would emit nothing anyway - a StateFlow does not re-emit a
            // value equal to the one it holds.
        }
    }

    /**
     * Fetches one chart from whoever in the band has it.
     *
     * Not the leader by default: the point of the aggregated library is that a
     * chart only the bass player has comes from the bass player. A blank host
     * means the leader, which is the one device whose address this one already
     * knows.
     *
     * Asking twice for the same chart is a no-op, because a list that
     * recomposes is not a second request.
     */
    fun fetchFromBand(chart: AggregatedChart) {
        if (_peerFetches.value[chart.contentHash]?.let { it.done || it.inFlight } == true) return

        val me = settings.settings.value.deviceId
        val owner = Catalogue.sourceFor(chart, me)
        if (owner == null || owner.deviceId == me) return

        val host = owner.host.ifBlank { client?.chartSource?.value?.host.orEmpty() }
        if (host.isBlank()) {
            setPeerFetch(chart, PeerFetch(chart, failed = "No address for ${owner.deviceName}."))
            return
        }

        setPeerFetch(chart, PeerFetch(chart, from = owner.deviceName))

        scope.launch {
            val offer = ChartOffer(
                contentHash = chart.contentHash,
                title = chart.title,
                displayName = chart.displayName,
                kind = org.droidmusic.library.SongRef.kindOf(chart.displayName),
                sizeBytes = chart.sizeBytes,
                artist = chart.artist,
                keyText = chart.keyText,
            )
            val outcome = fetcher.fetch(host, owner.filePort, offer) { received ->
                _peerFetches.value = _peerFetches.value.toMutableMap().apply {
                    this[chart.contentHash]?.let { this[chart.contentHash] = it.copy(receivedBytes = received) }
                }
            }
            when (outcome) {
                is ChartFetcher.Outcome.Installed -> {
                    Diagnostics.log(
                        Area.FOLLOWER,
                        "fetched \"${chart.title}\" from ${owner.deviceName}",
                    )
                    setPeerFetch(chart, PeerFetch(chart, from = owner.deviceName, done = true))
                }

                is ChartFetcher.Outcome.Failed -> setPeerFetch(
                    chart,
                    PeerFetch(chart, from = owner.deviceName, failed = outcome.reason),
                )
            }
        }
    }

    private fun setPeerFetch(chart: AggregatedChart, state: PeerFetch) {
        _peerFetches.value = _peerFetches.value + (chart.contentHash to state)
    }

    private fun updateTransfer(hash: String, change: (ChartTransfer) -> ChartTransfer) {
        _sharing.value = _sharing.value.copy(
            transfers = _sharing.value.transfers.map {
                if (it.offer.contentHash == hash) change(it) else it
            },
        )
    }

    fun clearMessage() {
        _message.value = null
    }

    fun consumePushedSetlist() {
        _pushedSetlist.value = null
    }

    fun stop() {
        _sharing.value = ChartSharing()
        _aggregate.value = emptyList()
        _peerFetches.value = emptyMap()
        ownChartServer?.stop()
        ownChartServer = null
        server?.stop()
        server = null
        client?.leave()
        client = null
        _role.value = SessionRole.NONE
        _leaderState.value = null
        _followerState.value = null
        _sessionLabel.value = null
        _remotePosition.value = null
        _requestedCheck.value = null
    }

    companion object {
        /**
         * A one-line description of the session for the viewer's status strip.
         *
         * A pure function of the four things it describes, rather than a method
         * reading the flows directly. That is not tidiness: a method that reaches
         * into `StateFlow.value` is invisible to Compose, so the strip would keep
         * saying "Leading - nobody has joined yet" after the whole band had
         * joined. Taking the values as parameters forces the caller to collect
         * them, which is what makes the strip update.
         *
         * Worth getting right, because it is the only thing a player looks at to
         * know whether their page turns are their own, and it has to be readable
         * at a glance in the dark from a metre away.
         */
        fun statusLine(
            role: SessionRole,
            leader: LeaderState?,
            follower: FollowerState?,
            sessionLabel: String?,
        ): String? = when (role) {
            SessionRole.NONE -> null

            SessionRole.LEADER -> {
                val followers = leader?.followers?.size ?: 0
                val lost = leader?.outOfStep()?.size ?: 0
                when {
                    followers == 0 -> "Leading - nobody has joined yet"
                    lost > 0 -> "Leading $followers - $lost out of step"
                    else -> "Leading $followers"
                }
            }

            SessionRole.FOLLOWER -> when {
                follower == null -> "Joining..."
                follower.link != LinkState.CONNECTED -> "Offline - turning your own pages"
                follower.mode == FollowMode.DETACHED -> "On your own - tap to rejoin"
                else -> "Following ${sessionLabel ?: "the leader"}"
            }
        }
    }
}
