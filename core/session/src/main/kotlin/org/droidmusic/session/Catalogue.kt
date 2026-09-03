package org.droidmusic.session

import kotlinx.serialization.Serializable
import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.normaliseForMatching

/**
 * One device in the session, and everything it can hand over.
 *
 * [host] and [filePort] are how a chart actually gets fetched, and neither is
 * something the device itself can usefully say. A phone knows its port but its
 * address on this network is whatever the leader saw when it connected, so the
 * leader fills in [host] from the socket before passing a catalogue on. A
 * follower announcing its own address would be announcing whichever interface
 * it guessed at, which on a phone holding wifi and mobile data at once is a coin
 * toss.
 *
 * An empty [host] means "the leader you are already talking to" - the leader
 * cannot know which of its addresses a given follower reached it on, and the
 * follower already has one that works.
 */
@Serializable
data class CatalogueDevice(
    val deviceId: String,
    val deviceName: String,
    val host: String = "",
    val filePort: Int = 0,
    val charts: List<ChartOffer> = emptyList(),
) {
    /** False for a device that cannot serve, so its charts are listed and not offered. */
    val canServe: Boolean get() = filePort > 0
}

/** Who has a chart, for the line under it. */
data class ChartOwner(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val filePort: Int,
) {
    val canServe: Boolean get() = filePort > 0
}

/**
 * One chart as the band collectively has it.
 *
 * [owners] is everybody who can supply it, which is usually one device and
 * occasionally all of them. Ordered, so the same chart is always fetched from
 * the same place until that device leaves.
 */
data class AggregatedChart(
    val contentHash: String,
    val title: String,
    val displayName: String,
    val artist: String?,
    val keyText: String?,
    val sizeBytes: Long,
    val owners: List<ChartOwner>,
) {
    /** True when at least one device holding it is in a position to send it. */
    val obtainable: Boolean get() = owners.any { it.canServe }
}

/**
 * Merging what every device says it has into one library.
 *
 * A pure function of the announcements, kept here rather than in the screen for
 * the usual reason: the interesting parts are all decisions - which title wins
 * when two devices disagree, what happens to a chart two people have, whether a
 * device that cannot serve still counts as having it - and every one of them is
 * cheap to test and a nuisance to reproduce with four phones in a room.
 */
object Catalogue {

    /**
     * The union, by content hash.
     *
     * Hash rather than title, because that is what a fetch asks for and what
     * the bytes are checked against. Two different scans of the same song are
     * two entries here, and that is correct: they are different files, and a
     * player asking for one should not be handed the other.
     *
     * The consequence is worth stating: the same song can appear twice, once
     * per copy the band has. Collapsing them by title would hide that the bass
     * player has a different transcription, which is exactly the surprise this
     * screen exists to prevent.
     */
    fun merge(devices: List<CatalogueDevice>): List<AggregatedChart> {
        val byHash = LinkedHashMap<String, MutableList<Pair<CatalogueDevice, ChartOffer>>>()
        for (device in devices) {
            for (chart in device.charts) {
                if (chart.contentHash.isBlank()) continue
                byHash.getOrPut(chart.contentHash) { mutableListOf() } += device to chart
            }
        }

        return byHash.map { (hash, holders) ->
            // The best-described copy wins the row. Devices index the same file
            // differently - one scanned a folder with chart reading off, another
            // imported it from a link with the artist in the page - and the row
            // should show whatever somebody actually knows.
            val described = holders.maxByOrNull { (_, chart) -> describedness(chart) }!!.second

            AggregatedChart(
                contentHash = hash,
                title = titleFor(described),
                displayName = described.displayName,
                artist = holders.firstNotNullOfOrNull { it.second.artist?.takeIf(String::isNotBlank) },
                keyText = holders.firstNotNullOfOrNull { it.second.keyText?.takeIf(String::isNotBlank) },
                // The largest, because a truncated copy is the one that reports
                // short and a provider that would not say reports zero.
                sizeBytes = holders.maxOf { it.second.sizeBytes },
                owners = holders.map { (device, _) ->
                    ChartOwner(
                        deviceId = device.deviceId,
                        deviceName = device.deviceName,
                        host = device.host,
                        filePort = device.filePort,
                    )
                }.distinctBy { it.deviceId },
            )
        }.sortedWith(
            compareBy(
                { it.title.normaliseForMatching() },
                { it.contentHash },
            ),
        )
    }

