package org.droidmusic.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One file attached to a release.
 *
 * Field names are GitHub's, mapped rather than renamed, so that what is written
 * here can be checked against their documentation without a translation step.
 */
@Serializable
data class ReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0L,
    @SerialName("content_type") val contentType: String = "",
) {
    /**
     * The installable APK, as opposed to the debug one published beside it.
     *
     * The debug APK is deliberately excluded. It is built with a different
     * application id (`.debug`), so installing it does not update anything - it
     * puts a second copy of DroidMusic on the phone, which is a confusing thing
     * to do to somebody who asked for an update.
     */
    val isInstallableApk: Boolean
        get() = name.endsWith(".apk", ignoreCase = true) &&
            !name.contains("-debug", ignoreCase = true)

    val isChecksums: Boolean get() = name.equals(CHECKSUMS_NAME, ignoreCase = true)

    companion object {
        const val CHECKSUMS_NAME = "SHA256SUMS.txt"
    }
}

/** One published release, as the GitHub releases API describes it. */
@Serializable
data class Release(
    @SerialName("tag_name") val tag: String = "",
    val name: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
) {
    val version: Version? get() = Version.parse(tag)
    val apk: ReleaseAsset? get() = assets.firstOrNull { it.isInstallableApk }
    val checksums: ReleaseAsset? get() = assets.firstOrNull { it.isChecksums }
}

/** Which releases a player is willing to be offered. */
@Serializable
enum class UpdateChannel {
    /** Full releases only - what `main` publishes. */
    STABLE,

    /** Also the automatic pre-releases that every push to `dev` produces. */
    PRERELEASE,
}

/** An update that exists, is installable, and is newer than what is running. */
data class AvailableUpdate(
    val version: Version,
    val release: Release,
    val apk: ReleaseAsset,
    val checksums: ReleaseAsset?,
) {
    val tag: String get() = release.tag
    val isPreRelease: Boolean get() = release.prerelease || version.isPreRelease
}

/** What a check found. */
sealed interface UpdateStatus {

    /** Something newer is published and installable. */
    data class Available(val update: AvailableUpdate) : UpdateStatus

    /** Nothing newer on this channel. */
    data class UpToDate(val current: Version) : UpdateStatus

    /**
     * This build is ahead of everything published.
     *
     * Normal for a local build, and normal for anyone running a `main` release
     * while watching the stable channel after the dev tags have been reset. It
     * is reported rather than folded into [UpToDate] because "you are running
     * something newer than anything released" and "you have the latest release"
     * are different facts, and a developer needs to be able to tell them apart.
     */
    data class Ahead(val current: Version, val newest: Version) : UpdateStatus

    /**
     * Releases exist, but this build cannot say whether it is behind them,
     * because it does not know which release it came from. A build made on
     * somebody's laptop rather than by CI.
     */
    data class UnknownCurrent(val newest: AvailableUpdate?) : UpdateStatus

    /** Nothing published at all, or nothing carrying an APK to install. */
    data object NothingPublished : UpdateStatus
}

/**
 * Deciding what, if anything, to offer.
 *
 * All of it is pure: releases in, a decision out. The network call that fetches
 * the releases lives in the app module, so every rule about *which* release is
 * the right one can be tested on a plain JVM against captured payloads instead
 * of against GitHub.
 */
object UpdateCheck {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses the body of `GET /repos/{owner}/{repo}/releases`.
     *
     * Malformed input returns an empty list rather than throwing. This is fed by
     * a server over a phone's network connection, and a truncated response
     * should mean "could not check", not a crash on a stand.
     */
    fun parseReleases(body: String): List<Release> =
        runCatching { json.decodeFromString<List<Release>>(body) }.getOrDefault(emptyList())

    /**
     * The newest release on [channel] that actually has an APK attached.
     *
     * Ordered by version rather than by the order GitHub returned, and drafts
     * are dropped. GitHub sorts by creation time, which is *usually* the same
     * ordering and is not the same thing: a release published from an older
     * commit, or re-published after being deleted, arrives out of order and
     * would otherwise be offered as an upgrade to somebody already past it.
     */
    fun newest(releases: List<Release>, channel: UpdateChannel): AvailableUpdate? =
        releases.asSequence()
            .filterNot { it.draft }
            .filter { channel == UpdateChannel.PRERELEASE || !it.prerelease }
            .mapNotNull { release ->
                val version = release.version ?: return@mapNotNull null
                val apk = release.apk ?: return@mapNotNull null
                AvailableUpdate(version, release, apk, release.checksums)
            }
            .maxByOrNull { it.version }

    /**
     * The whole decision.
     *
     * [currentTag] is the tag this build was published under, which CI stamps
     * into the APK. It is deliberately not the version *name*: every pre-release
     * built from `dev` carries the same versionName, so a build that knew only
     * its version name could never tell one pre-release from another and would
     * report "up to date" forever. A build that was not published by CI has no
     * tag, and says so rather than guessing.
     */
    fun check(
        currentTag: String?,
        releases: List<Release>,
        channel: UpdateChannel,
    ): UpdateStatus {
        val newest = newest(releases, channel)
        val current = Version.parse(currentTag)

        if (current == null) {
            return if (newest == null) UpdateStatus.NothingPublished
            else UpdateStatus.UnknownCurrent(newest)
        }
        if (newest == null) return UpdateStatus.NothingPublished

        return when {
            newest.version > current -> UpdateStatus.Available(newest)
            newest.version < current -> UpdateStatus.Ahead(current, newest.version)
            else -> UpdateStatus.UpToDate(current)
        }
    }
}
