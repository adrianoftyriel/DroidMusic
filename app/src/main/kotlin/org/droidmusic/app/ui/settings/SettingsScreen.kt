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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.droidmusic.app.data.AppSettings
import org.droidmusic.app.input.FootSwitchMap
import org.droidmusic.app.ui.common.ChoicePill
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.SectionLabel
import org.droidmusic.app.ui.common.SettingRow
import org.droidmusic.app.ui.viewer.PageMode

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onOpenFootSwitchSetup: () -> Unit,
    onBack: () -> Unit,
    versionName: String,
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

            SectionLabel("Turning pages")

            SettingRow(
                title = "Tap to turn",
                subtitle = "Right side forward, left side back. Turn this off if you only " +
                    "want to use a foot switch.",
                trailing = {
                    Switch(
                        checked = settings.viewer.tapZones.tapToTurnEnabled,
                        onCheckedChange = { on ->
                            onChange {
                                it.copy(
                                    viewer = it.viewer.copy(
                                        tapZones = it.viewer.tapZones.copy(tapToTurnEnabled = on),
                                    ),
                                )
                            }
                        },
                    )
                },
            )

            SettingRow(
                title = "Swap the sides",
                subtitle = "Back on the right instead of the left.",
                trailing = {
                    Switch(
                        checked = settings.viewer.tapZones.mirrored,
                        onCheckedChange = { on ->
                            onChange {
                                it.copy(
                                    viewer = it.viewer.copy(
                                        tapZones = it.viewer.tapZones.copy(mirrored = on),
                                    ),
                                )
                            }
                        },
                    )
                },
            )

            // Shown as a picture rather than a number, because "back zone: 0.33"
            // means nothing and a diagram of the screen means everything.
            TapZonePreview(settings)

            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "Back zone: ${(settings.viewer.tapZones.backFraction * 100).toInt()}% " +
                        "of the width",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.viewer.tapZones.backFraction,
                    onValueChange = { fraction ->
                        onChange {
                            it.copy(
                                viewer = it.viewer.copy(
                                    tapZones = it.viewer.tapZones.copy(backFraction = fraction),
                                ),
                            )
                        }
                    },
                    valueRange = 0.15f..0.5f,
                )
            }

            SettingRow(
                title = "Foot switch",
                subtitle = footSwitchSummary(settings.footSwitch),
                onClick = onOpenFootSwitchSetup,
            )

            SectionLabel("The page")

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Pages shown", style = MaterialTheme.typography.bodyLarge)
                Box(Modifier.weight(1f))
                ChoicePill(
                    text = "Automatic",
                    selected = settings.viewer.pageModeOverride == null,
                    onClick = {
                        onChange { it.copy(viewer = it.viewer.copy(pageModeOverride = null)) }
                    },
                )
                ChoicePill(
                    text = "One",
                    selected = settings.viewer.pageModeOverride == PageMode.SINGLE,
                    onClick = {
                        onChange {
                            it.copy(viewer = it.viewer.copy(pageModeOverride = PageMode.SINGLE))
                        }
                    },
                )
                ChoicePill(
                    text = "Two",
                    selected = settings.viewer.pageModeOverride == PageMode.SPREAD,
                    onClick = {
                        onChange {
                            it.copy(viewer = it.viewer.copy(pageModeOverride = PageMode.SPREAD))
                        }
                    },
                )
            }
            Text(
                "Automatic shows two pages in landscape on a screen wide enough to read them, " +
                    "and one everywhere else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Chart text size: ${(settings.viewer.chartFontScale * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.viewer.chartFontScale,
                    onValueChange = { scale ->
                        onChange { it.copy(viewer = it.viewer.copy(chartFontScale = scale)) }
                    },
                    valueRange = 0.6f..2.2f,
                )
            }

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
                title = "Keep the screen on",
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

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(
                "DroidMusic $versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Box(Modifier.height(32.dp))
        }
    }
}

/** A scale drawing of the screen, showing where a tap does what. */
@Composable
private fun TapZonePreview(settings: AppSettings) {
    val zones = settings.viewer.tapZones
    val backFirst = !zones.mirrored

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(64.dp),
    ) {
        val backWeight = zones.backFraction.coerceIn(0.05f, 0.95f)
        val zoneA = if (backFirst) "Back" else "Forward"
        val zoneB = if (backFirst) "Forward" else "Back"

        Box(
            Modifier
                .weight(backWeight)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(zoneA, style = MaterialTheme.typography.labelMedium)
        }
        Box(
            Modifier
                .weight(1f - backWeight)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                zoneB,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun footSwitchSummary(map: FootSwitchMap): String {
    val forward = map.next.take(3).joinToString(", ") { FootSwitchMap.describe(it) }
    val back = map.previous.take(3).joinToString(", ") { FootSwitchMap.describe(it) }
    return "Forward: $forward. Back: $back."
}
