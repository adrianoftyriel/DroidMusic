package org.droidmusic.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.droidmusic.library.FileKind
import org.droidmusic.library.Setlist

/**
 * The wire protocol between a band leader and the players following them.
 *
 * Line-delimited JSON over a plain TCP socket on the local network. Three
 * properties matter more than efficiency here, and all three point at the same
 * simple design:
 *
 *  - **It has to survive a bad network.** A pub's wifi is a hostile environment.
 *    TCP gives ordering and delivery within a connection; the [seq] number
 *    handles the rest, which is what happens across a reconnection.
 *  - **It has to be debuggable at a soundcheck.** When a follower is not turning
 *    pages and there are ten minutes before doors, being able to read the wire
 *    is worth more than saving bytes.
 *  - **Nothing here is secret.** It is the page number of a song, on a local
 *    network, for the next three hours.
 *
 * See docs/PROTOCOL.md.
 */
@Serializable
sealed interface Message {
    val seq: Long
}

/** Sent by a follower as the first line after connecting. */
@Serializable
@SerialName("hello")
data class Hello(
    override val seq: Long = 0,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val deviceName: String,
    val deviceId: String,
    val appVersion: String? = null,
) : Message

/** The leader's answer. A refusal carries [accepted] false and a [reason]. */
@Serializable
@SerialName("welcome")
data class Welcome(
    override val seq: Long,
    val accepted: Boolean,
    val sessionName: String,
    val leaderName: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val reason: String? = null,
    /**
     * The port the leader serves charts on, or zero when it is not offering to.
     *
     * Defaulted rather than required, which is what makes this addition
     * invisible to an older build: a leader that predates chart sharing sends no
     * such field, it decodes as zero, and the follower simply never asks. The
     * protocol version is deliberately not bumped for the same reason - see
     * [PROTOCOL_VERSION].
     */
    val filePort: Int = 0,
) : Message

/**
 * Where the leader is now.
 *
 * Deliberately absolute rather than a "next page" instruction. A follower that
 * missed three messages while its wifi dropped must not end up three pages
 * behind, and an absolute position means any single message is enough to be
 * correct again. It is also idempotent, so a duplicate delivery is free.
 */
@Serializable
@SerialName("position")
data class Position(
    override val seq: Long,
    /** Index into the shared set list, or -1 when a single song is open. */
    val setlistIndex: Int,
    val songId: String?,
    val songTitle: String? = null,
    val contentHash: String? = null,
    val page: Int,
    val transposeSemitones: Int = 0,
    val capo: Int = 0,
) : Message

/** The leader pushing the whole set list, so followers can load it. */
@Serializable
@SerialName("setlist")
data class SetlistPush(
    override val seq: Long,
    val setlist: Setlist,
) : Message

/**
 * The leader asking everyone to check they can actually open tonight's charts.
 *
 * The whole set list travels with the request rather than an id, because an id
 * means nothing on another device - and matching an incoming id against a list
 * adopted five minutes ago is a way to check the wrong set. What the follower
 * checks is exactly what the leader sent.
 */
@Serializable
@SerialName("check")
data class CheckRequest(
    override val seq: Long,
    val setlist: Setlist,
) : Message

/** A device's answer to [CheckRequest]. */
@Serializable
@SerialName("report")
data class CheckReport(
    override val seq: Long,
    val report: BackstageReport,
) : Message

/**
 * Sent every few seconds even when nothing changes.
 *
 * A TCP socket whose far end has walked out of range does not report an error;
 * it just goes quiet, sometimes for minutes. Without a heartbeat a follower
 * cannot tell "the leader has not turned a page" from "the leader is gone", and
 * those need opposite responses.
 */
@Serializable
@SerialName("ping")
data class Ping(override val seq: Long, val sentAt: Long) : Message

@Serializable
@SerialName("pong")
data class Pong(override val seq: Long, val sentAt: Long, val deviceId: String) : Message

/** A follower telling the leader what it is doing. Advisory only. */
@Serializable
@SerialName("status")
data class FollowerStatus(
    override val seq: Long,
    val deviceId: String,
    val deviceName: String,
    val following: Boolean,
    val page: Int,
    val songId: String? = null,
    /** Set when the follower does not have the song the leader is on. */
    val missingSong: Boolean = false,
) : Message

/**
 * A follower asking for the charts it has not got.
 *
 * Sent after a set list arrives and the follower has failed to resolve some of
 * it. The leader is asked by content hash and title - the same two things a set
 * list entry already carries - because a song's id is meaningful only on the
 * device that indexed it.
 */
@Serializable
@SerialName("wanted")
data class ChartsWanted(
    override val seq: Long,
    val deviceId: String,
    val wanted: List<ChartWant>,
) : Message

@Serializable
data class ChartWant(val contentHash: String? = null, val title: String)

/**
 * The leader answering with what it can actually send.
 *
 * Sizes are included because the follower is about to be asked whether to accept
 * them, and "three charts" is a different question from "three charts, 60 MB" on
 * a phone at a venue.
 */
@Serializable
@SerialName("offered")
data class ChartsOffered(
    override val seq: Long,
    val offers: List<ChartOffer>,
) : Message

