package org.droidmusic.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.droidmusic.app.data.DocumentSources
import org.droidmusic.app.ui.common.ChoicePill
import org.droidmusic.app.ui.common.EmptyState
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.HeaderAction
import org.droidmusic.app.ui.common.Pill
import org.droidmusic.app.ui.common.SectionLabel
import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.SongRef
import org.droidmusic.library.SourceKind
import org.droidmusic.library.SourceRef
import org.droidmusic.music.Key

/**
 * The library: every chart the app can reach, from every folder it has been
 * pointed at.
 *
 * Sources are listed but not made into a hierarchy to browse. A player looking
 * for a chart wants the chart, not to remember which of five folders it is in,
 * so the default view is one flat searchable list and the folders are a filter.
 *
 * Adding and removing those folders both live behind one header action rather
 * than being split between an add button here and a remove somewhere in
 * settings. "Where my charts come from" is a single question, and the answer is
 * a single list you can add a row to or take one away from.
 */
@Composable
fun LibraryScreen(
    controller: LibraryController,
    onOpenSong: (SongRef) -> Unit,
    onAddSongToSetlist: (SongRef) -> Unit,
    onAddSongsToSetlist: (List<SongRef>) -> Unit,
    onEditSong: (SongRef) -> Unit,
    onNewFromUrl: () -> Unit,
    onNewFromText: () -> Unit,
    onNewBlank: () -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit,
) {
    val index by controller.index.collectAsState()
    var query by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var showSources by remember { mutableStateOf(false) }
    var showNewChart by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SongRef?>(null) }
    var deleting by remember { mutableStateOf<SongRef?>(null) }
    var transposing by remember { mutableStateOf<SongRef?>(null) }

    // What a bulk action is about. Resolved from the selection at the moment
    // the action is chosen, so a dialog cannot be left pointing at a chart that
    // has since been dropped from the selection underneath it.
    var transposingMany by remember { mutableStateOf<List<SongRef>>(emptyList()) }
    var removingMany by remember { mutableStateOf<List<SongRef>>(emptyList()) }

    val addFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            controller.addTree(uri)
        }
    }

    val addFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        val uris = buildList {
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
            }
            data.data?.let { add(it) }
        }
        if (uris.isNotEmpty()) controller.addFiles(uris)
    }

    // A folder that has just been removed must not go on filtering the list, or
    // the library looks empty and there is no pill left to tap to get it back.
    val activeFilter = sourceFilter?.takeIf { id -> index.sources.any { it.id == id } }

    // `visible` rather than `songs`: a chart the user removed from the library is
    // still in the index, so that a rescan cannot put it back, and must not be
    // listed.
    val songs = remember(index, query, activeFilter) {
        controller.filter(index.visible, query, activeFilter)
    }

    transposing?.let { song ->
        TransposeDialog(
            song = song,
            onConfirm = { semitones, capo ->
                controller.setTranspose(song, semitones, capo)
                transposing = null
            },
            onDismiss = { transposing = null },
        )
    }

    renaming?.let { song ->
        RenameDialog(
            song = song,
            onConfirm = { name ->
                controller.rename(song, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { song ->
        ConfirmDeleteFileDialog(
            song = song,
            onConfirm = {
                controller.deleteFile(song)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }

    if (transposingMany.isNotEmpty()) {
        val subjects = transposingMany
        BulkTransposeDialog(
            songs = subjects,
            onConfirm = { semitones, capo ->
                controller.setTransposeAll(subjects, semitones, capo)
                transposingMany = emptyList()
            },
            onDismiss = { transposingMany = emptyList() },
        )
    }

    if (removingMany.isNotEmpty()) {
        val subjects = removingMany
        ConfirmRemoveManyDialog(
            count = subjects.size,
            onConfirm = {
                controller.removeFromLibraryAll(subjects)
                removingMany = emptyList()
            },
            onDismiss = { removingMany = emptyList() },
        )
    }

    if (showSources) {
        SourcesDialog(
            index = index,
            controller = controller,
            onAddFolder = { addFolder.launch(DocumentSources.pickTreeIntent()) },
            onAddFiles = { addFiles.launch(DocumentSources.pickFilesIntent()) },
            onDismiss = { showSources = false },
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (controller.selecting) {
            Header(
                title = if (controller.selection.isEmpty()) {
                    "Select charts"
                } else {
                    "${controller.selection.size} selected"
                },
                subtitle = "Tap to pick, tap again to drop",
                onBack = { controller.stopSelecting() },
                actions = {
                    TextButton(
                        onClick = { controller.selectAll(songs.map { it.id }) },
                    ) { Text("All") }
                    TextButton(onClick = { controller.stopSelecting() }) { Text("Done") }
                },
            )
        } else {
            Header(
                title = "Library",
                subtitle = "${index.visible.size} charts in ${index.sources.size} places",
                onBack = onBack,
                actions = {
                    Box {
                        HeaderAction(Icons.Filled.Add, "New chart") { showNewChart = true }
                        NewChartMenu(
                            expanded = showNewChart,
                            onDismiss = { showNewChart = false },
                            onFromUrl = { showNewChart = false; onNewFromUrl() },
                            onFromText = { showNewChart = false; onNewFromText() },
                            onBlank = { showNewChart = false; onNewBlank() },
                            onScan = { showNewChart = false; onScan() },
                        )
                    }
                    HeaderAction(Icons.Filled.Checklist, "Bulk edit") {
                        controller.startSelecting()
                    }
                    HeaderAction(Icons.Filled.Refresh, "Rescan") { controller.rescanAll() }
                    HeaderAction(Icons.Filled.Folder, "Folders and files") { showSources = true }
                },
            )
        }

        if (controller.scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                controller.scanStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // Errors were being set and never shown, which is the worst of both: the
        // app knows exactly why the folder did not work and says nothing.
        controller.lastError?.let { message ->
            ErrorBanner(message) { controller.dismissError() }
        }

        controller.removed?.let { song ->
            RemovedBanner(
                song = song,
                onUndo = { controller.restore(song) },
                onDismiss = { controller.dismissRemoved() },
            )
        }

        // The bulk action bar, deliberately the same actions the per-chart menu
        // offers. A player who learns that holding a chart offers "transpose"
        // should not find that selecting forty of them offers something else.
        // Edit is the one exception, and it is context-menu only because
        // editing forty charts at once is not a thing anybody means.
        if (controller.selecting) {
            BulkActionBar(
                enabled = controller.selection.isNotEmpty(),
                onTranspose = { transposingMany = controller.selectedSongs() },
                onAddToSetlist = { onAddSongsToSetlist(controller.selectedSongs()) },
                onRemove = { removingMany = controller.selectedSongs() },
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search titles, artists and keys") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )

        if (index.sources.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChoicePill(
                    text = "All",
                    selected = activeFilter == null,
                    onClick = { sourceFilter = null },
                )
                for (source in index.sources) {
                    ChoicePill(
                        text = source.label,
                        selected = activeFilter == source.id,
                        onClick = { sourceFilter = source.id },
                    )
                }
            }
        }

        HorizontalDivider()

        if (index.sources.isEmpty()) {
            EmptyState(
                title = "No charts yet",
                body = "Point DroidMusic at a folder. Anything the system file picker can " +
                    "reach works - a folder on this device, or one in Google Drive, Dropbox, " +
                    "Box, Proton Drive or anything else that shows up there.\n\n" +
                    "Some services, OneDrive among them, offer their files to the picker but " +
                    "not their folders. Pick the charts individually for those.",
                action = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = { addFolder.launch(DocumentSources.pickTreeIntent()) }) {
                            Text("Choose a folder")
                        }
                        TextButton(onClick = { addFiles.launch(DocumentSources.pickFilesIntent()) }) {
                            Text("Or pick individual files")
                        }
                        TextButton(onClick = onScan) { Text("Or photograph a page") }
                    }
                },
            )
        } else if (songs.isEmpty()) {
            EmptyState(
                title = if (query.isBlank()) "Nothing here" else "No match",
                body = if (query.isBlank()) {
                    "That folder has no PDFs, images, Word documents or chord charts in it."
                } else {
                    "Nothing matches \"$query\"."
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    // Where the gesture gets discovered. A press and hold that
                    // nobody knows about is not a feature.
                    SectionLabel(
                        if (controller.selecting) {
                            "${controller.selection.size} of ${songs.size} selected"
                        } else {
                            "${songs.size} charts - hold one for set lists, editing and more"
                        },
                    )
                }
                items(songs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        sourceLabel = controller.sourceLabel(index, song.sourceId),
                        canDeleteFile = { controller.canDeleteFile(song) },
                        selecting = controller.selecting,
                        selected = song.id in controller.selection,
                        // While a selection is running both gestures mean the
                        // same thing. A tap that opened a chart in the middle of
                        // picking forty would lose the selection.
                        onClick = {
                            if (controller.selecting) {
                                controller.toggleSelected(song.id)
                            } else {
                                onOpenSong(song)
                            }
                        },
                        onSelect = { controller.toggleSelected(song.id) },
                        onAddToSetlist = { onAddSongToSetlist(song) },
                        onTranspose = { transposing = song },
                        onEdit = { onEditSong(song) },
                        onRename = { renaming = song },
                        onRemove = { controller.removeFromLibrary(song) },
                        onDeleteFile = { deleting = song },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

/**
 * Everywhere the library gets its charts from, with a way to add one and a way
 * to stop using one.
 *
 * The note about providers that offer no folders is here rather than in a help
 * page because this is the screen somebody is on at the exact moment they are
 * wondering where OneDrive went.
 */
@Composable
private fun SourcesDialog(
    index: LibraryIndex,
    controller: LibraryController,
    onAddFolder: () -> Unit,
    onAddFiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<SourceRef?>(null) }

    pendingRemoval?.let { source ->
        ConfirmRemoveDialog(
            source = source,
            chartCount = index.songsFrom(source.id).size,
            onConfirm = {
                controller.removeSource(source.id)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where charts come from") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (index.sources.isEmpty()) {
                    Text(
                        "Nothing added yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                for (source in index.sources) {
                    SourceRow(
                        label = source.label,
                        summary = controller.sourceSummary(index, source),
                        onRemove = { pendingRemoval = source },
                    )
                }

                // The way back for a chart removed from the library after the
                // undo banner has gone. It lives here because this dialog is
                // already the answer to "what is my library made of", and a
                // removed chart is part of that answer.
                if (index.hiddenCount > 0) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val charts =
                            if (index.hiddenCount == 1) "1 chart" else "${index.hiddenCount} charts"
                        Text(
                            "$charts removed from the library. The files are still there.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { controller.restoreAllRemoved() }) {
                            Text("Put back")
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 10.dp))

                Text(
                    "Not every service offers its folders to Android's folder picker - " +
                        "OneDrive is the one people run into. It is still there under " +
                        "\"Add files\", and charts added that way work exactly the same.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onAddFolder) { Text("Add a folder") }
                    TextButton(onClick = onAddFiles) { Text("Add files") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SourceRow(label: String, summary: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Remove $label",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The confirmation, which exists to say the one thing the user actually needs to
 * know: their files are not being deleted.
 *
 * "Remove" next to a folder full of somebody's sheet music is frightening
 * without that sentence, and a library nobody dares tidy is the reason this was
 * missing in the first place.
 */
@Composable
private fun ConfirmRemoveDialog(
    source: SourceRef,
    chartCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val charts = if (chartCount == 1) "1 chart" else "$chartCount charts"
    val whereTheyLive = when (source.kind) {
        SourceKind.EXTERNAL_TREE ->
            "The folder and its files are not touched - they stay in " +
                "${DocumentSources.providerLabel(source.authority)}."
        SourceKind.EXTERNAL_FILE ->
            "The files themselves are not touched - they stay where you picked them from."
        SourceKind.MANAGED ->
            "The copies DroidMusic made on this device are deleted; the originals are not."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${source.label}?") },
        text = {
            Text(
                "DroidMusic will stop listing its $charts. $whereTheyLive\n\n" +
                    "Any set list entry pointing at one of them will show as missing until " +
                    "you add it back.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Choosing the key a chart is played in.
 *
 * The same two rows the viewer offers - every key rather than up and down by a
 * semitone, because a singer asks for a key by name and not by an interval from
 * wherever the chart happened to be written - except that here the choice is
 * remembered rather than lasting until the chart is closed.
 *
 * There is no separate reset. The pill for the chart's own key is the reset, and
 * it is where the selection already is for a chart nobody has changed.
 */
@Composable
private fun TransposeDialog(
    song: SongRef,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var semitones by remember(song.id) { mutableStateOf(song.userTransposeSemitones) }
    var capo by remember(song.id) { mutableStateOf(song.userCapo) }
    val written = song.key

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transpose") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    written?.let { "Written in ${it.display()}." }
                        ?: "The key of this chart could not be worked out, so these are " +
                        "semitones from however it was written.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Key", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (step in -5..6) {
                        ChoicePill(
                            text = written?.transposedTo(step)?.toUnicode() ?: signed(step),
                            selected = semitones == step,
                            onClick = { semitones = step },
                        )
                    }
                }

                Text("Capo", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (fret in 0..7) {
                        ChoicePill(
                            text = if (fret == 0) "off" else "$fret",
                            selected = capo == fret,
                            onClick = { capo = fret },
                        )
                    }
                }

                Text(
                    transposeSummary(written, semitones, capo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(semitones, capo) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun signed(step: Int): String = if (step > 0) "+$step" else "$step"

/**
 * What the choice actually means, in the terms a player would use: what it will
 * sound like, and - once a capo is on - what their hands will be doing, which is
 * a different key and the one they need to read.
 */
private fun transposeSummary(written: Key?, semitones: Int, capo: Int): String {
    if (semitones == 0 && capo == 0) return "Opens in the key it was written in."
    val sounding = written?.transposedTo(semitones)?.toUnicode()
        ?: "${signed(semitones)} semitones"
    if (capo == 0) return "Opens sounding $sounding, from now on."
    val played = written?.transposedTo(semitones - capo)?.toUnicode()
    return if (played == null) {
        "Opens sounding $sounding with a capo at $capo, from now on."
    } else {
        "Opens sounding $sounding - capo $capo, fingered in $played - from now on."
    }
}

/**
 * Renaming a chart, for DroidMusic's purposes.
 *
 * The dialog says plainly that the file is not being renamed, because the
 * alternative is somebody renaming forty charts and then wondering why their
 * Drive folder still shows the old names. Clearing the field is how a rename is
 * undone - there is no separate "reset" to find, and an empty box asking to be
 * filled reads as "what should this be called" either way.
 */
@Composable
private fun RenameDialog(
    song: SongRef,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(song.id) { mutableStateOf(song.bestTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name in DroidMusic") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The file is not renamed - it stays as ${song.displayName}. " +
                        "Clear the box to go back to calling it \"${song.detectedTitle}\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The confirmation for the one action here that cannot be undone.
 *
 * It names the file rather than the song, because the file is what is going. A
 * chart whose title and filename differ - which is most ChordPro - would
 * otherwise be confirmed by a name that does not appear anywhere on disk.
 */
@Composable
private fun ConfirmDeleteFileDialog(
    song: SongRef,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${song.displayName}?") },
        text = {
            Text(
                "This deletes the file from this device. It cannot be undone, and any set " +
                    "list entry pointing at it will show as missing.\n\n" +
                    "To keep the file and only stop DroidMusic listing it, use " +
                    "\"Remove from library\" instead.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The offer to undo a removal.
 *
 * Not a nicety. The chart disappears from the list the instant it is removed, so
 * the menu that removed it is no longer reachable - without this the only way
 * back would be the folder list, which is not where anybody would look. The
 * durable path is there too, for the ones dismissed rather than undone.
 */
@Composable
private fun RemovedBanner(song: SongRef, onUndo: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Removed ${song.bestTitle}. The file is still there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUndo) { Text("Undo") }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

/**
 * One chart in the list.
 *
 * A tap opens it and a press and hold offers what can be done to it, which is
 * the pairing every list on a phone already uses. Filing into a set list is the
 * first item because it is the one done twenty times in a row, at the point where
 * the player is looking at the chart and thinking "yes, that one".
 *
 * "Delete file" is absent rather than disabled when the file cannot be deleted.
 * A greyed-out row invites a tap and then explains itself; an absent one says
 * the same thing without the detour. What it would have said - that DroidMusic
 * has read access to that folder and no more - is in the confirmation for the
 * charts it *can* delete, and in docs/FORMATS.md.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: SongRef,
    sourceLabel: String,
    canDeleteFile: () -> Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onAddToSetlist: () -> Unit,
    onTranspose: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onDeleteFile: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Asked once, when the menu opens, and off the main thread. Answering it can
    // mean asking a document provider - which for a cloud folder is a network
    // call - and neither the composition nor a library of four hundred rows
    // should be waiting on that. Until the answer arrives the item is absent,
    // which is also what it will be for most charts.
    var canDelete by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) {
        canDelete = menuOpen && withContext(Dispatchers.IO) { canDeleteFile() }
    }

    Box {
        SongMenu(
            expanded = menuOpen,
            canDeleteFile = canDelete,
            // A PDF is a picture of a page. There is nothing in one to rewrite,
            // so it is not offered a key.
            canTranspose = song.isTransposable,
            // Only a chart made of characters has anything to edit. A PDF gets
            // Rename, which is the whole of what can be changed about it.
            canEdit = song.isTransposable,
            onDismiss = { menuOpen = false },
            onAddToSetlist = {
                menuOpen = false
                onAddToSetlist()
            },
            onTranspose = {
                menuOpen = false
                onTranspose()
            },
            onEdit = {
                menuOpen = false
                onEdit()
            },
            onRename = {
                menuOpen = false
                onRename()
            },
            onRemove = {
                menuOpen = false
                onRemove()
            },
            onDeleteFile = {
                menuOpen = false
                onDeleteFile()
            },
        )
        SongRowBody(
            song = song,
            sourceLabel = sourceLabel,
            selecting = selecting,
            selected = selected,
            onClick = onClick,
            // A press and hold picks rather than opening the menu once a
            // selection is running: the menu's actions are all on the bar.
            onLongClick = { if (selecting) onSelect() else menuOpen = true },
        )
    }
}

@Composable
private fun SongMenu(
    expanded: Boolean,
    canDeleteFile: Boolean,
    canTranspose: Boolean,
    canEdit: Boolean,
    onDismiss: () -> Unit,
    onAddToSetlist: () -> Unit,
    onTranspose: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onDeleteFile: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Add to a set list") }, onClick = onAddToSetlist)
        if (canTranspose) {
            DropdownMenuItem(text = { Text("Transpose\u2026") }, onClick = onTranspose)
        }
        if (canEdit) {
            DropdownMenuItem(text = { Text("Edit the chart\u2026") }, onClick = onEdit)
        }
        DropdownMenuItem(text = { Text("Rename\u2026") }, onClick = onRename)
        DropdownMenuItem(text = { Text("Remove from library") }, onClick = onRemove)
        if (canDeleteFile) {
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text("Delete file\u2026", color = MaterialTheme.colorScheme.error)
                },
                onClick = onDeleteFile,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRowBody(
    song: SongRef,
    sourceLabel: String,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
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
                onLongClick = onLongClick,
                onLongClickLabel = if (selecting) {
                    "Select this chart"
                } else {
                    "What to do with this chart"
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(
                checked = selected,
                // The whole row is the target and already announces itself; a
                // checkbox that also took clicks would give a screen reader two
                // controls doing one job.
                onCheckedChange = null,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.bestTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            val detail = listOfNotNull(
                song.artist,
                sourceLabel,
                song.kind.name.lowercase(),
                // Only when it is not the obvious. A chart played as written has
                // nothing to say here, and a row that spells out "capo 0" for
                // four hundred charts is four hundred rows of noise.
                song.userCapo.takeIf { it > 0 }?.let { "capo $it" },
            ).joinToString(" - ")
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // The key the chart will open in, not the key it was written in. Those
        // are the same thing until somebody chooses otherwise, and once they
        // have, the written key is not the useful one to put in front of them.
        song.soundingKey?.let { sounding ->
            if (song.isTransposed) {
                Pill(
                    sounding.toUnicode(),
                    background = MaterialTheme.colorScheme.primary,
                    foreground = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Pill(sounding.toUnicode())
            }
        }
        Box(Modifier.padding(start = 8.dp)) {
            if (song.isTransposable) {
                Pill(
                    "transposable",
                    background = MaterialTheme.colorScheme.primaryContainer,
                    foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * The four ways a chart gets into the library that do not involve a folder.
 *
 * Scanning is here rather than beside the folder button because photographing a
 * page produces a new chart, which is what this menu is for. Where the app looks
 * for charts that already exist is a different question with its own button.
 */
@Composable
private fun NewChartMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onFromUrl: () -> Unit,
    onFromText: () -> Unit,
    onBlank: () -> Unit,
    onScan: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Import from URL\u2026") }, onClick = onFromUrl)
        DropdownMenuItem(text = { Text("Import from text\u2026") }, onClick = onFromText)
        DropdownMenuItem(text = { Text("Blank song") }, onClick = onBlank)
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Photograph a page") }, onClick = onScan)
    }
}

/**
 * What a selection can have done to it.
 *
 * Buttons rather than a menu, because with a selection already made the next tap
 * should be the action itself. Deleting files is deliberately absent: it is
 * irreversible, it applies to only some charts, and offering it as one tap over
 * forty of them is how somebody loses a folder of scans.
 */
@Composable
private fun BulkActionBar(
    enabled: Boolean,
    onTranspose: () -> Unit,
    onAddToSetlist: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(enabled = enabled, onClick = onAddToSetlist) { Text("Add to set list") }
        TextButton(enabled = enabled, onClick = onTranspose) { Text("Transpose") }
        Box(Modifier.weight(1f))
        TextButton(enabled = enabled, onClick = onRemove) { Text("Remove") }
    }
}

/**
 * Setting the key a selection of charts opens in.
 *
 * An absolute value rather than a nudge. "Everything in this set goes up two"
 * is not a thing anybody means; "these are the ones we play in D" is - and a
 * relative shift applied to forty charts already in different keys produces
 * forty different wrong answers.
 */
@Composable
private fun BulkTransposeDialog(
    songs: List<SongRef>,
    onDismiss: () -> Unit,
    onConfirm: (semitones: Int, capo: Int) -> Unit,
) {
    var semitones by remember { mutableStateOf(0) }
    var capo by remember { mutableStateOf(0) }
    val transposable = songs.count { it.isTransposable }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transpose ${songs.size} charts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Transpose: ${signed(semitones)} semitones",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = semitones.toFloat(),
                    onValueChange = { semitones = it.toInt() },
                    valueRange = -11f..11f,
                    steps = 21,
                )
                Text(
                    "Capo: ${if (capo == 0) "none" else "fret $capo"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = capo.toFloat(),
                    onValueChange = { capo = it.toInt() },
                    valueRange = 0f..11f,
                    steps = 10,
                )
                // Said, not prevented. A PDF among forty charts is not a reason
                // to refuse the whole action.
                if (transposable < songs.size) {
                    Text(
                        "${songs.size - transposable} of these are scans or PDFs. There is " +
                            "nothing in a picture of a page to rewrite, so they are left " +
                            "as they are.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = transposable > 0,
                onClick = { onConfirm(semitones, capo) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The bulk remove confirmation.
 *
 * Leads with the fact that nothing is deleted, because "remove 40 charts" next
 * to somebody's whole library is frightening without that sentence - and it is
 * undoable, which is the other thing worth saying before they decide.
 */
@Composable
private fun ConfirmRemoveManyDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove $count charts from the library?") },
        text = {
            Text(
                "DroidMusic stops listing them. No files are deleted - they stay wherever " +
                    "they came from, and Settings has a way to put them all back.\n\n" +
                    "Any set list entry pointing at one of them will show as missing.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
