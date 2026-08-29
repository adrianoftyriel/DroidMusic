package org.droidmusic.app.ui.library

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.droidmusic.app.data.DocumentSources
import org.droidmusic.app.data.LibraryRepository
import org.droidmusic.app.data.SettingsRepository
import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.SongRef
import org.droidmusic.library.SourceKind
import org.droidmusic.library.SourceRef
import org.droidmusic.library.normaliseForMatching

/** Drives adding, scanning and searching the library. */
class LibraryController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
    private val settings: SettingsRepository,
) {
    val index: StateFlow<LibraryIndex> get() = repository.index

    var scanning by mutableStateOf(false)
        private set
    var scanStatus by mutableStateOf("")
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    fun addTree(treeUri: Uri) {
        scope.launch {
            if (!DocumentSources.persistPermission(context, treeUri)) {
                lastError = "Android would not grant lasting access to that folder. " +
                    "Try picking it again from the file picker."
                return@launch
            }
            val source = DocumentSources.sourceFromTree(context, treeUri, null)
            repository.addSource(source)
            scan(source)
        }
    }

    /**
     * Adds files picked one at a time.
     *
     * Grouped under a single synthetic source rather than one source per file,
     * so that picking forty scans does not produce forty entries in the filter
     * row.
     */
    fun addFiles(uris: List<Uri>) {
        scope.launch {
            scanning = true
            scanStatus = "Adding ${uris.size} files"

            val source = index.value.sources.firstOrNull { it.kind == SourceKind.EXTERNAL_FILE }
                ?: SourceRef(
                    id = UUID.randomUUID().toString(),
                    kind = SourceKind.EXTERNAL_FILE,
                    uri = "",
                    label = "Picked files",
                    addedAt = System.currentTimeMillis(),
                ).also { repository.addSource(it) }

            val added = uris.mapNotNull { uri ->
                DocumentSources.persistPermission(context, uri)
                val name = displayName(uri) ?: return@mapNotNull null
                val kind = SongRef.kindOf(name, context.contentResolver.getType(uri))
                if (kind == org.droidmusic.library.FileKind.UNKNOWN) return@mapNotNull null
                SongRef(
                    id = DocumentSources.stableId(source.id, uri.toString()),
                    sourceId = source.id,
                    uri = uri.toString(),
                    displayName = name,
                    kind = kind,
                )
            }

            val existing = index.value.songsFrom(source.id)
            val merged = (existing + added).distinctBy { it.uri }
            val enriched = DocumentSources.enrich(
                context,
                merged,
                settings.settings.value.indexChartContents,
            )
            repository.replaceSongsFrom(source.id, enriched, System.currentTimeMillis())

            scanning = false
            scanStatus = ""
        }
    }

    fun rescanAll() {
        scope.launch {
            for (source in index.value.sources) {
                if (source.kind == SourceKind.EXTERNAL_TREE) scan(source)
            }
        }
    }

    fun removeSource(sourceId: String) {
        scope.launch { repository.removeSource(sourceId) }
    }

    private suspend fun scan(source: SourceRef) {
        scanning = true
        scanStatus = "Reading ${source.label}"

        val found = DocumentSources.scanTree(context, source)
        scanStatus = "Found ${found.size} charts, reading the text ones"

        val enriched = DocumentSources.enrich(
            context,
            found,
            settings.settings.value.indexChartContents,
        )
        repository.replaceSongsFrom(source.id, enriched, System.currentTimeMillis())

        scanning = false
        scanStatus = ""
        if (found.isEmpty()) {
            lastError = "Nothing readable in ${source.label}. DroidMusic opens PDFs, images, " +
                "and chord charts in .txt, .cho, .pro, .crd and .tab files."
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    fun sourceLabel(sourceId: String): String {
        val source = index.value.sources.firstOrNull { it.id == sourceId } ?: return "Unknown"
        return if (source.kind == SourceKind.EXTERNAL_TREE) {
            "${source.label} (${DocumentSources.providerLabel(source.authority)})"
        } else {
            source.label
        }
    }

    /**
     * Search across the fields a player would actually search by. Matching is
     * done on the same normalised form used to reconcile shared set lists, so
     * searching finds the same things a set list import would.
     */
    fun filter(songs: List<SongRef>, query: String, sourceId: String?): List<SongRef> {
        val scoped = if (sourceId == null) songs else songs.filter { it.sourceId == sourceId }
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            scoped
        } else {
            val needle = trimmed.normaliseForMatching()
            scoped.filter { song ->
                song.bestTitle.normaliseForMatching().contains(needle) ||
                    song.artist?.normaliseForMatching()?.contains(needle) == true ||
                    song.key?.toString()?.lowercase() == trimmed.lowercase() ||
                    song.displayName.normaliseForMatching().contains(needle)
            }
        }
        return filtered.sortedBy { it.bestTitle.lowercase() }
    }
}
