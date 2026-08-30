package org.droidmusic.app.capture

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A corner, in the pixel coordinates of whatever image it was found in. */
data class QuadCorner(val x: Float, val y: Float)

/**
 * The four corners of the page in a photograph, in reading order.
 *
 * A photograph of a page on a music stand is never a rectangle. The camera is
 * held at an angle and slightly rotated, so the page arrives as a quadrilateral
 * with no two sides parallel - which is why this is four corners rather than a
 * rectangle and an angle. Straightening it is one perspective transform;
 * rotating a bounding box would leave the keystone in place.
 */
data class PageQuad(
    val topLeft: QuadCorner,
    val topRight: QuadCorner,
    val bottomRight: QuadCorner,
    val bottomLeft: QuadCorner,
) {
    val corners: List<QuadCorner> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Moves the quad from the analysed image's scale to the full photograph's. */
    fun scaled(scaleX: Float, scaleY: Float): PageQuad = PageQuad(
        QuadCorner(topLeft.x * scaleX, topLeft.y * scaleY),
        QuadCorner(topRight.x * scaleX, topRight.y * scaleY),
        QuadCorner(bottomRight.x * scaleX, bottomRight.y * scaleY),
        QuadCorner(bottomLeft.x * scaleX, bottomLeft.y * scaleY),
    )

    /** Area by the shoelace formula, always positive. */
    val area: Float
        get() {
            var sum = 0f
            val points = corners
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                sum += a.x * b.y - b.x * a.y
            }
            return abs(sum) / 2f
        }

    private fun side(a: QuadCorner, b: QuadCorner) = hypot(b.x - a.x, b.y - a.y)

    val shortestSide: Float
        get() = minOf(
            side(topLeft, topRight),
            side(topRight, bottomRight),
            side(bottomRight, bottomLeft),
            side(bottomLeft, topLeft),
        )

    /**
     * The size the straightened page should come out at.
     *
     * The longer of each pair of opposite sides wins, so nothing is squeezed:
     * the near edge of a page photographed at an angle is longer than the far
     * edge, and scaling to the shorter one would throw away the detail the
     * camera actually captured at the near end.
     */
    fun outputSize(maxEdge: Int): Pair<Int, Int> {
        val width = max(side(topLeft, topRight), side(bottomLeft, bottomRight))
        val height = max(side(topLeft, bottomLeft), side(topRight, bottomRight))
        if (width <= 0f || height <= 0f) return 1 to 1

        val longest = max(width, height)
        val scale = if (longest > maxEdge) maxEdge / longest else 1f
        return (width * scale).toInt().coerceAtLeast(1) to
            (height * scale).toInt().coerceAtLeast(1)
    }
}

/**
 * Otsu's method: the luminance that best separates a picture into two classes.
 *
 * Used rather than a fixed threshold because there is no such thing as a
 * standard photograph of a page. A phone on a music stand under a pub light
 * produces a picture where the paper is a mid grey and the "dark" background is
 * darker grey; one taken by a window has paper at 250 and shadow at 30. A fixed
 * cut is right for exactly one of those. Otsu finds the split that minimises the
 * spread within each side of it, which is the same answer a person would give by
 * eye and is a dozen lines of arithmetic.
 */
object Otsu {

    fun threshold(histogram: IntArray): Int {
        val total = histogram.sum()
        if (total <= 0) return 128

        var sum = 0.0
        for (value in histogram.indices) sum += value.toDouble() * histogram[value]

        var sumBackground = 0.0
        var weightBackground = 0
        var best = 0.0
        var bestThreshold = 0

        for (value in histogram.indices) {
            weightBackground += histogram[value]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break

            sumBackground += value.toDouble() * histogram[value]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sum - sumBackground) / weightForeground

            // Between-class variance. The threshold that maximises it is the one
            // that separates the two populations most cleanly.
            val between = weightBackground.toDouble() * weightForeground *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (between > best) {
                best = between
                bestThreshold = value
            }
        }
        return bestThreshold
    }
}

