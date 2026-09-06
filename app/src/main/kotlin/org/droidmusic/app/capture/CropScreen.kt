package org.droidmusic.app.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.droidmusic.app.ui.common.Header

/**
 * Agreeing on where the page is, before anything is cut.
 *
 * The camera here is the system's, so there is no live overlay of the edges to
 * line a shot up against - the first and only look at the crop is this one. It
 * shows the photograph with the four corners the finder settled on drawn over
 * it, each one draggable, and it will not straighten anything until the player
 * says the corners are right. When the finder came back with nothing, the same
 * screen opens on a rectangle just inside the frame, so a photograph the
 * arithmetic could not read is still one drag away from a straightened page
 * rather than a photograph silently kept whole.
 *
 * The brightness and contrast sliders are here for the same reason the corners
 * are: what a page needs is decided by the light in the room, and this is the
 * only screen where the page and the person who saw that room are both present.
 * They are applied to the photograph as it is drawn, so what is on screen is
 * what gets kept.
 */
@Composable
fun CropScreen(
    pending: PendingPage,
    initialEnhancement: PageEnhancement,
    busy: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    onApply: (PageQuad?, PageEnhancement) -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
) {
    var quad by remember(pending.photo) { mutableStateOf(pending.quad) }
    var enhancement by remember(pending.photo) { mutableStateOf(initialEnhancement) }

    BackHandler(enabled = true) { onCancel() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(
            title = "Check the crop",
            subtitle = if (pending.detected) {
                "Drag a corner to adjust"
            } else {
                "Drag the corners onto the page"
            },
            onBack = onCancel,
        )

        error?.let { message ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissError) { Text("Dismiss") }
            }
        }

        CropCanvas(
            pending = pending,
            quad = quad,
            enhancement = enhancement,
            onQuadChange = { quad = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        EnhancementSliders(
            enhancement = enhancement,
            onChange = { enhancement = it },
        )

        Text(
            if (pending.detected) {
                "The edges of the page were found. Check the corners sit on them, then " +
                    "straighten the page."
            } else {
                "The edges of the page could not be found. Put each corner on a corner of " +
                    "the page, or keep the photo whole."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { onApply(quad, enhancement) }, enabled = !busy) {
                Text("Straighten page")
            }
            OutlinedButton(onClick = { onApply(null, enhancement) }, enabled = !busy) {
                Text("Whole photo")
            }
            TextButton(onClick = onRetake, enabled = !busy) {
                Text("Retake")
            }
            if (busy) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
            }
        }
    }
}

/**
 * The photograph, the crop drawn on it, and the four handles.
 *
 * Everything is drawn into one canvas rather than an image with an overlay on
 * top of it, because the crop and the photograph have to agree to the pixel:
 * the same fit that places the photograph inside the canvas is the one that
 * turns a finger's position back into a point in the photograph, so a corner
 * dropped on the edge of the page cuts on the edge of the page.
 */
@Composable
private fun CropCanvas(
    pending: PendingPage,
    quad: PageQuad,
    enhancement: PageEnhancement,
    onQuadChange: (PageQuad) -> Unit,
    modifier: Modifier = Modifier,
) {
    var preview by remember(pending.photo) { mutableStateOf<ImageBitmap?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember(pending.photo) { mutableStateOf(-1) }

    // The gesture handler outlives the recompositions that move the crop - it is
    // restarted only when its keys change - so it has to read the current quad
    // rather than the one it closed over when the finger went down. Without
    // these, every drag after the first starts from the original corners.
    val currentQuad by rememberUpdatedState(quad)
    val changeQuad by rememberUpdatedState(onQuadChange)

    LaunchedEffect(pending.photo) {
        preview = withContext(Dispatchers.IO) {
            decodePreview(pending)?.asImageBitmap()
        }
    }

    val density = LocalDensity.current
    val marginPx = with(density) { HANDLE_RADIUS.toPx() * 1.5f }
    val grabPx = with(density) { GRAB_RADIUS.toPx() }
    val handlePx = with(density) { HANDLE_RADIUS.toPx() }
    val strokePx = with(density) { 2.dp.toPx() }

    val fit = remember(canvasSize, pending.width, pending.height, marginPx) {
        CropFit.of(canvasSize, pending.width, pending.height, marginPx)
    }

    // The same matrix the saved page is drawn through, handed to the draw call
    // instead of to a bitmap. Nothing is re-decoded and no second copy of the
    // photograph exists while a slider is moving - which is what makes dragging
    // one show the answer rather than a page that catches up afterwards.
    val filter = remember(enhancement) {
        if (enhancement.isNeutral) {
            null
        } else {
            ColorFilter.colorMatrix(ColorMatrix(enhancement.matrix()))
        }
    }

    Box(
        modifier
            .background(Color(0xFF15151A))
            .onSizeChanged { canvasSize = it },
        contentAlignment = Alignment.Center,
    ) {
        val image = preview
        if (image == null || fit == null) {
            CircularProgressIndicator(Modifier.width(28.dp).height(28.dp))
            return@Box
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(fit, pending.photo) {
                    detectDragGestures(
                        onDragStart = { at ->
                            dragging = nearestCorner(currentQuad, fit, at, grabPx)
                        },
                        onDragEnd = { dragging = -1 },
                        onDragCancel = { dragging = -1 },
                        onDrag = { change, delta ->
                            val index = dragging
                            if (index >= 0) {
                                change.consume()
                                // Moved from where the corner is now rather than
                                // to where the finger is: the corner keeps its
                                // offset under the fingertip instead of jumping
                                // to it, which is what lets somebody nudge a
                                // corner they cannot see past their own thumb.
                                val moved = fit.toScreen(currentQuad.corners[index]) + delta
                                val point = fit.toPhoto(moved)
                                changeQuad(
                                    currentQuad.movingCorner(index, point.x, point.y)
                                        .clampedTo(pending.width, pending.height),
                                )
                            }
                        },
                    )
                },
        ) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                dstSize = IntSize(
                    (pending.width * fit.scale).roundToInt().coerceAtLeast(1),
                    (pending.height * fit.scale).roundToInt().coerceAtLeast(1),
                ),
                colorFilter = filter,
                filterQuality = FilterQuality.Medium,
            )

            val screen = quad.corners.map { fit.toScreen(it) }
            val outline = Path().apply {
                moveTo(screen[0].x, screen[0].y)
                for (i in 1 until screen.size) lineTo(screen[i].x, screen[i].y)
                close()
            }

            // Everything outside the crop is dimmed rather than hidden, so the
            // player can still see what is being left out and whether that was
            // the intention.
            clipPath(outline, ClipOp.Difference) {
                drawRect(Color(0xCC101014))
            }
            drawPath(outline, CROP_COLOUR, style = Stroke(width = strokePx))

            screen.forEachIndexed { index, point ->
                val radius = if (index == dragging) handlePx * 1.35f else handlePx
                drawCircle(Color(0x33FFFFFF), radius = radius * 1.6f, center = point)
                drawCircle(Color.White, radius = radius, center = point)
                drawCircle(
                    CROP_COLOUR,
                    radius = radius,
                    center = point,
                    style = Stroke(width = strokePx),
                )
            }
        }
    }
}

