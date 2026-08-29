package org.droidmusic.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import org.droidmusic.music.ChartLayout
import org.droidmusic.music.ChartRow
import org.droidmusic.music.Song
import org.droidmusic.music.SongParser
import org.droidmusic.music.TransposeRequest
import org.droidmusic.music.TransposeResult
import org.droidmusic.music.Transposer

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
    suspend fun render(page: Int, widthPx: Int, heightPx: Int): Bitmap?
}

/** A page source that produces text rows: chord charts and tab. */
interface TextPageSource : PageSource {
    fun rowsFor(page: Int): List<ChartRow>
    val widestRow: Int
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

    override suspend fun render(page: Int, widthPx: Int, heightPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (page !in 0 until pageCount || widthPx <= 0 || heightPx <= 0) return@withContext null
            mutex.withLock {
                runCatching {
                    renderer.openPage(page).use { pdfPage ->
                        // Fit the page inside the viewport without distorting it.
                        // Sheet music that has been stretched to fill a screen is
                        // harder to read, not easier.
                        val scale = minOf(
                            widthPx.toFloat() / pdfPage.width,
                            heightPx.toFloat() / pdfPage.height,
                        )
                        val targetWidth = (pdfPage.width * scale).toInt().coerceAtLeast(1)
                        val targetHeight = (pdfPage.height * scale).toInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(
                            targetWidth,
                            targetHeight,
                            Bitmap.Config.ARGB_8888,
                        )
                        // The renderer composites onto whatever is already there,
                        // and a fresh bitmap is transparent. Without this, a scan
                        // with a transparent background renders as black on black.
                        bitmap.eraseColor(Color.WHITE)
                        pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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

    override suspend fun render(page: Int, widthPx: Int, heightPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
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

                val options = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, widthPx, heightPx)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, options)
                }
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
 * Unlike a PDF this is reflowable, so the page count is not a property of the
 * file. [relayout] is called whenever the viewport or the font size changes, and
 * it deliberately reports how the current position maps onto the new pagination
 * so the viewer can keep the player on the line they were reading rather than on
 * "page 3", which may now be somewhere else entirely.
 */
class ChartPageSource(
    private val original: Song,
    private val unicodeAccidentals: Boolean,
) : TextPageSource {

    private var pages: List<List<ChartRow>> = listOf(emptyList())
    private var allRows: List<ChartRow> = emptyList()
    private var linesPerPage: Int = DEFAULT_LINES_PER_PAGE

    /** The transposed song currently being shown, and what was done to it. */
    var current: TransposeResult = Transposer.transpose(original, TransposeRequest())
        private set

    override val pageCount: Int get() = pages.size
    override val isReflowable: Boolean get() = true
    override var widestRow: Int = 0
        private set

    init {
        rebuild(linesPerPage)
    }

    override fun rowsFor(page: Int): List<ChartRow> = pages.getOrElse(page) { emptyList() }

    /** Re-transposes and re-paginates. Returns the page holding [keepRowIndex]. */
    fun apply(request: TransposeRequest, linesPerPage: Int, keepRowIndex: Int = 0): Int {
        current = Transposer.transpose(original, request)
        this.linesPerPage = linesPerPage
        rebuild(linesPerPage)
        return pageContaining(keepRowIndex)
    }

    /**
     * Re-paginates for a new viewport, keeping the reader in place.
     *
     * Returns the page that now holds the row they were looking at. The row
     * index, not the page number, is the durable thing across a rotation.
     */
    fun relayout(linesPerPage: Int, keepRowIndex: Int): Int {
        this.linesPerPage = linesPerPage
        rebuild(linesPerPage)
        return pageContaining(keepRowIndex)
    }

    /** The index, in the full row list, of the first row on [page]. */
    fun firstRowIndexOf(page: Int): Int {
        var index = 0
        for (i in 0 until page.coerceIn(0, pages.size)) index += pages[i].size
        return index
    }

    private fun pageContaining(rowIndex: Int): Int {
        var seen = 0
        for ((index, page) in pages.withIndex()) {
            seen += page.size
            if (rowIndex < seen) return index
        }
        return (pages.size - 1).coerceAtLeast(0)
    }

    private fun rebuild(linesPerPage: Int) {
        allRows = ChartLayout.rows(current.song, unicodeAccidentals)
        widestRow = ChartLayout.widestRow(allRows)
        pages = ChartLayout.paginate(allRows, linesPerPage)
    }

    override fun close() = Unit

    companion object {
        const val DEFAULT_LINES_PER_PAGE = 30

        suspend fun open(context: Context, uri: Uri, unicodeAccidentals: Boolean): ChartPageSource? =
            withContext(Dispatchers.IO) {
                val text = org.droidmusic.app.data.DocumentSources
                    .readText(context.contentResolver, uri) ?: return@withContext null
                ChartPageSource(SongParser.parse(text), unicodeAccidentals)
            }
    }
}

object PageSources {

    /**
     * Opens whatever [song] turns out to be. Returns null when the file cannot
     * be opened at all, which on stage means "this chart is not going to work,
     * tell them now" rather than a blank screen.
     */
    suspend fun open(
        context: Context,
        song: SongRef,
        unicodeAccidentals: Boolean,
    ): PageSource? = when (song.kind) {
        FileKind.PDF -> PdfPageSource.open(context, Uri.parse(song.uri))
        FileKind.IMAGE -> ImagePageSource(context, listOf(Uri.parse(song.uri)))
        FileKind.CHORDPRO, FileKind.TEXT ->
            ChartPageSource.open(context, Uri.parse(song.uri), unicodeAccidentals)
        FileKind.UNKNOWN -> null
    }
}
