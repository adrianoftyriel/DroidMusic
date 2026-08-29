package org.droidmusic.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

/** The leader closing the session cleanly, as opposed to vanishing. */
@Serializable
@SerialName("bye")
data class Goodbye(override val seq: Long, val reason: String? = null) : Message

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
