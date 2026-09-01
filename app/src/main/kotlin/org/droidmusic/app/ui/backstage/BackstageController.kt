package org.droidmusic.app.ui.backstage

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.droidmusic.app.data.LibraryRepository
import org.droidmusic.app.data.SettingsRepository
import org.droidmusic.app.render.PageSources
import org.droidmusic.app.render.RasterPageSource
import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.Setlist
import org.droidmusic.library.SetlistEntry
import org.droidmusic.library.SongRef
import org.droidmusic.library.SourceKind
import org.droidmusic.session.BackstageReport
import org.droidmusic.session.ChartCheck
import org.droidmusic.session.ChartState
import org.droidmusic.session.ChartWant

/**
 * The check a player runs before the first song: can this device actually open
 * every chart in tonight's set list.
 *
 * The important word is *open*. Looking each song up in the library index would
 * be instant and would answer a different question - the index is this app's own
 * memory of a folder listing, and a chart can be in it while the file behind it
 * is gone, renamed, still in the cloud, or behind a permission the provider
 * quietly withdrew. None of those show up until somebody taps the song, and by
 * then the band is playing. So every entry is opened for real, through exactly
 * the code path the viewer will use at the downbeat, and a scan or a PDF is made
 * to render a page as well - because a truncated download opens perfectly and
 * draws nothing.
 *
 * That costs a second or two for a long set list. It is the cheapest second or
 * two in the whole app.
 */
class BackstageController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) {
    /**
     * Told about every finished check, so it can be sent to the leader. Set by
     * the root composable, because the check itself works identically whether or
     * not anyone is listening.
     */
    var onReport: ((BackstageReport) -> Unit)? = null

    var setlist by mutableStateOf<Setlist?>(null)
        private set

    /** Where the set list came from: null when it is this device's own. */
    var requestedBy by mutableStateOf<String?>(null)
        private set

    var checks by mutableStateOf<List<ChartCheck>>(emptyList())
        private set

    var checking by mutableStateOf(false)
        private set

    var checkedAt by mutableStateOf(0L)
        private set

    /** How many of tonight's charts live somewhere that needs a network. */
    var cloudCount by mutableStateOf(0)
        private set

    private var job: Job? = null

    val problems: List<ChartCheck> get() = checks.filter { it.isProblem }

    val allReady: Boolean get() = checks.isNotEmpty() && problems.isEmpty()

    /**
     * Starts checking [setlist]. [requestedBy] names the leader when the check
     * was asked for over a session rather than started here.
     */
    fun begin(setlist: Setlist, requestedBy: String? = null) {
        this.setlist = setlist
        this.requestedBy = requestedBy
        check()
    }

    /** Runs the check again, for after somebody has gone and found the file. */
    fun check() {
        val list = setlist ?: return
        // A second tap while the first check is still walking the set list would
        // otherwise interleave two sets of results into the same list.
        job?.cancel()
        job = scope.launch {
            checking = true
            checks = emptyList()

            val index = library.index.value
            val unicode = settings.settings.value.viewer.unicodeAccidentals

            val results = mutableListOf<ChartCheck>()
            for ((position, entry) in list.entries.withIndex()) {
                // A cancelled check must not keep writing into the list the
                // replacement check is already filling.
                if (!isActive) return@launch
                results += checkEntry(position, entry, index, unicode)
                // Published as they land. On a twenty-song set list over a slow
                // provider, a screen that fills in front of the player is the
                // difference between "it is working" and "it has hung".
                checks = results.toList()
            }

            cloudCount = list.entries.count { entry ->
                val ref = resolve(entry, index)
                val source = ref?.let { found ->
                    index.sources.firstOrNull { it.id == found.sourceId }
                }
                source != null && source.kind != SourceKind.MANAGED
            }

            checkedAt = System.currentTimeMillis()
            checking = false
            onReport?.invoke(report())
        }
    }

