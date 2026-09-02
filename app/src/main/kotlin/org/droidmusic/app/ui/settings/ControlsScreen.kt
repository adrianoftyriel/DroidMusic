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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.droidmusic.app.data.AppSettings
import org.droidmusic.app.ui.common.ChoicePill
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.SectionLabel
import org.droidmusic.app.ui.common.SettingRow
import org.droidmusic.app.ui.viewer.PageMode
import org.droidmusic.app.ui.viewer.TapZoneConfig

/**
 * Everything about what a hand does to the page.
 *
 * Split out of Settings because these are set once, carefully, on a table - and
 * then never touched again. Leaving them inline meant the settings somebody does
 * change (the theme, the screen lock, what the band calls them) sat below four
 * sliders they had already finished with.
 */
@Composable
fun ControlsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Header(title = "Controls", onBack = onBack)

        Column(Modifier.verticalScroll(rememberScrollState())) {

            SectionLabel("Turning pages")

            SettingRow(
                title = "Tap to turn",
                subtitle = "Right side forward, left side back. Turn this off if you only " +
                    "want to use a foot switch - the top of the screen still opens the menu.",
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
                title = "Double tap to zoom",
                subtitle = "On a scan or a PDF, double tapping crops the margins away and " +
                    "fills the screen with the music. Double tap again for the whole page. " +
                    "The cost: where this is possible, a tap has to wait to see whether a " +
                    "second one is coming, so turning the page by tapping is a little slower. " +
                    "A foot switch is never affected.",
                trailing = {
                    Switch(
                        checked = settings.viewer.doubleTapToZoom,
                        onCheckedChange = { on ->
                            onChange { it.copy(viewer = it.viewer.copy(doubleTapToZoom = on)) }
                        },
                    )
                },
            )
            Box(Modifier.height(32.dp))
        }
    }
}

/**
 * A scale drawing of the screen, showing where a tap does what.
 *
 * The menu band across the top is drawn to scale as well, and is drawn even
 * though it cannot be changed - what it is doing here is telling somebody who
 * has just turned tap-to-turn off that there is still a way back out of a
 * full-screen chart.
 */
@Composable
private fun TapZonePreview(settings: AppSettings) {
    val zones = settings.viewer.tapZones
    val backFirst = !zones.mirrored

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(84.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(TapZoneConfig.MENU_BAND_FRACTION)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Menu",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        Row(Modifier.fillMaxWidth().weight(1f - TapZoneConfig.MENU_BAND_FRACTION)) {
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
}
