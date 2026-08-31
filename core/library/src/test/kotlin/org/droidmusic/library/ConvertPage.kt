package org.droidmusic.library

import java.io.File
import org.droidmusic.music.ChartAnalyzer
import org.droidmusic.music.Line
import org.droidmusic.music.SongParser

/**
 * Converts a saved chart page and reports what came out of it.
 *
 *     ./gradlew -PcoreOnly :core:library:convertPage -Ppage=learn-to-fly.html
 *
 * **What this is for.** The import is only as good as what it makes of a real
 * page, and a real page is a quarter of a megabyte of somebody else's markup
 * that changes without notice. This turns "does that chart import?" into one
 * command against a page saved with `curl`, which is a great deal faster than
 * building an APK, and it prints the summary - how many chords were found, which
 * sections, whether every line kept its chords - that says whether the answer is
 * *yes* rather than merely *no crash*.
 *
 * It lives in the test source set on purpose: it is a tool for working on the
 * app, and nothing here goes into the APK.
 *
 * The pages themselves are deliberately not in the repository. They are other
 * people's transcriptions of other people's songs, and a checkout is not the
 * place for them - the unit tests use fixtures written for the purpose instead.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: convertPage <saved-page.html> [source-url]")
        return
    }

    val file = File(args[0])
    if (!file.isFile) {
        println("No such file: ${file.absolutePath}")
        return
    }

    val sourceUrl = args.getOrNull(1)
    val chart = UltimateGuitar.parsePage(file.readText(), sourceUrl)
    if (chart == null) {
        println("No chart found on that page.")
        println("Official and Pro tabs are interactive players with no text behind them.")
        return
    }

    val chordPro = UltimateGuitar.toChordPro(chart)
    val song = SongParser.parse(chordPro)

    // The analyser is the one step here allowed to fail without ending the
    // report. It is where the awkward cases live - a remote key, an unspellable
    // note behind a capo - and when it does throw, that exception is the single
    // most useful line this tool can print, so it is caught and shown rather
    // than taking the rest of the summary down with it.
    val analysis = runCatching { ChartAnalyzer.analyze(song) }
    val analysisError = analysis.exceptionOrNull()
    val detectedKey = analysis.getOrNull()?.effectiveKey

    val sections = song.lines.filterIsInstance<Line.SectionHeader>().map { it.label }
    val lyricLines = song.lines.filterIsInstance<Line.Lyric>()
    val withWords = lyricLines.filter { it.plainText.isNotBlank() }

    println("=".repeat(72))
    println("file        ${file.name}  (${file.length() / 1024} KB)")
    println("title       ${chart.title ?: "-"}")
    println("artist      ${chart.artist ?: "-"}")
    println("key         declared ${chart.keyText ?: "-"}, detected ${detectedKey ?: "-"}")
    println("capo        ${chart.capo}")
    println("tuning      ${chart.tuning ?: "-"}")
    println("file name   ${UltimateGuitar.fileNameFor(chart)}")
    println("-".repeat(72))
    println("sections    ${sections.size}: ${sections.joinToString(", ")}")
    println("chords      ${song.chords().size} in total, ${song.chords().distinct().size} distinct")
    println("            ${song.chords().distinct().joinToString(" ")}")
    println("lines       ${lyricLines.size} lyric, ${withWords.size} with words")
    println("            ${withWords.count { line -> line.segments.any { it.chord != null } }} of those carry chords")
    println("tab blocks  ${song.lines.count { it is Line.Tab }}")

    if (analysisError != null) {
        println("-".repeat(72))
        println("THE ANALYSER THREW on this chart. The import still saves it - the")
        println("key badge is the only thing lost - but this is a core bug worth")
        println("reporting, and the stack trace below is what identifies it:")
        analysisError.printStackTrace()
    }

    // The failure worth catching by eye: chords that came out of the page but
    // did not land on any word. A count cannot tell that from an instrumental
    // line, so the first few are printed to be looked at.
    println("-".repeat(72))
    println("first lines, as imported:")
    for (line in song.lines.take(PREVIEW_LINES)) {
        when (line) {
            is Line.SectionHeader -> println("  [${line.label}]")
            is Line.Lyric -> println("  ${line.segments.joinToString("") { seg ->
                (seg.chord?.let { "[$it]" } ?: "") + seg.text
            }}")
            is Line.Comment -> println("  {${line.text}}")
            is Line.Tab -> println("  ${line.text}")
            Line.Blank -> println()
        }
    }
    println("=".repeat(72))
}

/**
 * Enough to see the first verse land, and not so much that a chart's words are
 * printed out in full in somebody's terminal.
 */
private const val PREVIEW_LINES = 14
