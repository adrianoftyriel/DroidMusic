package org.droidmusic.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision itself: given what is published, what should this build be told?
 *
 * Written against payloads shaped like the ones the release workflow actually
 * produces - `DroidMusic-0.1.0.apk`, a debug APK beside it, an AAB, and a
 * SHA256SUMS.txt - so that a change to the workflow's file naming breaks a test
 * here rather than the update button on somebody's phone.
 */
class UpdateCheckTest {

    private fun release(
        tag: String,
        prerelease: Boolean = tag.contains("-dev."),
        draft: Boolean = false,
        assets: List<ReleaseAsset> = standardAssets("0.1.0"),
    ) = Release(
        tag = tag,
        name = "DroidMusic $tag",
        htmlUrl = "https://github.com/owner/repo/releases/tag/$tag",
        draft = draft,
        prerelease = prerelease,
        assets = assets,
    )

    private fun standardAssets(version: String) = listOf(
        ReleaseAsset("DroidMusic-$version.apk", "https://example.invalid/app.apk", 9_000_000),
        ReleaseAsset("DroidMusic-$version-debug.apk", "https://example.invalid/debug.apk", 11_000_000),
        ReleaseAsset("DroidMusic-$version.aab", "https://example.invalid/app.aab", 8_000_000),
        ReleaseAsset("SHA256SUMS.txt", "https://example.invalid/sums", 300),
    )

    @Test
    fun `a newer pre-release is offered on the pre-release channel`() {
        val status = UpdateCheck.check(
            currentTag = "v0.1.0-dev.11",
            releases = listOf(release("v0.1.0-dev.12"), release("v0.1.0-dev.11")),
            channel = UpdateChannel.PRERELEASE,
        )
        val available = status as UpdateStatus.Available
        assertEquals("v0.1.0-dev.12", available.update.tag)
        assertTrue(available.update.isPreRelease)
    }

    @Test
    fun `the same pre-release is up to date`() {
        val status = UpdateCheck.check(
            currentTag = "v0.1.0-dev.12",
            releases = listOf(release("v0.1.0-dev.12")),
            channel = UpdateChannel.PRERELEASE,
        )
        assertTrue(status is UpdateStatus.UpToDate)
    }

    @Test
    fun `an older pre-release is never offered as an update`() {
        // The downgrade case. Android will happily install it - the versionCode
        // is the same for every dev build - so nothing but this check stands
        // between a player and being walked backwards.
        val status = UpdateCheck.check(
            currentTag = "v0.1.0-dev.12",
            releases = listOf(release("v0.1.0-dev.9")),
            channel = UpdateChannel.PRERELEASE,
        )
        val ahead = status as UpdateStatus.Ahead
        assertEquals("v0.1.0-dev.12", ahead.current.toString())
        assertEquals("v0.1.0-dev.9", ahead.newest.toString())
    }

    @Test
    fun `pre-releases are invisible on the stable channel`() {
        val status = UpdateCheck.check(
            currentTag = "v0.1.0-dev.11",
            releases = listOf(release("v0.1.0-dev.12"), release("v0.1.0-dev.11")),
            channel = UpdateChannel.STABLE,
        )
        assertTrue(status is UpdateStatus.NothingPublished)
    }

    @Test
    fun `a full release is offered to somebody on a dev build, on either channel`() {
        val releases = listOf(release("v0.1.0", prerelease = false), release("v0.1.0-dev.12"))

        for (channel in UpdateChannel.entries) {
            val available = UpdateCheck.check("v0.1.0-dev.12", releases, channel)
                as UpdateStatus.Available
            assertEquals("channel $channel", "v0.1.0", available.update.tag)
        }
    }

    @Test
    fun `the newest is chosen by version, not by the order the server sent`() {
        // GitHub sorts by creation time, which is usually the same ordering and
        // is not the same thing - a release re-published after being deleted, or
        // cut from an older commit, arrives out of order.
        val status = UpdateCheck.check(
            currentTag = "v0.1.0-dev.8",
            releases = listOf(
                release("v0.1.0-dev.9"),
                release("v0.1.0-dev.11"),
                release("v0.1.0-dev.10"),
            ),
            channel = UpdateChannel.PRERELEASE,
        )
        assertEquals("v0.1.0-dev.11", (status as UpdateStatus.Available).update.tag)
    }

    @Test
    fun `a draft is never offered`() {
        val status = UpdateCheck.check(
            currentTag = "v0.1.0-dev.11",
            releases = listOf(release("v0.1.0-dev.99", draft = true), release("v0.1.0-dev.11")),
            channel = UpdateChannel.PRERELEASE,
        )
        assertTrue(status is UpdateStatus.UpToDate)
    }

    @Test
    fun `a release with no APK is not an update`() {
        val status = UpdateCheck.check(
            currentTag = "v0.1.0-dev.11",
            releases = listOf(
                release(
                    "v0.1.0-dev.12",
                    assets = listOf(ReleaseAsset("DroidMusic-0.1.0.aab", "https://example.invalid/a")),
                ),
            ),
            channel = UpdateChannel.PRERELEASE,
        )
        assertTrue(status is UpdateStatus.NothingPublished)
    }

