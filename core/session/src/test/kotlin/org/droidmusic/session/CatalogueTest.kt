package org.droidmusic.session

import org.droidmusic.library.FileKind
import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.SongRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the band collectively has, worked out from what each device said.
 *
 * Every case here is a four-phones-in-a-room scenario reduced to a value: two
 * people with the same chart, two people with different scans of the same song,
 * somebody whose device cannot serve, a device that has left. All of them are a
 * nuisance to stage and trivial to write down.
 */
class CatalogueTest {

    private fun chart(
        hash: String,
        title: String,
        artist: String? = null,
        keyText: String? = null,
        size: Long = 1000,
    ) = ChartOffer(
        contentHash = hash,
        title = title,
        displayName = "$title.cho",
        kind = FileKind.CHORDPRO,
        sizeBytes = size,
        artist = artist,
        keyText = keyText,
    )

    private fun device(
        id: String,
        name: String,
        port: Int = 5000,
        vararg charts: ChartOffer,
    ) = CatalogueDevice(
        deviceId = id,
        deviceName = name,
        host = "192.168.1.$port",
        filePort = port,
        charts = charts.toList(),
    )

    @Test
    fun `a chart two people have is one row naming both`() {
        val aggregate = Catalogue.merge(
            listOf(
                device("d1", "Ann", 5001, chart("h1", "Wagon Wheel")),
                device("d2", "Bo", 5002, chart("h1", "Wagon Wheel")),
            ),
        )

        assertEquals(1, aggregate.size)
        assertEquals(listOf("Ann", "Bo"), aggregate[0].owners.map { it.deviceName })
    }

    @Test
    fun `two different copies of the same song stay two rows`() {
        // Deliberate. They are different files, and a player who asks for the
        // bass player's scan must not be handed the guitarist's ChordPro - the
        // repeats may not match, which is the surprise this screen prevents.
        val aggregate = Catalogue.merge(
            listOf(
                device("d1", "Ann", 5001, chart("h1", "Wagon Wheel")),
                device("d2", "Bo", 5002, chart("h2", "Wagon Wheel")),
            ),
        )

        assertEquals(2, aggregate.size)
        assertEquals(setOf("h1", "h2"), aggregate.map { it.contentHash }.toSet())
    }

    @Test
    fun `the best-described copy names the row`() {
        // One device scanned a folder with chart reading off and knows only a
        // filename; another imported the same file from a link and knows the
        // artist and the key. The row should say what somebody actually knows.
        val aggregate = Catalogue.merge(
            listOf(
                device("d1", "Ann", 5001, chart("h1", "wagon-wheel", artist = null)),
                device(
                    "d2",
                    "Bo",
                    5002,
                    chart("h1", "Wagon Wheel", artist = "Old Crow", keyText = "A"),
                ),
            ),
        )

        assertEquals(1, aggregate.size)
        assertEquals("Wagon Wheel", aggregate[0].title)
        assertEquals("Old Crow", aggregate[0].artist)
        assertEquals("A", aggregate[0].keyText)
    }

    @Test
    fun `a blank title falls back to the filename rather than an empty row`() {
        val aggregate = Catalogue.merge(
            listOf(device("d1", "Ann", 5001, chart("h1", ""))),
        )
        assertEquals(".cho", aggregate[0].displayName)
        assertTrue(aggregate[0].title.isNotBlank())
    }

    @Test
    fun `the size shown is the largest reported`() {
        // A provider that will not say reports zero, and a truncated copy
        // reports short. Neither should be what the row promises.
        val aggregate = Catalogue.merge(
            listOf(
                device("d1", "Ann", 5001, chart("h1", "Song", size = 0)),
                device("d2", "Bo", 5002, chart("h1", "Song", size = 240_000)),
            ),
        )
        assertEquals(240_000L, aggregate[0].sizeBytes)
    }

