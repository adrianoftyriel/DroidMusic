package org.droidmusic.app.diag

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What part of the app a line came from, so a log can be read at a glance. */
enum class Area { SESSION, LEADER, FOLLOWER, CHART, SETLIST, LIBRARY }

data class LogEntry(val at: Long, val area: Area, val message: String)

/**
 * A few hundred lines of what just happened, kept in memory so a player can send
 * them to somebody who can read them.
 *
 * The problems this exists for are the ones that cannot be reproduced at a desk:
 * a follower that stopped following twenty minutes into a set, a chart that
 * appeared on three phones out of four, an mDNS lookup that found nothing in one
 * particular room. All of them happen once, on somebody else's device, on a
 * network nobody controls - and all of them leave no trace at all after the fact
 * beyond somebody's recollection of the order things happened in.
 *
 * Deliberately in memory and nowhere else. A log file on disk would need a
 * retention policy, would grow while nobody was reading it, and would sit in the
 * app's storage holding the names of somebody's band and the titles of their
 * charts for as long as the app was installed. This one holds the last
 * [CAPACITY] lines, is lost when the app is killed, and leaves the device only
 * when somebody deliberately shares it.
 *
 * Every call site is a fact, not a narrative: what happened, to whom, with the
 * numbers. Whoever reads it is looking for the moment the thing went wrong, and
 * a line that says "handling reconnect" tells them nothing.
 */
object Diagnostics {

    /**
     * Enough to cover a set, small enough to be nothing.
     *
     * Four hundred lines is roughly an hour of a session at a heartbeat every
     * five seconds, given that heartbeats are not logged - and about 40KB, which
     * is a text file anybody can mail.
     */
    const val CAPACITY = 400

    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>(CAPACITY)

    /** True once anything has been recorded, so the UI can say "nothing yet". */
    val isEmpty: Boolean get() = synchronized(lock) { entries.isEmpty() }

    fun log(area: Area, message: String, at: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(LogEntry(at, area, message))
        }
    }

    fun entries(): List<LogEntry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /**
     * The whole log as text, with a header saying what device wrote it.
     *
     * [about] is a list of label-and-value pairs rather than a fixed set,
     * because what is worth knowing differs by problem and the caller is the one
     * that knows: the app version and the device name always, the session role
     * and the library size when there is a session to describe.
     */
    fun render(about: List<Pair<String, String>> = emptyList(), zone: ZoneId = ZoneId.systemDefault()): String {
        val lines = entries()
        return buildString {
            appendLine("DroidMusic diagnostic log")
            for ((label, value) in about) appendLine("$label: $value")
            appendLine("Lines: ${lines.size}${if (lines.size >= CAPACITY) " (oldest dropped)" else ""}")
            appendLine()
            appendLine(
                "This is what the app did, not what is in your files: no chart is read into " +
                    "it, and it goes nowhere unless you send it. It names your device, the " +
                    "other devices in the session, the session itself, the songs by title, " +
                    "and the addresses those devices had on the local network.",
            )
            appendLine()
            if (lines.isEmpty()) {
                appendLine("(nothing recorded)")
                return@buildString
            }
            for (entry in lines) {
                appendLine("${stamp(entry.at, zone)}  ${entry.area.name.padEnd(8)} ${entry.message}")
            }
        }
    }

    private fun stamp(at: Long, zone: ZoneId): String =
        TIME.format(Instant.ofEpochMilli(at).atZone(zone))

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /**
     * A device id, shortened for reading.
     *
     * The full UUID is 36 characters of noise that makes every line harder to
     * scan, and the first six are plenty to tell two phones apart in one log.
     */
    fun short(deviceId: String?): String = when {
        deviceId.isNullOrBlank() -> "?"
        deviceId.length <= 6 -> deviceId
        else -> deviceId.take(6)
    }
}
