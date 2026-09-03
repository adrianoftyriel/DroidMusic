package org.droidmusic.session

import org.droidmusic.library.Setlist
import org.droidmusic.library.SetlistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {

    @Test
    fun `every message type round trips`() {
        val messages = listOf<Message>(
            Hello(seq = 0, deviceName = "Jim's Pixel", deviceId = "d1", appVersion = "0.1.0"),
            Welcome(seq = 1, accepted = true, sessionName = "Anchor", leaderName = "Jim"),
            Welcome(seq = 2, accepted = false, sessionName = "X", leaderName = "Y", reason = "full"),
            Position(seq = 3, setlistIndex = 2, songId = "s", songTitle = "T", page = 4),
            SetlistPush(
                seq = 4,
                setlist = Setlist(
                    id = "sl",
                    name = "Set",
                    entries = listOf(SetlistEntry(songId = "a", title = "A")),
                ),
            ),
            Ping(seq = 5, sentAt = 123L),
            Pong(seq = 6, sentAt = 123L, deviceId = "d1"),
            FollowerStatus(seq = 7, deviceId = "d1", deviceName = "n", following = true, page = 2),
            Goodbye(seq = 8, reason = "set finished"),
            CataloguePublish(
                seq = 9,
                deviceId = "d1",
                deviceName = "Jim's Pixel",
                filePort = 41235,
                charts = listOf(
                    ChartOffer(
                        contentHash = "h1",
                        title = "Wichita Lineman",
                        displayName = "Wichita Lineman.pdf",
                        kind = org.droidmusic.library.FileKind.PDF,
                        sizeBytes = 240_000,
                        artist = "Jimmy Webb",
                        keyText = "F",
                    ),
                ),
                final = false,
            ),
            CataloguePeer(
                seq = 10,
                device = CatalogueDevice(
                    deviceId = "d2",
                    deviceName = "Bo",
                    host = "192.168.1.42",
                    filePort = 41236,
                    charts = emptyList(),
                ),
            ),
            CatalogueGone(seq = 11, deviceId = "d2"),
        )
        for (message in messages) {
            val decoded = Wire.decode(Wire.encode(message))
            assertEquals(message, decoded)
        }
    }

    // Newline-delimited framing only works if no encoded message ever contains a
    // bare newline. Set list names and song titles are user text, so this is a
    // real risk rather than a theoretical one.
    @Test
    fun `an encoded message is exactly one line even with newlines in the data`() {
        val message = SetlistPush(
            seq = 1,
            setlist = Setlist(
                id = "s",
                name = "Set\nwith\nnewlines",
                entries = listOf(SetlistEntry(songId = "a", title = "Title\r\nbroken")),
            ),
        )
        val encoded = Wire.encode(message)
        assertEquals(1, encoded.count { it == '\n' })
        assertTrue(encoded.endsWith("\n"))
        assertEquals(message, Wire.decode(encoded))
    }

    @Test
    fun `garbage on the wire is dropped rather than thrown`() {
        assertNull(Wire.decode(""))
        assertNull(Wire.decode("   "))
        assertNull(Wire.decode("this is not json"))
        assertNull(Wire.decode("{\"type\":\"somethingFromTheFuture\",\"seq\":1}"))
        assertNull(Wire.decode("{\"seq\":1}"))
    }

    @Test
    fun `unknown fields from a newer build are ignored`() {
        val line = "{\"type\":\"position\",\"seq\":1,\"setlistIndex\":0,\"songId\":\"s\"," +
            "\"page\":3,\"somethingNew\":\"ignored\"}"
        val decoded = Wire.decode(line) as? Position
        assertNotNull(decoded)
        assertEquals(3, decoded!!.page)
    }

    @Test
    fun `version compatibility is checked explicitly`() {
        assertTrue(Wire.isCompatible(PROTOCOL_VERSION))
        assertFalse(Wire.isCompatible(PROTOCOL_VERSION + 1))
        assertFalse(Wire.isCompatible(0))
    }
}

class FollowerMachineTest {

    private fun position(seq: Long, page: Int, songId: String? = "song-1") =
        Position(seq = seq, setlistIndex = 0, songId = songId, page = page)

    private val connected = FollowerState(link = LinkState.CONNECTED, mode = FollowMode.FOLLOWING)

    @Test
    fun `a following device applies the leader's page`() {
        val t = FollowerMachine.reduce(connected, FollowerEvent.LeaderPosition(position(1, 3)))
        assertEquals(listOf(FollowerEffect.ApplyPosition(position(1, 3))), t.effects)
        assertEquals(3, t.state.local?.page)
    }

