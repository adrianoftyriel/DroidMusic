package org.droidmusic.music

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against regular expressions that compile here and throw on a phone.
 *
 * These modules are plain Kotlin/JVM so that their tests run in seconds on any
 * machine, which is the whole reason for the split - but it means every test in
 * them runs against OpenJDK's regex engine, and the app runs against Android's,
 * which is ICU. The two do not agree, and the disagreement that has actually
 * bitten is the brace: OpenJDK reads a lone `}` as the literal character, while
 * ICU rejects the pattern outright.
 *
 * That failure is as bad as it gets. The pattern is a `val` in an `object`, so
 * it is compiled when the class is first touched; the exception comes out of a
 * static initialiser as an `ExceptionInInitializerError`, and every later
 * reference to that class fails with `NoClassDefFoundError` instead. What the
 * user sees is "could not be laid out as a chart" for every ChordPro file, from
 * a parser whose entire test suite is green.
 *
 * So the rule this enforces is simple: inside a regular expression, a brace is
 * either part of a `{n,m}` quantifier or it is escaped. Never bare.
 *
 * It reads the source rather than the compiled patterns because a pattern that
 * has not been constructed yet cannot be inspected, and constructing them all
 * here would only prove they work on the machine that cannot see the problem.
 */
class AndroidRegexTest {

    @Test
    fun `no regex in the repository contains a bare brace`() {
        val offenders = mutableListOf<String>()

        for (file in mainSources()) {
            val text = file.readText()
            for ((pattern, line) in regexLiteralsIn(text)) {
                if (hasBareBrace(pattern)) {
                    offenders += "${file.name}:$line  Regex(\"$pattern\")"
                }
            }
        }

        assertTrue(
            "These patterns compile on the JVM and throw on Android. Escape the " +
                "brace as \\\\{ or \\\\}, or make it a {n,m} quantifier:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the check itself recognises the shapes it is looking for`() {
        // The bug that got through, and the fix for it.
        assertTrue(hasBareBrace("^\\\\s*\\\\{(.*)}\\\\s*$"))
        assertTrue(!hasBareBrace("^\\\\s*\\\\{(.*)\\\\}\\\\s*$"))

        // Quantifiers are braces and are perfectly legal.
        assertTrue(!hasBareBrace("[-|]{4,}"))
        assertTrue(!hasBareBrace("[0-9a-f]{4}"))
        assertTrue(!hasBareBrace("([0-9a-fA-F]{64})"))

        // An opening brace on its own is just as unsafe as a closing one.
        assertTrue(hasBareBrace("a{b"))
    }

    /**
     * Whether a pattern, written as it appears in Kotlin source, holds a brace
     * that is neither escaped nor part of a quantifier.
     */
    private fun hasBareBrace(sourceLiteral: String): Boolean = sourceLiteral
        // An escaped brace is written `\\{` in source, meaning `\{` in the pattern.
        .replace("\\\\{", "")
        .replace("\\\\}", "")
        // A quantifier is a brace pair holding digits and at most one comma.
        .replace(Regex("\\{\\d+(?:,\\d*)?\\}"), "")
        .any { it == '{' || it == '}' }

    /**
     * Every string literal handed to a `Regex(` constructor, with the line it
     * starts on.
     *
     * Scans from each `Regex(` to the literal that follows it, so a constructor
     * whose pattern sits on the next line - as a long one does - is covered too.
     * Raw strings are skipped: they cannot contain the doubled backslashes this
     * is reasoning about, and none of them is a pattern.
     */
    private fun regexLiteralsIn(text: String): List<Pair<String, Int>> {
        val found = mutableListOf<Pair<String, Int>>()
        var at = text.indexOf("Regex(")

        while (at >= 0) {
            var i = at + "Regex(".length
            while (i < text.length && text[i].isWhitespace()) i++
            if (i < text.length && text[i] == '"' && !text.startsWith("\"\"\"", i)) {
                val literal = StringBuilder()
                i++
                while (i < text.length && text[i] != '"') {
                    // A `\"` inside the literal is two source characters and is
                    // not the end of it.
                    if (text[i] == '\\' && i + 1 < text.length) {
                        literal.append(text[i]).append(text[i + 1])
                        i += 2
                    } else {
                        literal.append(text[i])
                        i++
                    }
                }
                found += literal.toString() to text.take(at).count { it == '\n' } + 1
            }
            at = text.indexOf("Regex(", at + 1)
        }
        return found
    }

    /**
     * The main sources of every module, found by walking up to the repository
     * root so that the test does not depend on which directory Gradle happened
     * to start it in.
     *
     * `app` is included deliberately, even though this test lives in `core`. The
     * app module is the one that only ever runs on Android, so it is where a
     * pattern like this hides longest - and it has no fast JVM suite of its own
     * to catch it. Reading its source costs nothing here.
     */
    private fun mainSources(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        val root = checkNotNull(dir) { "could not find the repository root from ${File("").absolutePath}" }

        val modules = listOf("core/music", "core/library", "core/session", "core/update", "app")
            .map { File(root, "$it/src/main/kotlin") }
            .filter { it.isDirectory }
        check(modules.isNotEmpty()) { "no module sources found under $root" }

        return modules.flatMap { module ->
            module.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
    }
}