/**
 * Finding the page in a photograph of one.
 *
 * **The shape of the problem.** A player props a piece of music on a stand and
 * photographs it. What the camera returns is a page somewhere in the middle of a
 * frame that also contains the stand, a music room floor, and whatever else was
 * behind it - tilted a few degrees, and narrower at the top than the bottom
 * because the phone was not perfectly parallel to the page.
 *
 * **How the page is found.** Otsu splits the picture into two brightness
 * classes; the class the *centre of the frame* belongs to is taken as the page,
 * rather than always the brighter one, because the thing being photographed is
 * the thing pointed at. The largest connected run of those pixels is the page,
 * which is what rejects a bright window or a lamp elsewhere in the shot.
 *
 * **How the corners are found.** For a convex blob, the corner nearest the top
 * left is the pixel with the smallest x + y, the one nearest the top right has
 * the largest x - y, and so on round. It is four passes over the blob and no
 * line fitting, no Hough transform and no tuning constants beyond the sanity
 * checks - and unlike contour tracing it degrades into something sensible rather
 * than into nothing when the edge of the page is partly in shadow.
 *
 * **What it refuses to do.** Every rejection here ends in the photograph being
 * kept whole and uncropped. A scanner that mangles a page is worse than one that
 * leaves it alone, because the player only finds out at the stand.
 */
object PageDetector {

    /** The page must be at least this much of the frame to be believable. */
    const val MIN_AREA_FRACTION = 0.15f

    /**
     * ...and less than this much, or it is not a page but a failure to find one.
     *
     * A quad covering the whole frame is what every degenerate case collapses
     * to: a photograph of nothing, a frame filled edge to edge by paper, a
     * picture whose two brightness classes are meaningless. Reporting that as a
     * detection would be a lie dressed as a crop; reporting nothing lets the
     * caller keep the photograph whole and say so.
     */
    const val MAX_AREA_FRACTION = 0.98f

    /** ...and no side of it shorter than this share of the frame's short edge. */
    const val MIN_SIDE_FRACTION = 0.15f

    /**
     * The blob must fill most of the quad drawn round it.
     *
     * A page is convex, so its four corners enclose almost exactly the page. A
     * blob shaped like an L, a ring, or two bright patches with a gap between
     * them is not convex, and the quad round it encloses a great deal of nothing
     * - which is precisely the shape that yields a confident, wrong crop.
     */
    const val MIN_FILL_RATIO = 0.6f

    /** Beyond this ratio of long side to short, it is not a page. */
    const val MAX_ASPECT = 4f

    /** Fraction of the frame, centred, used to decide which class is the page. */
    const val CENTRE_FRACTION = 0.2f

    /**
     * Returns the page's corners in the pixel coordinates of [pixels], or null
     * when nothing convincing was found.
     */
    fun detect(pixels: IntArray, width: Int, height: Int): PageQuad? {
        if (width <= 2 || height <= 2 || pixels.size < width * height) return null

        val luminance = IntArray(width * height)
        val histogram = IntArray(256)
        for (i in 0 until width * height) {
            val value = luminanceOf(pixels[i])
            luminance[i] = value
            histogram[value]++
        }

        val threshold = Otsu.threshold(histogram)
        val pageIsBright = centreIsBright(luminance, width, height, threshold)

        val isPage = BooleanArray(width * height) { index ->
            if (pageIsBright) luminance[index] > threshold else luminance[index] <= threshold
        }

        val blob = largestBlob(isPage, width, height) ?: return null
        if (blob.size < MIN_AREA_FRACTION * width * height) return null

        val quad = PageQuad(
            topLeft = QuadCorner(blob.minSumX.toFloat(), blob.minSumY.toFloat()),
            topRight = QuadCorner(blob.maxDiffX.toFloat(), blob.maxDiffY.toFloat()),
            bottomRight = QuadCorner(blob.maxSumX.toFloat(), blob.maxSumY.toFloat()),
            bottomLeft = QuadCorner(blob.minDiffX.toFloat(), blob.minDiffY.toFloat()),
        )

        return quad.takeIf { it.isPlausible(width, height, blob.size) }
    }

