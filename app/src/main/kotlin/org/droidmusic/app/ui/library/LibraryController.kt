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
import org.droidmusic.library.FileKind
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
     * Grouped by the provider they came from rather than one source per file, so
     * that picking forty scans does not produce forty entries in the filter row.
     *
     * By *provider* and not into one bucket, because picking files one at a time
     * is how a provider that offers no folders gets used at all - see
     * [DocumentSources.pickTreeIntent]. Somebody who reaches OneDrive this way
     * ends up with "OneDrive files" beside their folders, which they can filter
     * by and, when it comes to it, remove in one go.
     */
    fun addFiles(uris: List<Uri>) {
        scope.launch {
            scanning = true
            scanStatus = if (uris.size == 1) "Adding a file" else "Adding ${uris.size} files"
            lastError = null
            // Named, not counted. Somebody who picked eleven files and got ten
            // needs to know which one is missing, and a number does not say.
            val skipped = mutableListOf<String>()

            for ((_, picked) in uris.groupBy { DocumentSources.pickedFilesLabel(it.authority) }) {
                val source = fileSourceFor(picked.first().authority)
                val added = picked.mapNotNull { uri ->
                    DocumentSources.persistPermission(context, uri)
                    val name = displayName(uri) ?: uri.lastPathSegment ?: "that file"
                    val kind = DocumentSources.kindOfPickedFile(
                        resolver = context.contentResolver,
                        uri = uri,
                        displayName = name,
                        mimeType = context.contentResolver.getType(uri),
                    )
                    if (kind == FileKind.UNKNOWN) {
                        skipped += name
                        return@mapNotNull null
                    }
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
            }

            scanning = false
            scanStatus = ""

            // A file that was asked for by name and then quietly did not appear
            // is the worst outcome here: there is nothing on screen to explain
            // it and nothing to try next.
            if (skipped.isNotEmpty()) {
                lastError = buildString {
                    append(if (skipped.size == 1) "Could not read " else "Could not read these: ")
                    append(skipped.take(5).joinToString(", "))
                    if (skipped.size > 5) append(", and ${skipped.size - 5} more")
                    append(". DroidMusic opens PDFs, images, Word documents and any chart ")
                    append("saved as text - ChordPro included, whatever it is called.")
                }
            }
        }
    }

    /**
     * The bucket picked files land in, made if it is not there yet.
     *
     * Matched on the label rather than on the authority, so that files picked
     * from two different local providers - Downloads and internal storage, say -
     * do not produce two pills both saying "Picked files". It also means a
     * library written by an older build, where everything went into one
     * unattributed bucket, keeps using that bucket instead of stranding it.
     */
    private suspend fun fileSourceFor(authority: String?): SourceRef {
        val label = DocumentSources.pickedFilesLabel(authority)
        index.value.sources
            .firstOrNull { it.kind == SourceKind.EXTERNAL_FILE && it.label == label }
            ?.let { return it }

        val source = SourceRef(
            id = UUID.randomUUID().toString(),
            kind = SourceKind.EXTERNAL_FILE,
            uri = "",
            label = label,
            authority = authority,
            addedAt = System.currentTimeMillis(),
        )
        repository.addSource(source)
        return source
    }

    fun rescanAll() {
        scope.launch {
            for (source in index.value.sources) {
                if (source.kind == SourceKind.EXTERNAL_TREE) scan(source)
            }
        }
    }

    /**
     * Forgets a folder, or a bucket of picked files, and everything indexed from
     * it.
     *
     * The files are not touched. They belong to somebody's Drive or their
     * Downloads folder, and the app being told to stop listing them is not the
     * same instruction as delete them - which is also why the confirmation says
     * so rather than leaving the user to guess.
     *
     * The URI permission is handed back at the same time. Without that, Android
     * goes on counting this app among those holding a persistable grant on the
     * folder, which shows up in the system's own storage-access screens as an app
     * that still has access to a folder the user has just removed from it. There
     * is also a per-app cap on how many grants can be held at once, and a library
     * that has been rearranged a few times can reach it.
     */
    fun removeSource(sourceId: String) {
        scope.launch {
            val snapshot = index.value
            val source = snapshot.sources.firstOrNull { it.id == sourceId } ?: return@launch

            // A tree was granted once, as a tree; the documents inside it were
            // never granted separately and there is nothing of theirs to give
            // back. Individually picked files are the other way round: the grant
            // is on each file, and the source they are grouped under is a label
            // this app invented.
            val granted = when (source.kind) {
                SourceKind.EXTERNAL_TREE -> listOf(Uri.parse(source.uri))
                SourceKind.EXTERNAL_FILE -> snapshot.songsFrom(sourceId).map { Uri.parse(it.uri) }
                SourceKind.MANAGED -> {
                    DocumentSources.deleteManagedCopies(context, snapshot.songsFrom(sourceId))
                    emptyList()
                }
            }

            repository.removeSource(sourceId)
            DocumentSources.releasePermissions(context, granted)
        }
    }

    fun dismissError() {
        lastError = null
    }

    /** "12 charts - Google Drive", for a row in the folder list. */
    fun sourceSummary(index: LibraryIndex, source: SourceRef): String {
        val count = index.songsFrom(source.id).size
        val charts = if (count == 1) "1 chart" else "$count charts"
        return when (source.kind) {
            SourceKind.EXTERNAL_TREE ->
                "$charts - ${DocumentSources.providerLabel(source.authority)}"
            else -> charts
        }
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
                "Word documents, and chord charts in .txt, .cho, .pro, .crd and .tab files."
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /**
     * Takes the index rather than reading the flow, because this is called while
     * a list row is being composed. Reading `StateFlow.value` there gives a
     * snapshot Compose is not subscribed to, so the label would keep saying
     * "Unknown" after a rescan until something else happened to recompose it.
     */
    fun sourceLabel(index: LibraryIndex, sourceId: String): String {
        val source = index.sources.firstOrNull { it.id == sourceId } ?: return "Unknown"
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
