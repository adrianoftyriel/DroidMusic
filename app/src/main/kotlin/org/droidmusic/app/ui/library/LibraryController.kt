package org.droidmusic.app.ui.library

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.droidmusic.app.data.DocumentSources
import org.droidmusic.app.data.LibraryRepository
import org.droidmusic.app.data.SettingsRepository
import org.droidmusic.app.data.WebChart
import org.droidmusic.library.FileKind
import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.SongRef
import org.droidmusic.library.SourceKind
import org.droidmusic.library.SourceRef
import org.droidmusic.library.UltimateGuitar
import org.droidmusic.library.UltimateGuitarChart
import org.droidmusic.library.normaliseForMatching
import org.droidmusic.music.ChartAnalyzer
import org.droidmusic.music.Key
import org.droidmusic.music.SongParser
import org.droidmusic.session.ChartShare

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

    /**
     * The chart the last link import produced, for the caller to open.
     *
     * Held rather than handed to a callback because the import is started by a
     * share arriving from outside the app, which can happen while the library
     * screen is not on screen to have been given one.
     */
    var imported by mutableStateOf<SongRef?>(null)
        private set

    /**
     * The chart just removed from the library, so the screen can offer to undo
     * it. Cleared when the offer is taken or dismissed.
     */
    var removed by mutableStateOf<SongRef?>(null)
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

            // Files whose grant will not outlive this share, kept by copying.
            val copies = mutableListOf<SongRef>()

            for ((_, picked) in uris.groupBy { DocumentSources.pickedFilesLabel(it.authority) }) {
                val referenced = mutableListOf<Referenced>()

                for (uri in picked) {
                    val persisted = DocumentSources.persistPermission(context, uri)
                    val name = displayName(uri) ?: uri.lastPathSegment ?: "that file"
                    val kind = DocumentSources.kindOfPickedFile(
                        resolver = context.contentResolver,
                        uri = uri,
                        displayName = name,
                        mimeType = context.contentResolver.getType(uri),
                    )
                    if (kind == FileKind.UNKNOWN) {
                        skipped += name
                        continue
                    }

                    // A chart shared in from another app carries a permission
                    // that lasts for that share and no longer. Keeping the URI
                    // and hoping produced a library row that opened once and
                    // then said the file could not be read - which is nothing
                    // anybody can act on, and which is discovered at the stand
                    // rather than at the moment of adding.
                    //
                    // So where Android will not make the grant last, the bytes
                    // are copied instead. It costs the size of a chart and the
                    // file opens for good. Anything the grant does cover stays
                    // referenced where it lies, because a duplicate of
                    // somebody's Drive folder is not what they asked for.
                    if (persisted) {
                        referenced += Referenced(uri, name, kind)
                        continue
                    }

                    val copied = DocumentSources.copyIntoManagedStorage(
                        context,
                        SongRef(
                            id = DocumentSources.stableId(
                                DocumentSources.MANAGED_SOURCE_ID,
                                uri.toString(),
                            ),
                            sourceId = DocumentSources.MANAGED_SOURCE_ID,
                            uri = uri.toString(),
                            displayName = name,
                            kind = kind,
                        ),
                        DocumentSources.MANAGED_SOURCE_ID,
                    )
                    if (copied != null) copies += copied else skipped += name
                }

                // Made only once something is going to point at it, so that a
                // share whose files all had to be copied does not leave an empty
                // row in the folder list.
                if (referenced.isEmpty()) continue
                val source = fileSourceFor(picked.first().authority)
                val added = referenced.map {
                    SongRef(
                        id = DocumentSources.stableId(source.id, it.uri.toString()),
                        sourceId = source.id,
                        uri = it.uri.toString(),
                        displayName = it.name,
                        kind = it.kind,
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

            if (copies.isNotEmpty()) {
                fileManaged(
                    DocumentSources.enrich(
                        context,
                        copies,
                        settings.settings.value.indexChartContents,
                    ),
                )
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
     * Imports a chart from a link somebody shared into the app.
     *
     * **What is stored is the chart, not the link.** The page is fetched once,
     * converted, and written into the library as an ordinary ChordPro file. A
     * bookmark would be less work and useless: charts are read on stage, where
     * the wifi is somebody else's and there may be no signal at all, and a chart
     * that has to be downloaded before it can be read is a chart that is not
     * there when it is needed. Once this returns, the network is never involved
     * in that song again.
     *
     * The file lands in managed storage rather than in one of the user's own
     * folders, for the same reason a scan does: it is the app's file, nothing
     * else put it there, and writing uninvited into somebody's Drive is not the
     * app's business.
     */
    fun importFromShare(shared: String) {
        // Not queued behind the scan, and not dropped either. Both write to the
        // same progress line, and a share that vanishes without a word looks
        // exactly like an app that ignored it.
        if (scanning) {
            lastError = "The library is busy. Share the link again in a moment."
            return
        }
        scope.launch {
            scanning = true
            scanStatus = "Importing the chart"
            lastError = null
            try {
                lastError = importChart(shared)
            } finally {
                scanning = false
                scanStatus = ""
            }
        }
    }

    var fetchingUrl by mutableStateOf(false)
        private set
    var fetchError by mutableStateOf<String?>(null)
        private set

    /**
     * Fetches a chart from an address the player typed, rather than shared.
     *
     * An Ultimate Guitar link goes down the same path a shared one does - the
     * converter, the metadata, straight into the library - because that path
     * already knows how to turn one of those pages into a chart.
     *
     * Anything else is fetched as a page and handed to [onText] to open in the
     * editor rather than filed. What comes back from an arbitrary URL is
     * frequently not what was wanted - a login wall, a listing, the right song
     * in the wrong format - and seeing it before it is saved is the difference
     * between noticing that now and finding out on a stand.
     */
    fun importFromUrl(url: String, onText: (title: String, text: String) -> Unit) {
        if (fetchingUrl) return
        scope.launch {
            fetchingUrl = true
            fetchError = null

            if (UltimateGuitar.chartUrlIn(url) != null) {
                fetchError = importChart(url)
                fetchingUrl = false
                return@launch
            }

            when (val result = WebChart.fetch(url)) {
                is WebChart.FetchResult.Failed -> fetchError = result.message
                is WebChart.FetchResult.Ok -> {
                    val body = result.html
                    if (body.isBlank()) {
                        fetchError = "That address returned nothing."
                    } else {
                        onText(titleFromUrl(url), body)
                    }
                }
            }
            fetchingUrl = false
        }
    }

    fun dismissFetchError() {
        fetchError = null
    }

    /**
     * A first guess at what the chart is called, from the address.
     *
     * A guess, and editable the moment the editor opens - which is why it is
     * allowed to be wrong. The last path segment is right often enough to save
     * typing and never load-bearing.
     */
    private fun titleFromUrl(url: String): String =
        url.trim()
            .substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .replace(Regex("[-_+]+"), " ")
            .trim()

    /** Runs the import, returning a message to show if it could not be done. */
    private suspend fun importChart(shared: String): String? {
        // A browser shares the page title and the address together, so the link
        // is looked for inside what arrived rather than assumed to be all of it.
        val url = UltimateGuitar.chartUrlIn(shared)
            ?: return "DroidMusic imports chord charts from Ultimate Guitar links, " +
                "and there is no Ultimate Guitar chart link in what was shared."

        val html = when (val result = WebChart.fetch(url)) {
            is WebChart.FetchResult.Ok -> result.html
            is WebChart.FetchResult.Failed -> return result.message
        }

        val chart = UltimateGuitar.parsePage(html, url)
            ?: return "There is no text chart on that page. Ultimate Guitar's official " +
                "and Pro tabs are interactive players, and there is nothing in one to import."

        val chordPro = UltimateGuitar.toChordPro(chart)
        val written = withContext(Dispatchers.IO) {
            runCatching { write(UltimateGuitar.fileNameFor(chart), chart, chordPro) }
        }
        val song = written.getOrElse { failure ->
            // Named, not merely refused. "Could not be saved" on its own is a
            // dead end, and the usual cause - no room left on the device - is
            // something the player can act on the moment they are told.
            val reason = failure.message?.trim()?.takeIf { it.isNotEmpty() }
            return "The chart was read but could not be saved to this device" +
                (reason?.let { ": $it" } ?: ".")
        }

        fileManaged(listOf(song))
        imported = song
        return null
    }

    /**
     * Writes the chart into managed storage and describes what was written.
     *
     * **Only the write itself may fail this.** The metadata comes from the page,
     * which has already said what the song is called, who wrote it and what key
     * it is in, so nothing here re-derives any of that by parsing the file back
     * in. That round trip was not merely wasted work: it put the key detector on
     * the path between a converted chart and a saved one, and a chart the
     * analyser cannot make sense of is still a chart the player asked for.
     */
    private fun write(
        fileName: String,
        chart: UltimateGuitarChart,
        chordPro: String,
    ): SongRef {
        val directory = File(context.filesDir, MANAGED_DIRECTORY).apply { mkdirs() }
        val target = File(directory, uniqueName(directory, fileName))
        target.writeText(chordPro)

        return SongRef(
            id = DocumentSources.stableId(DocumentSources.MANAGED_SOURCE_ID, target.name),
            sourceId = DocumentSources.MANAGED_SOURCE_ID,
            uri = Uri.fromFile(target).toString(),
            displayName = target.name,
            kind = FileKind.CHORDPRO,
            sizeBytes = target.length(),
            modifiedAt = target.lastModified(),
            contentHash = DocumentSources.hashOf(chordPro.toByteArray()),
            title = chart.title,
            artist = chart.artist,
            keyText = chart.keyText?.let { Key.parse(it) }?.toString() ?: detectedKey(chordPro),
        )
    }

    /**
     * The key worked out from the chords, for a page that did not name one.
     *
     * Guarded, as every other caller of the analyser guards it: the viewer so
     * that a chart which defeats it still gets played, the indexer so that one
     * awkward file does not empty a folder scan. This is the one part of filing
     * an imported chart allowed to come back with nothing. A key badge is worth
     * having and is not worth losing the chart for, and refusing to save a chart
     * that had already been fetched and converted because the key could not be
     * guessed is the worst outcome on offer - the player is left with an error
     * and no file, and nothing they can do differently.
     */
    private fun detectedKey(chordPro: String): String? = runCatching {
        ChartAnalyzer.analyze(SongParser.parse(chordPro)).effectiveKey?.toString()
    }.getOrNull()

    /** Adds charts to the managed source, making that source if it is not there. */
    private suspend fun fileManaged(songs: List<SongRef>) {
        if (songs.isEmpty()) return
        val source = DocumentSources.managedSource()
        if (index.value.sources.none { it.id == source.id }) {
            repository.addSource(source)
        }
        val incoming = songs.map { it.id }.toSet()
        val existing = index.value.songsFrom(source.id).filterNot { it.id in incoming }
        repository.replaceSongsFrom(source.id, existing + songs, System.currentTimeMillis())
    }

    /**
     * Never overwrites. Two imports of the same song are usually two different
     * people's transcriptions of it, and which one a player wants is not a
     * question this code can answer by throwing one away.
     */
    private fun uniqueName(directory: File, name: String): String {
        if (!File(directory, name).exists()) return name
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "chopro")
        var suffix = 2
        while (File(directory, "$stem ($suffix).$extension").exists()) suffix++
        return "$stem ($suffix).$extension"
    }

    fun consumeImported() {
        imported = null
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

    // ---- Bulk edit ---------------------------------------------------------

    /**
     * The charts a bulk action is about.
     *
     * Held on the controller rather than in the screen so that it survives the
     * dialogs the actions put up. A selection that emptied itself because a
     * confirmation appeared over it would remove nothing and say it had.
     */
    var selection by mutableStateOf<Set<String>>(emptySet())
        private set

    var selecting by mutableStateOf(false)
        private set

    fun startSelecting(first: String? = null) {
        selecting = true
        selection = if (first == null) emptySet() else setOf(first)
    }

    fun stopSelecting() {
        selecting = false
        selection = emptySet()
    }

    fun toggleSelected(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun selectAll(ids: List<String>) {
        selection = ids.toSet()
    }

    /** The songs a bulk action applies to, resolved against the current index. */
    fun selectedSongs(): List<SongRef> = index.value.visible.filter { it.id in selection }

    /**
     * Sets the key a whole selection is played in.
     *
     * Charts that cannot be rewritten are skipped rather than refused: a PDF in
     * a selection of forty is not a reason to abandon the other thirty-nine, and
     * there is nothing in a picture of a page to transpose.
     */
    fun setTransposeAll(songs: List<SongRef>, semitones: Int, capo: Int) {
        val ids = songs.filter { it.isTransposable }.map { it.id }.toSet()
        if (ids.isEmpty()) return
        scope.launch {
            repository.updateSongs(ids) { it.withTranspose(semitones, capo) }
            stopSelecting()
        }
    }

    /**
     * Stops listing a selection of charts, in one write.
     *
     * One write rather than one per chart: forty saves racing each other through
     * the same store is a library that comes back having forgotten an arbitrary
     * subset. Undo is the "put them all back" in settings rather than the single
     * chart banner, because a banner naming forty charts is not a banner.
     */
    fun removeFromLibraryAll(songs: List<SongRef>) {
        val ids = songs.map { it.id }.toSet()
        if (ids.isEmpty()) return
        scope.launch {
            repository.updateSongs(ids) { it.copy(hidden = true) }
            stopSelecting()
        }
    }

    // ---- Writing a chart ---------------------------------------------------

    var savingChart by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set

    /**
     * Reads a chart's text so the editor can open it.
     *
     * Suspending rather than a property: the chart may be behind a document
     * provider that has to fetch it, and a composable reading it synchronously
     * would block the frame drawing the editor.
     */
    suspend fun readChartText(song: SongRef): String? = withContext(Dispatchers.IO) {
        DocumentSources.readChartText(context.contentResolver, Uri.parse(song.uri), song.kind)
    }

    /**
     * Writes a chart from the editor.
     *
     * Editing rewrites the file behind [replacing] in place, which keeps the
     * song's id - and that matters more than it looks, because every set list
     * that contains this chart refers to it by id and a save that produced a new
     * one would silently empty the running orders it appears in.
     *
     * A chart that lives in one of the user's own folders is not written to. The
     * app holds read access to those, and quietly failing to save somebody's
     * edit is the worst of the available outcomes, so it says so instead.
     */
    fun saveChart(title: String, text: String, replacing: SongRef?, onSaved: (SongRef) -> Unit) {
        if (savingChart) return
        scope.launch {
            savingChart = true
            saveError = null

            val written = withContext(Dispatchers.IO) {
                runCatching {
                    if (replacing != null) rewrite(replacing, title, text)
                    else write(chartFileName(title), title, text)
                }
            }

            savingChart = false
            written.onSuccess { song ->
                fileManaged(listOf(song))
                onSaved(song)
            }.onFailure { failure ->
                saveError = failure.message?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { "Could not save the chart: $it" }
                    ?: "Could not save the chart to this device."
            }
        }
    }

    fun dismissSaveError() {
        saveError = null
    }

    /** Overwrites a chart this app owns, keeping its id and its place. */
    private fun rewrite(song: SongRef, title: String, text: String): SongRef {
        val file = DocumentSources.managedFileOf(context, song)
            ?: throw IllegalStateException(
                "that chart lives in one of your own folders, and DroidMusic only has " +
                    "read access to it",
            )
        file.writeText(text)
        return song.copy(
            sizeBytes = file.length(),
            modifiedAt = file.lastModified(),
            contentHash = DocumentSources.hashOf(text.toByteArray()),
            title = title.ifBlank { null } ?: song.title,
            keyText = detectedKey(text) ?: song.keyText,
        )
    }

    /** A new chart in the app's own storage. */
    private fun write(fileName: String, title: String, text: String): SongRef {
        val directory = File(context.filesDir, MANAGED_DIRECTORY).apply { mkdirs() }
        val target = File(directory, uniqueName(directory, fileName))
        target.writeText(text)

        return SongRef(
            id = DocumentSources.stableId(DocumentSources.MANAGED_SOURCE_ID, target.name),
            sourceId = DocumentSources.MANAGED_SOURCE_ID,
            uri = Uri.fromFile(target).toString(),
            displayName = target.name,
            kind = FileKind.CHORDPRO,
            sizeBytes = target.length(),
            modifiedAt = target.lastModified(),
            contentHash = DocumentSources.hashOf(text.toByteArray()),
            title = title.ifBlank { null },
            keyText = detectedKey(text),
        )
    }

    /**
     * A filename for a chart typed in the app.
     *
     * Through [org.droidmusic.session.ChartShare.safeFileName] rather than a
     * sanitiser of its own, because the rule for "a name that is safe to create"
     * has one correct answer and it already has tests around it - including the
     * one that matters, that no input can produce a path.
     */
    private fun chartFileName(title: String): String =
        ChartShare.safeFileName("${title.ifBlank { "Untitled" }}.chopro", fallback = "Untitled")

    // ---- What a press and hold offers -------------------------------------

    /**
     * Renames a chart, for DroidMusic's purposes only. The file is not touched.
     *
     * What counts as a rename and what counts as clearing one is
     * [SongRef.withRename], in the core, so that the rule has a test.
     */
    fun rename(song: SongRef, name: String) {
        scope.launch { repository.updateSong(song.id) { it.withRename(name) } }
    }

    /**
     * Remembers the key this chart is played in, so it opens in that key rather
     * than the one it was written in.
     *
     * Only meaningful for a chart the app can rewrite - see
     * [SongRef.isTransposable] - which is why the menu does not offer it for a
     * PDF. There is nothing in a picture of a page to transpose.
     */
    fun setTranspose(song: SongRef, semitones: Int, capo: Int) {
        scope.launch { repository.updateSong(song.id) { it.withTranspose(semitones, capo) } }
    }

    /**
     * Stops listing a chart, without touching the file.
     *
     * Held in [removed] afterwards so the screen can offer to undo it. That
     * matters more here than it looks: the chart is invisible the moment this
     * runs, so without an undo the only way back would be a menu the user cannot
     * reach any more.
     */
    fun removeFromLibrary(song: SongRef) {
        scope.launch {
            repository.updateSong(song.id) { it.copy(hidden = true) }
            removed = song
        }
    }

    /** Puts back a chart that was removed from the library. */
    fun restore(song: SongRef) {
        scope.launch {
            repository.updateSong(song.id) { it.copy(hidden = false) }
            if (removed?.id == song.id) removed = null
        }
    }

    /** Puts back every chart that was removed from the library. */
    fun restoreAllRemoved() {
        scope.launch {
            repository.restoreHidden()
            removed = null
        }
    }

    fun dismissRemoved() {
        removed = null
    }

    /**
     * Whether the file behind a chart can actually be deleted, which decides
     * whether the menu offers to.
     *
     * True for the copies this app made - a photographed page, a chart imported
     * from a link - and false for anything in one of the user's own folders,
     * because the app holds read access to those and nothing more.
     */
    fun canDeleteFile(song: SongRef): Boolean = DocumentSources.canDeleteFile(context, song)

    /**
     * Deletes the file behind a chart and forgets it.
     *
     * The index entry is dropped rather than hidden. Hiding exists to stop a
     * rescan resurrecting a chart whose file is still there, and there is nothing
     * to resurrect once the file is gone.
     */
    fun deleteFile(song: SongRef) {
        scope.launch {
            if (DocumentSources.deleteFile(context, song)) {
                repository.dropSong(song.id)
            } else {
                lastError = "Could not delete ${song.bestTitle}. " +
                    "DroidMusic has read-only access to that file, so deleting it has to be " +
                    "done wherever it lives."
            }
        }
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

    /** A picked file that is staying where it is, once it is known to be readable. */
    private data class Referenced(val uri: Uri, val name: String, val kind: FileKind)

    private companion object {
        const val MANAGED_DIRECTORY = "managed"
    }
}
