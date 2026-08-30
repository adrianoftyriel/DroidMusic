package org.droidmusic.app.ui.setlist

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.droidmusic.app.ui.common.EmptyState
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.HeaderAction
import org.droidmusic.app.ui.common.Pill
import org.droidmusic.app.ui.common.dragToReorder
import org.droidmusic.app.ui.common.rememberDragReorderState
import org.droidmusic.library.Setlist
import org.droidmusic.library.SetlistEntry
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

    // The other half of "send them to other devices": opening one somebody
    // sent. The manifest also catches a set list tapped in a mail client, but
    // that only fires when the sending app labels it as JSON - plenty do not,
    // so there has to be a way in from this screen as well.
    val importFile = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) controller.import(uri)
    }

    Column(Modifier.fillMaxSize()) {
        Header(
            title = "Set lists",
            subtitle = "${book.setlists.size} saved",
            onBack = onBack,
            actions = {
                HeaderAction(Icons.Filled.Download, "Open a set list someone sent") {
                    importFile.launch(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(
                                Intent.EXTRA_MIME_TYPES,
                                arrayOf("application/json", "text/plain", "application/octet-stream"),
                            )
                        },
                    )
                }
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
 * There are two ways to move a song, and both are here on purpose. Press, hold
 * and drag is what a hand reaches for, and it is the only sane way to move the
 * last song of the night up to third. The up and down buttons stay because a
 * drag on a phone balanced on an amp ten minutes before a set is easy to get
 * wrong, and because a drag is invisible to a screen reader - so the buttons are
 * both the careful path and the accessible one.
 *
 * A drag is committed when the finger lifts, not on every row it crosses. Each
 * save is a whole-file write, and writing the running order thirty times during
 * one drag would be both slow and a good way to leave a half-written set list
 * behind if the app dies mid-gesture.
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
    // The order under the finger. The saved one comes back through a file write
    // and a flow, which cannot keep up with a drag, so while one is in flight
    // this screen shows its own copy and then goes back to following the store.
    var pendingOrder by remember(setlist.id) { mutableStateOf<List<SetlistEntry>?>(null) }
    val entries = pendingOrder ?: setlist.entries

    // The gesture detector on a row is set up once and then keeps whatever it
    // closed over, so the set list it saves has to be read through state at the
    // moment the finger lifts rather than captured when the row was composed.
    val currentSetlist by rememberUpdatedState(setlist)

    val listState = rememberLazyListState()
    val dragState = rememberDragReorderState(listState) { from, to ->
        val current = pendingOrder ?: setlist.entries
        if (from in current.indices && to in current.indices) {
            pendingOrder = current.toMutableList().apply { add(to, removeAt(from)) }
        }
    }

    LaunchedEffect(setlist.entries) {
        if (pendingOrder == setlist.entries) pendingOrder = null
    }

    // Stable across a reorder, unlike the position, so a row that moves is the
    // same row to the list rather than a new one appearing where it landed.
    val rowKeys = remember(entries) {
        val seen = mutableMapOf<String, Int>()
        entries.map { entry ->
            val nth = (seen[entry.songId] ?: 0) + 1
            seen[entry.songId] = nth
            "${entry.songId}#$nth"
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(
            title = setlist.name,
            subtitle = "${entries.size} songs",
            onBack = onBack,
            actions = {
                HeaderAction(Icons.Filled.Add, "Add songs", onAddSongs)
                HeaderAction(Icons.Filled.Share, "Send") { controller.export(setlist) }
                HeaderAction(Icons.Filled.Delete, "Delete") { controller.delete(setlist.id) }
            },
        )

        if (entries.isEmpty()) {
            EmptyState(
                title = "Empty set list",
                body = "Add charts from the library to build the running order. Press and " +
                    "hold a song there to file it straight into this list.",
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

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(entries, key = { index, _ -> rowKeys[index] }) { index, entry ->
                val song = songFor(entry.songId)
                val dragging = dragState.draggingIndex == index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .dragToReorder(dragState, index, rowKeys[index]) {
                            val order = pendingOrder
                            val saved = currentSetlist
                            when {
                                order == null -> Unit
                                order == saved.entries -> pendingOrder = null
                                else -> controller.save(saved.copy(entries = order))
                            }
                        }
                        // Opaque only while it is in the air, so a lifted row
                        // hides the ones sliding under it and an ordinary row
                        // still sits on the screen's own colour.
                        .background(
                            if (dragging) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable {
                            // Not while a drag is being saved: the positions on
                            // screen and the positions in the stored list are
                            // the same again a moment later, and opening the
                            // wrong song is not a moment anyone wants.
                            val held = dragState.consumeSuppressedClick()
                            if (!held && pendingOrder == null) onPlay(index)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.DragHandle,
                        // The row itself is the drag target, and the buttons
                        // beside it already say "move up" and "move down" out
                        // loud; announcing a third control here would only be
                        // one more thing to swipe past.
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp, end = 10.dp),
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
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

/**
 * Files one chart into a set list, from a long press in the library.
 *
 * This is deliberately the whole of the flow: the list of set lists, the way to
 * make a new one, and no step in between. Adding a song is something that
 * happens forty times in an evening while the running order is being worked out,
 * and a flow that starts by asking which screen you would like to go to first
 * does not survive that.
 */
@Composable
fun AddToSetlistDialog(
    song: SongRef,
    controller: SetlistController,
    onDismiss: () -> Unit,
    onAdded: (Setlist) -> Unit,
) {
    val book by controller.book.collectAsState()
    // With nothing to add to, the choice is not a choice; go straight to making
    // the first set list.
    var naming by remember { mutableStateOf(book.setlists.isEmpty()) }
    var newName by remember { mutableStateOf("") }

    if (naming) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("New set list") },
            text = {
                Column {
                    Text(
                        "\"${song.bestTitle}\" goes in as the first song.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        onAdded(controller.create(newName.trim(), listOf(song)))
                        onDismiss()
                    },
                ) { Text("Create and add") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to a set list") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    song.bestTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                for (setlist in book.setlists) {
                    // Said, not prevented. A song that comes back in the encore
                    // is in the set twice, and that is the band's call.
                    val already = setlist.entries.count { it.songId == song.id }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                controller.add(setlist, song)
                                onAdded(setlist)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(setlist.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(
                                    "${setlist.size} songs",
                                    setlist.venue,
                                    if (already > 0) "already in this list" else null,
                                ).joinToString(" - "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    newName = ""
                    naming = true
                },
            ) { Text("New set list") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
