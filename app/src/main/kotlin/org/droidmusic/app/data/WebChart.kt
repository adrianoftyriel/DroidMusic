package org.droidmusic.app.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetching a chart page that somebody shared into the app.
 *
 * **Why `HttpURLConnection` and not an HTTP client.** One GET of one page, on a
 * button press, is not a reason to add a networking library to an APK. This is
 * the same reasoning - and very nearly the same forty lines - as
 * [org.droidmusic.app.update.Updater], and it is kept separate from it because
 * the two have different rules about what they will fetch and want to stay that
 * way.
 *
 * **This only ever runs because somebody shared a link.** There is no
 * prefetching, no retry in the background and no cache warming. The app fetches
 * exactly the page the user handed it, once, and then it is holding a file and
 * has no further use for the network - which is the property that matters, since
 * the chart has to open later in a room with no signal.
 */
object WebChart {

    /**
     * A browser's User-Agent, and not a made-up one naming this app.
     *
     * Stated plainly because it looks like something to be embarrassed about:
     * chart sites serve their pages to browsers and commonly answer an
     * unrecognised agent with a block page, which arrives as a 200 with no chart
     * in it - a failure that presents as "the import is broken" rather than as
     * anything a user could act on. The request is an ordinary one for a page a
     * person is looking at, made because that person asked for it.
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Mobile Safari/537.36"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /**
     * A chart page is tens of kilobytes of chart inside a few hundred of
     * markup. Two megabytes is far past any real one and still small enough
     * that a link to something else entirely cannot fill the phone.
     */
    private const val MAX_BYTES = 2 * 1024 * 1024

    /** What a fetch produced, or why it did not. */
    sealed interface FetchResult {
        data class Ok(val html: String) : FetchResult

        /** A message written to be shown to the user as it is. */
        data class Failed(val message: String) : FetchResult
    }

    suspend fun fetch(url: String): FetchResult = withContext(Dispatchers.IO) {
        runCatching { get(url) }
            .fold(
                onSuccess = { FetchResult.Ok(it) },
                onFailure = { FetchResult.Failed(describe(it)) },
            )
    }

    private fun get(url: String): String {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "That link is not an encrypted one, so it was not opened."
        }

        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            // Asking for no compression rather than unpacking it afterwards.
            // `HttpURLConnection` only decodes gzip transparently when it added
            // the header itself, and a body that arrives gzipped and is read as
            // characters is a page of mojibake with no chart in it.
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) error(describeHttp(code))
            val bytes = connection.inputStream.use { it.readBytesUpTo(MAX_BYTES) }
            return String(bytes, charsetOf(connection.contentType))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The charset the server named, falling back to UTF-8.
     *
     * Worth asking rather than assuming: a chart carries names and words with
     * accents in them, and decoding those with the wrong charset does not fail,
     * it quietly produces the wrong characters in somebody's song title.
     */
    private fun charsetOf(contentType: String?): Charset {
        val named = contentType
            ?.substringAfter("charset=", "")
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() }
            ?: return Charsets.UTF_8
        return runCatching { Charset.forName(named) }.getOrDefault(Charsets.UTF_8)
    }

    /**
     * Written out rather than using `readNBytes`, which is a Java 9 API and does
     * not reach Android until API 33 - on a minSdk 26 build that is a crash on
     * most phones in the field rather than a compile error.
     */
    private fun InputStream.readBytesUpTo(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (total < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun describeHttp(code: Int): String = when (code) {
        401, 403 ->
            "The site would not serve that page to the app. Charts behind a sign-in " +
                "cannot be imported this way."
        404 -> "That page is not there any more."
        429 -> "The site is asking for fewer requests. Trying again in a few minutes will work."
        in 500..599 -> "The site returned an error ($code). Try again shortly."
        else -> "The site answered with $code."
    }

    private fun describe(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "No network, or the site could not be found."
        is java.net.SocketTimeoutException -> "The site took too long to answer."
        is javax.net.ssl.SSLException -> "The secure connection to the site failed."
        else -> error.message ?: "That link could not be opened."
    }
}
