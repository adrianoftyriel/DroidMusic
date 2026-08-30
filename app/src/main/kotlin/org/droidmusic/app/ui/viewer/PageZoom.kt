package org.droidmusic.app.ui.viewer

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * The part of a page that actually has something printed on it, as fractions of
 * the page from 0 to 1.
 */
data class ContentRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /**
     * Whether cropping to this is worth doing.
     *
     * A page that is already printed edge to edge has nothing to trim, and
     * zooming it would shift everything by a hair for no gain - which on a stand
     * reads as the app twitching rather than as a feature.
     */
    fun trimsEnoughToZoom(): Boolean =
        width <= 1f - MIN_TRIM || height <= 1f - MIN_TRIM

    companion object {
        /** The whole page: what a page with no margins to trim comes back as. */
        val FULL = ContentRect(0f, 0f, 1f, 1f)

        /** Below this much trimmed off, zooming is not worth the movement. */
        const val MIN_TRIM = 0.04f
    }
}

/**
 * Finding the music on the page, so a double tap can fill the screen with it.
 *
 * **The problem this solves.** A scan of a piece of sheet music is mostly paper.
 * Fitted to a phone on a stand, the notation ends up occupying perhaps two
 * thirds of the height and half the width, and the player is reading something
 * far smaller than their screen could show. Cropping the margins and scaling
 * what is left is the whole feature.
 *
 * **Why the background is measured rather than assumed.** "Crop the white"
 * fails on a photograph of a page taken under a warm light, on a scan with a
 * grey cast, and on the occasional chart printed light-on-dark. So the most
 * common luminance in the page is taken as the background, whatever it happens
 * to be, and anything far enough from it is ink.
 *
 * **Why a single dark pixel is not ink.** This is the part that decides whether
 * the feature works on real scans. A speck of scanner dust, a punch hole, a
 * JPEG artefact in the margin, or the shadow of a page edge is enough to push
 * the bounding box back out to the full page, and the zoom silently does
 * nothing. So a row or column only counts as containing ink once enough pixels
 * in it are ink - which a line of music always has, and a speck never does.
 */
object InkBounds {

    /**
     * How far from the background a pixel must be to count as ink, out of 255.
     *
     * Low enough to catch a faint pencil annotation or a grey scan, high enough
     * to ignore the mottling of paper texture and JPEG noise.
     */
    const val LUMINANCE_THRESHOLD = 40

    /**
     * The fraction of a row that must be ink before the row counts as printed.
     *
     * A staff line spans nearly the full width, and a line of lyrics most of it,
     * so this can be small and still exclude specks. It is a fraction rather
     * than a count so it behaves the same on a 300 pixel scan and a 3000 pixel
     * one.
     */
    const val MIN_INK_FRACTION = 0.004f

    /** At least this many ink pixels, however narrow the page. */
    const val MIN_INK_PIXELS = 3

    /**
     * Breathing room left around the content, as a fraction of the page.
     *
     * Cropping exactly to the ink puts the outermost notehead against the edge
     * of the screen, which looks like the page has been cut off even though
     * nothing is missing.
     */
    const val PADDING = 0.012f

    /**
     * The longest edge the scan is done at.
     *
     * Margins do not need pixel accuracy - a 512-pixel scan locates an edge to
     * within a fifth of a percent of the page, and the padding above is six
     * times that. Working at this size keeps the whole scan inside a megabyte
     * and off the main thread for a couple of milliseconds rather than fifty.
     */
    const val MAX_SCAN_EDGE = 512

    /**
     * Scans a page and returns the box its content sits in, or null when the
     * page is blank.
     *
     * [pixels] is row-major ARGB, as `Bitmap.getPixels` produces.
     */
    fun of(
        pixels: IntArray,
        width: Int,
        height: Int,
        threshold: Int = LUMINANCE_THRESHOLD,
        padding: Float = PADDING,
    ): ContentRect? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null

        val background = backgroundLuminance(pixels, width * height)

        val rowInk = IntArray(height)
        val columnInk = IntArray(width)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                if (abs(luminance(pixels[base + x]) - background) > threshold) {
                    rowInk[y]++
                    columnInk[x]++
                }
            }
        }

        val minRowInk = inkFloor(width)
        val minColumnInk = inkFloor(height)

        val top = rowInk.indexOfFirst { it >= minRowInk }
        if (top < 0) return null
        val bottom = rowInk.indexOfLast { it >= minRowInk }
        val left = columnInk.indexOfFirst { it >= minColumnInk }
        if (left < 0) return null
        val right = columnInk.indexOfLast { it >= minColumnInk }

        return ContentRect(
            left = ((left.toFloat() / width) - padding).coerceIn(0f, 1f),
            top = ((top.toFloat() / height) - padding).coerceIn(0f, 1f),
            right = (((right + 1).toFloat() / width) + padding).coerceIn(0f, 1f),
            bottom = (((bottom + 1).toFloat() / height) + padding).coerceIn(0f, 1f),
        ).takeIf { it.width > 0f && it.height > 0f }
    }

    private fun inkFloor(across: Int): Int =
        maxOf(MIN_INK_PIXELS, (across * MIN_INK_FRACTION).toInt())

    /**
     * The most common luminance in the page, taken as the colour of the paper.
     *
     * The mode rather than the mean, because the mean of a page of music sits
     * somewhere between the paper and the ink and belongs to neither.
     */
    private fun backgroundLuminance(pixels: IntArray, count: Int): Int {
        val histogram = IntArray(256)
        for (i in 0 until count) histogram[luminance(pixels[i])]++
        var best = 0
        for (value in 1 until 256) if (histogram[value] > histogram[best]) best = value
        return best
    }

    private fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

/**
 * The Android side of the scan: shrink the page, read its pixels, measure it.
 *
 * Separate from [InkBounds] so that everything deciding where the edge of the
 * music is stays plain Kotlin and can be tested against pixel arrays written out
 * by hand, rather than needing a device to prove that a speck of dust does not
 * defeat the crop.
 */
fun contentRectOf(bitmap: Bitmap): ContentRect? = runCatching {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= 0) return null

    val scale = if (longest > InkBounds.MAX_SCAN_EDGE) {
        InkBounds.MAX_SCAN_EDGE.toFloat() / longest
    } else {
        1f
    }
    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)

    // Filtered on purpose. Point sampling a shrunk page steps straight over a
    // one-pixel staff line; averaging leaves it as grey, which is still a long
    // way from the paper and still reads as ink.
    val small = if (width == bitmap.width && height == bitmap.height) {
        bitmap
    } else {
        Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    val pixels = IntArray(width * height)
    small.getPixels(pixels, 0, width, 0, 0, width, height)
    if (small !== bitmap) small.recycle()

    InkBounds.of(pixels, width, height)
}.getOrNull()