/**
 * Brightness and contrast, and a way back to neither.
 *
 * Two sliders rather than one "enhance" switch, because the two failures they
 * fix are different failures: a page shot in shade is dark, and a pencilled
 * photocopy is flat. A single control would have to guess which one it is
 * looking at, which is the guess this whole screen exists to stop making.
 */
@Composable
private fun EnhancementSliders(
    enhancement: PageEnhancement,
    onChange: (PageEnhancement) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Enhance",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            // Only offered once there is something to undo, so the row does not
            // carry a button that does nothing on the page that needs nothing.
            if (!enhancement.isNeutral) {
                TextButton(onClick = { onChange(PageEnhancement.NONE) }) { Text("Reset") }
            }
        }

        EnhancementSlider(
            label = "Brightness",
            value = enhancement.brightness,
            onChange = { onChange(enhancement.copy(brightness = it)) },
        )
        EnhancementSlider(
            label = "Contrast",
            value = enhancement.contrast,
            onChange = { onChange(enhancement.copy(contrast = it)) },
        )
    }
}

@Composable
private fun EnhancementSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = -1f..1f,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Where the photograph sits inside the canvas.
 *
 * Fitted and centred, with a margin the width of a handle so a corner sitting
 * exactly on the edge of the photograph is still a whole circle somebody can
 * get a finger on rather than a half one clipped by the side of the screen.
 */
private data class CropFit(val left: Float, val top: Float, val scale: Float) {

    fun toScreen(corner: QuadCorner) = Offset(left + corner.x * scale, top + corner.y * scale)

    fun toPhoto(offset: Offset) =
        QuadCorner((offset.x - left) / scale, (offset.y - top) / scale)

    companion object {
        fun of(canvas: IntSize, width: Int, height: Int, margin: Float): CropFit? {
            if (canvas.width <= 0 || canvas.height <= 0 || width <= 0 || height <= 0) return null
            val available = Offset(
                canvas.width - 2f * margin,
                canvas.height - 2f * margin,
            )
            if (available.x <= 0f || available.y <= 0f) return null

            val scale = minOf(available.x / width, available.y / height)
            if (scale <= 0f) return null
            return CropFit(
                left = (canvas.width - width * scale) / 2f,
                top = (canvas.height - height * scale) / 2f,
                scale = scale,
            )
        }
    }
}

/**
 * The corner being reached for, or -1 when the touch was nowhere near one.
 *
 * A drag that starts away from every handle moves nothing. On a crop that fills
 * the screen, the alternative - always grabbing the nearest corner - turns a
 * scroll or a mis-tap into a page cut through the middle, and the player has no
 * way of knowing which corner they just moved.
 */
private fun nearestCorner(quad: PageQuad, fit: CropFit, at: Offset, within: Float): Int {
    var best = -1
    var bestDistance = within
    quad.corners.forEachIndexed { index, corner ->
        val point = fit.toScreen(corner)
        val distance = (point - at).getDistance()
        if (distance <= bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best
}

/**
 * A screen-sized decode of the photograph.
 *
 * The crop is dragged against this, not against the full page: the file is up
 * to a couple of thousand pixels on its long edge, which is more than a phone
 * can show and enough to matter when it is held open next to the bitmaps the
 * page strip is already keeping.
 */
private fun decodePreview(pending: PendingPage): Bitmap? = runCatching {
    val longest = maxOf(pending.width, pending.height)
    var sample = 1
    while (longest / sample > PREVIEW_EDGE) sample *= 2
    BitmapFactory.decodeFile(
        pending.photo.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()

private val CROP_COLOUR = Color(0xFF4C8DFF)
private val HANDLE_RADIUS = 11.dp
private val GRAB_RADIUS = 52.dp
private const val PREVIEW_EDGE = 1400
