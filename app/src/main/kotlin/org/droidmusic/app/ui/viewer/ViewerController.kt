package org.droidmusic.app.ui.viewer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.droidmusic.app.data.LibraryRepository
import org.droidmusic.app.diag.Area
import org.droidmusic.app.diag.Diagnostics
import org.droidmusic.app.render.ChartPageSource
import org.droidmusic.app.render.OpenResult
import org.droidmusic.app.render.PageSource
import org.droidmusic.app.render.PageSources
import org.droidmusic.library.Setlist
import org.droidmusic.library.SongRef
import org.droidmusic.music.ChartAnalysis
import org.droidmusic.music.ChartRow
import org.droidmusic.music.ChartAnalyzer
import org.droidmusic.music.Key
import org.droidmusic.music.TransposeRequest
import org.droidmusic.music.TransposeResult

/**
 * Everything the viewer screen needs to know, and the only place that decides
 * what page it is on.
 *
 * Page turns funnel through [goTo] whatever asked for them - a tap, a foot
 * switch, or the band leader - so there is exactly one implementation of "what
 * does next mean", and the answer stays consistent when a chart reflows or a set
 * list rolls over into the next song.
 */
class ViewerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
) {
    /**
     * Told about every move, so a session can broadcast it. Set by the root
     * composable rather than passed in, because the controller outlives any one
     * session and must work perfectly well when nothing is listening.
     */
    var positionReporter: ((page: Int, userInitiated: Boolean) -> Unit)? = null

    var song by mutableStateOf<SongRef?>(null)
        private set
    var source by mutableStateOf<PageSource?>(null)
        private set
    var page by mutableStateOf(0)
        private set
    var mode by mutableStateOf(PageMode.SINGLE)
        private set

    /**
     * Whether pages are cropped to their content rather than shown whole.
     *
     * Deliberately kept across page turns and across songs. A player who zooms
     * in is telling the app that this stand, at this distance, needs the music
     * bigger - and that is not something that stops being true at the end of the
     * page. Each page works out its own crop, so a songbook whose margins wander
     * still lands right.
     */
    var zoomed by mutableStateOf(false)
        private set

    /** Double tap. Nothing else in the app turns this on or off. */
    fun toggleZoom() {
        zoomed = !zoomed
    }
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var setlist by mutableStateOf<Setlist?>(null)
        private set
    var setlistIndex by mutableStateOf(-1)
        private set

    var analysis by mutableStateOf<ChartAnalysis?>(null)
        private set
    var transposeSemitones by mutableStateOf(0)
        private set
    var capo by mutableStateOf(0)
        private set
    var transposeNotes by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * The laid-out pages of a chart, and the transposition that produced them.
     *
     * A copy of what [ChartPageSource] holds, kept here as observable state,
     * and the reason is the whole of why transposing appeared to do nothing at
     * all. Transposing a chart rewrites the rows *inside* the page source
     * without changing the object, the page number, or anything else the screen
     * was watching - so Compose had no reason to draw again, and the chart sat
     * there in its original key while the key pill next to it said otherwise.
     *
     * Publishing the result here rather than making the page source observable
     * keeps the rule the rest of the viewer already follows: the screen reads
     * the controller, the controller owns the state, and the render layer stays
     * a plain object that knows nothing about Compose.
     */
    var chartPages by mutableStateOf<List<List<ChartRow>>>(emptyList())
        private set

    /** The transposed song on screen: its key, what it was, and any capo. */
    var transposed by mutableStateOf<TransposeResult?>(null)
        private set

    /**
     * The row of the chart at the top of the current page.
     *
     * Tracked because it, not the page number, is what a reflowable chart has to
     * preserve across a rotation. See [onViewportChanged].
     */
    private var topRowIndex = 0

    /**
     * Set when we arrive at a song by turning *backwards* into it, so the load
     * lands on its last page instead of its first.
     *
     * A flag rather than a "go to the last page" call after opening, because
     * opening is asynchronous: the page count is not known until the file has
     * been read, and asking for the last page before then reliably asks for page
     * minus one of zero.
     */
    private var openAtLastPage = false
    private var linesPerPage = ChartPageSource.DEFAULT_LINES_PER_PAGE

    /**
     * Pages in the open document.
     *
     * For a chart this comes from [chartPages] rather than from the source, so
     * that a re-flow which changes the page count is seen by anything drawing
     * from it. For a PDF the count is a property of the file and never moves.
     */
    val pageCount: Int get() = if (chartSource != null) chartPages.size else source?.pageCount ?: 0

    val visiblePages: List<Int> get() = PageLayoutRules.visiblePages(page, pageCount, mode)

    val isReflowable: Boolean get() = source?.isReflowable == true

    val chartSource: ChartPageSource? get() = source as? ChartPageSource

    /** The rows to draw on [page]. Empty for anything that is not a chart. */
    fun rowsFor(page: Int): List<ChartRow> = chartPages.getOrElse(page) { emptyList() }

    /**
     * Republishes the laid-out chart after anything that rewrites it.
     *
     * Called from the three places that can: opening a song, transposing, and
     * re-flowing for a new viewport or text size. Forgetting it in a fourth
     * would show a stale chart, so they are all within a few lines of each
     * other and each one is immediately followed by this.
     */
    private fun syncChart() {
        val chart = chartSource
        if (chart == null) {
            chartPages = emptyList()
            transposed = null
            return
        }
        chartPages = chart.pages()
        transposed = chart.current
    }

    fun open(songId: String, setlist: Setlist?, index: Int, unicodeAccidentals: Boolean) {
        val ref = library.index.value.findById(songId)
        if (ref == null) {
            error = "That chart is no longer in the library."
            return
        }
        open(ref, setlist, index, unicodeAccidentals)
    }

    /**
     * Opens a chart the caller has already found.
     *
     * The band-leader path comes in here rather than through the id, because
     * resolving one device's song id against another device's library is not
     * something the viewer can do - and is not something it should know exists.
     */
    fun open(song: SongRef, setlist: Setlist?, index: Int, unicodeAccidentals: Boolean) {
        this.setlist = setlist
        this.setlistIndex = index
        openRef(song, unicodeAccidentals)
    }

    /**
     * Says that the band has moved to a chart this device has not got.
     *
     * Named rather than blank: a player looking at a screen that did not change
     * needs to know whether the app is broken, the network is down, or they are
     * missing a file - and only the third is true here.
     */
    fun reportMissing(title: String?) {
        source?.close()
        source = null
        song = null
        syncChart()
        Diagnostics.log(Area.CHART, "band is on \"${title ?: "?"}\", which is not in this library")
        error = if (title.isNullOrBlank()) {
            "The band is on a chart this device has not got."
        } else {
            "The band is on \"$title\", which is not in this library. Backstage will find " +
                "the rest before the next set."
        }
    }

    private fun openRef(ref: SongRef, unicodeAccidentals: Boolean) {
        scope.launch {
            loading = true
            error = null
            source?.close()
            source = null
            analysis = null
            transposeNotes = emptyList()
            syncChart()

            // The reason comes back with the failure rather than being worked
            // out afterwards. A grant Android has stopped honouring, a file that
            // has moved, a cloud file never fetched and a file that reads
            // perfectly but defeats the chart reader all failed here
            // identically before, and only one of the four was ever named.
            val opened = when (val result = PageSources.open(context, ref, unicodeAccidentals)) {
                is OpenResult.Ok -> result.source
                is OpenResult.Failed -> {
                    loading = false
                    Diagnostics.log(Area.CHART, "${ref.displayName} would not open: ${result.reason}")
                    error = "Could not open ${ref.displayName}. ${result.reason}"
                    return@launch
                }
            }

            song = ref
            source = opened
            page = 0
            topRowIndex = 0
            syncChart()

            // Which key to open in, most specific first: what the set list says
            // for tonight, then the key the band always plays this song in, then
            // the key the file was written in.
            //
            // A set list entry wins even when it says zero. That is not a
            // fallthrough to the song's key but an entry that means "as
            // written", and entries are seeded from the song's key when they are
            // created, so a zero there was chosen rather than defaulted.
            val entry = setlist?.entries?.getOrNull(setlistIndex)
            transposeSemitones = entry?.transposeSemitones ?: ref.userTransposeSemitones
            capo = entry?.capo ?: ref.userCapo

            (opened as? ChartPageSource)?.let { chart ->
                // The chart itself is open and readable by this point. What is
                // left - the key badge, the capo suggestions, the transposition
                // the set list asked for - is worth having and not worth the
                // chart for, so a chart that defeats the analyser still gets
                // played.
                runCatching {
                    analysis = ChartAnalyzer.analyze(chart.current.song)
                    applyTranspose()
                }
                // Even when the analyser threw and the transposition never ran,
                // the chart itself is open and has rows to draw.
                syncChart()
            }

            if (openAtLastPage) {
                openAtLastPage = false
                val last = (opened.pageCount - 1).coerceAtLeast(0)
                page = if (mode == PageMode.SPREAD) last - (last % 2) else last
                (opened as? ChartPageSource)?.let { topRowIndex = it.firstRowIndexOf(page) }
            }

            loading = false
            positionReporter?.invoke(page, false)
        }
    }

    /** Called when the viewport changes size, including on rotation. */
    fun onViewportChanged(widthDp: Int, heightDp: Int, linesThatFit: Int, override: PageMode?) {
        mode = PageLayoutRules.modeFor(widthDp, heightDp, override)
        val chart = chartSource ?: return
        if (linesThatFit == linesPerPage) return
        linesPerPage = linesThatFit
        // Reflow, then land on whichever page now holds the line they were on.
        // Restoring "page 3" instead would move a reader by an arbitrary amount
        // every time the phone was rotated.
        page = chart.relayout(linesThatFit, topRowIndex)
        topRowIndex = chart.firstRowIndexOf(page)
        syncChart()
    }

    /**
     * Moves to [target]. [userInitiated] is false when the band leader moved us,
     * which is what stops a leader's page turn from being reported back as this
     * player taking control.
     */
    fun goTo(target: Int, userInitiated: Boolean) {
        val count = pageCount
        if (count <= 0) return
        val clamped = target.coerceIn(0, count - 1)
        val settled = if (mode == PageMode.SPREAD) clamped - (clamped % 2) else clamped
        if (settled == page) return
        page = settled
        chartSource?.let { topRowIndex = it.firstRowIndexOf(settled) }
        positionReporter?.invoke(settled, userInitiated)
    }

    /**
     * A page turn. Past the end of a chart it rolls into the next song of the
     * set list rather than stopping dead, which is what a player expects at the
     * end of a page and what makes a set list feel like one document.
     */
    fun turn(forward: Boolean, unicodeAccidentals: Boolean, userInitiated: Boolean = true) {
        val count = pageCount
        if (count <= 0) return

        if (forward && PageLayoutRules.isAtEnd(page, count, mode)) {
            if (!nextSong(unicodeAccidentals)) return
            return
        }
        if (!forward && PageLayoutRules.isAtStart(page)) {
            if (!previousSong(unicodeAccidentals, toLastPage = true)) return
            return
        }
        goTo(PageLayoutRules.advance(page, count, mode, forward), userInitiated)
    }

    fun nextSong(unicodeAccidentals: Boolean): Boolean {
        val list = setlist ?: return false
        val next = setlistIndex + 1
        if (next >= list.entries.size) return false
        val entry = list.entries[next]
        val ref = library.index.value.findById(entry.songId)
            ?: library.index.value.match(entry.contentHash, entry.title)
            ?: return false
        setlistIndex = next
        openRef(ref, unicodeAccidentals)
        return true
    }

    fun previousSong(unicodeAccidentals: Boolean, toLastPage: Boolean = false): Boolean {
        val list = setlist ?: return false
        val previous = setlistIndex - 1
        if (previous < 0) return false
        val entry = list.entries[previous]
        val ref = library.index.value.findById(entry.songId)
            ?: library.index.value.match(entry.contentHash, entry.title)
            ?: return false
        setlistIndex = previous
        // Landing on page one of the previous song when you turned back a single
        // page is disorienting; a book opens where you left it.
        openAtLastPage = toLastPage
        openRef(ref, unicodeAccidentals)
        return true
    }

    /**
     * Named `chooseTranspose` rather than `setTranspose`, and `chooseCapo`
     * rather than `setCapo`, because `capo` and `transposeSemitones` are
     * observable properties: Kotlin already generates `setCapo(int)` for the
     * property, and a method of the same name is the same JVM signature twice.
     * The names also read better for what they are - the player picking a key,
     * not a field being assigned.
     */
    fun chooseTranspose(semitones: Int) {
        transposeSemitones = Key.foldSemitones(semitones)
        applyTranspose()
    }

    fun chooseCapo(fret: Int) {
        capo = fret.coerceIn(0, 11)
        applyTranspose()
    }

    fun transposeToKey(target: Key) {
        val from = chartSource?.current?.fromKey ?: return
        val up = Math.floorMod(target.pitchClass - from.pitchClass, 12)
        chooseTranspose(if (up > 6) up - 12 else up)
    }

    private fun applyTranspose() {
        val chart = chartSource ?: return
        val request = TransposeRequest(
            semitones = transposeSemitones,
            capo = capo,
            includeTab = false,
        )
        page = chart.apply(request, linesPerPage, topRowIndex)
        topRowIndex = chart.firstRowIndexOf(page)
        transposeNotes = chart.current.notes
        analysis = ChartAnalyzer.analyze(chart.current.song)
        syncChart()
    }

    /** Applies a position that came from the band leader. */
    fun applyRemote(page: Int, transposeSemitones: Int, capo: Int, unicodeAccidentals: Boolean) {
        if (this.transposeSemitones != transposeSemitones || this.capo != capo) {
            this.transposeSemitones = transposeSemitones
            this.capo = capo
            applyTranspose()
        }
        goTo(page, userInitiated = false)
    }

    fun close() {
        source?.close()
        source = null
        syncChart()
    }
}
