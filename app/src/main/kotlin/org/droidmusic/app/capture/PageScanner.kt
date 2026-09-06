package org.droidmusic.app.capture

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
    /** False when the photograph was kept whole rather than straightened. */
    val straightened: Boolean,
)

/**
 * A photograph that has been taken, and a crop waiting to be agreed to.
 *
 * The photograph here is already the right way up and already shrunk to the size
 * a page is saved at, so the crop the player drags is in the same pixels the
 * straightening will later read - a corner put exactly on the edge of the page
 * cuts exactly there, rather than a rounding error away from it.
 */
data class PendingPage(
    val photo: File,
    val width: Int,
    val height: Int,
    /** Where the crop editor opens: the detected page, or a plain inset. */
    val quad: PageQuad,
    /** False when nothing was found and [quad] is only a starting point. */
    val detected: Boolean,
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
 * built on CameraX would have.
 *
 * That loss is why this is two steps rather than one. [prepare] takes the
 * photograph apart and offers its best guess at where the page is; the player
 * agrees with the crop, drags it onto the page, or throws it away; only then
 * does [crop] straighten anything. Edge finding from a single still is a guess,
 * and a guess that quietly keeps the whole photograph - which is what this did
 * before - is indistinguishable at a music stand from a scanner that does not
 * crop at all.
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
     * Turns the camera's photograph at [source] into one waiting at [into] for
     * its crop to be confirmed, with the page's corners already found if they
     * can be.
     *
     * The photograph is rewritten rather than handed on as it came out of the
     * camera. What comes back from a phone camera is a many-megapixel JPEG with
     * an orientation tag on it; what the crop editor and the straightening both
     * want is an upright bitmap at the size a page is kept at, and doing that
     * turn and that shrink once here is what keeps the corners the player drags
     * and the corners the transform reads in the same coordinates.
     */
    suspend fun prepare(source: File, into: File): PendingPage? =
        withContext(Dispatchers.IO) {
            runCatching {
                val photo = decodeUpright(source) ?: return@runCatching null

                into.parentFile?.mkdirs()
                into.outputStream().use { out ->
                    photo.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }

                val found = detectOn(photo)
                val result = PendingPage(
                    photo = into,
                    width = photo.width,
                    height = photo.height,
                    quad = (found ?: PageQuad.inset(photo.width, photo.height))
                        .clampedTo(photo.width, photo.height),
                    detected = found != null,
                )
                photo.recycle()
                result
            }.getOrNull()
        }

    /**
     * Straightens the prepared photograph at [source] onto [quad] and writes the
     * page to [into]. A null [quad] keeps the photograph whole.
     *
     * Nothing here decides whether the crop is any good. By this point a person
     * has looked at the photograph with the crop drawn on it and said yes, which
     * is a better judge of where the page is than any amount of arithmetic - so
     * a crop that arrives is applied, and the only refusal left is a transform
     * the geometry cannot express, which comes back as the photograph whole.
     */
    suspend fun crop(source: File, quad: PageQuad?, into: File): ScannedPage? =
        withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val photo = BitmapFactory.decodeFile(source.absolutePath, options)
                    ?: return@runCatching null

                val page = if (quad == null) {
                    photo
                } else {
                    straighten(photo, quad.clampedTo(photo.width, photo.height))
                }

                into.parentFile?.mkdirs()
                into.outputStream().use { out ->
                    page.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }

                // Compared by identity rather than by whether a quad was passed:
                // straighten() answers a transform it cannot build by handing
                // the photograph back, and that page was not straightened.
                val result = ScannedPage(
                    file = into,
                    width = page.width,
                    height = page.height,
                    straightened = page !== photo,
                )
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
        if (degrees == 0) return shrunkToOutput(decoded)

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
        }.getOrNull() ?: return shrunkToOutput(decoded)

        if (rotated !== decoded) decoded.recycle()
        return shrunkToOutput(rotated)
    }

    /**
     * Brings the photograph down to the size a page is kept at.
     *
     * The sample size above halves, so it only ever gets within a factor of two
     * of the target and stops early on a frame whose short edge is already
     * under it - a 4000 by 3000 photograph comes out of the decoder untouched.
     * That was harmless while every kept page went through the straightening,
     * which resizes anyway; it is not harmless now that a player can keep the
     * photograph whole and get a page twice the size of every other one in the
     * PDF.
     */
    private fun shrunkToOutput(photo: Bitmap): Bitmap {
        val longest = maxOf(photo.width, photo.height)
        if (longest <= MAX_OUTPUT_EDGE) return photo

        val scale = MAX_OUTPUT_EDGE.toFloat() / longest
        val width = (photo.width * scale).toInt().coerceAtLeast(1)
        val height = (photo.height * scale).toInt().coerceAtLeast(1)

        val scaled = runCatching {
            Bitmap.createScaledBitmap(photo, width, height, true)
        }.getOrNull() ?: return photo

        if (scaled !== photo) photo.recycle()
        return scaled
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
