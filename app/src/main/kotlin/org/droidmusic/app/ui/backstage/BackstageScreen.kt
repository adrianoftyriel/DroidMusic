package org.droidmusic.app.ui.backstage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.droidmusic.app.ui.common.Dot
import org.droidmusic.app.ui.common.EmptyState
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.Pill
import org.droidmusic.app.ui.common.SectionLabel
import org.droidmusic.app.ui.session.SessionRole
import org.droidmusic.app.ui.session.PeerFetch
import org.droidmusic.library.LibraryIndex
import org.droidmusic.session.AggregatedChart
import org.droidmusic.session.Backstage
import org.droidmusic.session.BackstageReport
import org.droidmusic.session.Catalogue
import org.droidmusic.session.ChartCheck
import org.droidmusic.session.ChartState
import org.droidmusic.session.Follower

/**
 * The five minutes before the first song, on a screen.
 *
 * Everything here answers one question - *will this work* - and it answers it
 * for the whole band rather than for one device, because the failure this exists
 * to prevent is not "my chart is missing" but "the bass player's chart was
 * missing and nobody knew until bar one".
 *
 * Two rules shape the layout. Trouble is named by song and by person, because
 * "two devices have problems" is not something anyone can act on and "nobody but
 * you has Copperhead Road" is. And nothing here blocks the set: the start button
 * works whatever the check says, because a band that has decided to busk a song
 * from memory does not need an app's permission.
 */
