package org.droidmusic.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.droidmusic.library.FileKind
import org.droidmusic.library.SongRef
import org.droidmusic.app.ui.viewer.ContentRect
import org.droidmusic.music.ChartLayout
import org.droidmusic.music.ChartRow
import org.droidmusic.music.Song
import org.droidmusic.music.SongParser
import org.droidmusic.music.TransposeRequest
import org.droidmusic.music.TransposeResult
import org.droidmusic.music.Transposer

/**
 * What opening a chart produced, or why it did not.
 *
 * The reason is decided here, at the point of failure, rather than worked out
 * afterwards by trying the file a second time. A second attempt is a different
 * attempt: it can succeed where the first failed, and then the viewer explains a
 * failure that no longer reproduces. It also cannot see the one thing worth
 * knowing when the bytes were fine and something later went wrong - which
 * exception, from which step.
 */
sealed interface OpenResult {
    data class Ok(val source: PageSource) : OpenResult

    /** A message written to be shown to the player as it is. */
    data class Failed(val reason: String) : OpenResult
}

/**
 * A document the viewer can page through, whatever it actually is.
 *
 * The viewer knows only this interface, which is what lets a PDF, a folder of
 * scans and a ChordPro file all be turned by the same foot switch and the same
 * band-leader message. The one place the difference leaks through is
 * [isReflowable]: a chart's page count depends on the size of the screen it is
 * being read on, and a PDF's does not.
 */
sealed interface PageSource : Closeable {
    val pageCount: Int

    /**
     * Whether page boundaries move when the viewport changes.
     *
     * This matters for band-leader mode. Two devices showing the same PDF agree
     * on what page four is; a phone and a tablet showing the same ChordPro at
     * different font sizes do not. The session protocol therefore syncs a page
     * number, and the viewer translates it through the reflow - see the note in
     * [ChartPageSource].
     */
    val isReflowable: Boolean get() = false
}

/** A page source that draws to a bitmap: PDFs and images. */
interface RasterPageSource : PageSource {
    /**
     * Draws a page to fit [widthPx] by [heightPx].
     *
     * [crop] narrows it to a region of the page - the box the music actually
     * occupies, so that the margins of a scan do not take up half the screen.
     * It is passed down to the renderer rather than applied to the finished
     * bitmap, so a PDF re-renders at the larger scale and comes out sharp
     * instead of being a magnified picture of a smaller one.
     */
    suspend fun render(
        page: Int,
        widthPx: Int,
        heightPx: Int,
        crop: ContentRect? = null,
    ): Bitmap?
}

/** A page source that produces text rows: chord charts and tab. */
interface TextPageSource : PageSource {
    fun rowsFor(page: Int): List<ChartRow>

    /** The widest row as laid out, which is what a sideways scroll has to cover. */
    val widestRow: Int

    /**
     * The widest row *before* wrapping.
     *
     * This is the number a fit-to-width zoom needs: the question it answers is
     * "how large can the font be before the chart's longest line stops fitting",
     * and the longest line is a property of the chart, not of the width it has
     * already been wrapped to.
     */
    val naturalWidestRow: Int
}

/**
 * Pages of a PDF, through the platform renderer.
 *
 * [PdfRenderer] is explicitly not thread-safe and allows exactly one page open
 * at a time, which the mutex here enforces. Getting this wrong does not fail
 * loudly - it produces the wrong page, occasionally, under load, which is the
 * worst possible symptom for a page turner.
 */
class PdfPageSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : RasterPageSource {

    private val mutex = Mutex()

    override val pageCount: Int get() = renderer.pageCount

