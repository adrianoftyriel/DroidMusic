package org.droidmusic.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.SharedFlow
import org.droidmusic.app.data.AppSettings
import org.droidmusic.app.input.FootSwitchMap
import org.droidmusic.app.input.PageAction
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.Pill
import org.droidmusic.app.ui.common.SectionLabel
import org.droidmusic.app.ui.common.SettingRow

/**
 * Teaching the app what a pedal sends.
 *
 * There is no standard for what a page-turner pedal transmits, so no list of
 * defaults will ever cover every device. The reliable answer is to stop guessing
 * and watch: the player presses the switch, the app sees whatever key code
 * arrives, and binds it. That works for a pedal nobody has heard of, and for a
 * Bluetooth remote intended for something else entirely.
 */
@Composable
fun FootSwitchSetupScreen(
    settings: AppSettings,
    pedalEvents: SharedFlow<PageAction>,
    rawKeys: SharedFlow<Int>,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var learning by remember { mutableStateOf<PageAction?>(null) }
    var lastAction by remember { mutableStateOf<PageAction?>(null) }

    // While learning, the next key code to arrive from anywhere is the answer.
    // Collecting the raw stream rather than the mapped actions is the point: a
    // pedal sending something the app does not recognise yet produces no action
    // at all, and that is exactly the pedal this screen exists for.
    LaunchedEffect(learning) {
        val target = learning ?: return@LaunchedEffect
        rawKeys.collect { code ->
            onChange { it.copy(footSwitch = it.footSwitch.bind(code, target)) }
            // Setting this to null re-keys the effect, which cancels this
            // collection - one press binds one key.
            learning = null
        }
    }

    LaunchedEffect(Unit) {
        pedalEvents.collect { lastAction = it }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Header(title = "Foot switch", onBack = onBack)

        Text(
            "Bluetooth and USB pedals both appear to Android as keyboards, so there is nothing " +
                "to pair inside this app - pair the pedal in Android's Bluetooth settings, or " +
                "plug it in, and it will work here. Most pedals are already covered by the " +
                "defaults. If yours is not, use Learn and press the switch.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )

        if (learning != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Press the switch you want for " +
                        when (learning) {
                            PageAction.NEXT_PAGE -> "the next page"
                            PageAction.PREVIOUS_PAGE -> "the previous page"
                            PageAction.NEXT_SONG -> "the next song"
                            PageAction.PREVIOUS_SONG -> "the previous song"
                            else -> "this action"
                        },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        SectionLabel("Bindings")

        Binding(
            label = "Next page",
            keys = settings.footSwitch.next,
            onLearn = { learning = PageAction.NEXT_PAGE },
        )
        Binding(
            label = "Previous page",
            keys = settings.footSwitch.previous,
            onLearn = { learning = PageAction.PREVIOUS_PAGE },
        )
        Binding(
            label = "Next song",
            keys = settings.footSwitch.nextSong,
            onLearn = { learning = PageAction.NEXT_SONG },
        )
        Binding(
            label = "Previous song",
            keys = settings.footSwitch.previousSong,
            onLearn = { learning = PageAction.PREVIOUS_SONG },
        )

        SettingRow(
            title = "Allow the volume keys",
            subtitle = "Some pedals send volume up and down. Turning this on stops the volume " +
                "rocker working while a chart is open.",
            trailing = {
                Switch(
                    checked = settings.footSwitch.allowVolumeKeys,
                    onCheckedChange = { on ->
                        onChange { it.copy(footSwitch = it.footSwitch.copy(allowVolumeKeys = on)) }
                    },
                )
            },
        )

        SectionLabel("Test")
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Press a switch:", style = MaterialTheme.typography.bodyMedium)
            Pill(
                text = when (lastAction) {
                    PageAction.NEXT_PAGE -> "next page"
                    PageAction.PREVIOUS_PAGE -> "previous page"
                    PageAction.NEXT_SONG -> "next song"
                    PageAction.PREVIOUS_SONG -> "previous song"
                    null -> "nothing yet"
                    else -> "ignored"
                },
                background = MaterialTheme.colorScheme.primaryContainer,
                foreground = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onChange { it.copy(footSwitch = FootSwitchMap()) } },
            ) { Text("Reset to defaults") }
        }
    }
}

@Composable
private fun Binding(label: String, keys: Set<Int>, onLearn: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (keys.isEmpty()) {
                    "Not bound"
                } else {
                    keys.joinToString(", ") { FootSwitchMap.describe(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onLearn) { Text("Learn") }
    }
}
