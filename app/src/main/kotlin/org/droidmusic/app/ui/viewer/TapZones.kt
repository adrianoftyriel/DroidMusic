package org.droidmusic.app.ui.viewer

import kotlinx.serialization.Serializable
import org.droidmusic.app.input.PageAction

/**
 * Where a tap lands and what it means.
 *
 * The default split is the one asked for and the one that is right: the left
 * third goes back, the right two thirds go forward. It is asymmetric on purpose.
 * Forward is what you do ninety-nine times out of a hundred, so it gets the
 * larger, easier target, and the smaller back zone is on the side your hand is
 * least likely to brush while turning a page in a hurry.
 *
 * A small centre strip at the top opens the controls, so the transport and the
 * set list are reachable without a tap ever being ambiguous with a page turn.
 */
@Serializable
data class TapZoneConfig(
    /** Fraction of the width, from the left edge, that goes back. */
    val backFraction: Float = DEFAULT_BACK_FRACTION,
    /** Swap the two zones, for anyone who reads a screen from the other side. */
    val mirrored: Boolean = false,
    /** Fraction of the height, from the top, that opens the controls instead. */
    val controlsBandFraction: Float = DEFAULT_CONTROLS_BAND,
    /** Horizontal extent of that band, centred. */
    val controlsBandWidthFraction: Float = DEFAULT_CONTROLS_WIDTH,
    val tapToTurnEnabled: Boolean = true,
) {
    /**
     * Resolves a tap at ([x], [y]) in a viewport of [width] by [height] pixels.
     */
    fun actionAt(x: Float, y: Float, width: Float, height: Float): PageAction {
        if (width <= 0f || height <= 0f) return PageAction.NONE

        val relX = (x / width).coerceIn(0f, 1f)
        val relY = (y / height).coerceIn(0f, 1f)

        if (relY <= controlsBandFraction) {
            val halfBand = controlsBandWidthFraction / 2f
            if (relX in (0.5f - halfBand)..(0.5f + halfBand)) return PageAction.TOGGLE_CONTROLS
        }

        if (!tapToTurnEnabled) return PageAction.NONE

        val isBackSide = relX < backFraction
        val back = if (mirrored) !isBackSide else isBackSide
        return if (back) PageAction.PREVIOUS_PAGE else PageAction.NEXT_PAGE
    }

    companion object {
        const val DEFAULT_BACK_FRACTION = 1f / 3f
        const val DEFAULT_CONTROLS_BAND = 0.12f
        const val DEFAULT_CONTROLS_WIDTH = 0.34f
    }
}
