package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsThemeTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    // The map colour that is hardest for each glass to hold against, under its lightest (top) end for night.
    private fun worstBacking(night: Boolean): Int {
        val colors = LyricsTheme.colors(night)
        return if (night) LyricsTheme.over(colors.glassTop, white) else LyricsTheme.over(colors.glassBottom, black)
    }

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
            assertTrue((rail.glassTop ushr 24) >= 0xF0)
            assertTrue((rail.glassBottom ushr 24) >= 0xF0)
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
    fun themesDiffer() {
        assertNotEquals(LyricsTheme.colors(true).glassBottom, LyricsTheme.colors(false).glassBottom)
        assertNotEquals(LyricsTheme.colors(true).current, LyricsTheme.colors(false).current)
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
