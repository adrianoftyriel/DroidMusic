package org.droidmusic.app.ui.backstage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.droidmusic.app.ui.common.Pill
import org.droidmusic.app.ui.common.SectionLabel
import org.droidmusic.app.ui.session.PeerFetch
import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.normaliseForMatching
import org.droidmusic.session.AggregatedChart

/**
 * Everything the band has between them, in one list.
 *
 * This is the ad hoc session's answer to "what can we play". A running order
 * says what is planned; this says what is *possible*, which is the question at
 * the gig where the next song is called from the stage and the answer used to
 * be four people looking at four phones.
 *
 * Every row says who has the chart, because that is the actionable part. "Six
 * charts are missing" is not something anybody can do anything about; "only Bo
 * has Copperhead Road" means tap it and it comes across.
 *
 * The same song appears once per copy the band has, not once per song. That is
 * deliberate and it is explained in [org.droidmusic.session.Catalogue]: two
 * different transcriptions are two different files, and quietly collapsing them
 * would hide that the bass player's version has a different repeat.
 */
@Composable
fun BandLibrary(
    aggregate: List<AggregatedChart>,
    library: LibraryIndex,
    fetches: Map<String, PeerFetch>,
    onOpen: (String) -> Unit,
    onFetch: (AggregatedChart) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    // Resolved against this device's own library, so a row can say whether it
    // is here. By hash first and title second, the same way a set list resolves
    // - a chart somebody else has as a PDF and this device has as a ChordPro of
    // the same song is not missing, it is a second copy of something present.
    val rows = remember(aggregate, library, query) {
        val needle = query.trim().normaliseForMatching()
        aggregate
            .map { chart -> chart to library.match(chart.contentHash, chart.title) }
            .filter { (chart, _) ->
                needle.isEmpty() ||
                    chart.title.normaliseForMatching().contains(needle) ||
                    chart.artist?.normaliseForMatching()?.contains(needle) == true ||
                    chart.owners.any { it.deviceName.normaliseForMatching().contains(needle) }
            }
    }

    val here = rows.count { it.second != null }

    Column(Modifier.fillMaxSize()) {
        SectionLabel(
            if (aggregate.isEmpty()) {
                "The band's library"
            } else {
                "The band's library - $here of ${aggregate.size} on this device"
            },
        )

        if (aggregate.isEmpty()) {
            Text(
                "Nothing yet. Every device publishes what it has when it joins, so this " +
                    "fills in as the band arrives. A device running an older build does not " +
                    "publish anything and simply will not appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search titles, artists and players") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )

        if (rows.isEmpty()) {
            Text(
                "Nobody in the session has anything matching \"$query\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.first.contentHash }) { (chart, local) ->
                BandChartRow(
                    chart = chart,
                    haveIt = local != null,
                    fetch = fetches[chart.contentHash],
                    onOpen = { local?.let { onOpen(it.id) } },
                    onFetch = { onFetch(chart) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            item { Box(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun BandChartRow(
    chart: AggregatedChart,
    haveIt: Boolean,
    fetch: PeerFetch?,
    onOpen: () -> Unit,
    onFetch: () -> Unit,
) {
    // A chart this device holds opens; one it does not, fetches. Tapping the row
    // does whichever applies, so the common case is one tap and the button is
    // there for whoever would rather read a word than guess.
    val action: (() -> Unit)? = when {
        haveIt -> onOpen
        chart.obtainable && fetch?.inFlight != true -> onFetch
        else -> null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .then(if (action != null) Modifier.clickable(onClick = action) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    chart.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    detailFor(chart, haveIt, fetch),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fetch?.failed != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                )
            }
            chart.keyText?.let { Pill(it) }
            Box(Modifier.padding(start = 8.dp)) {
                when {
                    haveIt -> Pill(
                        "here",
                        background = MaterialTheme.colorScheme.primaryContainer,
                        foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                    )

                    fetch?.inFlight == true -> Pill("coming")

                    chart.obtainable -> TextButton(onClick = onFetch) {
                        Text(if (fetch?.failed != null) "Retry" else "Get")
                    }
                }
            }
        }

        if (fetch?.inFlight == true) {
            LinearProgressIndicator(
                progress = { fetch.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

/**
 * The line under the title.
 *
 * Says the one thing that is most worth knowing, rather than everything: what
 * went wrong if something did, then what is happening if anything is, then who
 * has it. A row that recited artist, key, size and owners would be four facts
 * nobody reads.
 */
private fun detailFor(chart: AggregatedChart, haveIt: Boolean, fetch: PeerFetch?): String {
    fetch?.failed?.let { return it }
    if (fetch?.inFlight == true) {
        return "coming from ${fetch.from.ifBlank { "the band" }}"
    }

    val who = chart.owners.joinToString(", ") { it.deviceName }
    val artist = chart.artist?.takeIf { it.isNotBlank() }

    return when {
        haveIt && chart.owners.size <= 1 -> listOfNotNull(artist, "on this device").joinToString(" - ")
        haveIt -> listOfNotNull(artist, "on this device and $who").joinToString(" - ")
        !chart.obtainable -> listOfNotNull(
            artist,
            "$who has it, and cannot send it",
        ).joinToString(" - ")
        else -> listOfNotNull(artist, "from $who").joinToString(" - ")
    }
}
