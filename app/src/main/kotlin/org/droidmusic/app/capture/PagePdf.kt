package org.droidmusic.app.capture

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writing scanned pages out as a PDF.
 *
 * **Why a PDF and not the images.** The app can already open a folder of images,
 * so this could have saved four JPEGs. A PDF makes the four of them one piece of
 * music: it is one row in the library, one thing to put in a set list, one file
 * to send to a band mate, and it turns pages in the right order without relying
 * on whatever a file manager decides alphabetical means.
 *
 * **Why the platform writer.** `android.graphics.pdf.PdfDocument` has been in
 * Android since API 19 and draws through the same Canvas as everything else, so
 * there is no PDF library in the APK and nothing to keep up to date. It is a
 * thin writer rather than a toolkit, which for "put this picture on that page"
 * is exactly the amount of PDF anybody needs.
 */
object PagePdf {

    /**
     * The long edge of a page, in points, so one point is 1/72 inch.
     *
     * 842 is A4's height, which is what sheet music mostly is. Pages keep the
     * proportions of the photograph rather than being squeezed into A4, so a
     * page torn from a wider songbook stays that shape and the notation is never
     * stretched.
     */
    const val LONG_EDGE_POINTS = 842f

    /**
     * Writes [pages] into [into], one page each, in order.
     *
     * Each image is decoded, drawn and released before the next is read, so the
     * memory this needs is one page rather than the whole piece - which for an
     * eight page arrangement photographed at full resolution is the difference
     * between working and being killed by the system.
     */
    suspend fun write(pages: List<File>, into: File): Boolean = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext false

        val document = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val ok = runCatching {
            for ((index, file) in pages.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: error("Could not read page ${index + 1}")

                val longest = maxOf(bitmap.width, bitmap.height).toFloat()
                val scale = LONG_EDGE_POINTS / longest
                val pageWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val pageHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)

                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create(),
                )
                page.canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(0, 0, pageWidth, pageHeight),
                    paint,
                )
                document.finishPage(page)
                bitmap.recycle()
            }

            into.parentFile?.mkdirs()
            into.outputStream().use { document.writeTo(it) }
            true
        }.getOrDefault(false)

        document.close()
        if (!ok) into.delete()
        ok
    }

    /**
     * A file name for a scan.
     *
     * Punctuation a file system might object to is replaced rather than dropped,
     * so "Blackbird (live)" stays legible instead of becoming "Blackbird live".
     */
    fun fileNameFor(title: String, fallback: String): String {
        val cleaned = title.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "-")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ', '-')
            .take(80)
        val name = cleaned.ifEmpty { fallback }
        return if (name.endsWith(".pdf", ignoreCase = true)) name else "$name.pdf"
    }
}
