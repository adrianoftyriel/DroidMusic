package org.droidmusic.app.ui.session

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.droidmusic.app.data.LibraryRepository
import org.droidmusic.app.data.SettingsRepository
import org.droidmusic.app.net.ChartFetcher
import org.droidmusic.app.net.ChartServer
import org.droidmusic.app.net.DiscoveredSession
import org.droidmusic.app.net.SessionClient
import org.droidmusic.app.net.SessionDiscovery
import org.droidmusic.app.net.SessionServer
import org.droidmusic.library.Setlist
import org.droidmusic.session.ChartOffer
import org.droidmusic.session.ChartSharing
import org.droidmusic.session.ChartShare
import org.droidmusic.session.ChartTransfer
import org.droidmusic.session.FollowMode
import org.droidmusic.session.FollowerState
import org.droidmusic.session.LeaderState
import org.droidmusic.session.LinkState
import org.droidmusic.session.Position

/** What this device is doing in a session, if anything. */
enum class SessionRole { NONE, LEADER, FOLLOWER }

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

    fun discoverSessions() = discovery.discover()

    suspend fun startLeading(sessionName: String) {
        stop()
        val current = settings.settings.value
        val leaderName = current.deviceName.ifEmpty { "Leader" }
        val newServer = SessionServer(
            scope = scope,
            sessionName = sessionName,
            leaderName = leaderName,
            discovery = discovery,
            library = library,
            chartServer = ChartServer(scope, context, library),
        )
        server = newServer
        _role.value = SessionRole.LEADER
        _sessionLabel.value = sessionName

        scope.launch { newServer.state.collect { _leaderState.value = it } }
        runCatching { newServer.start() }
            .onFailure {
                _message.value = "Could not open a session on this network."
                stop()
            }
    }

    fun joinAsFollower(session: DiscoveredSession) {
        stop()
        val current = settings.settings.value
        val newClient = SessionClient(
            scope = scope,
            deviceId = current.deviceId,
            deviceName = current.deviceName.ifEmpty { "Player" },
            appVersion = appVersion,
        )
        client = newClient
        _role.value = SessionRole.FOLLOWER
        _sessionLabel.value = session.serviceName

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

    fun pushSetlist(setlist: Setlist) {
        server?.pushSetlist(setlist)
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
        if (client.chartSource.value == null) return
        val wanted = ChartShare.wanted(setlist, library.index.value)
        if (wanted.isEmpty()) return
        client.requestCharts(wanted)
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
        server?.stop()
        server = null
        client?.leave()
        client = null
        _role.value = SessionRole.NONE
        _leaderState.value = null
        _followerState.value = null
        _sessionLabel.value = null
        _remotePosition.value = null
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
