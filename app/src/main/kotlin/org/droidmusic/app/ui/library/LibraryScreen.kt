package org.droidmusic.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import org.droidmusic.library.SongRef

/**
 * The library: every chart the app can reach, from every folder it has been
 * pointed at.
 *
 * Sources are listed but not made into a hierarchy to browse. A player looking
 * for a chart wants the chart, not to remember which of five folders it is in,
 * so the default view is one flat searchable list and the folders are a filter.
 */
@Composable
fun LibraryScreen(
    controller: LibraryController,
    onOpenSong: (SongRef) -> Unit,
    onAddSongToSetlist: (SongRef) -> Unit,
    onOpenSetlists: () -> Unit,
    onOpenSession: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val index by controller.index.collectAsState()
    var query by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf<String?>(null) }

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

    val songs = remember(index, query, sourceFilter) {
        controller.filter(index.songs, query, sourceFilter)
    }

    Column(Modifier.fillMaxSize()) {
        Header(
            title = "Library",
            subtitle = "${index.songs.size} charts in ${index.sources.size} places",
            actions = {
                HeaderAction(Icons.Filled.Refresh, "Rescan") { controller.rescanAll() }
                HeaderAction(Icons.Filled.Add, "Add a folder") {
                    addFolder.launch(DocumentSources.pickTreeIntent())
                }
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
                    selected = sourceFilter == null,
                    onClick = { sourceFilter = null },
                )
                for (source in index.sources) {
                    org.droidmusic.app.ui.common.ChoicePill(
                        text = source.label,
                        selected = sourceFilter == source.id,
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
                    "reach works - a folder on this device, or one in Google Drive, OneDrive, " +
                    "Dropbox, Box, Proton Drive or anything else that shows up there.",
                action = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = { addFolder.launch(DocumentSources.pickTreeIntent()) }) {
                            Text("Choose a folder")
                        }
                        TextButton(onClick = { addFiles.launch(DocumentSources.pickFilesIntent()) }) {
                            Text("Or pick individual files")
                        }
                    }
                },
            )
        } else if (songs.isEmpty()) {
            EmptyState(
                title = if (query.isBlank()) "Nothing here" else "No match",
                body = if (query.isBlank()) {
                    "That folder has no PDFs, images or chord charts in it."
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
