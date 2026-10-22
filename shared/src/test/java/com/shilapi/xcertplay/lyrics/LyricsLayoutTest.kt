package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsLayoutTest {
    @Test
    fun railWidthClampsToMinimum() {
        assertEquals(240f, LyricsLayout.railWidthDp(600f), 0.01f)
    }

    @Test
    fun railWidthScalesInTheMiddle() {
        assertEquals(320f, LyricsLayout.railWidthDp(1000f), 0.01f)
    }

    @Test
    fun railWidthClampsToMaximum() {
        assertEquals(420f, LyricsLayout.railWidthDp(2000f), 0.01f)
    }

    @Test
    fun railNeverExceedsFortyFivePercentOfTheWindow() {
        assertTrue(LyricsLayout.railWidthDp(400f) <= 180f)
        assertTrue(LyricsLayout.railWidthDp(300f) <= 135f)
    }

    @Test
    fun narrowWindowUsesSmallTypeAndThreeLines() {
        assertEquals(0.8f, LyricsLayout.typeScale(300f), 0.001f)
        assertEquals(3, LyricsLayout.railVisibleLines(300f))
        assertEquals(1f, LyricsLayout.typeScale(480f), 0.001f)
        assertEquals(5, LyricsLayout.railVisibleLines(480f))
    }

    @Test
    fun windowAtTheNarrowLimitIsNotNarrow() {
        assertEquals(1f, LyricsLayout.typeScale(LyricsLayout.NARROW_HEIGHT_DP), 0.001f)
    }
}
