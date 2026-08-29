package org.droidmusic.session

/** One device that has joined the session, from the leader's point of view. */
data class Follower(
    val deviceId: String,
    val deviceName: String,
    val connectedAt: Long,
    val lastSeenAt: Long,
    val following: Boolean = true,
    val page: Int = 0,
    val songId: String? = null,
    val missingSong: Boolean = false,
)

/**
 * The leader's view of the session: who is here, where everyone is, and what to
 * send next.
 *
 * Kept as a pure value so the interesting behaviour - stale follower eviction,
 * sequence numbering, who is out of step - is testable without a socket.
 */
data class LeaderState(
    val sessionName: String,
    val leaderName: String,
    val followers: List<Follower> = emptyList(),
    val position: Position? = null,
    val seq: Long = 0,
) {
    /** Followers who are not on the leader's page, for the "who is lost" indicator. */
    fun outOfStep(): List<Follower> {
        val here = position ?: return emptyList()
        return followers.filter { it.following && (it.page != here.page || it.songId != here.songId) }
    }

    fun missingTheSong(): List<Follower> = followers.filter { it.missingSong }
}

object LeaderSession {

    /**
     * How long a follower can go without a word before it is dropped from the
     * list. Long enough to ride out a phone locking its screen or a moment of
     * bad wifi, short enough that a player who packed up and went home is not
     * still showing as connected at the end of the set.
     */
    const val FOLLOWER_TIMEOUT_MS = 20_000L

    /** How often the leader sends a heartbeat. */
    const val HEARTBEAT_INTERVAL_MS = 5_000L

    fun withFollower(state: LeaderState, hello: Hello, now: Long): LeaderState {
        val existing = state.followers.firstOrNull { it.deviceId == hello.deviceId }
        val follower = Follower(
            deviceId = hello.deviceId,
            deviceName = hello.deviceName,
            // A device that reconnects is the same device; keep when it first joined.
            connectedAt = existing?.connectedAt ?: now,
            lastSeenAt = now,
        )
        return state.copy(
            followers = state.followers.filterNot { it.deviceId == hello.deviceId } + follower,
        )
    }

    fun withoutFollower(state: LeaderState, deviceId: String): LeaderState =
        state.copy(followers = state.followers.filterNot { it.deviceId == deviceId })

    fun withStatus(state: LeaderState, status: FollowerStatus, now: Long): LeaderState {
        val updated = state.followers.map { follower ->
            if (follower.deviceId != status.deviceId) {
                follower
            } else {
                follower.copy(
                    lastSeenAt = now,
                    following = status.following,
                    page = status.page,
                    songId = status.songId,
                    missingSong = status.missingSong,
                    deviceName = status.deviceName,
                )
            }
        }
        return state.copy(followers = updated)
    }

    fun evictStale(state: LeaderState, now: Long): LeaderState =
        state.copy(followers = state.followers.filter { now - it.lastSeenAt <= FOLLOWER_TIMEOUT_MS })

    /**
     * Advances the leader to a new position and produces the message to send.
     *
     * The sequence number increments on every announcement, including one that
     * repeats the same page. A follower uses it only to discard anything older
     * than what it has already applied, so it has to move even when the position
     * does not - otherwise a resend after a reconnect would be thrown away as
     * stale, which is exactly the moment it is needed most.
     */
    fun announce(
        state: LeaderState,
        setlistIndex: Int,
        songId: String?,
        songTitle: String?,
        contentHash: String?,
        page: Int,
        transposeSemitones: Int = 0,
        capo: Int = 0,
    ): Pair<LeaderState, Position> {
        val next = state.seq + 1
        val position = Position(
            seq = next,
            setlistIndex = setlistIndex,
            songId = songId,
            songTitle = songTitle,
            contentHash = contentHash,
            page = page,
            transposeSemitones = transposeSemitones,
            capo = capo,
        )
        return state.copy(seq = next, position = position) to position
    }

    fun nextSeq(state: LeaderState): Pair<LeaderState, Long> {
        val next = state.seq + 1
        return state.copy(seq = next) to next
    }
}
