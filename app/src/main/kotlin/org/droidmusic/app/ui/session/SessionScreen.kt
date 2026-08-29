package org.droidmusic.app.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import org.droidmusic.session.FollowMode
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
    deviceName: String,
    onBack: () -> Unit,
) {
    val role by coordinator.role.collectAsState()
    val leaderState by coordinator.leaderState.collectAsState()
    val followerState by coordinator.followerState.collectAsState()
    val message by coordinator.message.collectAsState()
    val sessionLabel by coordinator.sessionLabel.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var sessionName by remember { mutableStateOf("") }

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

    Column(Modifier.fillMaxSize()) {
        Header(
            title = "Band session",
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
            SessionRole.NONE -> Column(Modifier.fillMaxSize()) {
                SectionLabel("Lead the band")
                Text(
                    "Everyone who joins follows your page turns. If someone loses the " +
                        "network they carry on with their own, and pick you back up when it " +
                        "returns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = sessionName,
                        onValueChange = { sessionName = it },
                        label = { Text("Session name") },
                        placeholder = { Text(deviceName) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                coordinator.startLeading(sessionName.ifBlank { deviceName })
                            }
                        },
                    ) { Text("Start") }
                }

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
