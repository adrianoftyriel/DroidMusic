package org.droidmusic.session

/** Whether the socket to the leader is up. */
enum class LinkState { CONNECTED, RECONNECTING, OFFLINE }

/**
 * Whether this device is taking its page turns from the leader.
 *
 * [DETACHED] is not an error state. It is a player who turned a page themselves
 * and is now reading at their own pace, with a one-tap way back.
 */
enum class FollowMode { FOLLOWING, DETACHED }

data class LocalPosition(val songId: String?, val page: Int, val setlistIndex: Int = -1)

data class FollowerState(
    val link: LinkState = LinkState.OFFLINE,
    val mode: FollowMode = FollowMode.FOLLOWING,
    /** The last position the leader announced, whether or not it was applied. */
    val leaderPosition: Position? = null,
    val local: LocalPosition? = null,
    val lastSeq: Long = 0,
    /**
     * Whether the player has turned a page under their own steam since the link
     * dropped. This is the single fact that decides what happens on reconnect.
     */
    val navigatedWhileOffline: Boolean = false,
) {
    /** True when the leader is somewhere this device is not. */
    val isBehindLeader: Boolean
        get() {
            val leader = leaderPosition ?: return false
            val here = local ?: return true
            return leader.page != here.page || leader.songId != here.songId
        }

    val canRejoin: Boolean
        get() = link == LinkState.CONNECTED && mode == FollowMode.DETACHED && leaderPosition != null
}

sealed interface FollowerEvent {
    data class Connected(val sessionName: String) : FollowerEvent
    data class LeaderPosition(val position: Position) : FollowerEvent
    data object ConnectionLost : FollowerEvent

    /** The player turned a page on this device - by tap, foot switch or otherwise. */
    data class UserNavigated(val to: LocalPosition) : FollowerEvent

    /** The player asked to come back into step with the leader. */
    data object RejoinRequested : FollowerEvent

    /** The player chose to stop following for the rest of the session. */
    data object LeaveRequested : FollowerEvent
}

/** What the app should do as a result of a transition. */
sealed interface FollowerEffect {
    /** Move the viewer to this position. */
    data class ApplyPosition(val position: Position) : FollowerEffect

    /** Tell the leader where this device is and whether it is following. */
    data object ReportStatus : FollowerEffect

    /**
     * Leader is elsewhere and this device deliberately is not. Show the
     * offer to rejoin; do not move the page.
     */
    data object OfferRejoin : FollowerEffect
}

data class Transition(val state: FollowerState, val effects: List<FollowerEffect> = emptyList())

/**
 * The rules for a follower, as a pure function of state and event.
 *
 * This is separated from the socket code so it can be tested exhaustively, and
 * because the interesting parts of "band leader mode" are all decisions rather
 * than networking. Two of them are worth spelling out.
 *
 * **A page turn on this device stops it following.** Otherwise a player who
 * glances back at the previous page gets yanked forward by the leader half a
 * second later, and the two of them fight over the screen. Turning a page is
 * taken as meaning it, and rejoining is one tap.
 *
 * **Reconnecting does not always resync.** If the link dropped and the player
 * kept turning their own pages, they are reading somewhere on purpose, and
 * silently jumping them to the leader's page the moment the wifi comes back -
 * possibly mid-phrase, possibly mid-song - is exactly the wrong thing. So a
 * reconnection after autonomous page turns offers to rejoin rather than doing
 * it. A reconnection where the player did *not* turn a page resyncs silently,
 * because there is nothing to lose and it is what they want.
 */
object FollowerMachine {

    fun reduce(state: FollowerState, event: FollowerEvent): Transition = when (event) {

        is FollowerEvent.Connected -> {
            // Coming back after a drop. What happens next depends entirely on
            // whether the player took over while the link was down.
            if (state.navigatedWhileOffline) {
                Transition(
                    state.copy(
                        link = LinkState.CONNECTED,
                        mode = FollowMode.DETACHED,
                        navigatedWhileOffline = false,
                    ),
                    listOf(FollowerEffect.OfferRejoin, FollowerEffect.ReportStatus),
                )
            } else {
                Transition(
                    state.copy(link = LinkState.CONNECTED, mode = FollowMode.FOLLOWING),
                    listOf(FollowerEffect.ReportStatus),
                )
            }
        }

        is FollowerEvent.LeaderPosition -> {
            val position = event.position
            when {
                // Out of order or replayed. Positions are absolute, so the newest
                // one is the only one that matters and an older one is noise.
                position.seq <= state.lastSeq -> Transition(state)

                state.mode == FollowMode.FOLLOWING -> Transition(
                    state.copy(
                        leaderPosition = position,
                        lastSeq = position.seq,
                        local = LocalPosition(position.songId, position.page, position.setlistIndex),
                    ),
                    listOf(FollowerEffect.ApplyPosition(position)),
                )

                else -> Transition(
                    state.copy(leaderPosition = position, lastSeq = position.seq),
                    listOf(FollowerEffect.OfferRejoin),
                )
            }
        }

        FollowerEvent.ConnectionLost -> Transition(
            state.copy(link = LinkState.RECONNECTING, navigatedWhileOffline = false),
        )

        is FollowerEvent.UserNavigated -> {
            val offline = state.link != LinkState.CONNECTED
            Transition(
                state.copy(
                    local = event.to,
                    // Turning a page while connected means taking over. Turning one
                    // while the link is down is just reading, and is remembered so
                    // the reconnect knows not to jump the page.
                    mode = if (offline) state.mode else FollowMode.DETACHED,
                    navigatedWhileOffline = offline || state.navigatedWhileOffline,
                ),
                if (offline) emptyList() else listOf(FollowerEffect.ReportStatus),
            )
        }

        FollowerEvent.RejoinRequested -> {
            val leader = state.leaderPosition
            if (leader == null) {
                Transition(state.copy(mode = FollowMode.FOLLOWING), listOf(FollowerEffect.ReportStatus))
            } else {
                Transition(
                    state.copy(
                        mode = FollowMode.FOLLOWING,
                        local = LocalPosition(leader.songId, leader.page, leader.setlistIndex),
                        navigatedWhileOffline = false,
                    ),
                    listOf(FollowerEffect.ApplyPosition(leader), FollowerEffect.ReportStatus),
                )
            }
        }

        FollowerEvent.LeaveRequested -> Transition(
            state.copy(link = LinkState.OFFLINE, mode = FollowMode.DETACHED),
        )
    }

    /** Convenience for driving a sequence of events, mostly for tests. */
    fun reduceAll(initial: FollowerState, events: List<FollowerEvent>): Transition =
        events.fold(Transition(initial)) { acc, event -> reduce(acc.state, event) }
}
