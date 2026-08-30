package org.droidmusic.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import org.droidmusic.app.data.DocumentSources
import org.droidmusic.app.ui.common.EmptyState
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.HeaderAction
import org.droidmusic.app.ui.common.Pill
import org.droidmusic.app.ui.common.SectionLabel
import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.SongRef
import org.droidmusic.library.SourceKind
import org.droidmusic.library.SourceRef

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
    onOpenSetlists: () -> Unit,
    onOpenSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onScan: () -> Unit,
) {
    val index by controller.index.collectAsState()
    var query by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var showSources by remember { mutableStateOf(false) }

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

    val songs = remember(index, query, activeFilter) {
        controller.filter(index.songs, query, activeFilter)
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
        Header(
            title = "Library",
            subtitle = "${index.songs.size} charts in ${index.sources.size} places",
            actions = {
                HeaderAction(Icons.Filled.PhotoCamera, "Scan music", onScan)
                HeaderAction(Icons.Filled.Refresh, "Rescan") { controller.rescanAll() }
                HeaderAction(Icons.Filled.Folder, "Folders and files") { showSources = true }
                HeaderAction(Icons.Filled.Settings, "Settings", onOpenSettings)
            },
        )

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

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onOpenSetlists) { Text("Set lists") }
            TextButton(onClick = onOpenSession) { Text("Session") }
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
                org.droidmusic.app.ui.common.ChoicePill(
                    text = "All",
                    selected = activeFilter == null,
                    onClick = { sourceFilter = null },
                )
                for (source in index.sources) {
                    org.droidmusic.app.ui.common.ChoicePill(
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
                    SectionLabel("${songs.size} charts - hold one to add it to a set list")
                }
                items(songs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        sourceLabel = controller.sourceLabel(index, song.sourceId),
                        onClick = { onOpenSong(song) },
                        onLongClick = { onAddSongToSetlist(song) },
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
 * A tap opens it and a press and hold files it into a set list, which is the
 * pairing every list on a phone already uses. Building tonight's running order
 * is otherwise a trip to another screen and back for each of twenty songs, and
 * it is done at the point where the player is looking at the chart and thinking
 * "yes, that one" - so that is where it should be possible.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: SongRef,
    sourceLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "Add to a set list",
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            ).joinToString(" - ")
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        song.key?.let { Pill(it.toUnicode()) }
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