@Serializable
data class ChartOffer(
    /** Identifies the chart for the duration of the session. Also what the bytes are checked against. */
    val contentHash: String,
    val title: String,
    val displayName: String,
    val kind: FileKind,
    val sizeBytes: Long,
    /**
     * What a person needs to recognise the chart in a list of four hundred.
     *
     * Added for the aggregated library, where the same shape describes a chart
     * nobody has asked for yet - so a row has to be readable on its own rather
     * than being the answer to a question about a known song. Both default to
     * absent, so a build that never sent them still decodes.
     */
    val artist: String? = null,
    val keyText: String? = null,
)

/**
 * One request on the file channel: send me this chart, starting here.
 *
 * The offset is what makes a dropped transfer resumable. A forty-megabyte scan
 * over a pub's wifi will not always arrive first time, and starting again from
 * nothing each time is how it never arrives at all.
 */
@Serializable
data class ChartFetch(val contentHash: String, val offset: Long = 0)

/**
 * The leader's answer on the file channel, as one JSON line, followed by
 * [length] raw bytes when [ok].
 *
 * Raw rather than base64: a scan is large enough that a third again in size and
 * a decode pass on a phone are both worth avoiding, and this channel carries
 * nothing else that framing has to be shared with.
 */
@Serializable
data class ChartFetchHeader(
    val ok: Boolean,
    val contentHash: String = "",
    val displayName: String = "",
    val kind: FileKind = FileKind.UNKNOWN,
    /** The whole chart's size, not the size of this response. */
    val sizeBytes: Long = 0,
    val offset: Long = 0,
    /** How many bytes follow this line. */
    val length: Long = 0,
    val reason: String? = null,
)

/**
 * A device telling the session what it has.
 *
 * Sent by a follower after `welcome`, and again whenever its library changes.
 * Paged, with [final] on the last one: a library of two thousand charts is a
 * third of a megabyte of JSON, and putting that on the control socket as one
 * line would sit in front of every page turn behind it - on exactly the network
 * where that hurts.
 *
 * [filePort] is where this device will serve those charts from. Zero means it
 * cannot serve, and its charts are then listed for the band to see without being
 * offered for fetching - which is honest, and better than a row that fails when
 * tapped.
 *
 * Deliberately not the address: a phone knows its port and cannot reliably say
 * which of its addresses another device should use. The leader fills that in
 * from the socket the follower actually arrived on.
 */
@Serializable
@SerialName("catalogue")
data class CataloguePublish(
    override val seq: Long,
    val deviceId: String,
    val deviceName: String,
    val filePort: Int = 0,
    val charts: List<ChartOffer> = emptyList(),
    val final: Boolean = true,
) : Message

/**
 * The leader passing one device's catalogue on to everybody.
 *
 * One message per device per page rather than one aggregate: a device that
 * changes its library should cost one device's worth of traffic, not the whole
 * band's, and a receiver that accumulates per device can replace one without
 * disturbing the rest.
 *
 * The leader's own catalogue travels this way too, with an empty host, which the
 * receiver reads as "the leader you are already talking to".
 */
@Serializable
@SerialName("cataloguepeer")
data class CataloguePeer(
    override val seq: Long,
    val device: CatalogueDevice,
    val final: Boolean = true,
) : Message

/**
 * A device has left, so its charts are no longer on offer.
 *
 * Sent rather than inferred, because the alternative is every follower deciding
 * for itself when a peer has gone and a list that quietly keeps offering charts
 * from a phone that is in somebody's pocket in the car park.
 */
@Serializable
@SerialName("cataloguegone")
data class CatalogueGone(
    override val seq: Long,
    val deviceId: String,
) : Message

/** The leader closing the session cleanly, as opposed to vanishing. */
@Serializable
@SerialName("bye")
data class Goodbye(override val seq: Long, val reason: String? = null) : Message

/**
 * The version two peers must agree on.
 *
 * Still 1 with chart sharing and the backstage check added, on purpose. A bump
 * refuses every device that has not updated - see [Wire.isCompatible] - which
 * would mean a band could not play together halfway through updating, and the
 * cost of that is far higher than the cost of a follower quietly not offering to
 * fetch charts or not answering a readiness check. Every addition is built so
 * that it degrades instead: an unknown message (`check`, `report`, `wanted`,
 * `offered`, `catalogue`, `cataloguepeer`, `cataloguegone`) decodes to null and
 * is ignored, and the new [Welcome.filePort] reads as zero on a build that
 * never sent it. A build that does not publish a catalogue simply does not
 * appear in the aggregated library, and one that does not understand the
 * aggregate carries on with the set-list flow it already had. A device that cannot answer a
 * check is shown on the leader's screen as not having answered, never as ready.
 */
const val PROTOCOL_VERSION = 1

/** The mDNS service type the leader advertises and followers browse for. */
const val SERVICE_TYPE = "_droidmusic._tcp"

object Wire {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    /**
     * One message, one line. Newline-delimited framing is chosen over a length
     * prefix because it can be read by anything, including a person with netcat
     * at a soundcheck, and because no message here ever contains a raw newline -
     * the JSON encoder escapes them.
     */
    fun encode(message: Message): String = json.encodeToString(Message.serializer(), message) + "\n"

    /**
     * Decodes one line. Returns null for anything unreadable rather than
     * throwing: a malformed frame from a device running a different build should
     * cost one page turn, not the session.
     */
    fun decode(line: String): Message? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { json.decodeFromString(Message.serializer(), trimmed) }.getOrNull()
    }

    /** Whether a peer announcing [version] can be talked to. */
    fun isCompatible(version: Int): Boolean = version == PROTOCOL_VERSION
}
