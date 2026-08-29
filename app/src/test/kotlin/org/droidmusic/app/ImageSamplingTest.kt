package org.droidmusic.app

import org.droidmusic.app.render.ImagePageSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSamplingTest {

    // A phone photograph of a chart is often 40 megapixels. Decoded at full size
    // that is well over a hundred megabytes of bitmap and an immediate kill, so
    // the subsample has to be right rather than approximately right.
    @Test
    fun `a huge photo is subsampled down towards the viewport`() {
        assertEquals(8, ImagePageSource.sampleSizeFor(8000, 6000, 1000, 750))
        assertEquals(4, ImagePageSource.sampleSizeFor(4000, 3000, 1000, 750))
        assertEquals(1, ImagePageSource.sampleSizeFor(1000, 750, 1000, 750))
    }

    @Test
    fun `an image smaller than the viewport is not subsampled`() {
        assertEquals(1, ImagePageSource.sampleSizeFor(600, 400, 1200, 900))
    }

    @Test
    fun `nonsense dimensions do not produce a zero or negative sample size`() {
        assertEquals(1, ImagePageSource.sampleSizeFor(0, 0, 100, 100))
        assertEquals(1, ImagePageSource.sampleSizeFor(100, 100, 0, 0))
        assertEquals(1, ImagePageSource.sampleSizeFor(-1, -1, 100, 100))
    }

    @Test
    fun `the sample size is always a power of two`() {
        for (width in listOf(500, 1234, 4000, 9000)) {
            val sample = ImagePageSource.sampleSizeFor(width, width, 400, 400)
            assertEquals("width $width gave $sample", 0, sample and (sample - 1))
        }
    }
}
