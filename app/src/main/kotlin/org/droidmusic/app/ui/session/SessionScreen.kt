package org.droidmusic.app.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.droidmusic.app.net.DiscoveredSession
import org.droidmusic.app.ui.common.Dot
import org.droidmusic.app.ui.common.EmptyState
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.Pill
import org.droidmusic.app.ui.common.SectionLabel
import org.droidmusic.library.Setlist
import org.droidmusic.session.FollowMode
import org.droidmusic.session.ChartShare
import org.droidmusic.session.ChartSharing
import org.droidmusic.session.LinkState

/**
 * Starting or joining a band session.
 *
 * The whole screen is built around one idea: joining should take one tap, and
 * everything about the state of the session should be visible without tapping
 * anything. On stage there is no time to go looking.
 */
@Composable
fun SessionScreen(
    coordinator: SessionCoordinator,
    setlists: List<Setlist>,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    onStartWithSetlists: (name: String, setlists: List<Setlist>) -> Unit,
    onBack: () -> Unit,
) {
    val role by coordinator.role.collectAsState()
    val leaderState by coordinator.leaderState.collectAsState()
    val followerState by coordinator.followerState.collectAsState()
    val message by coordinator.message.collectAsState()
    val sessionLabel by coordinator.sessionLabel.collectAsState()
    val sharing by coordinator.sharing.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var sessionName by remember { mutableStateOf("") }
    var choosingSetlists by remember { mutableStateOf(false) }

    // Discovery runs only while this screen is open, and only while this device
    // is not already in a session. Browsing mDNS costs battery, and there is no
    // reason to do it while somebody is playing.
    //
    // Collected directly rather than through produceState: discovery is already
    // a flow, so there is nothing to produce - and assigning `value` from inside
    // a nested collect is a pattern neither a reader nor Compose's own lint can
    // follow.
    val discovery = remember(role) {
        if (role == SessionRole.NONE) {
            coordinator.discoverSessions().catch { emit(emptyList()) }
        } else {
            flowOf(emptyList<DiscoveredSession>())
        }
    }
    val discovered by discovery.collectAsState(initial = emptyList())

    if (choosingSetlists) {
        ChooseSetlistsDialog(
            setlists = setlists,
            onDismiss = { choosingSetlists = false },
            onStart = { chosen ->
                choosingSetlists = false
                onStartWithSetlists(sessionName.ifBlank { deviceName }, chosen)
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Header(
            title = "Sessions",
            subtitle = SessionCoordinator.statusLine(
                role = role,
                leader = leaderState,
                follower = followerState,
                sessionLabel = sessionLabel,
            ) ?: "Not in a session",
            onBack = onBack,
        )

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        }

        when (role) {
            SessionRole.NONE -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            ) {
                // The name goes first because it is the thing everybody else
                // sees, and the one setting somebody wants to fix in the ten
                // seconds before they tap Start rather than by going to
                // Settings and finding their way back.
                SectionLabel("Device name")
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChange,
                    label = { Text("What the band sees") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )

                SectionLabel("New session")
                Text(
                    "Everyone who joins follows your page turns. If someone loses the " +
                        "network they carry on with their own, and pick you back up when it " +
                        "returns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                OutlinedTextField(
                    value = sessionName,
                    onValueChange = { sessionName = it },
                    label = { Text("Session name") },
                    placeholder = { Text(deviceName) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                coordinator.startLeading(sessionName.ifBlank { deviceName })
                            }
                        },
                    ) { Text("Ad hoc") }
                    OutlinedButton(
                        enabled = setlists.isNotEmpty(),
                        onClick = { choosingSetlists = true },
                    ) { Text("Choose set lists") }
                }
                Text(
                    "Ad hoc is the gig called from the stage: no running order, and a chart " +
                        "reaches the band when you open it. Choosing set lists sends the " +
                        "running order to everyone who joins and checks, before the first " +
                        "song, that they can all open every chart in it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )

                HorizontalDivider()
                SectionLabel("Join a session")

                if (discovered.isEmpty()) {
                    EmptyState(
                        title = "Nobody is leading yet",
                        body = "Sessions on this wifi show up here on their own. If nothing " +
                            "appears, check every device is on the same network - some venue " +
                            "wifi blocks devices from seeing each other, and there is nothing " +
                            "the app can do about that from the inside.",
                    )
                } else {
                    LazyColumn {
                        items(discovered, key = { it.serviceName }) { session ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { coordinator.joinAsFollower(session) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        session.serviceName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        session.leaderName?.let { "Led by $it" }
                                            ?: "${session.host}:${session.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(onClick = { coordinator.joinAsFollower(session) }) {
                                    Text("Join")
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }

            SessionRole.LEADER -> Column(Modifier.fillMaxSize()) {
                SectionLabel("You are leading")
                val followers = leaderState?.followers.orEmpty()
                val lost = leaderState?.outOfStep().orEmpty().map { it.deviceId }.toSet()

                if (followers.isEmpty()) {
                    EmptyState(
                        title = "Waiting for the band",
                        body = "Ask everyone to open Session and tap your name.",
                    )
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(followers, key = { it.deviceId }) { follower ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Dot(
                                    when {
                                        follower.missingSong -> MaterialTheme.colorScheme.error
                                        follower.deviceId in lost -> Color(0xFFE0AF68)
                                        !follower.following -> MaterialTheme.colorScheme.outline
                                        else -> MaterialTheme.colorScheme.secondary
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(follower.deviceName, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        when {
                                            follower.missingSong -> "Does not have this chart"
                                            !follower.following -> "Reading on their own"
                                            follower.deviceId in lost -> "On page ${follower.page + 1}"
                                            else -> "In step"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }

                Row(Modifier.padding(16.dp)) {
                    OutlinedButton(onClick = { coordinator.stop() }) { Text("End the session") }
                }
            }

            SessionRole.FOLLOWER -> Column(Modifier.fillMaxSize()) {
                SectionLabel("You are following")
                val state = followerState

                ChartSharingPanel(
                    sharing = sharing,
                    onAccept = { coordinator.acceptCharts() },
                    onDecline = { coordinator.declineCharts() },
                )

                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(
                            when (state?.link) {
                                LinkState.CONNECTED -> MaterialTheme.colorScheme.secondary
                                LinkState.RECONNECTING -> Color(0xFFE0AF68)
                                else -> MaterialTheme.colorScheme.outline
                            },
                        )
                        Text(
                            when (state?.link) {
                                LinkState.CONNECTED -> "Connected"
                                LinkState.RECONNECTING -> "Trying to reconnect"
                                else -> "Not connected"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (state?.mode == FollowMode.DETACHED) Pill("on your own")
                    }

                    // The reassurance that makes the feature usable: say out loud
                    // that losing the network does not stop them working.
                    Text(
                        if (state?.link == LinkState.CONNECTED) {
                            "Page turns are coming from the leader. Turn a page yourself and " +
                                "you will take over; tap Rejoin to fall back in with them."
                        } else {
                            "You are turning your own pages. This device will pick the leader " +
                                "back up on its own when the network comes back, and will ask " +
                                "before jumping you to their page."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state?.canRejoin == true) {
                            Button(onClick = { coordinator.rejoin() }) { Text("Rejoin") }
                        }
                        OutlinedButton(onClick = { coordinator.stop() }) { Text("Leave") }
                    }
                }
            }
        }
    }
}

/**
 * The charts the leader has that this device has not.
 *
 * Asked once. The first offer of a session raises the question and everything
 * after it is taken on that answer - a second set list, a reconnection, a song
 * added halfway through. Being asked again between songs is worse than either
 * answer, and this is a screen somebody looks at while a room waits.
 *
 * What is shown before agreeing is the count and the weight, because "three
 * charts" and "three charts, 60 MB" are different questions on a phone at a
 * venue with somebody else's wifi.
 */
@Composable
private fun ChartSharingPanel(
    sharing: ChartSharing,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val pending = sharing.pending
    val active = sharing.active
    val failed = sharing.failed

    if (pending.isEmpty() && active.isEmpty() && failed.isEmpty() && sharing.refused.isEmpty()) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (pending.isNotEmpty() && !sharing.answered) {
            val charts = if (pending.size == 1) "1 chart" else "${pending.size} charts"
            val weight = ChartShare.megabytes(pending.sumOf { it.sizeBytes })
            Text(
                "The leader has $charts you have not got ($weight).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                pending.take(4).joinToString(", ") { it.title } +
                    if (pending.size > 4) " and ${pending.size - 4} more" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text("Get them") }
                OutlinedButton(onClick = onDecline) { Text("Not now") }
            }
        }

        for (transfer in active) {
            Text(
                "Fetching ${transfer.offer.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A chart whose size nobody declared gets an indeterminate bar rather
            // than a bar stuck at nothing.
            if (transfer.offer.sizeBytes > 0) {
                LinearProgressIndicator(
                    progress = { transfer.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        if (sharing.arrived > 0 && active.isEmpty()) {
            Text(
                "${sharing.arrived} arrived.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Named rather than dropped. A chart that silently did not arrive is the
        // failure this whole thing exists to prevent, and finding out on stage is
        // a different evening from being told at a soundcheck.
        for (transfer in failed) {
            Text(
                "${transfer.offer.title}: ${transfer.failed}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        for (refusal in sharing.refused) {
            Text(
                "${refusal.offer.title}: ${refusal.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Which running orders tonight is being played from.
 *
 * More than one on purpose: two sets with a break in the middle is the ordinary
 * shape of a gig, and making that two sessions would mean everybody rejoining
 * during the interval.
 */
@Composable
private fun ChooseSetlistsDialog(
    setlists: List<Setlist>,
    onDismiss: () -> Unit,
    onStart: (List<Setlist>) -> Unit,
) {
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What are you playing?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Everyone who joins gets these without being sent a file, and Backstage " +
                        "checks they can open every chart before you start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (setlist in setlists) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                chosen = if (setlist.id in chosen) {
                                    chosen - setlist.id
                                } else {
                                    chosen + setlist.id
                                }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = setlist.id in chosen, onCheckedChange = null)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(setlist.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(
                                    "${setlist.size} songs",
                                    setlist.venue,
                                    setlist.date,
                                ).joinToString(" - "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen.isNotEmpty(),
                onClick = { onStart(setlists.filter { it.id in chosen }) },
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
