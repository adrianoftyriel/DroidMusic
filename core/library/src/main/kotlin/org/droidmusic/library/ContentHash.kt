package org.droidmusic.library

import java.io.InputStream
import java.security.MessageDigest

/**
 * The fingerprint that decides whether two devices are holding the same chart.
 *
 * It covers the first megabyte of the file **and the file's length**. Hashing a
 * 60 MB scanned songbook in full, for every file, on a phone, to answer "do we
 * both have this" is not a trade worth making; a collision then needs two
 * different charts that share both their first megabyte and their exact length,
 * and the set list matcher falls back to the title anyway.
 *
 * The length is not a detail. Without it the prefix is the whole fingerprint,
 * and every file over a megabyte that begins the same way is the same file as
 * far as this is concerned - which for a library of scans from one songbook,
 * all opening on the same cover page, is not a hypothetical. That was the bug
 * this exists to hold a test against: the length being appended was the number
 * of bytes *hashed*, which for anything past a megabyte is the same constant
 * every time.
 */
object ContentHash {

    /** How much of a file is read. Everything past this is represented by the length. */
    const val PREFIX_BYTES = 1024 * 1024

    /**
     * Hashes a chart.
     *
     * [declaredLength] is the file's real length where the caller knows it - a
     * document provider usually says, and a local file always does. When nothing
     * knows, the stream is drained to find out, which costs a full read and is
     * the reason the length is worth passing in.
     */
    fun of(
        input: InputStream,
        declaredLength: Long,
        prefixBytes: Int = PREFIX_BYTES,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var hashed = 0L

        while (hashed < prefixBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), prefixBytes - hashed).toInt())
            if (read <= 0) break
            digest.update(buffer, 0, read)
            hashed += read
        }

        val length = if (declaredLength > 0) declaredLength else hashed + drain(input, buffer)
        digest.update(length.toString().toByteArray())
        return digest.digest().toHex()
    }

    /** Hashes bytes already in hand, where the length is not in question. */
    fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun drain(input: InputStream, buffer: ByteArray): Long {
        var extra = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) return extra
            extra += read
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