    private fun PageQuad.isPlausible(width: Int, height: Int, blobSize: Int): Boolean {
        val frame = (width * height).toFloat()
        if (area < MIN_AREA_FRACTION * frame) return false
        if (area > MAX_AREA_FRACTION * frame) return false
        if (shortestSide < MIN_SIDE_FRACTION * min(width, height)) return false
        if (blobSize < MIN_FILL_RATIO * area) return false

        val (outWidth, outHeight) = outputSize(Int.MAX_VALUE)
        val aspect = max(outWidth, outHeight).toFloat() / max(1, min(outWidth, outHeight))
        return aspect <= MAX_ASPECT
    }

    private fun centreIsBright(
        luminance: IntArray,
        width: Int,
        height: Int,
        threshold: Int,
    ): Boolean {
        val halfW = (width * CENTRE_FRACTION / 2f).toInt().coerceAtLeast(1)
        val halfH = (height * CENTRE_FRACTION / 2f).toInt().coerceAtLeast(1)
        val cx = width / 2
        val cy = height / 2

        var bright = 0
        var dark = 0
        for (y in (cy - halfH).coerceAtLeast(0)..(cy + halfH).coerceAtMost(height - 1)) {
            for (x in (cx - halfW).coerceAtLeast(0)..(cx + halfW).coerceAtMost(width - 1)) {
                if (luminance[y * width + x] > threshold) bright++ else dark++
            }
        }
        return bright >= dark
    }

    /** The extremes of the biggest connected run of page-coloured pixels. */
    private class Blob {
        var size = 0
        var minSum = Int.MAX_VALUE
        var maxSum = Int.MIN_VALUE
        var minDiff = Int.MAX_VALUE
        var maxDiff = Int.MIN_VALUE
        var minSumX = 0; var minSumY = 0
        var maxSumX = 0; var maxSumY = 0
        var minDiffX = 0; var minDiffY = 0
        var maxDiffX = 0; var maxDiffY = 0

        fun add(x: Int, y: Int) {
            size++
            val sum = x + y
            val diff = x - y
            if (sum < minSum) { minSum = sum; minSumX = x; minSumY = y }
            if (sum > maxSum) { maxSum = sum; maxSumX = x; maxSumY = y }
            if (diff < minDiff) { minDiff = diff; minDiffX = x; minDiffY = y }
            if (diff > maxDiff) { maxDiff = diff; maxDiffX = x; maxDiffY = y }
        }
    }

    /**
     * Flood fill, iterative and with its own stack.
     *
     * Recursion here would be a stack overflow rather than a bug: a page fills
     * most of a multi-megapixel frame, and the recursion depth of a flood fill is
     * the size of the region.
     */
    private fun largestBlob(isPage: BooleanArray, width: Int, height: Int): Blob? {
        val visited = BooleanArray(isPage.size)
        // Every pixel is marked visited at the moment it is pushed, so it is
        // pushed at most once and the stack can never outgrow the image.
        val stack = IntArray(isPage.size)
        var best: Blob? = null

        for (start in isPage.indices) {
            if (!isPage[start] || visited[start]) continue

            val blob = Blob()
            var top = 0
            stack[top++] = start
            visited[start] = true

            while (top > 0) {
                val index = stack[--top]
                val x = index % width
                val y = index / width
                blob.add(x, y)

                if (x > 0) {
                    val next = index - 1
                    if (!visited[next] && isPage[next]) {
                        visited[next] = true
                        stack[top++] = next
                    }
                }
                if (x < width - 1) {
                    val next = index + 1
                    if (!visited[next] && isPage[next]) {
                        visited[next] = true
                        stack[top++] = next
                    }
                }
                if (y > 0) {
                    val next = index - width
                    if (!visited[next] && isPage[next]) {
                        visited[next] = true
                        stack[top++] = next
                    }
                }
                if (y < height - 1) {
                    val next = index + width
                    if (!visited[next] && isPage[next]) {
                        visited[next] = true
                        stack[top++] = next
                    }
                }
            }

            if (blob.size > (best?.size ?: -1)) best = blob
        }
        return best
    }

    private fun luminanceOf(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
