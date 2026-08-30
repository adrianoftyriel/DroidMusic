package org.droidmusic.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumsTest {

    // Exactly what `sha256sum *` writes, which is what the release workflow runs.
    private val sums = """
        3b1f1e4c1c0e3d7a5b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a  DroidMusic-0.1.0-debug.apk
        a1b2c3d4e5f60718293a4b5c6d7e8f9012345678901234567890abcdefabcdef  DroidMusic-0.1.0.apk
        0000111122223333444455556666777788889999aaaabbbbccccddddeeeeffff  DroidMusic-0.1.0.aab
    """.trimIndent()

    @Test
    fun `the digest for a named file is found`() {
        assertEquals(
            "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678901234567890abcdefabcdef",
            Checksums.digestFor(sums, "DroidMusic-0.1.0.apk"),
        )
    }

    @Test
    fun `the release APK is not confused with the debug one beside it`() {
        // The names differ by one word and the file listing them contains both.
        val release = Checksums.digestFor(sums, "DroidMusic-0.1.0.apk")
        val debug = Checksums.digestFor(sums, "DroidMusic-0.1.0-debug.apk")
        assertTrue(release != debug)
        assertEquals("3b1f1e4c1c0e3d7a5b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a", debug)
    }

    @Test
    fun `binary mode markers are accepted`() {
        val binary = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678901234567890abcdefabcdef *app.apk"
        assertEquals(
            "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678901234567890abcdefabcdef",
            Checksums.digestFor(binary, "app.apk"),
        )
    }

    @Test
    fun `a file that is not listed cannot be verified`() {
        // Must be null, not false and not "assume fine". A release published
        // before the checksum file existed has no entry, and treating that as a
        // pass turns the check into something that silently stops happening.
        assertNull(Checksums.digestFor(sums, "DroidMusic-9.9.9.apk"))
        assertNull(Checksums.digestFor("", "DroidMusic-0.1.0.apk"))
        assertNull(Checksums.digestFor("not a checksum file at all", "DroidMusic-0.1.0.apk"))
    }

    @Test
    fun `a line that is not a digest is ignored`() {
        val noisy = """
            # a comment somebody added
            zzzz  DroidMusic-0.1.0.apk
            a1b2c3d4e5f60718293a4b5c6d7e8f9012345678901234567890abcdefabcdef  DroidMusic-0.1.0.apk
        """.trimIndent()
        assertEquals(
            "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678901234567890abcdefabcdef",
            Checksums.digestFor(noisy, "DroidMusic-0.1.0.apk"),
        )
    }

    @Test
    fun `matching ignores case but never accepts a missing digest`() {
        assertTrue(Checksums.matches("ABCD", "abcd"))
        assertTrue(Checksums.matches("abcd", "abcd"))
        assertFalse(Checksums.matches("abcd", "abce"))
        assertFalse(Checksums.matches(null, "abcd"))
        assertFalse(Checksums.matches("abcd", null))
        assertFalse(Checksums.matches(null, null))
    }
}
