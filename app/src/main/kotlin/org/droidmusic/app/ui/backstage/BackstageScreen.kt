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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
            // Only a follower can be sent a chart, and only when something is
            // actually wrong with one. The set list arriving already asked for
            // whatever the library could not resolve; this is the button for
            // afterwards, when the check has found a chart that is here and
            // broken - which that first ask cannot see.
            if (role == SessionRole.FOLLOWER && !controller.checking &&
                controller.checks.any {
                    it.state == ChartState.MISSING || it.state == ChartState.UNREADABLE
                }
            ) {
                OutlinedButton(onClick = onAskForMissing) { Text("Ask the leader for these") }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            item { SectionLabel("Tonight's charts") }

            itemsIndexed(controller.checks, key = { index, _ -> index }) { index, check ->
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
                        val detail = check.detail ?: wordFor(check.state)
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (check.isProblem) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
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
