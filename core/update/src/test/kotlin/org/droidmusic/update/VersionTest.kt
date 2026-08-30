package org.droidmusic.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    private fun v(text: String) = Version.parse(text) ?: error("did not parse: $text")

    @Test
    fun `a tag parses with or without its v`() {
        assertEquals(listOf(0, 1, 0), v("v0.1.0").numbers)
        assertEquals(listOf(0, 1, 0), v("0.1.0").numbers)
        assertEquals(listOf(1, 2), v("v1.2").numbers)
        assertEquals(listOf("dev", "12"), v("v0.1.0-dev.12").preRelease)
    }

    @Test
    fun `a pre-release suffix is recognised`() {
        assertTrue(v("v0.1.0-dev.12").isPreRelease)
        assertFalse(v("v0.1.0").isPreRelease)
    }

    @Test
    fun `the dev run number compares as a number, not as text`() {
        // The bug this whole class exists to prevent. As strings, "9" sorts
        // after "10", so a player on dev.9 would be told they were up to date
        // and would go on being told that for the next ninety releases.
        assertTrue(v("v0.1.0-dev.10") > v("v0.1.0-dev.9"))
        assertTrue(v("v0.1.0-dev.100") > v("v0.1.0-dev.99"))
        assertTrue(v("v0.1.0-dev.2") < v("v0.1.0-dev.11"))
    }

    @Test
    fun `a release beats its own pre-releases`() {
        // Merging to main has to read as an upgrade to everyone on a dev build.
        assertTrue(v("v0.1.0") > v("v0.1.0-dev.99"))
        assertTrue(v("v0.1.0-dev.1") < v("v0.1.0"))
    }

    @Test
    fun `the numeric core wins over any suffix`() {
        assertTrue(v("v0.2.0-dev.1") > v("v0.1.0"))
        assertTrue(v("v1.0.0-dev.1") > v("v0.9.9"))
        assertTrue(v("v0.1.1") > v("v0.1.0"))
    }

    @Test
    fun `a missing component is zero`() {
        assertEquals(0, v("v1.2").compareTo(v("v1.2.0")))
        assertTrue(v("v1.2.1") > v("v1.2"))
    }

    @Test
    fun `more suffix identifiers rank above fewer when the shared ones match`() {
        assertTrue(v("v1.0.0-dev.1.2") > v("v1.0.0-dev.1"))
    }

    @Test
    fun `a numeric identifier ranks below an alphanumeric one`() {
        assertTrue(v("v1.0.0-1") < v("v1.0.0-alpha"))
    }

    @Test
    fun `build metadata does not affect the ordering`() {
        assertEquals(0, v("v1.0.0+abc").compareTo(v("v1.0.0+zzz")))
        assertEquals(0, v("v1.0.0+abc").compareTo(v("v1.0.0")))
    }

    @Test
    fun `anything that is not a version parses to null`() {
        // Tag names are made by people. One that means nothing to this app must
        // leave it saying "I do not know" rather than inventing a number and
        // offering it as an upgrade.
        assertNull(Version.parse(null))
        assertNull(Version.parse(""))
        assertNull(Version.parse("   "))
        assertNull(Version.parse("v"))
        assertNull(Version.parse("latest"))
        assertNull(Version.parse("v1.x.0"))
        assertNull(Version.parse("release-2024"))
        assertNull(Version.parse("v1..0"))
        assertNull(Version.parse("v1.0.0-"))
    }

    @Test
    fun `the original text is kept for display`() {
        assertEquals("v0.1.0-dev.12", v("v0.1.0-dev.12").toString())
    }

    @Test
    fun `sorting a run of dev tags puts the newest last`() {
        val sorted = listOf("v0.1.0-dev.9", "v0.1.0", "v0.1.0-dev.10", "v0.1.0-dev.2")
            .map { v(it) }
            .sorted()
            .map { it.toString() }

        assertEquals(
            listOf("v0.1.0-dev.2", "v0.1.0-dev.9", "v0.1.0-dev.10", "v0.1.0"),
            sorted,
        )
    }
}
