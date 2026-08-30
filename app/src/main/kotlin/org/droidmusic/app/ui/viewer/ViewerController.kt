package org.droidmusic.app.ui.viewer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.droidmusic.app.data.LibraryRepository
import org.droidmusic.app.render.ChartPageSource
import org.droidmusic.app.render.PageSource
import org.droidmusic.app.render.PageSources
import org.droidmusic.library.Setlist
import org.droidmusic.library.SongRef
import org.droidmusic.music.ChartAnalysis
import org.droidmusic.music.ChartAnalyzer
import org.droidmusic.music.Key
import org.droidmusic.music.TransposeRequest

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

    val pageCount: Int get() = source?.pageCount ?: 0

    val visiblePages: List<Int> get() = PageLayoutRules.visiblePages(page, pageCount, mode)

    val isReflowable: Boolean get() = source?.isReflowable == true

    val chartSource: ChartPageSource? get() = source as? ChartPageSource

    fun open(songId: String, setlist: Setlist?, index: Int, unicodeAccidentals: Boolean) {
        val ref = library.index.value.findById(songId)
        if (ref == null) {
            error = "That chart is no longer in the library."
            return
        }
        this.setlist = setlist
        this.setlistIndex = index
        openRef(ref, unicodeAccidentals)
    }

    private fun openRef(ref: SongRef, unicodeAccidentals: Boolean) {
        scope.launch {
            loading = true
            error = null
            source?.close()
            source = null
            analysis = null
            transposeNotes = emptyList()

            val opened = PageSources.open(context, ref, unicodeAccidentals)
            if (opened == null) {
                loading = false
                error = "Could not open ${ref.displayName}. " +
                    "If it lives in a cloud folder, it may need to be available offline."
                return@launch
            }

            song = ref
            source = opened
            page = 0
            topRowIndex = 0

            // Per-song settings from the set list: the singer's key for tonight,
            // not the key the file was written in.
            val entry = setlist?.entries?.getOrNull(setlistIndex)
            transposeSemitones = entry?.transposeSemitones ?: 0
            capo = entry?.capo ?: 0

            (opened as? ChartPageSource)?.let { chart ->
                analysis = ChartAnalyzer.analyze(chart.current.song)
                applyTranspose()
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
        transposeSemitones = ((semitones + 6).mod(12)) - 6
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
    }
}
