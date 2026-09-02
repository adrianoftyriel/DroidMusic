package org.droidmusic.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.HeaderAction
import org.droidmusic.app.ui.common.SectionLabel
import org.droidmusic.library.SongRef

/**
 * Writing a chart, or fixing one.
 *
 * A plain text box and nothing else - no chord palette, no formatting toolbar,
 * no preview pane. ChordPro is already the editing interface: `[Am]` above the
 * word it belongs over is both what you type and what it means, and a toolbar
 * that inserted brackets would be slower than the keyboard for anybody who has
 * typed two charts.
 *
 * Monospaced, because a chart typed here is read by the same layout engine that
 * aligns chords over syllables, and typing it in a proportional font would mean
 * everything lining up on screen and nothing lining up when it opens.
 *
 * There is no autosave. A chart half-typed on a bus is not something to write
 * over a good copy, so leaving without saving asks first and then throws the
 * edit away.
 */
@Composable
fun SongEditorScreen(
    existing: SongRef?,
    seedText: String,
    seedTitle: String,
    saving: Boolean,
    error: String?,
    onSave: (title: String, text: String) -> Unit,
    onBack: () -> Unit,
) {
    var text by remember(existing?.id, seedText) { mutableStateOf(seedText) }
    var title by remember(existing?.id, seedTitle) {
        mutableStateOf(existing?.bestTitle ?: seedTitle)
    }
    var confirmDiscard by remember { mutableStateOf(false) }

    val startingText = remember(existing?.id, seedText) { seedText }
    val startingTitle = remember(existing?.id, seedTitle) { existing?.bestTitle ?: seedTitle }
    val dirty = text != startingText || title != startingTitle

    // A chart with nothing in it is not worth a file. The title alone is not
    // enough either: a named empty chart opens as a blank page on a stand and
    // reads as the app having lost it.
    val canSave = text.isNotBlank() && !saving

    fun leave() {
        if (dirty) confirmDiscard = true else onBack()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Header(
            title = if (existing == null) "New chart" else "Edit chart",
            subtitle = when {
                saving -> "Saving"
                dirty -> "Unsaved"
                existing != null -> existing.displayName
                else -> "ChordPro"
            },
            onBack = { leave() },
            actions = {
                if (saving) {
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    }
                } else {
                    HeaderAction(Icons.Filled.Check, "Save") {
                        if (canSave) onSave(title.trim(), text)
                    }
                }
            },
        )

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        SectionLabel("The chart")
        Text(
            "Chords in square brackets go above the letter they land on: " +
                "[Am]Yesterday. Lines like {title: ...}, {key: C} and {capo: 2} are read " +
                "and the rest is treated as lyrics.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // BasicTextField rather than OutlinedTextField: this is the whole rest
        // of the screen, and a Material text field with a floating label and a
        // container inset is the wrong shape for a page of text.
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Throw this away?") },
            text = {
                Text(
                    if (existing == null) {
                        "This chart has not been saved, and nothing else has a copy of it."
                    } else {
                        "The changes since you opened it will be lost. The saved chart " +
                            "stays as it was."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onBack()
                    },
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}

/** Pasting a chart in from somewhere else, on the way to the editor. */
@Composable
fun ImportTextDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Paste a chart from anywhere - a message, a web page, an email. It " +
                        "opens in the editor before it is saved, so you can tidy it first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Chart text") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    modifier = Modifier.height(200.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onImport(text) },
            ) { Text("Open in editor") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Fetching a chart from a web address. */
@Composable
fun ImportUrlDialog(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onFetch: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        // Not dismissable mid-fetch: the download would carry on with nowhere
        // to land, and the chart would appear a moment after the screen said
        // nothing was happening.
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Import from URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Web address") },
                    placeholder = { Text("example.com/wichita-lineman.cho") },
                    singleLine = true,
                    enabled = !busy,
                )
                Text(
                    "An Ultimate Guitar link is converted and filed straight away. " +
                        "Anything else is fetched and opened in the editor first, so you " +
                        "can see what actually came back before it is saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (busy) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Text("Fetching", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && !busy,
                onClick = { onFetch(url) },
            ) { Text("Fetch") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
