package org.droidmusic.app.capture

/**
 * Brightness and contrast for a photographed page.
 *
 * **Why a page needs this at all.** A photograph of paper is a photograph of a
 * light source as much as of the ink. Music on a stand is lit from one side by
 * whatever the room has; pencil corrections on a photocopy come out as grey on
 * grey; a phone metering for a bright room underexposes the page in front of it.
 * None of that is fixed by cropping, and all of it is the difference between a
 * chart that reads at arm's length in a dark venue and one that does not.
 *
 * **Why a slider and not a filter.** Automatic thresholding is what the page
 * finder already does, and the reason it can be wrong about a whole page is the
 * same reason it would be wrong here: it cannot see that the grey smudge is a
 * pencilled repeat rather than a shadow. Adjusting it live, against the page
 * being kept, is one drag and always right.
 *
 * Both values are zero when nothing is being asked for, so [NONE] is the default
 * and [isNeutral] is what lets every path skip the work entirely.
 */
data class PageEnhancement(
    /** -1 (darker) to 1 (brighter), 0 as photographed. */
    val brightness: Float = 0f,
    /** -1 (flatter) to 1 (harder), 0 as photographed. */
    val contrast: Float = 0f,
) {
    val isNeutral: Boolean get() = brightness == 0f && contrast == 0f

    /** The same settings with both values pulled back into range. */
    fun coerced(): PageEnhancement = PageEnhancement(
        brightness = brightness.coerceIn(-1f, 1f),
        contrast = contrast.coerceIn(-1f, 1f),
    )

    /**
     * The 4x5 colour matrix these settings mean, in the layout both
     * `android.graphics.ColorMatrix` and Compose's `ColorMatrix` use.
     *
     * Contrast is a scale about mid grey rather than about black, which is the
     * whole trick: scaling about black is a brightness change wearing contrast's
     * name, and it drives paper to white long before it has done anything for
     * the ink. Pivoting at 127.5 pushes the paper up and the notes down at once,
     * which is what "harder" means to somebody looking at a page.
     *
     * Alpha is left alone. A page is opaque, and a matrix that touched alpha
     * would turn a dimmed page into a transparent one over whatever the PDF
     * viewer draws behind it.
     */
    fun matrix(): FloatArray {
        val settings = coerced()
        val scale = 1f + settings.contrast
        val offset = settings.brightness * BRIGHTNESS_RANGE + MID_GREY * (1f - scale)

        return floatArrayOf(
            scale, 0f, 0f, 0f, offset,
            0f, scale, 0f, 0f, offset,
            0f, 0f, scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    companion object {
        val NONE = PageEnhancement()

        /**
         * How far the brightness slider can move a channel, in 0-255 units.
         *
         * Not the full range on purpose. A slider that can drive a page to solid
         * white at one end has most of its travel spent on settings nobody wants;
         * ninety-six is about a third of the scale, which covers a page shot in
         * shade and a page shot into a window and keeps the useful part of the
         * slider under the thumb.
         */
        const val BRIGHTNESS_RANGE = 96f

        /** The value contrast pivots around: mid grey, on a 0-255 scale. */
        const val MID_GREY = 127.5f
    }
}
