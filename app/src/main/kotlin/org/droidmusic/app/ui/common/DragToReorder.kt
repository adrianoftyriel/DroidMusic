package org.droidmusic.app.ui.common

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * The arithmetic behind dragging a row up and down a list, with no Compose in
 * it.
 *
 * Reordering by hand is the one gesture in this app where being slightly wrong
 * is silent: a row that settles one place further than the finger asked for
 * still looks like a list, and nobody counts the running order again before they
 * walk on stage. So the part that decides where a row lands is plain arithmetic
 * over plain numbers, and it is tested.
 *
 * All offsets are in the list's own viewport coordinates - the same ones
 * `LazyListLayoutInfo` reports - which is why scrolling under a stationary
 * finger needs no compensation here.
 */
object ReorderGeometry {

    /** One visible row: where the list has laid it out, and how tall it is. */
    data class Row(val index: Int, val offset: Int, val size: Int)

    /**
     * The index the dragged row should take, or null to leave the order alone.
     *
     * The test is the floating row's centre line rather than its edges: a row
     * only displaces a neighbour once it has covered more than half of it, which
     * is the point at which a player would say the two have swapped.
     */
    fun targetIndex(rows: List<Row>, draggingIndex: Int, floatingStart: Float, floatingSize: Int): Int? {
        val centre = floatingStart + floatingSize / 2f
        val target = rows.firstOrNull { row ->
            row.index != draggingIndex && centre >= row.offset && centre < row.offset + row.size
        }
        return target?.index
    }

    /**
     * How far to scroll this frame when the dragged row is held against an edge
     * of the list, positive being further down the list.
     *
     * A long set list is taller than a phone, so a drag from the encore back to
     * the top has to be able to pull the list along with it.
     */
    fun autoScroll(
        floatingStart: Float,
        floatingEnd: Float,
        viewportStart: Float,
        viewportEnd: Float,
        maxStep: Float,
    ): Float {
        val pastEnd = floatingEnd - viewportEnd
        if (pastEnd > 0f) return pastEnd.coerceAtMost(maxStep)
        val pastStart = floatingStart - viewportStart
        if (pastStart < 0f) return pastStart.coerceAtLeast(-maxStep)
        return 0f
    }
}

/**
 * Tracks one press-hold-drag over a [LazyListState].
 *
 * The list this drives is expected to be nothing but the reorderable rows, so
 * that a lazy item index is the index in the underlying list.
 */