    // Absolute positions plus a sequence number mean a late or duplicated message
    // can never move anyone backwards.
    @Test
    fun `stale and duplicate positions are ignored`() {
        var state = FollowerMachine.reduce(connected, FollowerEvent.LeaderPosition(position(5, 9))).state
        assertEquals(9, state.local?.page)

        val stale = FollowerMachine.reduce(state, FollowerEvent.LeaderPosition(position(4, 2)))
        assertTrue(stale.effects.isEmpty())
        assertEquals(9, stale.state.local?.page)

        val duplicate = FollowerMachine.reduce(state, FollowerEvent.LeaderPosition(position(5, 9)))
        assertTrue(duplicate.effects.isEmpty())

        state = FollowerMachine.reduce(state, FollowerEvent.LeaderPosition(position(6, 10))).state
        assertEquals(10, state.local?.page)
    }

    @Test
    fun `turning a page while connected takes control`() {
        val t = FollowerMachine.reduce(
            connected,
            FollowerEvent.UserNavigated(LocalPosition("song-1", 2)),
        )
        assertEquals(FollowMode.DETACHED, t.state.mode)

        // The leader moving on no longer drags this device with it.
        val after = FollowerMachine.reduce(t.state, FollowerEvent.LeaderPosition(position(1, 7)))
        assertEquals(2, after.state.local?.page)
        assertEquals(listOf(FollowerEffect.OfferRejoin), after.effects)
        // But the leader's position is still tracked, so "rejoin" knows where to go.
        assertEquals(7, after.state.leaderPosition?.page)
        assertTrue(after.state.canRejoin)
    }

    @Test
    fun `rejoining jumps to wherever the leader now is`() {
        val detached = FollowerMachine.reduceAll(
            connected,
            listOf(
                FollowerEvent.UserNavigated(LocalPosition("song-1", 2)),
                FollowerEvent.LeaderPosition(position(9, 12)),
            ),
        ).state

        val t = FollowerMachine.reduce(detached, FollowerEvent.RejoinRequested)
        assertEquals(FollowMode.FOLLOWING, t.state.mode)
        assertEquals(12, t.state.local?.page)
        assertTrue(t.effects.any { it is FollowerEffect.ApplyPosition })
    }

    // The requirement this whole class exists for: the wifi goes and the player
    // keeps playing.
    @Test
    fun `losing the link leaves the player free to turn their own pages`() {
        var state = FollowerMachine.reduce(connected, FollowerEvent.LeaderPosition(position(1, 4))).state
        state = FollowerMachine.reduce(state, FollowerEvent.ConnectionLost).state
        assertEquals(LinkState.RECONNECTING, state.link)

        // Page turns still work, and crucially they do not count as "taking over" -
        // there is nobody to take over from.
        state = FollowerMachine.reduce(state, FollowerEvent.UserNavigated(LocalPosition("song-1", 5))).state
        state = FollowerMachine.reduce(state, FollowerEvent.UserNavigated(LocalPosition("song-1", 6))).state
        assertEquals(6, state.local?.page)
        assertEquals(FollowMode.FOLLOWING, state.mode)
        assertTrue(state.navigatedWhileOffline)
    }

    @Test
    fun `reconnecting after the player kept reading offers to rejoin instead of jumping them`() {
        val state = FollowerMachine.reduceAll(
            connected,
            listOf(
                FollowerEvent.LeaderPosition(position(1, 4)),
                FollowerEvent.ConnectionLost,
                FollowerEvent.UserNavigated(LocalPosition("song-1", 5)),
            ),
        ).state

        val t = FollowerMachine.reduce(state, FollowerEvent.Connected("Anchor"))
        assertEquals(FollowMode.DETACHED, t.state.mode)
        assertTrue(t.effects.contains(FollowerEffect.OfferRejoin))
        // The page they were reading is still the page they are on.
        assertEquals(5, t.state.local?.page)
    }

    @Test
    fun `reconnecting after the player sat still resyncs silently`() {
        val state = FollowerMachine.reduceAll(
            connected,
            listOf(
                FollowerEvent.LeaderPosition(position(1, 4)),
                FollowerEvent.ConnectionLost,
            ),
        ).state

        val t = FollowerMachine.reduce(state, FollowerEvent.Connected("Anchor"))
        assertEquals(FollowMode.FOLLOWING, t.state.mode)
        assertFalse(t.effects.contains(FollowerEffect.OfferRejoin))

        // And the next position from the leader is applied without any prompting.
        val next = FollowerMachine.reduce(t.state, FollowerEvent.LeaderPosition(position(8, 11)))
        assertTrue(next.effects.any { it is FollowerEffect.ApplyPosition })
        assertEquals(11, next.state.local?.page)
    }