    @Test
    fun `the debug APK is never the one offered`() {
        // It is built with a different application id, so installing it does not
        // update anything - it puts a second DroidMusic on the phone.
        val status = UpdateCheck.check("v0.1.0-dev.11", listOf(release("v0.1.0-dev.12")), UpdateChannel.PRERELEASE)
        val update = (status as UpdateStatus.Available).update
        assertEquals("DroidMusic-0.1.0.apk", update.apk.name)
        assertFalse(update.apk.name.contains("debug"))
        assertEquals("SHA256SUMS.txt", update.checksums?.name)
    }

    @Test
    fun `a tag nobody can parse is skipped rather than offered`() {
        val status = UpdateCheck.check(
            currentTag = "v0.1.0-dev.11",
            releases = listOf(release("nightly", prerelease = true), release("v0.1.0-dev.11")),
            channel = UpdateChannel.PRERELEASE,
        )
        assertTrue(status is UpdateStatus.UpToDate)
    }

    @Test
    fun `a build with no release tag says so instead of guessing`() {
        // A build from somebody's laptop. It has a versionName, but versionName
        // is the same for every dev pre-release, so it proves nothing.
        val status = UpdateCheck.check(
            currentTag = null,
            releases = listOf(release("v0.1.0-dev.12")),
            channel = UpdateChannel.PRERELEASE,
        )
        val unknown = status as UpdateStatus.UnknownCurrent
        assertEquals("v0.1.0-dev.12", unknown.newest?.tag)
    }

    @Test
    fun `nothing published at all is its own answer`() {
        assertTrue(
            UpdateCheck.check("v0.1.0-dev.1", emptyList(), UpdateChannel.PRERELEASE)
                is UpdateStatus.NothingPublished,
        )
        assertTrue(
            UpdateCheck.check(null, emptyList(), UpdateChannel.PRERELEASE)
                is UpdateStatus.NothingPublished,
        )
    }

    @Test
    fun `a real releases payload decodes`() {
        val releases = UpdateCheck.parseReleases(SAMPLE_PAYLOAD)
        assertEquals(2, releases.size)

        val newest = UpdateCheck.newest(releases, UpdateChannel.PRERELEASE)
        assertEquals("v0.1.0-dev.12", newest?.tag)
        assertEquals("DroidMusic-0.1.0.apk", newest?.apk?.name)
        assertEquals(
            "https://github.com/owner/repo/releases/download/v0.1.0-dev.12/DroidMusic-0.1.0.apk",
            newest?.apk?.downloadUrl,
        )
        assertTrue(newest?.isPreRelease == true)

        // Only the pre-release channel sees it; the stable one finds the older
        // full release instead.
        assertEquals("v0.0.9", UpdateCheck.newest(releases, UpdateChannel.STABLE)?.tag)
    }

    @Test
    fun `a truncated response is not a crash`() {
        // Fed by a server over a phone's connection, in a venue, before a gig.
        assertTrue(UpdateCheck.parseReleases(SAMPLE_PAYLOAD.take(200)).isEmpty())
        assertTrue(UpdateCheck.parseReleases("").isEmpty())
        assertTrue(UpdateCheck.parseReleases("<html>Not found</html>").isEmpty())
        assertNull(UpdateCheck.newest(emptyList(), UpdateChannel.PRERELEASE))
    }

    private companion object {
        // Trimmed to the fields this app reads, with the shape and the unknown
        // extra keys GitHub actually sends.
        const val SAMPLE_PAYLOAD = """
        [
          {
            "url": "https://api.github.com/repos/owner/repo/releases/1",
            "id": 1,
            "tag_name": "v0.1.0-dev.12",
            "name": "DroidMusic v0.1.0-dev.12 (pre-release)",
            "draft": false,
            "prerelease": true,
            "published_at": "2026-08-30T05:31:34Z",
            "html_url": "https://github.com/owner/repo/releases/tag/v0.1.0-dev.12",
            "body": "Automated pre-release from `dev`.",
            "author": { "login": "someone", "id": 42 },
            "assets": [
              {
                "name": "DroidMusic-0.1.0.apk",
                "content_type": "application/vnd.android.package-archive",
                "size": 9123456,
                "download_count": 3,
                "browser_download_url": "https://github.com/owner/repo/releases/download/v0.1.0-dev.12/DroidMusic-0.1.0.apk"
              },
              {
                "name": "DroidMusic-0.1.0-debug.apk",
                "content_type": "application/vnd.android.package-archive",
                "size": 11234567,
                "browser_download_url": "https://github.com/owner/repo/releases/download/v0.1.0-dev.12/DroidMusic-0.1.0-debug.apk"
              },
              {
                "name": "SHA256SUMS.txt",
                "content_type": "text/plain",
                "size": 312,
                "browser_download_url": "https://github.com/owner/repo/releases/download/v0.1.0-dev.12/SHA256SUMS.txt"
              }
            ]
          },
          {
            "tag_name": "v0.0.9",
            "name": "DroidMusic v0.0.9",
            "draft": false,
            "prerelease": false,
            "published_at": "2026-08-01T00:00:00Z",
            "html_url": "https://github.com/owner/repo/releases/tag/v0.0.9",
            "body": "",
            "assets": [
              {
                "name": "DroidMusic-0.0.9.apk",
                "content_type": "application/vnd.android.package-archive",
                "size": 9000000,
                "browser_download_url": "https://github.com/owner/repo/releases/download/v0.0.9/DroidMusic-0.0.9.apk"
              }
            ]
          }
        ]
        """
    }
}
