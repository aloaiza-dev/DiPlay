package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, qualifiers = "en")
class LyricsTimingLabelTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun zeroIsOnTime() {
        assertEquals("On time", lyricsTimingLabel(context, 0))
    }

    @Test
    fun positiveMeansEarlierAndNegativeMeansLater() {
        assertEquals("0.5 s earlier", lyricsTimingLabel(context, 500))
        assertEquals("1 s later", lyricsTimingLabel(context, -1000))
    }

    @Test
    fun aQuarterSecondKeepsItsFraction() {
        assertEquals("0.25 s earlier", lyricsTimingLabel(context, 250))
        assertEquals("1.75 s later", lyricsTimingLabel(context, -1750))
    }
}
