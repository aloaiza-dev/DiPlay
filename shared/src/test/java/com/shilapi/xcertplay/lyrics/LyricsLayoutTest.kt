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
        assertEquals(840f, LyricsLayout.railWidthDp(5000f), 0.01f)
    }

    @Test
    fun railOnAnXpengSizedWindowIsAboutOneThirdOfTheScreen() {
        // 2666 dp wide at 0.9 density is 2400 px. The rail limits double with the type: 840 dp is 756 px.
        assertEquals(840f, LyricsLayout.railWidthDp(2666f), 0.01f)
    }

    @Test
    fun railIsAFloatingCardAboutSixTenthsOfTheWindowHeight() {
        assertEquals(0.62f * 700f, LyricsLayout.railHeightDp(700f), 0.01f)
        assertEquals(0.03f * 700f, LyricsLayout.railEdgeMarginDp(700f), 0.01f)
    }

    @Test
    fun widthScaleIsOneUpToTheReferenceWidth() {
        assertEquals(1f, LyricsLayout.widthScale(0f), 0.001f)
        assertEquals(1f, LyricsLayout.widthScale(1000f), 0.001f)
        assertEquals(1f, LyricsLayout.widthScale(1280f), 0.001f)
    }

    @Test
    fun widthScaleGrowsWithTheWindowAndStopsAtTwo() {
        assertEquals(1.5f, LyricsLayout.widthScale(1920f), 0.001f)
        assertEquals(2f, LyricsLayout.widthScale(2666f), 0.001f)
        assertEquals(2f, LyricsLayout.widthScale(9000f), 0.001f)
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
