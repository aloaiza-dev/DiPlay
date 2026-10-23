package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsThemeTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    // The glass is dark in both themes and the text white, so the hardest picture is a white one under its
    // lightest (top) end: a bright map in daylight.
    private fun worstBacking(night: Boolean): Int = LyricsTheme.over(LyricsTheme.colors(night).glassTop, white)

    private fun ratio(night: Boolean, pick: (LyricsColors) -> Int): Double =
        LyricsTheme.contrast(pick(LyricsTheme.colors(night)), worstBacking(night))

    @Test
    fun currentLineHasEightToOneInBothThemes() {
        assertTrue("night ${ratio(true) { it.current }}", ratio(true) { it.current } >= 8.0)
        assertTrue("day ${ratio(false) { it.current }}", ratio(false) { it.current } >= 8.0)
    }

    @Test
    fun nextLineHasFiveToOneInBothThemes() {
        assertTrue("night ${ratio(true) { it.next }}", ratio(true) { it.next } >= 5.0)
        assertTrue("day ${ratio(false) { it.next }}", ratio(false) { it.next } >= 5.0)
    }

    @Test
    fun farLinesHaveFourToOneInBothThemes() {
        assertTrue("night ${ratio(true) { it.far }}", ratio(true) { it.far } >= 4.0)
        assertTrue("day ${ratio(false) { it.far }}", ratio(false) { it.far } >= 4.0)
    }

    @Test
    fun glassStaysTranslucentSoTheRoadShowsThrough() {
        for (night in listOf(true, false)) {
            val colors = LyricsTheme.colors(night)
            assertTrue((colors.glassTop ushr 24) in 0xB0..0xF0)
            assertTrue((colors.glassBottom ushr 24) in 0xB0..0xF0)
        }
    }

    @Test
    fun theRailGlassIsMoreOpaqueThanThePillsAndKeepsItsContrast() {
        for (night in listOf(true, false)) {
            val pill = LyricsTheme.colors(night)
            val rail = LyricsTheme.railColors(night)
            assertTrue((rail.glassTop ushr 24) >= 0xE0)
            assertTrue((rail.glassBottom ushr 24) >= 0xE0)
            assertTrue((rail.glassTop ushr 24) > (pill.glassTop ushr 24))
            assertTrue((rail.glassBottom ushr 24) > (pill.glassBottom ushr 24))
            assertEquals(pill.current, rail.current)
            assertEquals(pill.far, rail.far)
        }
    }

    @Test
    fun rimFadesFromTopToBottom() {
        for (night in listOf(true, false)) {
            val colors = LyricsTheme.colors(night)
            assertTrue((colors.rimTop ushr 24) > (colors.rimBottom ushr 24))
        }
    }

    @Test
    fun bothThemesAreDarkGlassWithWhiteText() {
        for (night in listOf(true, false)) {
            val colors = LyricsTheme.colors(night)
            assertEquals(0xFFFFFFFF.toInt(), colors.current)
            assertTrue("glass luminance", LyricsTheme.contrast(white, LyricsTheme.over(colors.glassBottom, black)) > 12.0)
            assertTrue("a specular edge on top", (colors.rimTop ushr 24) >= 0x50)
            assertTrue("a sheen to lift the top", (colors.sheen ushr 24) in 0x14..0x40)
        }
    }

    @Test
    fun theRailStaysDarkWhiteTextAndHoldsTheFloorsOverAWhitePicture() {
        for (night in listOf(true, false)) {
            val rail = LyricsTheme.railColors(night)
            val backing = LyricsTheme.over(rail.glassTop, white)
            assertTrue(LyricsTheme.contrast(rail.current, backing) >= 8.0)
            assertTrue(LyricsTheme.contrast(rail.next, backing) >= 5.0)
            assertTrue(LyricsTheme.contrast(rail.far, backing) >= 4.0)
        }
    }

    @Test
    fun contrastOfBlackOnWhiteIsTwentyOne() {
        assertEquals(21.0, LyricsTheme.contrast(black, white), 0.01)
    }

    @Test
    fun overBlendsByAlpha() {
        // 50% white over black is mid grey.
        assertEquals(0xFF808080.toInt(), LyricsTheme.over(0x80FFFFFF.toInt(), black))
    }
}