    @Test
    fun `rows are ordered by title, ignoring case, punctuation and a leading the`() {
        val aggregate = Catalogue.merge(
            listOf(
                device(
                    "d1",
                    "Ann",
                    5001,
                    chart("h1", "Zebra"),
                    chart("h2", "The Ballad"),
                    chart("h3", "apple"),
                ),
            ),
        )
        assertEquals(listOf("apple", "The Ballad", "Zebra"), aggregate.map { it.title })
    }

    @Test
    fun `a chart with no hash is left out rather than listed unfetchable`() {
        val aggregate = Catalogue.merge(
            listOf(device("d1", "Ann", 5001, chart("", "Nameless"), chart("h1", "Real"))),
        )
        assertEquals(listOf("Real"), aggregate.map { it.title })
    }

    @Test
    fun `a device that cannot serve still has the chart but cannot supply it`() {
        val aggregate = Catalogue.merge(
            listOf(
                CatalogueDevice(
                    deviceId = "d1",
                    deviceName = "Ann",
                    host = "192.168.1.5",
                    filePort = 0,
                    charts = listOf(chart("h1", "Song")),
                ),
            ),
        )

        assertEquals(1, aggregate.size)
        assertFalse(aggregate[0].obtainable)
        assertNull(Catalogue.sourceFor(aggregate[0], thisDeviceId = "me"))
    }

    @Test
    fun `a chart is never fetched from this device itself`() {
        val aggregate = Catalogue.merge(
            listOf(
                device("me", "This phone", 5001, chart("h1", "Song")),
                device("d2", "Bo", 5002, chart("h1", "Song")),
            ),
        )

        val source = Catalogue.sourceFor(aggregate[0], thisDeviceId = "me")
        assertEquals("d2", source?.deviceId)
    }

    @Test
    fun `a chart only this device has has nowhere to be fetched from`() {
        val aggregate = Catalogue.merge(
            listOf(device("me", "This phone", 5001, chart("h1", "Song"))),
        )
        // Falls back to the only owner rather than returning null, because the
        // caller asks this before it knows whether it already has the file -
        // and a chart you hold needs opening, not fetching.
        assertEquals("me", Catalogue.sourceFor(aggregate[0], thisDeviceId = "me")?.deviceId)
    }

    @Test
    fun `missing skips a song this device already has under a different copy`() {
        val aggregate = Catalogue.merge(
            listOf(device("d2", "Bo", 5002, chart("h1", "Wagon Wheel"), chart("h2", "Copperhead"))),
        )

        // A different file of the same song: different hash, same title. That
        // is not missing, it is a duplicate, and offering to fetch it would be
        // offering the band a second copy of something already here.
        val local = LibraryIndex(
            songs = listOf(
                SongRef(
                    id = "local-1",
                    sourceId = "s",
                    uri = "file:///wagon.cho",
                    displayName = "wagon.cho",
                    kind = FileKind.CHORDPRO,
                    contentHash = "different-bytes",
                    title = "Wagon Wheel",
                ),
            ),
        )

        assertEquals(listOf("Copperhead"), Catalogue.missing(aggregate, local).map { it.title })
    }

    @Test
    fun `an empty session aggregates to nothing rather than throwing`() {
        assertTrue(Catalogue.merge(emptyList()).isEmpty())
        assertTrue(Catalogue.merge(listOf(device("d1", "Ann", 5001))).isEmpty())
    }

    @Test
    fun `uniqueTo counts what would be lost if one device walked out`() {
        val aggregate = Catalogue.merge(
            listOf(
                device("d1", "Ann", 5001, chart("h1", "Shared"), chart("h2", "Only Ann")),
                device("d2", "Bo", 5002, chart("h1", "Shared")),
            ),
        )

        assertEquals(1, Catalogue.uniqueTo(aggregate, "d1"))
        assertEquals(0, Catalogue.uniqueTo(aggregate, "d2"))
    }
}
