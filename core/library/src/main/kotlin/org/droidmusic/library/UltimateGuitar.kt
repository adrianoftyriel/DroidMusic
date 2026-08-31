package org.droidmusic.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.droidmusic.music.ChordsOverLyricsParser
import org.droidmusic.music.Key
import org.droidmusic.music.Line
import org.droidmusic.music.SectionKind
import org.droidmusic.music.Song
import org.droidmusic.music.SongParser
import org.droidmusic.music.SongWriter

/**
 * What was recovered from an Ultimate Guitar page, before it becomes a chart.
 *
 * [body] is the chart as Ultimate Guitar stores it: chords over lyrics, aligned
 * by column, with the site's own `[ch]` and `[tab]` markers still in it. Nothing
 * here is a [Song] yet - that is [UltimateGuitar.toChordPro]'s job.
 */
data class UltimateGuitarChart(
    val title: String? = null,
    val artist: String? = null,
    val keyText: String? = null,
    val capo: Int = 0,
    val tuning: String? = null,
    val sourceUrl: String? = null,
    val body: String = "",
)

/**
 * Turning a shared Ultimate Guitar link into a ChordPro file.
 *
 * **Why the page and not an API.** Ultimate Guitar has no public API to ask. What
 * it does have is a page that carries its own chart, verbatim, in a JSON blob
 * the site's own front end reads to draw it - so the chart is *data* on that
 * page rather than something to be scraped back out of rendered HTML. That blob
 * is what this reads, which is why the result is the chart as its author typed
 * it rather than a best guess at what the screen looked like.
 *
 * **Why there is no HTML parser in the APK.** The whole job is: find one
 * attribute, decode its entities, and hand the JSON to the serialization library
 * that is already here. An HTML parser would be a dependency and several
 * thousand lines to do less reliably what a page-specific reader does in a
 * hundred, because the thing being looked for is a known attribute on a known
 * element and not arbitrary markup.
 *
 * **Why the conversion reuses the ordinary chart parser.** Once the site's
 * markers come off, what is left *is* the chords-over-lyrics format the app
 * already reads - the alignment is the notation, and Ultimate Guitar's own
 * renderer strips the same markers before laying it out in a monospaced block.
 * So this is a decoder, not a second chart parser: it produces text, and
 * [ChordsOverLyricsParser] does what it always does with text. The same
 * reasoning as [DocxText], and for the same reason - one code path stays tested.
 *
 * **What is stored is a file, not a link.** The import writes a real ChordPro
 * file into the library. A chart that needed the network to open would be
 * useless in the place charts are actually read, which is a stage with bad wifi.
 */
object UltimateGuitar {

    /** The site, and any of its subdomains - charts live on `tabs.`. */
    private const val HOST = "ultimate-guitar.com"

    /**
     * Finds the chart link in shared text.
     *
     * Share sheets rarely send a bare URL. A browser sends the page title and
     * the address together, and some send a sentence around it, so the link is
     * looked for *inside* the text rather than the text being required to be
     * one. Trailing punctuation is trimmed, because a link at the end of a
     * sentence arrives with the full stop attached.
     */
    fun chartUrlIn(text: String): String? = URL_IN_TEXT
        .findAll(text)
        .map { it.value.trimEnd('.', ',', ')', ']', '"', '\'', '>') }
        .firstOrNull { isChartUrl(it) }

    /**
     * Whether a URL is an Ultimate Guitar chart page.
     *
     * The host is matched as a suffix on a dot boundary rather than with
     * `contains`, so that a lookalike domain with the site's name buried in it -
     * `ultimate-guitar.com.example.net` - is not accepted as the site itself.
     */
    fun isChartUrl(url: String): Boolean {
        val withoutScheme = url.substringAfter("://", url)
        val host = withoutScheme.substringBefore('/').substringBefore('?').lowercase()
        val path = withoutScheme.substringAfter('/', "")
        val onSite = host == HOST || host.endsWith(".$HOST")
        return onSite && path.startsWith("tab/")
    }

