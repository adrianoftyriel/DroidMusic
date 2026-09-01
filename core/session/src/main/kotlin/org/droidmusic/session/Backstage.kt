package org.droidmusic.session

import kotlinx.serialization.Serializable

/**
 * What one chart looks like on one device, a few minutes before the downbeat.
 *
 * The distinction that matters on stage is not "is this file present" but "will
 * this file open when I tap it", and those are different questions. A chart in a
 * cloud folder is present in the library index and may still fail to open in a
 * basement with no signal; a scan whose provider has revoked the app's grant
 * looks perfect in a list and is a blank screen at the downbeat. So the check
 * opens the file rather than looking it up.
 */
@Serializable
enum class ChartState {
    /** In the library, and the file opened. */
    READY,

    /** No copy of this chart on this device at all. */
    MISSING,

    /** There is a copy, and it would not open. */
    UNREADABLE,

    /**
     * A copy, but not the same bytes the leader has.
     *
     * Not an error. Two people having different scans of the same song, or a PDF
     * against a ChordPro of it, is ordinary - but it is worth saying out loud
     * before somebody discovers mid-song that their version has a different
     * repeat.
     */
    DIFFERENT,
}

/** One song's verdict, as it travels between devices. */
@Serializable
data class ChartCheck(
    /** Position in the set list, so the leader can name the song without guessing. */
    val index: Int,
    val title: String,
    val state: ChartState,
    /** One line for a person: "Not in this library", "capo file would not open". */
    val detail: String? = null,
) {
    val isProblem: Boolean get() = state != ChartState.READY
}

/**
 * One device's answer to "can you play tonight's set".
 *
 * Sent as a whole rather than as a running commentary. A player walking in late
 * produces one report, the leader's screen gains one row, and there is no
 * partial state anybody has to interpret.
 */
@Serializable
data class BackstageReport(
    val deviceId: String,
    val deviceName: String,
    val setlistName: String = "",
    val checks: List<ChartCheck> = emptyList(),
    val checkedAt: Long = 0L,
) {
    val problems: List<ChartCheck> get() = checks.filter { it.isProblem }

    /** True only when something was actually checked and all of it was fine. */
    val allReady: Boolean get() = checks.isNotEmpty() && problems.isEmpty()

    /** Problems that stop a song being played at all, as opposed to warnings. */
    val blocking: List<ChartCheck> get() = checks.filter {
        it.state == ChartState.MISSING || it.state == ChartState.UNREADABLE
    }
}

/** One song, and every device that has trouble with it. */
data class SongTrouble(
    val index: Int,
    val title: String,
    val state: ChartState,
    val deviceNames: List<String>,
)

/**
 * The reading of a set of reports, kept as pure functions so the wording that a
 * band relies on at five to nine can be tested.
 */
object Backstage {

    /** One line for a single device's report. */
    fun summarise(report: BackstageReport): String {
        if (report.checks.isEmpty()) return "Nothing checked yet"
        val missing = report.checks.count { it.state == ChartState.MISSING }
        val unreadable = report.checks.count { it.state == ChartState.UNREADABLE }
        val different = report.checks.count { it.state == ChartState.DIFFERENT }
        if (missing == 0 && unreadable == 0 && different == 0) {
            return "All ${report.checks.size} charts are here"
        }
        val parts = buildList {
            if (missing > 0) add("$missing missing")
            if (unreadable > 0) add("$unreadable will not open")
            if (different > 0) add("$different a different copy")
        }
        return parts.joinToString(", ") + " of ${report.checks.size}"
    }

    /**
     * One line for the leader, across everyone who has answered.
     *
     * [expected] is how many devices are in the session, so silence is reported
     * as silence rather than as agreement - the most dangerous possible reading
     * of a device that has not answered is that it is fine.
     */
    fun bandSummary(reports: List<BackstageReport>, expected: Int): String {
        val answered = reports.size
        val waiting = (expected - answered).coerceAtLeast(0)
        val struggling = reports.count { it.problems.isNotEmpty() }
        return when {
            expected == 0 -> "Nobody else is in this session"
            answered == 0 -> "Waiting for $waiting to check in"
            struggling == 0 && waiting == 0 -> "Everyone has every chart"
            struggling == 0 -> "$answered ready, waiting for $waiting"
            waiting == 0 -> "$struggling of $answered have something missing"
            else -> "$struggling of $answered have something missing, waiting for $waiting"
        }
    }

    /**
     * The songs somebody cannot play, worst first, each naming who.
     *
     * This is the leader's actual question. "Three devices have problems" is not
     * something anyone can act on; "nobody but you has Wagon Wheel" is - it means
     * send the file now or drop the song.
     */
    fun trouble(reports: List<BackstageReport>): List<SongTrouble> =
        reports
            .flatMap { report -> report.problems.map { it to report.deviceName } }
            .groupBy { (check, _) -> Triple(check.index, check.title, check.state) }
            .map { (key, pairs) ->
                SongTrouble(
                    index = key.first,
                    title = key.second,
                    state = key.third,
                    deviceNames = pairs.map { it.second }.distinct().sorted(),
                )
            }
            // Worst first: a song nobody can open matters more than one somebody
            // has a different copy of, and within that, the one affecting the
            // most people comes first.
            .sortedWith(
                compareBy(
                    { severity(it.state) },
                    { -it.deviceNames.size },
                    { it.index },
                ),
            )

    private fun severity(state: ChartState): Int = when (state) {
        ChartState.MISSING -> 0
        ChartState.UNREADABLE -> 1
        ChartState.DIFFERENT -> 2
        ChartState.READY -> 3
    }
}
