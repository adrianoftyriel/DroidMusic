package org.droidmusic.app.ui.viewer

import kotlinx.serialization.Serializable

/**
 * How many pages are on screen at once, and what "next" therefore means.
 */
enum class PageMode {
    SINGLE,
    /** Two pages side by side, turning two at a time, like a real book on a stand. */
    SPREAD,
    ;

    val pagesShown: Int get() = if (this == SPREAD) 2 else 1
}

/**
 * Decides how many pages to show for a given screen.
 *
 * The rule asked for is one page in portrait and two in landscape, and that is
 * the default. But "landscape" on a small phone is 360dp tall and 780dp wide,
 * and two pages of music on it are two columns of illegible squiggle - so the
 * decision is made on the actual size rather than the orientation alone. A
 * tablet in landscape gets its two pages; a phone in landscape gets one page,
 * bigger, which is what a player can actually read from a stand.
 *
 * [ViewerPreferences.pageModeOverride] lets anyone who disagrees say so.
 */
@Serializable
data class ViewerPreferences(
    val tapZones: TapZoneConfig = TapZoneConfig(),
    /** Null follows the screen; set to force one mode everywhere. */
    val pageModeOverride: PageMode? = null,
    val keepScreenOn: Boolean = true,
    val darkChart: Boolean = false,
    /** Half a page rather than a whole one, for charts that nearly fit. */
    val halfPageTurns: Boolean = false,
    val showChordDiagrams: Boolean = false,
    val unicodeAccidentals: Boolean = true,
    val chartFontScale: Float = 1f,
)

object PageLayoutRules {

    /**
     * The width, in dp, below which two pages stop being worth showing.
     *
     * Sheet music is roughly A4 in portrait, so a page shown two-up needs about
     * 380dp of width to stay readable at arm's length on a stand. Two of those
     * plus a gutter is where this number comes from, rather than from a device
     * category - which is the right way round, because a foldable is both a
     * phone and a tablet depending on the minute.
     */
    const val MIN_SPREAD_WIDTH_DP = 820

    /** Below this, a landscape screen is too short to show a page usefully two-up. */
    const val MIN_SPREAD_HEIGHT_DP = 380

    fun modeFor(
        widthDp: Int,
        heightDp: Int,
        override: PageMode? = null,
    ): PageMode {
        if (override != null) return override
        val landscape = widthDp > heightDp
        return if (landscape && widthDp >= MIN_SPREAD_WIDTH_DP && heightDp >= MIN_SPREAD_HEIGHT_DP) {
            PageMode.SPREAD
        } else {
            PageMode.SINGLE
        }
    }

    /**
     * The page index to move to.
     *
     * In spread mode the step is two, and the left-hand page is kept even so the
     * same two pages always face each other however you arrived at them -
     * otherwise turning back and forward again silently re-pairs the whole
     * document and the player loses their place.
     */
    fun advance(current: Int, pageCount: Int, mode: PageMode, forward: Boolean): Int {
        if (pageCount <= 0) return 0
        val step = mode.pagesShown
        val raw = if (forward) current + step else current - step
        val clamped = raw.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        return if (mode == PageMode.SPREAD) clamped - (clamped % 2) else clamped
    }

    /** The page indexes visible when the viewer is at [current]. */
    fun visiblePages(current: Int, pageCount: Int, mode: PageMode): List<Int> {
        if (pageCount <= 0) return emptyList()
        val first = current.coerceIn(0, pageCount - 1)
        return when (mode) {
            PageMode.SINGLE -> listOf(first)
            PageMode.SPREAD -> {
                val left = first - (first % 2)
                listOfNotNull(left, (left + 1).takeIf { it < pageCount })
            }
        }
    }

    /** True when there is nothing further forward in this document. */
    fun isAtEnd(current: Int, pageCount: Int, mode: PageMode): Boolean =
        visiblePages(current, pageCount, mode).lastOrNull()?.let { it >= pageCount - 1 } ?: true

    fun isAtStart(current: Int): Boolean = current <= 0
}