    @Test
    fun `leaving stops the device following for good`() {
        val t = FollowerMachine.reduce(connected, FollowerEvent.LeaveRequested)
        assertEquals(LinkState.OFFLINE, t.state.link)
        assertEquals(FollowMode.DETACHED, t.state.mode)
        assertFalse(t.state.canRejoin)
    }

    @Test
    fun `being behind the leader is reported for the indicator`() {
        val state = FollowerMachine.reduceAll(
            connected,
            listOf(
                FollowerEvent.UserNavigated(LocalPosition("song-1", 2)),
                FollowerEvent.LeaderPosition(position(4, 8)),
            ),
        ).state
        assertTrue(state.isBehindLeader)

        val caughtUp = FollowerMachine.reduce(state, FollowerEvent.RejoinRequested).state
        assertFalse(caughtUp.isBehindLeader)
    }
}

class LeaderSessionTest {

    private val base = LeaderState(sessionName = "Anchor", leaderName = "Jim")

    private fun hello(id: String, name: String) = Hello(deviceName = name, deviceId = id)

    @Test
    fun `followers join and leave`() {
        var state = LeaderSession.withFollower(base, hello("d1", "Pixel"), now = 0)
        state = LeaderSession.withFollower(state, hello("d2", "Tab"), now = 0)
        assertEquals(2, state.followers.size)

        state = LeaderSession.withoutFollower(state, "d1")
        assertEquals(listOf("d2"), state.followers.map { it.deviceId })
    }

    // A phone that drops and comes back is the same phone, not a second one in
    // the list.
    @Test
    fun `a reconnecting device replaces its own entry`() {
        var state = LeaderSession.withFollower(base, hello("d1", "Pixel"), now = 1_000)
        state = LeaderSession.withFollower(state, hello("d1", "Pixel"), now = 9_000)
        assertEquals(1, state.followers.size)
        assertEquals(1_000L, state.followers[0].connectedAt)
        assertEquals(9_000L, state.followers[0].lastSeenAt)
    }

    @Test
    fun `silent followers are evicted and talkative ones are not`() {
        var state = LeaderSession.withFollower(base, hello("d1", "Gone"), now = 0)
        state = LeaderSession.withFollower(state, hello("d2", "Here"), now = 0)
        state = LeaderSession.withStatus(
            state,
            FollowerStatus(seq = 1, deviceId = "d2", deviceName = "Here", following = true, page = 1),
            now = 25_000,
        )
        val pruned = LeaderSession.evictStale(state, now = 25_000)
        assertEquals(listOf("d2"), pruned.followers.map { it.deviceId })
    }

    @Test
    fun `the sequence number moves even when the page does not`() {
        val (afterFirst, first) = LeaderSession.announce(base, 0, "s", "T", null, page = 3)
        val (afterSecond, second) = LeaderSession.announce(afterFirst, 0, "s", "T", null, page = 3)
        assertEquals(first.page, second.page)
        assertTrue("seq must advance: ${first.seq} -> ${second.seq}", second.seq > first.seq)
        assertEquals(second.seq, afterSecond.seq)
    }

    @Test
    fun `the leader can see who is out of step and who is missing the song`() {
        var (state, _) = LeaderSession.announce(base, 0, "song-1", "T", null, page = 5)
        state = LeaderSession.withFollower(state, hello("d1", "A"), now = 0)
        state = LeaderSession.withFollower(state, hello("d2", "B"), now = 0)
        state = LeaderSession.withFollower(state, hello("d3", "C"), now = 0)

        state = LeaderSession.withStatus(
            state,
            FollowerStatus(1, "d1", "A", following = true, page = 5, songId = "song-1"),
            now = 0,
        )
        state = LeaderSession.withStatus(
            state,
            FollowerStatus(2, "d2", "B", following = true, page = 2, songId = "song-1"),
            now = 0,
        )
        state = LeaderSession.withStatus(
            state,
            FollowerStatus(3, "d3", "C", following = true, page = 0, songId = null, missingSong = true),
            now = 0,
        )

        assertEquals(listOf("d2", "d3"), state.outOfStep().map { it.deviceId }.sorted())
        assertEquals(listOf("d3"), state.missingTheSong().map { it.deviceId })
    }

    @Test
    fun `a detached follower is not counted as out of step`() {
        var (state, _) = LeaderSession.announce(base, 0, "song-1", "T", null, page = 5)
        state = LeaderSession.withFollower(state, hello("d1", "A"), now = 0)
        state = LeaderSession.withStatus(
            state,
            FollowerStatus(1, "d1", "A", following = false, page = 1, songId = "song-1"),
            now = 0,
        )
        assertTrue(state.outOfStep().isEmpty())
    }
}
