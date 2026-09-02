package org.droidmusic.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.droidmusic.app.data.AppSettings
import org.droidmusic.app.data.ThemeChoice
import org.droidmusic.app.input.FootSwitchMap
import org.droidmusic.app.ui.common.ChoicePill
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.SectionLabel
import org.droidmusic.app.ui.common.SettingRow

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onOpenFootSwitchSetup: () -> Unit,
    onOpenControls: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBack: () -> Unit,
    versionName: String,
    releaseTag: String?,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Header(title = "Settings", onBack = onBack)

        Column(Modifier.verticalScroll(rememberScrollState())) {

            SectionLabel("This device")
            OutlinedTextField(
                value = settings.deviceName,
                onValueChange = { name -> onChange { it.copy(deviceName = name) } },
                label = { Text("Name other players will see") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            SectionLabel("Theme")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChoicePill(
                    text = "Dark",
                    selected = settings.theme == ThemeChoice.DARK,
                    onClick = { onChange { it.copy(theme = ThemeChoice.DARK) } },
                )
                ChoicePill(
                    text = "Light",
                    selected = settings.theme == ThemeChoice.LIGHT,
                    onClick = { onChange { it.copy(theme = ThemeChoice.LIGHT) } },
                )
                ChoicePill(
                    text = "Follow the phone",
                    selected = settings.theme == ThemeChoice.SYSTEM,
                    onClick = { onChange { it.copy(theme = ThemeChoice.SYSTEM) } },
                )
            }
            Text(
                "Dark by default, because a bright screen on a stand is a light pointed " +
                    "at the audience.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SettingRow(
                title = "Dark chart background",
                subtitle = "Black page, light text. Kinder on a dark stage; harder to read " +
                    "a scanned PDF on.",
                trailing = {
                    Switch(
                        checked = settings.viewer.darkChart,
                        onCheckedChange = { on ->
                            onChange { it.copy(viewer = it.viewer.copy(darkChart = on)) }
                        },
                    )
                },
            )

            SettingRow(
                title = "Proper sharp and flat signs",
                subtitle = "Show B♭ rather than Bb.",
                trailing = {
                    Switch(
                        checked = settings.viewer.unicodeAccidentals,
                        onCheckedChange = { on ->
                            onChange { it.copy(viewer = it.viewer.copy(unicodeAccidentals = on)) }
                        },
                    )
                },
            )

            SectionLabel("Playing")

            SettingRow(
                title = "Foot switch",
                subtitle = footSwitchSummary(settings.footSwitch),
                onClick = onOpenFootSwitchSetup,
            )

            SettingRow(
                title = "Controls",
                subtitle = "Where a tap turns the page, how many pages are shown, and how " +
                    "large the chart text is.",
                onClick = onOpenControls,
            )

            SettingRow(
                title = "Keep awake",
                subtitle = "A phone that sleeps mid-song is worse than useless.",
                trailing = {
                    Switch(
                        checked = settings.viewer.keepScreenOn,
                        onCheckedChange = { on ->
                            onChange { it.copy(viewer = it.viewer.copy(keepScreenOn = on)) }
                        },
                    )
                },
            )

            SectionLabel("Library")

            SettingRow(
                title = "Read chord charts when scanning",
                subtitle = "Picks up the title, artist and key so search and transposition " +
                    "work. Turn off if scanning a very large folder is slow.",
                trailing = {
                    Switch(
                        checked = settings.indexChartContents,
                        onCheckedChange = { on -> onChange { it.copy(indexChartContents = on) } },
                    )
                },
            )

            SectionLabel("When something goes wrong")

            SettingRow(
                title = "Diagnostics",
                subtitle = "What the app did: who joined the session, what was sent, what " +
                    "would not open. Kept in memory for the last few hundred lines, and sent " +
                    "nowhere unless you send it.",
                onClick = onOpenDiagnostics,
            )

            SectionLabel("Updates")

            SettingRow(
                title = "Check for updates",
                subtitle = "Fetches the newest build from GitHub and installs it. Nothing is " +
                    "checked or downloaded until you ask for it.",
                onClick = onOpenUpdates,
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(
                releaseTag?.let { "DroidMusic $versionName - release $it" }
                    ?: "DroidMusic $versionName - built from source",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Box(Modifier.height(32.dp))
        }
    }
}

internal fun footSwitchSummary(map: FootSwitchMap): String {
    val forward = map.next.take(3).joinToString(", ") { FootSwitchMap.describe(it) }
    val back = map.previous.take(3).joinToString(", ") { FootSwitchMap.describe(it) }
    return "Forward: $forward. Back: $back."
}
