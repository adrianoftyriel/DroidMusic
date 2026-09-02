package org.droidmusic.app.ui.setlist

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import org.droidmusic.app.ui.common.SectionLabel
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

    // The set list a press and hold was on, and the one being edited. Held
    // apart so the edit dialog survives the menu closing under it.
    var editing by remember { mutableStateOf<Setlist?>(null) }
    var deleting by remember { mutableStateOf<List<Setlist>>(emptyList()) }

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
        if (controller.selecting) {
            Header(
                title = if (controller.selection.isEmpty()) {
                    "Select set lists"
                } else {
                    "${controller.selection.size} selected"
                },
                subtitle = "Tap to pick, tap again to drop",
                onBack = { controller.stopSelecting() },
                actions = {
                    TextButton(
                        onClick = { controller.selectAll(book.setlists.map { it.id }) },
                    ) { Text("All") }
                    TextButton(onClick = { controller.stopSelecting() }) { Text("Done") }
                },
            )
        } else {
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
                                    arrayOf(
                                        "application/json",
                                        "text/plain",
                                        "application/octet-stream",
                                    ),
                                )
                            },
                        )
                    }
                    HeaderAction(Icons.Filled.Checklist, "Bulk edit") {
                        controller.startSelecting()
                    }
                    HeaderAction(Icons.Filled.Add, "New set list") {
                        newName = ""
                        creating = true
                    }
                },
            )
        }

        // Only Delete. It is the one thing that means something done to several
        // running orders at once - renaming forty is not a thing, and neither
        // is sending them as one file.
        if (controller.selecting) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = controller.selection.isNotEmpty(),
                    onClick = { deleting = controller.selectedSetlists() },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }

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
                item {
                    SectionLabel(
                        if (controller.selecting) {
                            "${controller.selection.size} of ${book.setlists.size} selected"
                        } else {
                            "hold one for what you can do to it"
                        },
                    )
                }
                itemsIndexed(book.setlists, key = { _, it -> it.id }) { _, setlist ->
                    SetlistRow(
                        setlist = setlist,
                        selecting = controller.selecting,
                        selected = setlist.id in controller.selection,
                        onClick = {
                            if (controller.selecting) {
                                controller.toggleSelected(setlist.id)
                            } else {
                                onOpen(setlist)
                            }
                        },
                        onSelect = { controller.toggleSelected(setlist.id) },
                        onEdit = { editing = setlist },
                        onSend = { controller.export(setlist) },
                        onDelete = { deleting = listOf(setlist) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    editing?.let { setlist ->
        EditSetlistDialog(
            setlist = setlist,
            onDismiss = { editing = null },
            onSave = { name, venue, date ->
                controller.setDetails(setlist, name, venue, date)
                editing = null
            },
        )
    }

    if (deleting.isNotEmpty()) {
        val subjects = deleting
        AlertDialog(
            onDismissRequest = { deleting = emptyList() },
            title = {
                Text(
                    subjects.singleOrNull()?.let { "Delete \"${it.name}\"?" }
                        ?: "Delete ${subjects.size} set lists?",
                )
            },
            text = {
                Text(
                    "The running " + (if (subjects.size == 1) "order goes" else "orders go") +
                        ", along with the key and capo saved against each song in " +
                        (if (subjects.size == 1) "it" else "them") +
                        ". No charts are deleted - they stay in the library.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.deleteAll(subjects.map { it.id }.toSet())
                        deleting = emptyList()
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = emptyList() }) { Text("Cancel") }
            },
        )
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
 * One set list in the list of them.
 *
 * A tap opens it, a press and hold offers what can be done to it. Once a
 * selection is running both mean the same thing - pick or drop - because a tap
 * that opened a running order in the middle of selecting five would lose the
 * selection.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SetlistRow(
    setlist: Setlist,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Open the running order") },
                onClick = { menuOpen = false; onClick() },
            )
            DropdownMenuItem(
                text = { Text("Edit name, venue and date\u2026") },
                onClick = { menuOpen = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text("Send to someone") },
                onClick = { menuOpen = false; onSend() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete\u2026", color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; onDelete() },
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (selecting) onSelect() else menuOpen = true },
                    onLongClickLabel = if (selecting) {
                        "Select this set list"
                    } else {
                        "What to do with this set list"
                    },
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
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
            // Hidden while selecting: a send button inside a row being tapped to
            // select it is a way to share the wrong set list by accident.
            if (!selecting) {
                HeaderAction(Icons.Filled.Share, "Send", onSend)
            }
        }
    }
}

/**
 * What a set list is called, where it is being played and when.
 *
 * Venue and date are free text on purpose. A date picker would insist on a
 * calendar date, and half of these say "Friday" or "the second night".
 */
@Composable
private fun EditSetlistDialog(
    setlist: Setlist,
    onDismiss: () -> Unit,
    onSave: (name: String, venue: String, date: String) -> Unit,
) {
    var name by remember(setlist.id) { mutableStateOf(setlist.name) }
    var venue by remember(setlist.id) { mutableStateOf(setlist.venue.orEmpty()) }
    var date by remember(setlist.id) { mutableStateOf(setlist.date.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit set list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = venue,
                    onValueChange = { venue = it },
                    label = { Text("Venue") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Date") },
                    placeholder = { Text("Friday, or 14 March") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), venue.trim(), date.trim()) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
    onStartSet: () -> Unit,
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

        // Starting the set goes backstage first, rather than straight into the
        // first song. It is one tap in the way, once a night, in exchange for
        // finding out that the bass player's copy of song four is missing while
        // there is still time to send it.
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartSet) { Text("Start the set") }
                TextButton(onClick = { onPlay(0) }) { Text("Straight to song one") }
            }
            Text(
                "Backstage checks every chart in this list opens - on this device, and on " +
                    "everyone else's if you are leading a session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
 * Files charts into a set list, from a press and hold or a selection in the
 * library.
 *
 * This is deliberately the whole of the flow: the list of set lists, the way to
 * make a new one, and no step in between. Adding a song is something that
 * happens forty times in an evening while the running order is being worked out,
 * and a flow that starts by asking which screen you would like to go to first
 * does not survive that.
 *
 * One chart or forty changes the wording and nothing else. A selection goes in
 * in the order the library was showing it, which is alphabetical - not a running
 * order, but a starting point that can be dragged into one.
 */
@Composable
fun AddToSetlistDialog(
    songs: List<SongRef>,
    controller: SetlistController,
    onDismiss: () -> Unit,
    onAdded: (Setlist) -> Unit,
) {
    val what = songs.singleOrNull()?.bestTitle ?: "${songs.size} charts"
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
                        if (songs.size == 1) {
                            "\"$what\" goes in as the first song."
                        } else {
                            "$what go in, in the order they are listed."
                        },
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
                        onAdded(controller.create(newName.trim(), songs))
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
                    what,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                for (setlist in book.setlists) {
                    // Said, not prevented. A song that comes back in the encore
                    // is in the set twice, and that is the band's call.
                    val already = songs.count { song ->
                        setlist.entries.any { it.songId == song.id }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                controller.addAll(setlist, songs)
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
                                    when {
                                        already == 0 -> null
                                        songs.size == 1 -> "already in this list"
                                        else -> "$already already in this list"
                                    },
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
