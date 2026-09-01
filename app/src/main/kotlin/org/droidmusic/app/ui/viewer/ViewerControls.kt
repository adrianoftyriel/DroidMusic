package org.droidmusic.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.droidmusic.app.ui.common.ChoicePill
import org.droidmusic.app.ui.common.Pill

/**
 * The menu that slides over the chart when the top of the screen is tapped.
 *
 * Laid out for someone holding an instrument: large targets, one row of things,
 * and the transposition controls first because that is what gets touched during
 * a soundcheck. Everything here is one tap from the chart and one tap back.
 *
 * The three things it must always offer are the three a player cannot get at
 * while a chart is filling the screen: the key, the size of the text, and the
 * way out. Two of them are only meaningful on a chord chart and are absent on a
 * PDF - there is nothing in a picture of a page for the app to re-spell or
 * re-flow - but the way out is always there, in the same place.
 */
@Composable
fun ViewerControls(
    controller: ViewerController,
    preferences: ViewerPreferences,
    onPreferencesChange: ((ViewerPreferences) -> ViewerPreferences) -> Unit,
    sessionStatus: String?,
    canRejoin: Boolean,
    onRejoin: () -> Unit,
    onOpenSetlist: () -> Unit,
    onOpenSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    unicodeAccidentals: Boolean,
    modifier: Modifier = Modifier,
) {
    val song = controller.song
    val chart = controller.chartSource
    val analysis = controller.analysis

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            // A long set of controls on a phone in landscape is taller than the
            // screen. Scrolling it beats hiding the bottom of it.
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = song?.bestTitle ?: "No chart open",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                val position = controller.setlist?.let { list ->
                    "${controller.setlistIndex + 1} of ${list.size} - ${list.name}"
                }
                val pages = if (controller.pageCount > 0) {
                    "Page ${controller.page + 1} of ${controller.pageCount}"
                } else {
                    null
                }
                val subtitle = listOfNotNull(position, pages).joinToString("   ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onClose) { Text("Done") }
        }

        if (sessionStatus != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill(
                    text = sessionStatus,
                    background = MaterialTheme.colorScheme.primaryContainer,
                    foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (canRejoin) {
                    TextButton(onClick = onRejoin) { Text("Rejoin") }
                }
            }
        }

        // Transposition, only where it can mean anything. A PDF is a picture of a
        // page; there is nothing in it the app could rewrite, and offering the
        // control anyway would be a promise the app cannot keep.
        if (chart != null) {
            TransposeControls(controller, unicodeAccidentals)

            if (analysis != null) {
                AnalysisSummary(analysis, controller, unicodeAccidentals)
            }

            for (note in controller.transposeNotes) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            TextControls(preferences, onPreferencesChange)
        } else if (song != null) {
            Text(
                "This is a ${song.kind.name.lowercase()} file, so it can be turned but not " +
                    "transposed or re-sized. Chord charts in text or ChordPro format can be.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PageControls(preferences, onPreferencesChange)

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        Row(
            // Scrolls rather than wraps: on a narrow phone the four of these do
            // not fit, and a button that has silently moved to a second line is
            // a button somebody has to look for.
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The way out, as a filled button rather than one more text link.
            // Somebody looking for it is looking for the exit, and the exit
            // should not be the same weight as "Session".
            Button(onClick = onBack) { Text("Close the chart") }
            TextButton(onClick = onOpenSetlist) { Text("Set lists") }
            TextButton(onClick = onOpenSession) { Text("Session") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

/**
 * The size of the chart's text, where there is text to size.
 *
 * Changing the size here re-flows the chart immediately: the viewer works out
 * how many lines fit from the size on screen, so a bigger font is fewer lines a
 * page and more pages, and the reader stays on the line they were reading. That
 * is why this belongs in front of the chart rather than only in Settings - it is
 * something you adjust once you can see the result, standing where you will be
 * standing.
 */
@Composable
private fun TextControls(
    preferences: ViewerPreferences,
    onChange: ((ViewerPreferences) -> ViewerPreferences) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Text size", style = MaterialTheme.typography.labelLarge)
        ChoicePill(
            text = "A-",
            selected = false,
            onClick = { onChange { it.copy(chartFontScale = step(it.chartFontScale, -1)) } },
        )
        Pill("${(preferences.chartFontScale * 100).toInt()}%")
        ChoicePill(
            text = "A+",
            selected = false,
            onClick = { onChange { it.copy(chartFontScale = step(it.chartFontScale, 1)) } },
        )
        if (preferences.chartFontScale != 1f) {
            TextButton(onClick = { onChange { it.copy(chartFontScale = 1f) } }) {
                Text("Reset")
            }
        }
    }
}

/**
 * The page itself: its colour, and how many of them.
 *
 * Outside the chart-only block, because both apply just as much to a scanned
 * songbook - a dark stage is a dark stage whatever the file is, and a spread is
 * a spread.
 */
@Composable
private fun PageControls(
    preferences: ViewerPreferences,
    onChange: ((ViewerPreferences) -> ViewerPreferences) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Dark page", style = MaterialTheme.typography.labelLarge)
        Switch(
            checked = preferences.darkChart,
            onCheckedChange = { on -> onChange { it.copy(darkChart = on) } },
        )
        Box(Modifier.weight(1f))
        Text(
            "Pages",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoicePill(
            text = "auto",
            selected = preferences.pageModeOverride == null,
            onClick = { onChange { it.copy(pageModeOverride = null) } },
        )
        ChoicePill(
            text = "1",
            selected = preferences.pageModeOverride == PageMode.SINGLE,
            onClick = { onChange { it.copy(pageModeOverride = PageMode.SINGLE) } },
        )
        ChoicePill(
            text = "2",
            selected = preferences.pageModeOverride == PageMode.SPREAD,
            onClick = { onChange { it.copy(pageModeOverride = PageMode.SPREAD) } },
        )
    }
}

/**
 * One notch of text size, clamped to what stays readable.
 *
 * The same range the settings slider offers, so the two controls cannot
 * disagree about what is possible.
 */
private fun step(scale: Float, direction: Int): Float =
    (scale + direction * FONT_STEP).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)

private const val FONT_STEP = 0.1f
private const val MIN_FONT_SCALE = 0.6f
private const val MAX_FONT_SCALE = 2.2f

@Composable
private fun TransposeControls(controller: ViewerController, unicodeAccidentals: Boolean) {
    val chart = controller.chartSource ?: return
    val result = chart.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Key", style = MaterialTheme.typography.labelLarge)
            Pill(
                text = if (unicodeAccidentals) {
                    result.soundingKey?.toUnicode().orEmpty()
                } else {
                    result.soundingKey?.toString().orEmpty()
                },
                background = MaterialTheme.colorScheme.primary,
                foreground = MaterialTheme.colorScheme.onPrimary,
            )
            if (controller.capo > 0 && result.playedKey != result.soundingKey) {
                Text(
                    "capo ${controller.capo}, playing " +
                        (result.playedKey?.toUnicode() ?: "?"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (controller.transposeSemitones != 0) {
                val sign = if (controller.transposeSemitones > 0) "+" else ""
                Text(
                    "$sign${controller.transposeSemitones}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Every key, rather than only up and down by a semitone. Singers ask for
        // a key by name, not by an interval from wherever the chart happened to
        // be written.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val from = result.fromKey
            for (semitones in -5..6) {
                // Spelled the way the transposer will spell it, not the way
                // the default would. A pill offering "C#" for a chart that
                // then comes up in Db is a small lie told twelve times a
                // screen.
                val target = from?.transposedTo(semitones)
                ChoicePill(
                    text = when {
                        target == null -> "$semitones"
                        unicodeAccidentals -> target.toUnicode()
                        else -> target.toString()
                    },
                    selected = controller.transposeSemitones == semitones,
                    onClick = { controller.chooseTranspose(semitones) },
                )
            }
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Capo", style = MaterialTheme.typography.labelLarge)
            for (fret in 0..7) {
                ChoicePill(
                    text = if (fret == 0) "off" else "$fret",
                    selected = controller.capo == fret,
                    onClick = { controller.chooseCapo(fret) },
                )
            }
        }
    }
}

@Composable
private fun AnalysisSummary(
    analysis: org.droidmusic.music.ChartAnalysis,
    controller: ViewerController,
    unicodeAccidentals: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val detected = analysis.detectedKey
        if (analysis.declaredKey == null && detected != null) {
            val confidence = (detected.confidence * 100).toInt()
            Text(
                "The file does not say what key it is in. Best guess: " +
                    "${detected.key.display()} ($confidence% sure)" +
                    (detected.runnerUp?.let { ", otherwise ${it.display()}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (analysis.nonDiatonic.isNotEmpty()) {
            Text(
                "Outside the key: " + analysis.nonDiatonic.joinToString(" ") {
                    if (unicodeAccidentals) it.toUnicode() else it.toString()
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val capos = analysis.capoSuggestions.take(2)
        if (capos.isNotEmpty() && controller.capo == 0) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Easier with a capo:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (suggestion in capos) {
                    ChoicePill(
                        text = "${suggestion.fret} - play in ${suggestion.playedKey}",
                        selected = false,
                        onClick = { controller.chooseCapo(suggestion.fret) },
                    )
                }
            }
        }
    }
}

@Composable
fun ViewerStatusStrip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
