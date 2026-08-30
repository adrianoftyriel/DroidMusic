package org.droidmusic.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.droidmusic.app.render.ImagePageSource

/** One photograph, turned into a page. */
data class ScannedPage(
    val file: File,
    val width: Int,
    val height: Int,
    /** False when no page could be found and the photograph was kept whole. */
    val straightened: Boolean,
)

/**
 * Turning a photograph of a piece of music into a page.
 *
 * The camera is Android's own: an `ACTION_IMAGE_CAPTURE` intent writing to a
 * file this app owns. That is a deliberate choice over embedding a camera. It
 * needs no CAMERA permission at all - the picture is taken by the camera app,
 * which already has one - it brings no library into the APK, and it gives the
 * player the viewfinder they already know, with their own flash, focus and grid.
 * What it costs is a live preview of the detected edges, which a scanner app
 * built on CameraX would have. That is a real loss and the reason the result is
 * always shown for approval before it is kept.
 */
object PageScanner {

    /**
     * The longest edge of a saved page.
     *
     * A4 at 200dpi is about 2300 pixels tall, which is past the point where more
     * resolution shows a player anything they can read on a stand. Higher costs
     * megabytes per page in a file they will carry to a gig and re-render on
     * every page turn.
     */
    const val MAX_OUTPUT_EDGE = 2200

    /**
     * The size the page is *looked for* at.
     *
     * Edge finding does not want detail, it wants the shape - and at this size a
     * whole photograph is a fifth of a megapixel, which is a few milliseconds of
     * work instead of a visible pause holding a phone over a music stand.
     */
    const val ANALYSIS_EDGE = 480

    const val JPEG_QUALITY = 90

    /**
     * Reads the photograph at [source], straightens it if a page can be found,
     * and writes the result to [into].
     */
    suspend fun scan(context: Context, source: File, into: File): ScannedPage? =
        withContext(Dispatchers.IO) {
            runCatching {
                val photo = decodeUpright(source) ?: return@runCatching null

                val quad = detectOn(photo)
                val page = if (quad == null) photo else straighten(photo, quad)

                into.parentFile?.mkdirs()
                into.outputStream().use { out ->
                    page.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }

                val result = ScannedPage(into, page.width, page.height, straightened = quad != null)
                if (page !== photo) page.recycle()
                photo.recycle()
                result
            }.getOrNull()
        }

    /**
     * Decodes the photograph at a workable size, the right way up.
     *
     * The rotation matters more than it looks. A phone held in portrait records
     * a landscape frame plus an EXIF tag saying which way up it was, and a
     * decoder that ignores the tag hands back a page lying on its side. The page
     * finder would then find it perfectly and straighten it into a sideways
     * rectangle, which is the kind of bug that looks like the detector failing.
     */
    private fun decodeUpright(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImagePageSource.sampleSizeFor(
                bounds.outWidth,
                bounds.outHeight,
                MAX_OUTPUT_EDGE,
                MAX_OUTPUT_EDGE,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

        val degrees = exifRotation(file)
        if (degrees == 0) return decoded

        val rotated = runCatching {
            Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply { postRotate(degrees.toFloat()) },
                true,
            )
        }.getOrNull() ?: return decoded

        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    @Suppress("DEPRECATION")
    private fun exifRotation(file: File): Int = runCatching {
        // The platform's own ExifInterface rather than the AndroidX one. This
        // reads a file on local storage, which is the case the platform class
        // has always handled; pulling in a library to do it would be a dependency
        // bought for nothing.
        when (
            android.media.ExifInterface(file.absolutePath)
                .getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                )
        ) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    /** Runs the page finder on a shrunk copy and scales the answer back up. */
    private fun detectOn(photo: Bitmap): PageQuad? = runCatching {
        val longest = maxOf(photo.width, photo.height)
        if (longest <= 0) return null
        val scale = if (longest > ANALYSIS_EDGE) ANALYSIS_EDGE.toFloat() / longest else 1f
        val width = (photo.width * scale).toInt().coerceAtLeast(2)
        val height = (photo.height * scale).toInt().coerceAtLeast(2)

        val small = Bitmap.createScaledBitmap(photo, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        if (small !== photo) small.recycle()

        PageDetector.detect(pixels, width, height)
            ?.scaled(photo.width.toFloat() / width, photo.height.toFloat() / height)
    }.getOrNull()

    /**
     * Maps the four corners onto a rectangle.
     *
     * `setPolyToPoly` with four points is a full perspective transform, which is
     * what this needs and what a rotation would not be: a page photographed from
     * slightly above is a trapezium, and de-rotating it leaves it a trapezium.
     * It can refuse - four points that are collinear or coincident have no such
     * transform - and refusing is reported by returning the photograph untouched
     * rather than by drawing something wrong.
     */
    private fun straighten(photo: Bitmap, quad: PageQuad): Bitmap {
        val (width, height) = quad.outputSize(MAX_OUTPUT_EDGE)

        val source = floatArrayOf(
            quad.topLeft.x, quad.topLeft.y,
            quad.topRight.x, quad.topRight.y,
            quad.bottomRight.x, quad.bottomRight.y,
            quad.bottomLeft.x, quad.bottomLeft.y,
        )
        val destination = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            width.toFloat(), height.toFloat(),
            0f, height.toFloat(),
        )

        val matrix = Matrix()
        if (!matrix.setPolyToPoly(source, 0, destination, 0, 4)) return photo

        val output = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return photo

        Canvas(output).apply {
            // Paper, not transparency: a corner the transform does not reach
            // should read as the edge of a page rather than as a hole.
            drawColor(Color.WHITE)
            drawBitmap(
                photo,
                matrix,
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
            )
        }
        return output
    }
}
