package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsThemeTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    // The map colour that is hardest for each scrim to hold against.
    private fun backing(night: Boolean) = LyricsTheme.over(LyricsTheme.colors(night).scrim, if (night) white else black)

    private fun ratio(night: Boolean, pick: (LyricsColors) -> Int): Double =
        LyricsTheme.contrast(pick(LyricsTheme.colors(night)), backing(night))

    @Test
    fun currentLineHasTwelveToOneInBothThemes() {
        assertTrue(ratio(true) { it.current } >= 12.0)
        assertTrue(ratio(false) { it.current } >= 12.0)
    }

    @Test
    fun nextLineHasSevenToOneInBothThemes() {
        assertTrue(ratio(true) { it.next } >= 7.0)
        assertTrue(ratio(false) { it.next } >= 7.0)
    }

    @Test
    fun farLinesHaveFourPointFiveToOneInBothThemes() {
        assertTrue(ratio(true) { it.far } >= 4.5)
        assertTrue(ratio(false) { it.far } >= 4.5)
    }

    @Test
    fun themesDiffer() {
        assertNotEquals(LyricsTheme.colors(true).scrim, LyricsTheme.colors(false).scrim)
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
