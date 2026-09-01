package org.droidmusic.session

import org.droidmusic.library.Setlist
import org.droidmusic.library.SetlistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackstageReportTest {

    private fun check(index: Int, title: String, state: ChartState) =
        ChartCheck(index = index, title = title, state = state)

    private fun report(name: String, vararg checks: ChartCheck) = BackstageReport(
        deviceId = name.lowercase(),
        deviceName = name,
        setlistName = "Friday at the Anchor",
        checks = checks.toList(),
        checkedAt = 1_000L,
    )

    @Test
    fun `a report with nothing wrong is ready and one with anything wrong is not`() {
        val fine = report("Pixel", check(0, "A", ChartState.READY), check(1, "B", ChartState.READY))
        assertTrue(fine.allReady)
        assertTrue(fine.problems.isEmpty())

        val short = report("Tab", check(0, "A", ChartState.READY), check(1, "B", ChartState.MISSING))
        assertFalse(short.allReady)
        assertEquals(listOf("B"), short.problems.map { it.title })
    }

    // A device that checked nothing has not said it is fine. This is the whole
    // point of the screen, so it gets its own test.
    @Test
    fun `an empty report is not a ready report`() {
        assertFalse(report("Silent").allReady)
        assertEquals("Nothing checked yet", Backstage.summarise(report("Silent")))
    }

    // A different copy of a chart can still be played from; a missing one cannot.
    @Test
    fun `a different copy is a warning and not a blocker`() {
        val mixed = report(
            "Pixel",
            check(0, "A", ChartState.DIFFERENT),
            check(1, "B", ChartState.MISSING),
            check(2, "C", ChartState.UNREADABLE),
        )
        assertEquals(listOf("B", "C"), mixed.blocking.map { it.title })
        assertEquals(3, mixed.problems.size)
    }

    @Test
    fun `a summary counts each kind of trouble by name`() {
        assertEquals(
            "All 2 charts are here",
            Backstage.summarise(
                report("Pixel", check(0, "A", ChartState.READY), check(1, "B", ChartState.READY)),
            ),
        )
        assertEquals(
            "1 missing, 1 will not open of 3",
            Backstage.summarise(
                report(
                    "Tab",
                    check(0, "A", ChartState.READY),
                    check(1, "B", ChartState.MISSING),
                    check(2, "C", ChartState.UNREADABLE),
                ),
            ),
        )
    }
}

class BandSummaryTest {

    private fun report(name: String, vararg states: ChartState) = BackstageReport(
        deviceId = name.lowercase(),
        deviceName = name,
        checks = states.mapIndexed { i, state -> ChartCheck(i, "Song $i", state) },
    )

    // The dangerous reading of a device that has not answered is that it is
    // fine, so silence is always reported as silence.
    @Test
    fun `devices that have not answered are counted as waiting, never as ready`() {
        assertEquals(
            "Waiting for 3 to check in",
            Backstage.bandSummary(emptyList(), expected = 3),
        )
        assertEquals(
            "1 ready, waiting for 2",
            Backstage.bandSummary(listOf(report("Pixel", ChartState.READY)), expected = 3),
        )
    }

    @Test
    fun `everyone answering with everything is said plainly`() {
        val reports = listOf(
            report("Pixel", ChartState.READY, ChartState.READY),
            report("Tab", ChartState.READY, ChartState.READY),
        )
        assertEquals("Everyone has every chart", Backstage.bandSummary(reports, expected = 2))
    }

    @Test
    fun `trouble is counted across devices`() {
        val reports = listOf(
            report("Pixel", ChartState.READY, ChartState.MISSING),
            report("Tab", ChartState.READY, ChartState.READY),
            report("Fold", ChartState.UNREADABLE, ChartState.READY),
        )
        assertEquals(
            "2 of 3 have something missing",
            Backstage.bandSummary(reports, expected = 3),
        )
    }

    @Test
    fun `a session of one is not a band`() {
        assertEquals("Nobody else is in this session", Backstage.bandSummary(emptyList(), 0))
    }
}

class SongTroubleTest {

    private fun report(name: String, vararg checks: ChartCheck) =
        BackstageReport(deviceId = name.lowercase(), deviceName = name, checks = checks.toList())

    // The leader's actual question is not "how many devices have problems" but
    // "which song do I have to send to whom".
    @Test
    fun `trouble names the song and everyone who has it`() {
        val reports = listOf(
            report(
                "Pixel",
                ChartCheck(0, "Wagon Wheel", ChartState.READY),
                ChartCheck(1, "Copperhead Road", ChartState.MISSING),
            ),
            report(
                "Tab",
                ChartCheck(0, "Wagon Wheel", ChartState.READY),
                ChartCheck(1, "Copperhead Road", ChartState.MISSING),
            ),
            report("Fold", ChartCheck(0, "Wagon Wheel", ChartState.READY)),
        )

        val trouble = Backstage.trouble(reports)
        assertEquals(1, trouble.size)
        assertEquals("Copperhead Road", trouble[0].title)
        assertEquals(ChartState.MISSING, trouble[0].state)
        assertEquals(listOf("Pixel", "Tab"), trouble[0].deviceNames)
    }

