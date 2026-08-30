package org.droidmusic.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.droidmusic.update.AvailableUpdate
import org.droidmusic.update.Checksums
import org.droidmusic.update.Release
import org.droidmusic.update.UpdateCheck

/**
 * Fetching a release from GitHub and handing the APK to Android's installer.
 *
 * **Why there is no update library and no background service.** The whole job is
 * one HTTPS GET of a JSON document, one HTTPS GET of a file, and one intent.
 * That is `HttpURLConnection` and forty lines. An update framework would add a
 * dependency, a scheduler and a notification channel to an app whose users open
 * it deliberately, before a rehearsal, on a phone they are about to put on a
 * stand.
 *
 * **Nothing here happens on its own.** There is no periodic check, no check on
 * launch, no phoning home. A page turner that decides to fetch nine megabytes
 * over a venue's wifi ninety seconds before the first song has done something
 * unforgivable, and the only way to be certain it never happens is for every
 * request in this file to be the direct result of somebody pressing a button.
 *
 * **What actually protects the install.** Not this code. Android refuses to
 * replace an installed app with a package signed by a different key, and that
 * check - not the checksum below, not HTTPS - is what stops a substituted APK
 * from becoming the DroidMusic on somebody's phone. The consequence is worth
 * knowing before relying on any of this: an APK built by CI *without* signing
 * secrets is debug-signed, and it can neither update nor be updated by a
 * release-signed one. The install simply fails.
 */
object Updater {

    /**
     * GitHub rejects an API request with no User-Agent outright, with a 403 and
     * no useful body. It is not optional and it is not decoration.
     */
    private const val USER_AGENT = "DroidMusic-Updater"

    /**
     * Enough releases to find the newest on either channel without paging. The
     * dev branch publishes one per push, so a couple of dozen covers weeks.
     */
    private const val RELEASES_PER_PAGE = 30

    /** Refuses anything absurd before writing it to a phone's storage. */
    private const val MAX_APK_BYTES = 300L * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Where a downloaded APK waits to be installed. */
    private const val DOWNLOAD_DIRECTORY = "updates"

    /** What came back from asking GitHub what has been released. */
    sealed interface FetchResult {
        data class Ok(val releases: List<Release>) : FetchResult
        data class Failed(val reason: String) : FetchResult
    }

    /** What came back from downloading one. */
    sealed interface DownloadResult {
        /**
         * The APK is on disk and ready to hand to the installer.
         *
         * [verified] says whether its SHA-256 was actually checked against the
         * release's own `SHA256SUMS.txt`, rather than whether it passed - a
         * mismatch never gets this far. It is false for a release published
         * without that file, and the UI says so instead of implying a check that
         * did not happen.
         */
        data class Ready(val file: File, val verified: Boolean) : DownloadResult
        data class Failed(val reason: String) : DownloadResult
    }

