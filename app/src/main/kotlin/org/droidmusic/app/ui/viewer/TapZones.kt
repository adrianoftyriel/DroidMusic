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
 * Above all of that, the top tenth of the screen - the full width of it - opens
 * the menu instead of turning a page. It is a band rather than a small centre
 * strip because it has to be findable in the dark, at arm's length, without
 * looking; a strip you have to aim at is a strip that turns the page by mistake
 * when you miss it. Ten percent of a phone held in portrait is about a
 * fingertip and a half, which is enough to hit and small enough that it is not
 * in the way of the music.
 *
 * That band is deliberately **not** configurable. Every control it opens -
 * transposition, text size, the way out - is also reachable from the menu it
 * opens, so nothing is lost by fixing it; and a player who had shrunk it to
 * nothing would be left in a full-screen chart with no visible way out, which is
 * the one state this app must never be able to reach.
 */
@Serializable
data class TapZoneConfig(
    /** Fraction of the width, from the left edge, that goes back. */
    val backFraction: Float = DEFAULT_BACK_FRACTION,
    /** Swap the two zones, for anyone who reads a screen from the other side. */
    val mirrored: Boolean = false,
    val tapToTurnEnabled: Boolean = true,
) {
    /**
     * Resolves a tap at ([x], [y]) in a viewport of [width] by [height] pixels.
     */
    fun actionAt(x: Float, y: Float, width: Float, height: Float): PageAction {
        if (width <= 0f || height <= 0f) return PageAction.NONE

        val relX = (x / width).coerceIn(0f, 1f)
        val relY = (y / height).coerceIn(0f, 1f)

        if (relY <= MENU_BAND_FRACTION) return PageAction.TOGGLE_CONTROLS

        if (!tapToTurnEnabled) return PageAction.NONE

        val isBackSide = relX < backFraction
        val back = if (mirrored) !isBackSide else isBackSide
        return if (back) PageAction.PREVIOUS_PAGE else PageAction.NEXT_PAGE
    }

    companion object {
        const val DEFAULT_BACK_FRACTION = 1f / 3f

        /** The share of the height, from the top, that opens the menu. */
        const val MENU_BAND_FRACTION = 0.10f
    }
}