@Composable
fun BackstageScreen(
    controller: BackstageController,
    role: SessionRole,
    sessionLabel: String?,
    reports: List<BackstageReport>,
    followers: List<Follower>,
    aggregate: List<AggregatedChart>,
    library: LibraryIndex,
    peerFetches: Map<String, PeerFetch>,
    onStart: () -> Unit,
    onOpenSong: (Int) -> Unit,
    onOpenChart: (String) -> Unit,
    onFetchChart: (AggregatedChart) -> Unit,
    onAskForMissing: () -> Unit,
    onBack: () -> Unit,
) {
    val setlist = controller.setlist
    if (setlist == null) {
        // Three different people can be looking at this, and the old wording
        // spoke to only one of them. A player who has just joined has not
        // failed to open a set list - they are waiting on somebody else, and
        // telling them to start one is both wrong and not theirs to do.
        Column(Modifier.fillMaxSize()) {
            Header(
                title = "Backstage",
                subtitle = sessionLabel,
                onBack = onBack,
            )
            // In a session with no running order, the useful thing is not an
            // apology - it is what the band has between them. That is the whole
            // of an ad hoc session: no plan, so the question is what is
            // possible rather than what is next, and the answer is a list only
            // this screen can assemble.
            if (role != SessionRole.NONE) {
                Text(
                    when (role) {
                        SessionRole.LEADER ->
                            "No running order: open a chart and the band follows you to it. " +
                                "Everything anybody has is below."
                        else ->
                            "Waiting on the leader to start a set. Meanwhile, everything the " +
                                "band has between them is below."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                BandLibrary(
                    aggregate = aggregate,
                    library = library,
                    fetches = peerFetches,
                    onOpen = onOpenChart,
                    onFetch = onFetchChart,
                )
                return@Column
            }

            // Only reachable outside a session, now that being in one shows
            // the band's library above. Somebody here has opened Backstage from
            // a menu with no session running and nothing started.
            EmptyState(
                title = "Nothing to check",
                body = "Open a set list and start it, and this screen will make sure every " +
                    "chart in it opens before the first song. In a session it also lists " +
                    "everything the band has between them.",
            )
        }
        return
    }

    // What the band has, per song in the running order. The set list already
    // knows what this device cannot open; the catalogue knows who can supply it,
    // and joining the two is what turns "Missing" from a fact into a button.
    val offerFor: (Int) -> AggregatedChart? = { position ->
        setlist.entries.getOrNull(position)?.let { entry ->
            Catalogue.offering(aggregate, entry.contentHash, entry.title)
        }
    }

    // One row per chart that needs fetching, not per song: a number that comes
    // back in the encore is one file, and offering to fetch it twice would be
    // counting the same problem twice.
    val missing = remember(controller.checks, aggregate, setlist) {
        val seen = mutableSetOf<String>()
        controller.checks
            .filter { it.state == ChartState.MISSING || it.state == ChartState.UNREADABLE }
            .mapNotNull { check ->
                val entry = setlist.entries.getOrNull(check.index) ?: return@mapNotNull null
                if (!seen.add(entry.contentHash ?: entry.title.lowercase())) return@mapNotNull null
                MissingChart(check, offerFor(check.index))
            }
    }

    Column(Modifier.fillMaxSize()) {
        Header(
            title = "Backstage",
            subtitle = "${setlist.name} - ${setlist.size} songs",
            onBack = onBack,
        )

        OwnVerdict(controller)

        Row(
            Modifier
                .fillMaxWidth()
                // Scrolls rather than wraps: three buttons do not fit across a
                // phone, and one that has silently moved to a second line is one
                // somebody has to hunt for.
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onStart, enabled = setlist.entries.isNotEmpty()) {
                Text(if (role == SessionRole.FOLLOWER) "Open the first song" else "Start the set")
            }
            OutlinedButton(onClick = { controller.check() }, enabled = !controller.checking) {
                Text("Check again")
            }
            // The same offer the band's library makes on every row, made once
            // for the whole running order. A player with six songs missing does
            // not want to tap six times, and the count is the useful part: it is
            // the difference between one chart to chase and half the set.
            val canFix = missing.any { it.offer?.obtainable == true } ||
                role == SessionRole.FOLLOWER
            if (missing.isNotEmpty() && canFix && !controller.checking) {
                OutlinedButton(
                    onClick = {
                        // Only the ones somebody can actually send. A copy that
                        // is listed and unservable would be counted as dealt
                        // with and quietly never arrive.
                        val fetchable = missing
                            .mapNotNull { it.offer?.takeIf(AggregatedChart::obtainable) }
                            .distinctBy { it.contentHash }
                        fetchable.forEach(onFetchChart)
                        // Anything nobody in the band has published falls back to
                        // asking the leader outright, which reaches a device on
                        // an older build that announces no catalogue at all.
                        if (role == SessionRole.FOLLOWER && fetchable.size < missing.size) {
                            onAskForMissing()
                        }
                    },
                ) {
                    Text("Get all missing (${missing.size})")
                }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            item { SectionLabel("Tonight's charts") }

            itemsIndexed(controller.checks, key = { index, _ -> index }) { index, check ->
                val offer = offerFor(check.index)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSong(check.index) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Dot(colourOf(check.state))
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(check.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        val fetch = offer?.let { peerFetches[it.contentHash] }
                        val detail = fetch?.failed
                            ?: fetch?.takeIf { it.inFlight }
                                ?.let { "coming from ${it.from.ifBlank { "the band" }}" }
                            ?: check.detail
                            ?: wordFor(check.state)
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (check.isProblem || fetch?.failed != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (fetch?.inFlight == true) {
                            LinearProgressIndicator(
                                progress = { fetch.fraction },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                    }
                    ChartAction(
                        check = check,
                        offer = offer,
                        fetch = offer?.let { peerFetches[it.contentHash] },
                        onFetch = { offer?.let(onFetchChart) },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }

            // Rows that have not been reached yet, so the list is the length of
            // the set from the first frame and does not jump about as it fills.
            val pending = setlist.entries.drop(controller.checks.size)
            items(pending.size, key = { "pending-$it" }) { offset ->
                val entry = pending[offset]
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Dot(MaterialTheme.colorScheme.surfaceVariant)
                    Text(
                        "${controller.checks.size + offset + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }

            if (role == SessionRole.LEADER) {
                item {
                    Column {
                        SectionLabel("The band")
                        Text(
                            Backstage.bandSummary(reports, followers.size),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                if (followers.isEmpty()) {
                    item {
                        Text(
                            "Nobody has joined this session. Ask everyone to open Session and " +
                                "tap your name, then check again - their answers appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                items(followers, key = { it.deviceId }) { follower ->
                    val report = reports.firstOrNull { it.deviceId == follower.deviceId }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Dot(
                            when {
                                report == null -> MaterialTheme.colorScheme.outline
                                report.blocking.isNotEmpty() -> MaterialTheme.colorScheme.error
                                report.problems.isNotEmpty() -> WARNING
                                else -> MaterialTheme.colorScheme.secondary
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                follower.deviceName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                report?.let { Backstage.summarise(it) } ?: "Has not answered yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }

                val trouble = Backstage.trouble(reports)
                if (trouble.isNotEmpty()) {
                    item { SectionLabel("What to fix") }
                    items(trouble.size, key = { "trouble-$it" }) { position ->
                        val song = trouble[position]
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Dot(colourOf(song.state))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${song.index + 1}. ${song.title}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                )
                                Text(
                                    "${wordFor(song.state)}: " +
                                        song.deviceNames.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                    item {
                        Text(
                            "Send the file from the library, or move the song. A chart nobody " +
                                "else has is a song nobody else can play.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            if (controller.cloudCount > 0) {
                item {
                    Text(
                        "${controller.cloudCount} of these charts are read from a folder this " +
                            "device does not own. They open now, on this network. If the venue " +
                            "has no signal they may not - copy anything that has to be certain " +
                            "into the app's own storage from the library.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            item { Box(Modifier.height(24.dp)) }
        }
    }
}

/** A chart tonight needs and this device cannot open, with whoever has it. */
private data class MissingChart(val check: ChartCheck, val offer: AggregatedChart?)

/**
 * The word or the button at the end of a row in the running order.
 *
 * Deliberately the same vocabulary as the band's library on the ad hoc screen -
 * "here", "coming", "Get", "Retry" - because they are the same question asked of
 * two different lists, and a player should not have to learn it twice.
 *
 * A chart that is here says so and offers nothing. One that is not says who can
 * fix it, and the only case with no button is the one with no answer: nobody in
 * the session has published a copy.
 */
@Composable
private fun ChartAction(
    check: ChartCheck,
    offer: AggregatedChart?,
    fetch: PeerFetch?,
    onFetch: () -> Unit,
) {
    when {
        !check.isProblem -> Pill(
            "here",
            background = MaterialTheme.colorScheme.primaryContainer,
            foreground = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        // A different copy is still a copy. The row's colour and its detail line
        // have already said so; an offer to fetch would be offering a duplicate.
        check.state == ChartState.DIFFERENT -> Pill("here")

        fetch?.inFlight == true -> Pill("coming")

        offer?.obtainable == true -> TextButton(onClick = onFetch) {
            Text(if (fetch?.failed != null) "Retry" else "Get")
        }

        else -> Pill(
            "missing",
            background = MaterialTheme.colorScheme.errorContainer,
            foreground = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** The headline: what this device found, in one line and one colour. */
@Composable
private fun OwnVerdict(controller: BackstageController) {
    val total = controller.setlist?.size ?: 0
    val checking = controller.checking

    val background = when {
        checking -> MaterialTheme.colorScheme.surfaceVariant
        controller.checks.isEmpty() -> MaterialTheme.colorScheme.surfaceVariant
        controller.checks.any { it.state == ChartState.MISSING || it.state == ChartState.UNREADABLE } ->
            MaterialTheme.colorScheme.errorContainer
        controller.problems.isNotEmpty() -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (checking) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(
                text = when {
                    checking -> "Checking ${controller.checks.size + 1} of $total"
                    controller.checks.isEmpty() -> "Nothing checked yet"
                    controller.allReady -> "Every chart opens on this device"
                    else -> "${controller.problems.size} of $total need attention"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        controller.requestedBy?.let { leader ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill("asked for by $leader")
                if (!checking && controller.checks.isNotEmpty()) {
                    Text(
                        "Your answer has been sent.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (!checking && controller.problems.isNotEmpty()) {
            Text(
                "Nothing here stops the set. Start it anyway if the band would rather play " +
                    "than fix it now.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Amber: worth knowing, not worth stopping for. Matches the session screen. */
private val WARNING = Color(0xFFE0AF68)

@Composable
private fun colourOf(state: ChartState): Color = when (state) {
    ChartState.READY -> MaterialTheme.colorScheme.secondary
    ChartState.DIFFERENT -> WARNING
    ChartState.MISSING, ChartState.UNREADABLE -> MaterialTheme.colorScheme.error
}

private fun wordFor(state: ChartState): String = when (state) {
    ChartState.READY -> "Opens"
    ChartState.MISSING -> "Missing"
    ChartState.UNREADABLE -> "Will not open"
    ChartState.DIFFERENT -> "A different copy"
}