    @Test
    fun `the worst and most widespread trouble is listed first`() {
        val reports = listOf(
            report(
                "Pixel",
                ChartCheck(0, "One", ChartState.DIFFERENT),
                ChartCheck(1, "Two", ChartState.UNREADABLE),
                ChartCheck(2, "Three", ChartState.MISSING),
            ),
            report("Tab", ChartCheck(2, "Three", ChartState.MISSING)),
        )
        assertEquals(
            listOf("Three", "Two", "One"),
            Backstage.trouble(reports).map { it.title },
        )
    }

    @Test
    fun `a band with nothing wrong produces no trouble at all`() {
        val reports = listOf(report("Pixel", ChartCheck(0, "One", ChartState.READY)))
        assertTrue(Backstage.trouble(reports).isEmpty())
    }
}

class BackstageWireTest {

    private val setlist = Setlist(
        id = "sl",
        name = "Friday at the Anchor",
        entries = listOf(SetlistEntry(songId = "a", title = "Wagon Wheel")),
    )

    @Test
    fun `a check and its report round trip`() {
        val request: Message = CheckRequest(seq = 9, setlist = setlist)
        assertEquals(request, Wire.decode(Wire.encode(request)))

        val report: Message = CheckReport(
            seq = 10,
            report = BackstageReport(
                deviceId = "d1",
                deviceName = "Jim's Pixel",
                setlistName = setlist.name,
                checks = listOf(
                    ChartCheck(0, "Wagon Wheel", ChartState.MISSING, "Not in this library"),
                ),
                checkedAt = 1_724_946_000_000L,
            ),
        )
        assertEquals(report, Wire.decode(Wire.encode(report)))
    }

    // Both messages are additive, which only works if a build that has never
    // heard of them ignores the line rather than dropping the connection. That
    // is what `decode` returning null means at the call site.
    @Test
    fun `an older build ignores a check it cannot read`() {
        assertEquals(
            null,
            Wire.decode("{\"type\":\"somethingFromTheFuture\",\"seq\":1,\"setlist\":{}}"),
        )
        assertNotNull(Wire.decode(Wire.encode(CheckRequest(seq = 1, setlist = setlist))))
    }

    @Test
    fun `a report is one line however the songs are named`() {
        val message: Message = CheckReport(
            seq = 1,
            report = BackstageReport(
                deviceId = "d1",
                deviceName = "Jim's\nPixel",
                checks = listOf(ChartCheck(0, "Title\r\nbroken", ChartState.UNREADABLE)),
            ),
        )
        val encoded = Wire.encode(message)
        assertEquals(1, encoded.count { it == '\n' })
        assertEquals(message, Wire.decode(encoded))
    }
}

class LeaderReportTest {

    private val base = LeaderState(sessionName = "Anchor", leaderName = "Jim")

    private fun report(id: String, state: ChartState) = BackstageReport(
        deviceId = id,
        deviceName = id,
        checks = listOf(ChartCheck(0, "One", state)),
    )

    @Test
    fun `a device that checks again replaces its own answer`() {
        var state = LeaderSession.withReport(base, report("d1", ChartState.MISSING))
        state = LeaderSession.withReport(state, report("d1", ChartState.READY))
        assertEquals(1, state.reports.size)
        assertTrue(state.reports.single().allReady)
    }

    @Test
    fun `a new check starts from no answers`() {
        val state = LeaderSession.withReport(base, report("d1", ChartState.READY))
        assertTrue(LeaderSession.clearReports(state).reports.isEmpty())
    }

    // A player who packed up and went home must not still be counted as ready.
    @Test
    fun `a report leaves with the device that sent it`() {
        var state = LeaderSession.withFollower(
            base,
            Hello(deviceName = "Gone", deviceId = "d1"),
            now = 0,
        )
        state = LeaderSession.withFollower(state, Hello(deviceName = "Here", deviceId = "d2"), now = 0)
        state = LeaderSession.withReport(state, report("d1", ChartState.READY))
        state = LeaderSession.withReport(state, report("d2", ChartState.READY))

        state = LeaderSession.withoutFollower(state, "d1")
        assertEquals(listOf("d2"), state.reports.map { it.deviceId })

        // And the same for one that goes quiet rather than saying goodbye.
        state = LeaderSession.evictStale(state, now = LeaderSession.FOLLOWER_TIMEOUT_MS + 1)
        assertTrue(state.followers.isEmpty())
        assertTrue(state.reports.isEmpty())
    }

    @Test
    fun `the leader can see who has not answered`() {
        var state = LeaderSession.withFollower(base, Hello(deviceName = "A", deviceId = "d1"), now = 0)
        state = LeaderSession.withFollower(state, Hello(deviceName = "B", deviceId = "d2"), now = 0)
        state = LeaderSession.withReport(state, report("d1", ChartState.READY))
        assertEquals(listOf("d2"), state.awaitingReport().map { it.deviceId })
    }
}
