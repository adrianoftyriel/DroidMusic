package org.droidmusic.app.ui.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.droidmusic.app.data.SettingsRepository
import org.droidmusic.app.net.DiscoveredSession
import org.droidmusic.app.net.SessionClient
import org.droidmusic.app.net.SessionDiscovery
import org.droidmusic.app.net.SessionServer
import org.droidmusic.library.Setlist
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

    fun discoverSessions() = discovery.discover()

    suspend fun startLeading(sessionName: String) {
        stop()
        val current = settings.settings.value
        val leaderName = current.deviceName.ifEmpty { "Leader" }
        val newServer = SessionServer(scope, sessionName, leaderName, discovery)
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
            newClient.setlistPushes.collect { _pushedSetlist.value = it.setlist }
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

    fun clearMessage() {
        _message.value = null
    }

    fun consumePushedSetlist() {
        _pushedSetlist.value = null
    }

    fun stop() {
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