    /** Asks the repository what it has published. */
    suspend fun fetchReleases(repository: String): FetchResult = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$repository/releases?per_page=$RELEASES_PER_PAGE"
        when (val body = getText(url, accept = "application/vnd.github+json")) {
            is TextResult.Ok -> {
                val releases = UpdateCheck.parseReleases(body.text)
                if (releases.isEmpty() && body.text.isNotBlank()) {
                    // A 200 that does not decode is a captive portal's login
                    // page far more often than it is GitHub changing its API.
                    FetchResult.Failed(
                        "That did not look like a reply from GitHub. If this network has a " +
                            "sign-in page, that is usually what answered.",
                    )
                } else {
                    FetchResult.Ok(releases)
                }
            }
            is TextResult.Failed -> FetchResult.Failed(body.reason)
        }
    }

    /**
     * Downloads the APK, checking it as it arrives.
     *
     * The digest is computed in the same pass that writes the file, so verifying
     * costs one read of the stream rather than a second read of nine megabytes
     * off a phone's storage.
     *
     * [onProgress] is called with 0..1, or with -1 when the server did not say
     * how big the file is and there is no progress to report. Reporting a made-up
     * percentage would be worse than reporting none.
     */
    suspend fun download(
        context: Context,
        update: AvailableUpdate,
        onProgress: (Float) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val expected = update.checksums?.let { asset ->
            when (val sums = getText(asset.downloadUrl, accept = "text/plain")) {
                is TextResult.Ok -> Checksums.digestFor(sums.text, update.apk.name)
                is TextResult.Failed -> null
            }
        }

        val directory = File(context.cacheDir, DOWNLOAD_DIRECTORY)
        // Clear the directory first. A previous attempt that failed halfway, or
        // an APK from a release two weeks ago, is dead weight in a cache the
        // system may be about to reclaim anyway.
        runCatching { directory.listFiles()?.forEach { it.delete() } }
        if (!directory.exists() && !directory.mkdirs()) {
            return@withContext DownloadResult.Failed("Could not make room for the download.")
        }

        val target = File(directory, update.apk.name.substringAfterLast('/'))
        val digest = MessageDigest.getInstance("SHA-256")

        val outcome = runCatching {
            val connection = open(update.apk.downloadUrl, accept = "application/octet-stream")
            connection.use { stream, length ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_APK_BYTES) error("The file was far larger than an APK.")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(if (length > 0) (total.toFloat() / length) else -1f)
                    }
                    total
                }
            }
        }

        val written = outcome.getOrElse { error ->
            target.delete()
            return@withContext DownloadResult.Failed(describe(error))
        }

        if (written <= 0L) {
            target.delete()
            return@withContext DownloadResult.Failed("The download was empty.")
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && !Checksums.matches(expected, actual)) {
            // Refuse rather than hand it over. A truncated or rewritten APK
            // fails to install with a message that explains nothing, and the
            // player is left thinking the app is broken.
            target.delete()
            return@withContext DownloadResult.Failed(
                "The download did not match the checksum published with it, so it was " +
                    "discarded. Try again on a different network.",
            )
        }

        DownloadResult.Ready(target, verified = expected != null)
    }

    /**
     * Hands the APK to the system installer.
     *
     * Through a FileProvider because a `file://` URI to another app has been a
     * FileUriExposedException since Android 7, and the installer is another app.
     */
    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Whether the user has allowed this app to install packages.
     *
     * Since Android 8 this is granted per app rather than by one global "unknown
     * sources" switch, and it cannot be requested with a runtime permission
     * dialog - the user has to turn it on in Settings. So it is checked before
     * the download rather than after, because discovering it at the install step
     * means nine megabytes were fetched for nothing.
     */
    fun canInstallPackages(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** The Settings page where that permission is granted. */
    fun unknownSourcesIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Throws away a downloaded APK, once it has been handed over or abandoned. */
    fun clearDownloads(context: Context) {
        runCatching { File(context.cacheDir, DOWNLOAD_DIRECTORY).listFiles()?.forEach { it.delete() } }
    }

    // ---------------------------------------------------------------- plumbing

    private sealed interface TextResult {
        data class Ok(val text: String) : TextResult
        data class Failed(val reason: String) : TextResult
    }

    private fun getText(url: String, accept: String): TextResult = runCatching {
        open(url, accept).use { stream, _ ->
            // Capped: this reads a JSON document and a checksum file, and a
            // server that answers either with a gigabyte is not one to humour.
            String(stream.readBytesUpTo(4 * 1024 * 1024), Charsets.UTF_8)
        }
    }.fold(
        onSuccess = { TextResult.Ok(it) },
        onFailure = { TextResult.Failed(describe(it)) },
    )

    /**
     * A response body, with the length the server claimed.
     *
     * `HttpURLConnection` follows redirects on its own, which matters because a
     * release asset URL always redirects to wherever GitHub keeps the bytes.
     * What it will not do is follow one that changes protocol, so an https URL
     * cannot be quietly downgraded to http part-way through.
     */
    private class Response(private val connection: HttpURLConnection) {
        fun <T> use(block: (InputStream, Long) -> T): T = try {
            val length = connection.contentLengthLong
            connection.inputStream.use { block(it, length) }
        } finally {
            connection.disconnect()
        }
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

    private fun open(url: String, accept: String): Response {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "Refusing to fetch an update over an unencrypted connection."
        }

        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", USER_AGENT)
        }

        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            error(describeHttp(code))
        }
        return Response(connection)
    }

    private fun describeHttp(code: Int): String = when (code) {
        403, 429 ->
            "GitHub is rate-limiting this network. Checking again in an hour will work; " +
                "there is nothing wrong with the app."
        404 -> "That repository has no releases, or is not visible from here."
        in 500..599 -> "GitHub returned an error ($code). Try again shortly."
        else -> "The server answered with $code."
    }

    private fun describe(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() }
        return when (error) {
            is java.net.UnknownHostException ->
                "Could not reach GitHub. Check the network and try again."
            is java.net.SocketTimeoutException ->
                "The connection timed out. Venue wifi is often the reason."
            is javax.net.ssl.SSLException ->
                "The secure connection failed, which a captive portal will also do."
            is java.io.IOException -> message ?: "The download did not finish."
            else -> message ?: "Something went wrong fetching the update."
        }
    }
}
