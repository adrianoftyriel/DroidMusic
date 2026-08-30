package org.droidmusic.update

/**
 * A release version, ordered the way SemVer says it should be.
 *
 * **Why this is not a string comparison.** Every pre-release this project
 * publishes is tagged `v0.1.0-dev.<run number>`, so the difference between the
 * build somebody is running and the one they could have is often a single digit
 * in a suffix. Compared as text, `v0.1.0-dev.9` sorts *after* `v0.1.0-dev.10`,
 * and the app confidently tells a player on dev.9 that they are up to date and
 * goes on doing so for the next ninety releases. That failure is completely
 * silent, which is what makes it worth a type and a test file rather than a
 * one-line `>`.
 *
 * The two rules that matter, both from the SemVer specification:
 *
 * - A version with a pre-release suffix is *lower* than the same version
 *   without one: `0.1.0-dev.12 < 0.1.0`. So merging to `main` and publishing
 *   `v0.1.0` is an upgrade for everyone on a dev build, which is exactly what
 *   it should be.
 * - Numeric identifiers inside the suffix compare numerically, not as text.
 *   That is the `dev.9` versus `dev.10` case.
 */
data class Version(
    /** The dotted numeric core: 0.1.0 becomes [0, 1, 0]. */
    val numbers: List<Int>,
    /** The dot-separated pre-release identifiers, empty for a release. */
    val preRelease: List<String> = emptyList(),
    /** What it was parsed from, kept so the UI can show what the tag actually said. */
    val original: String = "",
) : Comparable<Version> {

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: Version): Int {
        val width = maxOf(numbers.size, other.numbers.size)
        for (i in 0 until width) {
            // A missing component is zero, so 1.2 and 1.2.0 are the same version.
            val mine = numbers.getOrElse(i) { 0 }
            val theirs = other.numbers.getOrElse(i) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }

        // 1.0.0 beats 1.0.0-anything.
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1

        for (i in 0 until maxOf(preRelease.size, other.preRelease.size)) {
            val mine = preRelease.getOrNull(i) ?: return -1
            val theirs = other.preRelease.getOrNull(i) ?: return 1
            val comparison = compareIdentifiers(mine, theirs)
            if (comparison != 0) return comparison
        }
        return 0
    }

    override fun toString(): String = original.ifEmpty {
        numbers.joinToString(".") + if (isPreRelease) "-" + preRelease.joinToString(".") else ""
    }

    companion object {

        /**
         * Reads `v0.1.0-dev.12`, `0.1.0`, `v2.0` and the rest.
         *
         * Returns null rather than throwing or guessing. This parses tag names
         * from a server, and a tag somebody made by hand that means nothing to
         * this app should leave the app saying "I do not know", not inventing a
         * version number and offering it as an upgrade.
         */
        fun parse(raw: String?): Version? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null

            // Build metadata (+abc) carries no ordering at all, so it is dropped.
            val withoutMetadata = text.substringBefore('+')
            val body = withoutMetadata.removePrefix("v").removePrefix("V")
            if (body.isEmpty()) return null

            // Split on the first hyphen, keeping the distinction between "no
            // suffix" and "a suffix that is empty". `v1.0.0-` is malformed, and
            // reading it as `v1.0.0` would let a mistyped tag be offered to
            // everybody as a release.
            val hyphen = body.indexOf('-')
            val core = if (hyphen < 0) body else body.substring(0, hyphen)
            val suffix = if (hyphen < 0) null else body.substring(hyphen + 1)

            val numbers = core.split('.').map { part ->
                if (part.isEmpty() || part.any { !it.isDigit() }) return null
                part.toIntOrNull() ?: return null
            }
            if (numbers.isEmpty()) return null

            val preRelease = if (suffix == null) {
                emptyList()
            } else {
                val parts = suffix.split('.')
                if (parts.any { it.isEmpty() }) return null
                parts
            }

            return Version(numbers, preRelease, text)
        }

        /**
         * Numeric identifiers compare as numbers; anything else as text; and a
         * numeric identifier always ranks below an alphanumeric one.
         */
        private fun compareIdentifiers(a: String, b: String): Int {
            val aNumber = a.toLongOrNull()
            val bNumber = b.toLongOrNull()
            return when {
                aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
                aNumber != null -> -1
                bNumber != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
