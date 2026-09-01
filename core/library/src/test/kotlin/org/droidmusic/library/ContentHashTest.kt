package org.droidmusic.library

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The fingerprint two devices compare when deciding whether they hold the same
 * chart.
 *
 * Worth a test of its own because getting it wrong is invisible: a set list
 * arrives, every entry resolves, and one of them resolves to the wrong song.
 */
class ContentHashTest {

    private val megabyte = ContentHash.PREFIX_BYTES

    /** A file that starts the same way as every other one made here. */
    private fun file(length: Int, tailByte: Byte = 0): ByteArray {
        val bytes = ByteArray(length)
        for (i in 0 until minOf(length, megabyte)) bytes[i] = (i % 251).toByte()
        for (i in megabyte until length) bytes[i] = tailByte
        return bytes
    }

    private fun hash(bytes: ByteArray, declared: Long = bytes.size.toLong()) =
        ContentHash.of(ByteArrayInputStream(bytes), declared)

    @Test
    fun `the same bytes hash the same way`() {
        assertEquals(hash(file(2000)), hash(file(2000)))
    }

    @Test
    fun `two small files that differ hash differently`() {
        assertNotEquals(hash("hello".toByteArray()), hash("hello world".toByteArray()))
    }

    @Test
    fun `two large files sharing a first megabyte are told apart by their length`() {
        // The bug this is here for. Only the first megabyte is read, so the
        // length is the only thing distinguishing these - and when the number
        // appended was the count of bytes *hashed* rather than the file's
        // length, it was the same constant for both and they collided.
        val a = file(megabyte + 1_000, tailByte = 1)
        val b = file(megabyte + 9_999, tailByte = 2)
        assertNotEquals(hash(a), hash(b))
    }

    @Test
    fun `two large files of the same length that differ only past a megabyte still collide`() {
        // Stated rather than fixed: reading 60 MB of every file in a folder, on
        // a phone, to answer "do we both have this" is not a trade worth making.
        // The set list matcher falls back to the title, which is what actually
        // catches this case.
        val a = file(megabyte + 4_096, tailByte = 1)
        val b = file(megabyte + 4_096, tailByte = 2)
        assertEquals(hash(a), hash(b))
    }

    @Test
    fun `a length nobody declared is worked out rather than guessed`() {
        // A provider that will not say how big a file is must not produce a
        // different fingerprint from one that will, or the same chart on two
        // devices would never match.
        val bytes = file(megabyte + 5_000, tailByte = 3)
        assertEquals(hash(bytes, declared = bytes.size.toLong()), hash(bytes, declared = 0))
        assertEquals(hash(bytes, declared = bytes.size.toLong()), hash(bytes, declared = -1))
    }

    @Test
    fun `an empty file hashes without complaint`() {
        assertEquals(hash(ByteArray(0)), hash(ByteArray(0)))
    }

    @Test
    fun `the two rules are different rules, and mixing them up cannot match`() {
        // The trap, pinned. A text chart is hashed from its characters and a PDF
        // from its bytes and length, and the two answers for the same file are
        // not the same answer. Chart sharing reimplemented the second rule and
        // applied it to everything, so every ChordPro transfer was checked
        // against a number nothing could reproduce and rejected as corrupt.
        //
        // Anything that needs a chart's hash asks DocumentSources.enrich, which
        // is the one place that knows which rule a kind gets.
        val chart = "{title: Everlong}\n[D]Hello".toByteArray()
        val fromText = ContentHash.of(chart)
        val fromBytes = ContentHash.of(ByteArrayInputStream(chart), chart.size.toLong())
        assertNotEquals(fromText, fromBytes)
    }

    @Test
    fun `hashing bytes in hand agrees with itself`() {
        assertEquals(ContentHash.of("abc".toByteArray()), ContentHash.of("abc".toByteArray()))
        assertNotEquals(ContentHash.of("abc".toByteArray()), ContentHash.of("abd".toByteArray()))
    }
}