    /**
     * Pulls the chart out of a page.
     *
     * Returns null when the page carries no chart to find, which is not always a
     * failure to report as one: Ultimate Guitar's "official" and Pro tabs are
     * interactive players with no text behind them, and there is no chart on
     * such a page for anybody to import.
     */
    fun parsePage(html: String, sourceUrl: String? = null): UltimateGuitarChart? {
        val store = storeJson(html) ?: return preformattedFallback(html, sourceUrl)
        val body = chartBody(store) ?: return preformattedFallback(html, sourceUrl)

        return UltimateGuitarChart(
            title = firstString(store, "song_name")?.trim()?.ifEmpty { null },
            artist = firstString(store, "artist_name")?.trim()?.ifEmpty { null },
            keyText = firstString(store, "tonality_name")?.trim()?.ifEmpty { null }
                ?: firstString(store, "tonality")?.trim()?.ifEmpty { null },
            capo = firstInt(store, "capo") ?: 0,
            tuning = tuningName(store),
            sourceUrl = sourceUrl,
            body = body,
        )
    }

    /**
     * The chart, as ChordPro.
     *
     * The body goes straight to the chords-over-lyrics parser rather than
     * through [SongParser]'s sniffing, because here the format is known and
     * sniffing it is not merely redundant but wrong. The sniffer treats a
     * bracketed `[C...]` as an inline ChordPro chord, and this site's headings
     * are bracketed: `[Chorus]` and `[Guitar Solo]` both read as one, which
     * routes the whole chart to the ChordPro parser, and that parser finds no
     * chords in a chart whose chords are a line above the words. Every chord in
     * the song is silently lost - so the format is stated, not guessed.
     *
     * The metadata replaces whatever the body parser inferred rather than
     * filling in around it. A chords-over-lyrics chart with no chords above its
     * first line has that line taken for a title - a reasonable guess for a
     * chart typed in a text file, and the wrong one here, where the page has
     * already said what the song is called.
     */
    fun toChordPro(chart: UltimateGuitarChart, unicodeAccidentals: Boolean = false): String {
        val parsed = ChordsOverLyricsParser.parse(cleanBody(chart.body))
        val lines = parsed.lines.map(::asSectionHeaderIfLabelled)

        val extra = buildMap {
            chart.tuning?.takeIf { it.isNotBlank() }?.let { put("tuning", it) }
            // Where it came from, kept in the file. A chart that turns out to be
            // somebody's rough transcription is worth being able to trace back,
            // and the alternative is that the only copy of that fact is in the
            // share sheet of a browser session that closed months ago.
            chart.sourceUrl?.takeIf { it.isNotBlank() }?.let { put("source", it) }
        }

        val meta = parsed.meta.copy(
            title = chart.title ?: parsed.meta.title,
            artist = chart.artist,
            key = chart.keyText?.let { Key.parse(it) },
            capo = chart.capo,
            extra = extra,
        )
        return SongWriter.toChordPro(Song(meta, lines), unicodeAccidentals)
    }

    /**
     * A file name for the imported chart.
     *
     * `.chopro` rather than `.txt`, so that what lands in the library announces
     * itself as ChordPro to everything downstream - and to whatever the user
     * hands it to next.
     */
    fun fileNameFor(chart: UltimateGuitarChart): String {
        val song = chart.title?.trim().orEmpty().ifEmpty { "Imported chart" }
        val artist = chart.artist?.trim().orEmpty()
        val stem = if (artist.isEmpty()) song else "$song - $artist"
        return sanitise(stem) + ".chopro"
    }

    // ---- the page ----------------------------------------------------------

    /**
     * The JSON the page carries its own chart in.
     *
     * Every candidate `data-content` attribute is decoded and tried rather than
     * the first one taken, because the page has several and which one holds the
     * chart is not a thing to hard-code against a site that redesigns.
     */
    private fun storeJson(html: String): JsonElement? {
        for (match in DATA_CONTENT.findAll(html)) {
            val decoded = decodeEntities(match.groupValues[1])
            if (!decoded.contains("\"tab_view\"") && !decoded.contains("\"wiki_tab\"")) continue
            val parsed = runCatching { Json.parseToJsonElement(decoded) }.getOrNull() ?: continue
            return parsed
        }
        return null
    }

    /**
     * The chart text inside the store.
     *
     * The documented path is tried first, then a search for any `content` that
     * carries the site's chord markers. The search is not defensive
     * over-engineering: the same page serves a chart, its revision history and
     * neighbouring suggestions, all of which have a `content`, and the markers
     * are what tell a chart apart from a paragraph of prose about one.
     */
    private fun chartBody(store: JsonElement): String? {
        val direct = store.at("store", "page", "data", "tab_view", "wiki_tab", "content")
            ?.stringOrNull()
        if (!direct.isNullOrBlank()) return direct

        return allStrings(store, "content").firstOrNull { looksLikeChart(it) }
    }

    /** Charts carry the site's chord markers; prose about a chart does not. */
    private fun looksLikeChart(text: String): Boolean =
        text.contains("[ch]") || text.contains("[tab]")