class DragReorderState internal constructor(
    private val listState: LazyListState,
    private val haptics: HapticFeedback?,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    /** The row under the finger, or null when nothing is being dragged. */
    var draggingIndex by mutableStateOf<Int?>(null)
        private set

    val isDragging: Boolean get() = draggingIndex != null

    /**
     * Set the moment a long press is recognised, and read by the row's own
     * click handler.
     *
     * A long press that never turns into a drag still ends in a pointer going
     * up, and a plain clickable row treats that as a tap - so holding a song to
     * move it, then thinking better of it, would open the song instead. The flag
     * goes up at the press rather than at the lift because the click can be
     * delivered before the gesture detector hears that the finger has gone.
     */
    private var suppressClick by mutableStateOf(false)

    // Snapshot-backed, because the row's own graphics layer reads them while the
    // finger is moving; a plain field would leave the row where it started.
    private var pressedAt by mutableStateOf(0f)
    private var travelled by mutableStateOf(0f)
    private var draggingSize = 0
    private var moved = false

    /** Where the top of the floating row sits, in viewport coordinates. */
    private val floatingStart: Float get() = pressedAt + travelled

    private fun infoFor(index: Int) =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

    /** How far to lift the dragged row from where the list has laid it out. */
    val draggingOffset: Float
        get() {
            val index = draggingIndex ?: return 0f
            val info = infoFor(index) ?: return 0f
            return floatingStart - info.offset
        }

    /**
     * Picks up the row the list knows by [key].
     *
     * By key rather than by position, because a gesture detector is set up once
     * per row and then left alone: a row that has been dragged from fourth to
     * second still holds the position it was composed at, and picking it up
     * again by that number would grab whatever is fourth now.
     */
    internal fun start(key: Any) {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        draggingIndex = info.index
        pressedAt = info.offset.toFloat()
        draggingSize = info.size
        travelled = 0f
        moved = false
        suppressClick = true
        haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    internal fun drag(deltaY: Float) {
        if (draggingIndex == null) return
        travelled += deltaY
        moved = true
        settle()
    }

    /**
     * Hands the dragged row past any neighbour it now covers.
     *
     * Called on every drag and on every frame of an auto-scroll, because a
     * finger held still at the bottom of the screen is still moving the list
     * past the row underneath it.
     */
    internal fun settle() {
        val from = draggingIndex ?: return
        val size = infoFor(from)?.size ?: draggingSize
        val rows = listState.layoutInfo.visibleItemsInfo.map {
            ReorderGeometry.Row(it.index, it.offset, it.size)
        }
        val to = ReorderGeometry.targetIndex(rows, from, floatingStart, size) ?: return
        onMove(from, to)
        // The layout has not run yet, so from here on the row is tracked at the
        // place it is moving to; its offset there is the one the list already
        // reports for that slot, which keeps the row under the finger.
        draggingIndex = to
    }

    internal fun autoScrollStep(maxStep: Float): Float {
        val from = draggingIndex ?: return 0f
        val size = infoFor(from)?.size ?: draggingSize
        val info = listState.layoutInfo
        return ReorderGeometry.autoScroll(
            floatingStart = floatingStart,
            floatingEnd = floatingStart + size,
            viewportStart = info.viewportStartOffset.toFloat(),
            viewportEnd = info.viewportEndOffset.toFloat(),
            maxStep = maxStep,
        )
    }

    internal fun end() {
        // A drag that moved has already swallowed the pointer's movement, so no
        // click is coming and nothing is waiting to be suppressed.
        if (moved) suppressClick = false
        draggingIndex = null
        travelled = 0f
        moved = false
    }

    /**
     * True when this click is the tail of a long press and should be ignored.
     * Asking clears it, so the next real tap goes through.
     */
    fun consumeSuppressedClick(): Boolean {
        if (!suppressClick) return false
        suppressClick = false
        return true
    }
}

/**
 * Remembers the drag state for a list, and runs the edge auto-scroll while a
 * drag is in flight.
 *
 * [onMove] is called as the row crosses each neighbour rather than once at the
 * end, so the list under the finger always shows the order that would be kept if
 * the finger lifted now.
 */
@Composable
fun rememberDragReorderState(
    listState: LazyListState,
    onMove: (from: Int, to: Int) -> Unit,
): DragReorderState {
    val haptics = LocalHapticFeedback.current
    val move = rememberUpdatedState(onMove)
    val state = remember(listState) {
        DragReorderState(listState, haptics) { from, to -> move.value(from, to) }
    }

    LaunchedEffect(state.isDragging) {
        if (!state.isDragging) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            state.settle()
            val step = state.autoScrollStep(AUTO_SCROLL_STEP)
            if (step != 0f) listState.scrollBy(step)
        }
    }

    return state
}

/** Pixels per frame at the fastest, which is a readable scroll rather than a jump. */
private const val AUTO_SCROLL_STEP = 18f

/**
 * Makes one row of a list draggable after a long press.
 *
 * [key] must be the key the lazy list itself uses for this row: it is what the
 * gesture reaches for when the finger goes down, and unlike [index] it still
 * means the same row after the order has been changed underneath it. [index] is
 * only used to decide whether this row is the one currently in the air.
 *
 * [onDragFinished] is where the new order gets kept. It is captured once, when
 * the gesture detector is set up, so anything it reads from the composition
 * around it has to be read through state rather than closed over.
 */
fun Modifier.dragToReorder(
    state: DragReorderState,
    index: Int,
    key: Any,
    onDragFinished: () -> Unit,
): Modifier {
    val dragging = state.draggingIndex == index
    return this
        .zIndex(if (dragging) 1f else 0f)
        .graphicsLayer {
            // Read in the draw phase, so a moving finger redraws the row rather
            // than recomposing the list under it.
            if (state.draggingIndex == index) {
                translationY = state.draggingOffset
                // A lifted row, so it is obvious which one the finger has and
                // that the rest of the list is sliding under it.
                shadowElevation = 6.dp.toPx()
            }
        }
        .pointerInput(key) {
            detectDragGesturesAfterLongPress(
                onDragStart = { state.start(key) },
                onDrag = { change, amount ->
                    change.consume()
                    state.drag(amount.y)
                },
                // Both endings are the same ending. A cancel here is usually
                // the row's own click handler having consumed the pointer going
                // up, not a gesture that failed, and either way the order the
                // list is showing is the one the finger asked for.
                onDragEnd = {
                    state.end()
                    onDragFinished()
                },
                onDragCancel = {
                    state.end()
                    onDragFinished()
                },
            )
        }
}
