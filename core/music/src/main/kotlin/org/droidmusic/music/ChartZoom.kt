package org.droidmusic.music

/**
 * Zooming a text chart, which is a different thing from zooming a scan.
 *
 * A scanned page has margins to crop and pixels to re-render; zooming it means
 * showing less of the page, larger. A chord chart has neither. It is text, laid
 * out to whatever width and height it is given, so the only knob is the **font
 * size** - and turning that knob changes how many characters fit across the
 * screen and how many lines fit down it, which means every zoom re-wraps and
 * re-paginates the chart. That is why the arithmetic lives here in the core,
 * where it can be tested, rather than in the gesture handler.
 *
 * Two gestures use it:
 *
 *  - **Pinch** sets the scale directly, and is the general answer to "I need
 *    this bigger" from wherever the player is standing tonight.
 *  - **Double tap** asks for [fitWidthScale]: the largest font at which the
 *    chart's widest line still fits across the screen. That is the chart
 *    equivalent of cropping a scan's margins - it spends the width that is
 *    currently going to waste, and at that size nothing wraps and nothing
 *    scrolls sideways.
 *
 * Both are clamped, and both are a scale rather than a font size, so the size
 * the player set in Settings stays the thing they are zooming relative to.
 */
object ChartZoom {

    /** Half size. Below this a chart is small enough to be decorative. */
    const val MIN = 0.5f

    /** Four times. Past this a page holds a couple of words. */
    const val MAX = 4f

    /** One press of the bigger/smaller buttons. */
    const val STEP = 1.15f

    /**
     * How much bigger a fit-to-width zoom must be before it is worth doing.
     *
     * The same judgement as a scan with no margins to trim: a double tap that
     * moves everything by two percent reads as the app twitching rather than as
     * a feature. It also means a chart already wider than the screen - where
     * fitting the width would mean shrinking the text, which is not what anyone
     * double tapping is asking for - simply does nothing.
     */
    const val MIN_WORTHWHILE_GAIN = 1.05f

    fun clamp(scale: Float): Float =
        if (!scale.isFinite()) 1f else scale.coerceIn(MIN, MAX)

    /** One press of a bigger/smaller button, clamped. */
    fun step(scale: Float, larger: Boolean): Float =
        clamp(if (larger) scale * STEP else scale / STEP)

    /**
     * The scale that would make the widest line exactly fill the width.
     *
     * [charWidthPx] is the width of one monospaced character *at the size
     * currently on screen*, which is why the answer is a multiplier of the
     * current size and needs only one measurement to work out: in a monospaced
     * font the width of a line is the count of its characters times one number.
     */
    fun fitWidthScale(widestRowChars: Int, availableWidthPx: Float, charWidthPx: Float): Float {
        if (widestRowChars <= 0 || availableWidthPx <= 0f || charWidthPx <= 0f) return 1f
        return availableWidthPx / (widestRowChars * charWidthPx)
    }

    fun worthZooming(fitScale: Float): Boolean =
        fitScale.isFinite() && fitScale >= MIN_WORTHWHILE_GAIN
}