    /**
     * Some pages - print views, and anything cached without the front end's
     * JSON - carry the chart in a plain preformatted block instead. Worth the
     * dozen lines: it is the difference between an import that works on an odd
     * page and one that reports a failure the user cannot act on.
     */
    private fun preformattedFallback(html: String, sourceUrl: String?): UltimateGuitarChart? {
        val body = PRE_BLOCK.findAll(html)
            .map { decodeEntities(stripTags(it.groupValues[1])) }
            .firstOrNull { looksLikeChart(it) || it.lineSequence().any(ChordsOverLyricsParser::isChordLine) }
            ?: return null

        return UltimateGuitarChart(
            title = decodeEntities(TITLE_TAG.find(html)?.groupValues?.get(1).orEmpty())
                .substringBefore(" CHORDS").substringBefore(" by ").trim().ifEmpty { null },
            sourceUrl = sourceUrl,
            body = body,
        )
    }

    // ---- the chart ---------------------------------------------------------

    /**
     * Takes the site's markers off.
     *
     * Alignment survives this, and that is the point: `[ch]` and `[tab]` are
     * invisible to the reader of the page, so the spacing either side of them is
     * already counted as though they were not there. Removing them leaves the
     * columns exactly where the chart's author put them - which for a
     * chords-over-lyrics chart *is* the information.
     */
    internal fun cleanBody(body: String): String = decodeEntities(
        body
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("[tab]", "")
            .replace("[/tab]", "")
            .replace("[ch]", "")
            .replace("[/ch]", ""),
    )

    /**
     * Rescues a section heading the general parser did not recognise.
     *
     * The ordinary parser only trusts a bracketed line when it starts with a
     * word it knows, which is right for a `.txt` file of unknown provenance
     * where `[Dm]` and `[x2]` are also bracketed lines. On this site the
     * convention is not in doubt - a line that is nothing but brackets is a
     * heading - so `[Guitar Solo]` becomes one here rather than being left to
     * sit in the lyrics.
     *
     * Repeat markers are excluded, because they are the one bracketed line on
     * these pages that is not a heading.
     */
    private fun asSectionHeaderIfLabelled(line: Line): Line {
        if (line !is Line.Lyric) return line
        if (line.segments.any { it.chord != null }) return line

        val label = BRACKETED.matchEntire(line.plainText.trim())?.groupValues?.get(1)?.trim()
            ?: return line
        if (label.isEmpty() || !label.first().isLetter()) return line
        if (REPEAT_MARKER.matches(label)) return line

        return Line.SectionHeader(label, sectionKind(label))
    }

    private fun sectionKind(label: String): SectionKind {
        val lower = label.lowercase()
        return when {
            lower.contains("chorus") -> SectionKind.CHORUS
            lower.contains("bridge") -> SectionKind.BRIDGE
            lower.contains("verse") -> SectionKind.VERSE
            else -> SectionKind.OTHER
        }
    }

    // ---- JSON without a schema ---------------------------------------------