    /**
     * The charts to ask the leader for: the ones missing, and the ones that are
     * here and will not open.
     *
     * The second half is the reason this is not simply "what the library cannot
     * resolve". A half-downloaded scan resolves perfectly and is still unusable,
     * so the automatic ask made when a set list arrives never sees it - and it
     * is precisely the chart somebody wants a fresh copy of. Asked by content
     * hash and title, because a song's id means nothing on the leader's device.
     */
    fun wanted(): List<ChartWant> {
        val entries = setlist?.entries ?: return emptyList()
        val seen = mutableSetOf<String>()
        return checks
            .filter { it.state == ChartState.MISSING || it.state == ChartState.UNREADABLE }
            .mapNotNull { check -> entries.getOrNull(check.index) }
            // One want per chart however many times the set list names it: an
            // encore is the same file.
            .filter { entry -> seen.add(entry.contentHash ?: entry.title.lowercase()) }
            .map { entry -> ChartWant(contentHash = entry.contentHash, title = entry.title) }
    }

    /** This device's answer, ready to be sent to the leader. */
    fun report(): BackstageReport = BackstageReport(
        deviceId = settings.settings.value.deviceId,
        deviceName = settings.settings.value.deviceName.ifEmpty { "This device" },
        setlistName = setlist?.name.orEmpty(),
        checks = checks,
        checkedAt = checkedAt,
    )

    fun clear() {
        job?.cancel()
        job = null
        setlist = null
        requestedBy = null
        checks = emptyList()
        checking = false
        checkedAt = 0L
        cloudCount = 0
    }

    private fun resolve(entry: SetlistEntry, index: LibraryIndex): SongRef? =
        index.findById(entry.songId) ?: index.match(entry.contentHash, entry.title)

    private suspend fun checkEntry(
        position: Int,
        entry: SetlistEntry,
        index: LibraryIndex,
        unicodeAccidentals: Boolean,
    ): ChartCheck {
        val ref = resolve(entry, index)
            ?: return ChartCheck(
                index = position,
                title = entry.title,
                state = ChartState.MISSING,
                detail = "Not in this library. Add the file, or ask for it to be sent.",
            )

        val failure = openFailure(ref, unicodeAccidentals)
        if (failure != null) {
            return ChartCheck(position, entry.title, ChartState.UNREADABLE, failure)
        }

        // Only worth saying when both sides know their own hash. A set list made
        // before hashing, or a chart the indexer has not read yet, is not
        // evidence of anything.
        val different = entry.contentHash != null &&
            ref.contentHash != null &&
            entry.contentHash != ref.contentHash
        if (different) {
            return ChartCheck(
                index = position,
                title = entry.title,
                state = ChartState.DIFFERENT,
                detail = "Your copy (${ref.displayName}) is not the same file. Same song, " +
                    "different chart - check the repeats before the downbeat.",
            )
        }

        return ChartCheck(position, entry.title, ChartState.READY)
    }

    /**
     * Opens the chart the way the viewer will. Returns null when it worked, and
     * a sentence for the player when it did not.
     */
    private suspend fun openFailure(ref: SongRef, unicodeAccidentals: Boolean): String? =
        withContext(Dispatchers.IO) {
            val source = runCatching {
                PageSources.open(context, ref, unicodeAccidentals)
            }.getOrNull() ?: return@withContext cannotOpen(ref)

            try {
                if (source.pageCount <= 0) return@withContext cannotOpen(ref)
                // A picture that will not draw is the failure a page count cannot
                // see: a half-downloaded scan opens, reports one page, and shows
                // nothing. Drawing it small is cheap and settles it.
                if (source is RasterPageSource && source.render(0, PROBE_PX, PROBE_PX) == null) {
                    return@withContext "The file is there but the page will not draw. It may " +
                        "have been only partly downloaded."
                }
                null
            } finally {
                runCatching { source.close() }
            }
        }

    private fun cannotOpen(ref: SongRef): String {
        val scheme = runCatching { Uri.parse(ref.uri).scheme }.getOrNull()
        return if (scheme == "file") {
            "${ref.displayName} would not open. The file may have been moved or deleted."
        } else {
            "${ref.displayName} would not open. If it lives in a cloud folder it may need to " +
                "be made available offline, or the app's access to that folder may have lapsed."
        }
    }

    companion object {
        /**
         * How big a probe render is. Small enough to be nearly free on a twenty
         * page PDF, large enough that the renderer does real work rather than
         * rejecting the size.
         */
        const val PROBE_PX = 64
    }
}
