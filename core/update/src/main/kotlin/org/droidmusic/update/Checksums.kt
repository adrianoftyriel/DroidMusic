package org.droidmusic.update

/**
 * The `SHA256SUMS.txt` that every release carries.
 *
 * **What this is for, and what it is not for.** It catches a download that
 * arrived wrong: a connection dropped halfway through in a venue with bad wifi,
 * a captive portal that served a login page with a 200, a proxy that helpfully
 * transcoded something. Those are the realistic failures, they are common on
 * exactly the networks this app gets used on, and an APK truncated at 80% fails
 * to install with a message that explains nothing.
 *
 * It is **not** a signature, and it must not be described as one anywhere in the
 * UI. The checksum file comes from the same release as the APK, so anyone who
 * could replace one could replace the other. The thing that actually stops a
 * substituted APK is Android's own signature check at install time: a package
 * signed with a different key cannot replace an installed app, and the install
 * fails. That check is the security boundary here; this is an integrity check.
 */
object Checksums {

    private val LINE = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")

    /**
     * Finds the digest for one file.
     *
     * Returns null when the file is not listed, which the caller must treat as
     * "cannot verify" rather than "verified". A release published before the
     * checksum file existed has no entry, and silently accepting that would make
     * the check something that quietly stops happening.
     */
    fun digestFor(sumsFile: String, fileName: String): String? =
        sumsFile.lineSequence()
            .mapNotNull { LINE.matchEntire(it.trim()) }
            .firstOrNull { it.groupValues[2].trim() == fileName }
            ?.groupValues?.get(1)
            ?.lowercase()

    /** Whether a computed digest matches, compared without regard to case. */
    fun matches(expected: String?, actual: String?): Boolean =
        expected != null && actual != null && expected.equals(actual, ignoreCase = true)
}