    /**
     * These walk the document rather than deserialising into data classes on
     * purpose. The page is somebody else's, it is not versioned for this app's
     * benefit, and a `@Serializable` mirror of it would turn every field the
     * site adds or renames into a parse failure for the whole chart. Asking for
     * the handful of values actually wanted degrades one field at a time.
     */
    private fun JsonElement.at(vararg path: String): JsonElement? {
        var current: JsonElement = this
        for (step in path) {
            current = (current as? JsonObject)?.get(step) ?: return null
        }
        return current
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun firstString(root: JsonElement, key: String): String? =
        allStrings(root, key).firstOrNull()

    private fun firstInt(root: JsonElement, key: String): Int? {
        var found: Int? = null
        walk(root) { name, value ->
            if (found == null && name == key) {
                found = (value as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
            }
        }
        return found
    }

    /** Every string stored under [key], anywhere in the document, in page order. */
    private fun allStrings(root: JsonElement, key: String): Sequence<String> {
        val out = mutableListOf<String>()
        walk(root) { name, value ->
            if (name == key) value.stringOrNull()?.let(out::add)
        }
        return out.asSequence()
    }

    /**
     * The tuning is a name in some shapes of the page and an object holding one
     * in others, so both are accepted.
     */
    private fun tuningName(store: JsonElement): String? {
        var found: String? = null
        walk(store) { name, value ->
            if (found != null || name != "tuning") return@walk
            found = value.stringOrNull()
                ?: (value as? JsonObject)?.get("name")?.stringOrNull()
                ?: (value as? JsonObject)?.get("value")?.stringOrNull()
        }
        return found?.trim()?.ifEmpty { null }
    }

    /**
     * Visits every key/value pair in the document, depth first.
     *
     * Iterative, with a bounded work list. The page's JSON is deep and this runs
     * on a phone against a document nobody here controls the shape of; a
     * recursive walk over one that is unexpectedly deep is a crash rather than a
     * slow import.
     *
     * Children are pushed in reverse so that they come back off the stack in the
     * order the document lists them. Without that the walk still visits
     * everything, but "the first `content` on the page" - which is how the chart
     * is told apart from the revision history beside it - would mean whichever
     * one happened to be pushed last.
     */
    private fun walk(root: JsonElement, visit: (String, JsonElement) -> Unit) {
        val stack = ArrayDeque<JsonElement>()
        stack += root
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_JSON_NODES) {
            visited++
            when (val element = stack.removeLast()) {
                is JsonObject -> {
                    for ((name, value) in element) visit(name, value)
                    for (value in element.values.reversed()) stack += value
                }
                is JsonArray -> for (value in element.reversed()) stack += value
                else -> Unit
            }
        }
    }

    // ---- text --------------------------------------------------------------

    /**
     * Decodes HTML entities in one pass.
     *
     * One pass and not a chain of `replace` calls, which would decode text that
     * was never encoded: `&amp;lt;` means the literal characters `&lt;`, and
     * replacing `&amp;` before `&lt;` turns it into a `<` that was not in the
     * document. Everything the page's JSON attribute is escaped with is here;
     * anything else is left alone rather than guessed at.
     */
    internal fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        val out = StringBuilder(text.length)
        var i = 0

        while (i < text.length) {
            val char = text[i]
            if (char != '&') {
                out.append(char)
                i++
                continue
            }

            val end = text.indexOf(';', i + 1)
            if (end < 0 || end - i > MAX_ENTITY_LENGTH) {
                out.append(char)
                i++
                continue
            }

            val entity = text.substring(i + 1, end)
            val replacement = when {
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.drop(2).toIntOrNull(16)?.let(::codePoint)
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePoint)
                else -> NAMED_ENTITIES[entity]
            }

            if (replacement == null) {
                out.append(char)
                i++
            } else {
                out.append(replacement)
                i = end + 1
            }
        }
        return out.toString()
    }

    /** Refuses anything outside Unicode, and anything that would be a NUL. */
    private fun codePoint(value: Int): String? =
        if (value in 1..0x10FFFF) String(Character.toChars(value)) else null

    private fun stripTags(html: String): String =
        html.replace(TAG, "").replace("<br>", "\n").replace("<br/>", "\n")

    /**
     * Makes a file name out of a song title.
     *
     * The reserved set is Windows', not Android's, and deliberately so: these
     * files get copied to a laptop and synced through a drive, and a name that
     * is legal here and refuses to copy there is a problem discovered late.
     */
    private fun sanitise(name: String): String = name
        .map { if (it in RESERVED_CHARACTERS || it.code < 0x20) ' ' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.')
        .take(MAX_NAME_LENGTH)
        .trim()
        .ifEmpty { "Imported chart" }

    private val DATA_CONTENT = Regex("data-content=\"([^\"]*)\"")
    private val PRE_BLOCK = Regex("<pre[^>]*>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL)
    private val TITLE_TAG = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]*>")
    private val BRACKETED = Regex("\\[([^\\[\\]]+)]")
    private val REPEAT_MARKER = Regex("^[xX]\\s?\\d+$|^\\d+\\s?[xX]$")

    /**
     * A URL inside shared text. Stops at whitespace and at the angle brackets
     * some clients wrap a link in.
     */
    private val URL_IN_TEXT = Regex("https?://[^\\s<>\"]+")

    private val NAMED_ENTITIES = mapOf(
        "quot" to "\"",
        "apos" to "'",
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "nbsp" to " ",
        "hellip" to "…",
        "mdash" to "—",
        "ndash" to "–",
        "lsquo" to "‘",
        "rsquo" to "’",
        "ldquo" to "“",
        "rdquo" to "”",
    )

    private val RESERVED_CHARACTERS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    private const val MAX_NAME_LENGTH = 90
    private const val MAX_ENTITY_LENGTH = 12

    /** A page's JSON is large but bounded; this is well past any real one. */
    private const val MAX_JSON_NODES = 200_000
}
