package org.droidmusic.app.ui.setlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.droidmusic.app.ui.common.EmptyState
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.HeaderAction
import org.droidmusic.app.ui.common.Pill
import org.droidmusic.library.Setlist
import org.droidmusic.library.SongRef

@Composable
fun SetlistsScreen(
    controller: SetlistController,
    onBack: () -> Unit,
    onOpen: (Setlist) -> Unit,
) {
    val book by controller.book.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Header(
            title = "Set lists",
            subtitle = "${book.setlists.size} saved",
            onBack = onBack,
            actions = {
                HeaderAction(Icons.Filled.Add, "New set list") {
                    newName = ""
                    creating = true
                }
            },
        )

        controller.importMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp),
            )
        }

        if (book.setlists.isEmpty()) {
            EmptyState(
                title = "No set lists yet",
                body = "A set list is the running order for one night, with each song's key " +
                    "and capo saved against it. You can send one to the rest of the band as a " +
                    "file, or push it to everyone at once from a session.",
                action = {
                    Button(onClick = { newName = ""; creating = true }) { Text("New set list") }
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(book.setlists, key = { _, it -> it.id }) { _, setlist ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(setlist) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                setlist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
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
                        HeaderAction(Icons.Filled.Share, "Send") { controller.export(setlist) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New set list") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        controller.create(newName.trim())
                        creating = false
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

/**
 * One set list, in the order it will be played.
 *
 * Reordering is by up and down buttons rather than drag and drop. Drag looks
 * better in a demo and is worse here: this gets used on a phone balanced on an
 * amp, ten minutes before a set, and a mis-drag that silently moves song four to
 * position eleven is a problem nobody notices until they are on stage.
 */
@Composable
fun SetlistDetailScreen(
    setlist: Setlist,
    controller: SetlistController,
    songFor: (String) -> SongRef?,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onAddSongs: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Header(
            title = setlist.name,
            subtitle = "${setlist.size} songs",
            onBack = onBack,
            actions = {
                HeaderAction(Icons.Filled.Add, "Add songs", onAddSongs)
                HeaderAction(Icons.Filled.Share, "Send") { controller.export(setlist) }
                HeaderAction(Icons.Filled.Delete, "Delete") { controller.delete(setlist.id) }
            },
        )

        if (setlist.entries.isEmpty()) {
            EmptyState(
                title = "Empty set list",
                body = "Add charts from the library to build the running order.",
                action = { Button(onClick = onAddSongs) { Text("Add songs") } },
            )
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { onPlay(0) }) { Text("Start the set") }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(setlist.entries, key = { index, e -> "$index-${e.songId}" }) { index, entry ->
                val song = songFor(entry.songId)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(index) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                        )
                        val detail = buildList {
                            entry.artist?.let { add(it) }
                            if (entry.transposeSemitones != 0) {
                                val sign = if (entry.transposeSemitones > 0) "+" else ""
                                add("transposed $sign${entry.transposeSemitones}")
                            }
                            if (entry.capo > 0) add("capo ${entry.capo}")
                            entry.note?.let { add(it) }
                            if (song == null) add("not in this library")
                        }.joinToString(" - ")
                        if (detail.isNotEmpty()) {
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (song == null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                        }
                    }
                    song?.key?.let { Pill(it.toUnicode()) }
                    HeaderAction(Icons.Filled.KeyboardArrowUp, "Move up") {
                        controller.move(setlist, index, index - 1)
                    }
                    HeaderAction(Icons.Filled.KeyboardArrowDown, "Move down") {
                        controller.move(setlist, index, index + 1)
                    }
                    HeaderAction(Icons.Filled.PlayArrow, "Open") { onPlay(index) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}