    override suspend fun render(
        page: Int,
        widthPx: Int,
        heightPx: Int,
        crop: ContentRect?,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (page !in 0 until pageCount || widthPx <= 0 || heightPx <= 0) return@withContext null
        mutex.withLock {
            runCatching {
                renderer.openPage(page).use { pdfPage ->
                    val pageWidth = pdfPage.width.toFloat()
                    val pageHeight = pdfPage.height.toFloat()
                    val region = crop ?: ContentRect.FULL
                    val regionWidth = (region.width * pageWidth).coerceAtLeast(1f)
                    val regionHeight = (region.height * pageHeight).coerceAtLeast(1f)

                    // Fit the region inside the viewport without distorting it.
                    // Sheet music that has been stretched to fill a screen is
                    // harder to read, not easier.
                    val scale = minOf(widthPx / regionWidth, heightPx / regionHeight)
                    val targetWidth = (regionWidth * scale).toInt().coerceAtLeast(1)
                    val targetHeight = (regionHeight * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(
                        targetWidth,
                        targetHeight,
                        Bitmap.Config.ARGB_8888,
                    )
                    // The renderer composites onto whatever is already there,
                    // and a fresh bitmap is transparent. Without this, a scan
                    // with a transparent background renders as black on black.
                    bitmap.eraseColor(Color.WHITE)

                    // No transform at all in the ordinary case, so the untouched
                    // path stays exactly what it was: the renderer scales the
                    // page to the bitmap itself.
                    val transform = if (crop == null) {
                        null
                    } else {
                        Matrix().apply {
                            setTranslate(-region.left * pageWidth, -region.top * pageHeight)
                            postScale(scale, scale)
                        }
                    }
                    pdfPage.render(
                        bitmap,
                        null,
                        transform,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                    )
                    bitmap
                }
            }.getOrNull()
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        suspend fun open(context: Context, uri: Uri): PdfPageSource? = withContext(Dispatchers.IO) {
            runCatching {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@runCatching null
                PdfPageSource(descriptor, PdfRenderer(descriptor))
            }.getOrNull()
        }
    }
}

/**
 * One or more image files as pages.
 *
 * A single image is a one-page document. Several, named in order, are a
 * multi-page one - which is how scanned charts usually arrive.
 */
class ImagePageSource(
    private val context: Context,
    private val uris: List<Uri>,
) : RasterPageSource {

    override val pageCount: Int get() = uris.size

    override suspend fun render(
        page: Int,
        widthPx: Int,
        heightPx: Int,
        crop: ContentRect?,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val uri = uris.getOrNull(page) ?: return@withContext null
        runCatching {
            // Two passes: measure, then decode subsampled. A 40-megapixel
            // phone photo of a chart decoded at full size is 160MB of bitmap
            // and an immediate out-of-memory kill.
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val region = crop ?: ContentRect.FULL
            val regionWidth = (region.width * bounds.outWidth).toInt().coerceAtLeast(1)
            val regionHeight = (region.height * bounds.outHeight).toInt().coerceAtLeast(1)

            // The subsample is chosen for the *region* being shown, not the whole
            // file, which is what makes a zoom sharper rather than merely bigger:
            // the same viewport now holds fewer source pixels, so fewer are
            // thrown away.
            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(regionWidth, regionHeight, widthPx, heightPx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, options)
            } ?: return@runCatching null

            if (crop == null) return@runCatching decoded

            // Crop in the decoded bitmap's own coordinates, which the subsample
            // has already shrunk.
            val left = (region.left * decoded.width).toInt().coerceIn(0, decoded.width - 1)
            val top = (region.top * decoded.height).toInt().coerceIn(0, decoded.height - 1)
            val width = (region.width * decoded.width).toInt()
                .coerceIn(1, decoded.width - left)
            val height = (region.height * decoded.height).toInt()
                .coerceIn(1, decoded.height - top)

            val cropped = Bitmap.createBitmap(decoded, left, top, width, height)
            if (cropped !== decoded) decoded.recycle()
            cropped
        }.getOrNull()
    }

    override fun close() = Unit

    companion object {
        /** Largest power-of-two subsample that still covers the viewport. */
        fun sampleSizeFor(
            sourceWidth: Int,
            sourceHeight: Int,
            targetWidth: Int,
            targetHeight: Int,
        ): Int {
            if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
            var sample = 1
            while (
                sourceWidth / (sample * 2) >= targetWidth &&
                sourceHeight / (sample * 2) >= targetHeight
            ) {
                sample *= 2
            }
            return sample
        }
    }
}

/**
 * A text chart, laid out into pages for the current viewport.
 *
 * Unlike a PDF this is reflowable, and doubly so: the page count depends on the
 * height of the screen, and - once lines are wrapped rather than left to run off
 * the edge - the *rows* depend on its width and on the font size too. Every
 * change of viewport, font size or wrap setting therefore goes through
 * [relayout], which reports where the reader's line ended up so the viewer can
 * keep them on it rather than on "page 3", which may now be somewhere else
 * entirely.
 *
 * Positions in and out of this class are **song line numbers**, not row
 * indexes. A row index means nothing across a reflow: wrapping turns one line
 * into two rows at one font size and three at the next. The song line does not
 * move.
 */
class ChartPageSource(
    private val original: Song,
    private val unicodeAccidentals: Boolean,
) : TextPageSource {

    private var pages: List<List<ChartRow>> = listOf(emptyList())

    /**
     * The chart's rows before any wrapping, kept because a pinch re-lays out the
     * chart on every frame of the gesture and turning the song back into rows is
     * the expensive half. Only a transposition changes them.
     */
    private var baseRows: List<ChartRow> = emptyList()
    private var linesPerPage: Int = DEFAULT_LINES_PER_PAGE
    private var columns: Int = DEFAULT_COLUMNS
    private var wrapLines: Boolean = true

    /** The transposed song currently being shown, and what was done to it. */
    var current: TransposeResult = Transposer.transpose(original, TransposeRequest())
        private set

    override val pageCount: Int get() = pages.size
    override val isReflowable: Boolean get() = true
    override var widestRow: Int = 0
        private set
    override var naturalWidestRow: Int = 0
        private set

    init {
        rebuildRows()
    }

    override fun rowsFor(page: Int): List<ChartRow> = pages.getOrElse(page) { emptyList() }

    /**
     * Every page as laid out right now.
     *
     * Handed out whole so the viewer can hold it as observable state: what
     * changes when a chart is transposed is the content of these rows, not the
     * page count, the page number or this object, and a caller watching any of
     * those would never learn that the chart had been rewritten.
     */
    fun pages(): List<List<ChartRow>> = pages

    /** Re-transposes and re-paginates. Returns the page holding [keepSourceLine]. */
    fun apply(request: TransposeRequest, keepSourceLine: Int = 0): Int {
        current = Transposer.transpose(original, request)
        rebuildRows()
        return pageContaining(keepSourceLine)
    }

    /**
     * Re-wraps and re-paginates for a new viewport, keeping the reader in place.
     *
     * Returns the page that now holds the line they were reading.
     */
    fun relayout(
        linesPerPage: Int,
        columns: Int,
        wrapLines: Boolean,
        keepSourceLine: Int,
    ): Int {
        this.linesPerPage = linesPerPage
        this.columns = columns
        this.wrapLines = wrapLines
        relayoutRows()
        return pageContaining(keepSourceLine)
    }

    /** The song line [page] starts on: the reader's position, durably. */
    fun sourceLineOf(page: Int): Int =
        ChartLayout.firstSourceLineOf(pages.getOrElse(page) { emptyList() })

    private fun pageContaining(sourceLine: Int): Int =
        ChartLayout.pageContainingSourceLine(pages, sourceLine)

    private fun rebuildRows() {
        baseRows = ChartLayout.rows(current.song, unicodeAccidentals)
        naturalWidestRow = ChartLayout.widestRow(baseRows)
        relayoutRows()
    }

    private fun relayoutRows() {
        val laid = if (wrapLines) ChartLayout.wrap(baseRows, columns) else baseRows
        widestRow = ChartLayout.widestRow(laid)
        pages = ChartLayout.paginate(laid, linesPerPage)
    }

    override fun close() = Unit

    companion object {
        const val DEFAULT_LINES_PER_PAGE = 30

        /** A common chart width, used only until the screen has been measured. */
        const val DEFAULT_COLUMNS = 60

        /**
         * The exception, and everything underneath it.
         *
         * Stopping at the top of the chain can say nothing at all. A static
         * initialiser that throws surfaces as ExceptionInInitializerError with
         * no message of its own and the real fault as its cause, so a report
         * that does not follow the chain names the messenger. The full class
         * names are used rather than the short ones because in a release build
         * they are obfuscated, and the package is half of what makes an
         * obfuscated name findable in the mapping file.
         */
        private fun describe(error: Throwable): String {
            val chain = mutableListOf<String>()
            var current: Throwable? = error
            while (current != null && chain.size < MAX_CAUSE_DEPTH) {
                val step = current
                chain += step::class.java.name + (step.message?.let { ": $it" } ?: "")
                current = step.cause?.takeIf { it !== step }
            }
            return chain.joinToString(", caused by ")
        }

        /** Enough to reach the real fault under a wrapper or two. */
        private const val MAX_CAUSE_DEPTH = 4


        /**
         * [kind] is passed rather than sniffed because a Word document has to be
         * unzipped before there is any text to sniff. Everything after that point
         * is identical for every text chart - see DocumentSources.readChartText.
         */
        suspend fun open(
            context: Context,
            song: SongRef,
            uri: Uri,
            unicodeAccidentals: Boolean,
        ): OpenResult = withContext(Dispatchers.IO) {
            val text = org.droidmusic.app.data.DocumentSources
                .readChartText(context.contentResolver, uri, song.kind)
                ?: return@withContext OpenResult.Failed(
                    org.droidmusic.app.data.DocumentSources
                        .describeOpenFailure(context.contentResolver, song),
                )

            // A chart file is arbitrary input - written by hand, exported by
            // some other app, or sent by a band mate - and the honest failure
            // for arbitrary input is "this one will not open", which the viewer
            // already knows how to say. It is never a dead app thirty seconds
            // before the downbeat.
            //
            // What it does say now is which step failed. The bytes are already
            // in hand at this point, so storage and permissions are not the
            // cause and saying they might be sends somebody to check a setting
            // that was never wrong.
            runCatching {
                ChartPageSource(SongParser.parse(text), unicodeAccidentals)
            }.fold(
                onSuccess = { OpenResult.Ok(it) },
                onFailure = { failure ->
                    OpenResult.Failed(
                        "It was read - ${text.length} characters - but could not be laid " +
                            "out as a chart. " + describe(failure),
                    )
                },
            )
        }
    }
}

object PageSources {

    /**
     * Opens whatever [song] turns out to be, or says why it could not - which on
     * stage means "this chart is not going to work, and here is the thing to do
     * about it" rather than a blank screen.
     */
    suspend fun open(
        context: Context,
        song: SongRef,
        unicodeAccidentals: Boolean,
    ): OpenResult {
        val uri = Uri.parse(song.uri)
        return when (song.kind) {
            FileKind.PDF -> PdfPageSource.open(context, uri)
                ?.let { OpenResult.Ok(it) }
                ?: OpenResult.Failed(
                    org.droidmusic.app.data.DocumentSources
                        .describeOpenFailure(context.contentResolver, song),
                )

            FileKind.IMAGE -> OpenResult.Ok(ImagePageSource(context, listOf(uri)))

            FileKind.CHORDPRO, FileKind.TEXT, FileKind.DOCX ->
                ChartPageSource.open(context, song, uri, unicodeAccidentals)

            FileKind.UNKNOWN -> OpenResult.Failed(
                "DroidMusic does not recognise this kind of file.",
            )
        }
    }
}