    /**
     * A row is never blank.
     *
     * The title if there is one, then the filename without its extension, then
     * the filename itself - which covers the file called `.cho` and nothing
     * else, whose stem is the empty string. A row with no text in it is
     * untappable and unreadable, and one of these three always has characters.
     */
    private fun titleFor(chart: ChartOffer): String {
        chart.title.takeIf { it.isNotBlank() }?.let { return it }
        chart.displayName.substringBeforeLast('.').takeIf { it.isNotBlank() }?.let { return it }
        return chart.displayName.takeIf { it.isNotBlank() } ?: "Untitled chart"
    }

    /** How much a row would say if this copy were the one describing it. */
    private fun describedness(chart: ChartOffer): Int =
        (if (chart.title.isNotBlank()) 2 else 0) +
            (if (!chart.artist.isNullOrBlank()) 1 else 0) +
            (if (!chart.keyText.isNullOrBlank()) 1 else 0)

    /**
     * The charts in the aggregate this device cannot already open.
     *
     * Matched the way a set list is - hash first, then normalised title - so a
     * chart somebody else has as a PDF and this device has as a ChordPro of the
     * same song does not show up as missing. It is not missing; it is a
     * different file of a song already here, and offering to fetch it would be
     * offering a duplicate.
     */
    fun missing(aggregate: List<AggregatedChart>, library: LibraryIndex): List<AggregatedChart> =
        aggregate.filter { library.match(it.contentHash, it.title) == null }

    /**
     * The band's copy of one set list entry, if anybody has one.
     *
     * The mirror of [missing]: that asks what of the band's library this device
     * lacks, and this asks who in the band has a particular song. It is what
     * lets a row in tonight's running order offer to fetch the chart rather than
     * only naming it.
     *
     * Hash first, then normalised title - the same order everything else in the
     * app resolves a chart in, so a row offers to fetch exactly what a set list
     * import would have matched. A copy somebody can actually send is preferred
     * to one that is merely listed, because the point of the row is the tap.
     */
    fun offering(
        aggregate: List<AggregatedChart>,
        contentHash: String?,
        title: String,
    ): AggregatedChart? {
        if (!contentHash.isNullOrBlank()) {
            best(aggregate) { it.contentHash == contentHash }?.let { return it }
        }
        val wanted = title.normaliseForMatching()
        if (wanted.isEmpty()) return null
        return best(aggregate) { it.title.normaliseForMatching() == wanted }
    }

    private fun best(
        aggregate: List<AggregatedChart>,
        matches: (AggregatedChart) -> Boolean,
    ): AggregatedChart? =
        aggregate.firstOrNull { matches(it) && it.obtainable }
            ?: aggregate.firstOrNull(matches)

    /**
     * Where to fetch a chart from, preferring anywhere but this device.
     *
     * This device can appear among the owners - it announced its own catalogue
     * like everybody else - and fetching a file from yourself is a round trip to
     * nowhere. Beyond that the first owner that can serve, so a chart is always
     * pulled from the same device rather than a different one each attempt.
     */
    fun sourceFor(chart: AggregatedChart, thisDeviceId: String): ChartOwner? =
        chart.owners.firstOrNull { it.canServe && it.deviceId != thisDeviceId }
            ?: chart.owners.firstOrNull { it.canServe }

    /** How many charts each device is contributing that nobody else has. */
    fun uniqueTo(aggregate: List<AggregatedChart>, deviceId: String): Int =
        aggregate.count { chart ->
            chart.owners.size == 1 && chart.owners.first().deviceId == deviceId
        }
}
