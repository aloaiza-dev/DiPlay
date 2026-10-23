package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class LyricsScrimTest {
    private val colors = LyricsTheme.colors(true)
    private val scrim = LyricsScrim(colors)

    private fun assertFloors(pixel: Int) {
        val shaded = scrim.shade(pixel)
        assertEquals("opaque", 0xFF, shaded ushr 24)
        assertTrue("current over %08x".format(pixel), LyricsTheme.contrast(colors.current, shaded) >= LyricsScrim.CURRENT_CONTRAST)
        assertTrue("next over %08x".format(pixel), LyricsTheme.contrast(colors.next, shaded) >= LyricsScrim.NEXT_CONTRAST)
        assertTrue("far over %08x".format(pixel), LyricsTheme.contrast(colors.far, shaded) >= LyricsScrim.FAR_CONTRAST)
    }

    @Test
    fun whiteTextKeepsItsContrastOverEveryPrimaryAndGrey() {
        val values = listOf(0, 64, 128, 192, 255)
        for (r in values) for (g in values) for (b in values) assertFloors((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
    }

    @Test
    fun whiteTextKeepsItsContrastOverRandomPictures() {
        val random = Random(7)
        repeat(4000) { assertFloors(0xFF000000.toInt() or random.nextInt(0x1000000)) }
    }

    @Test
    fun bothThemesHoldTheFloors() {
        for (night in listOf(true, false)) {
            val theme = LyricsTheme.colors(night)
            val s = LyricsScrim(theme)
            for (pixel in listOf(0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00B7FF.toInt(), 0xFFFF2D55.toInt())) {
                assertTrue(LyricsTheme.contrast(theme.current, s.shade(pixel)) >= LyricsScrim.CURRENT_CONTRAST)
            }
        }
    }

    @Test
    fun aDarkPictureIsBarelyDarkenedSoItsColourShowsThrough() {
        val navy = 0xFF1B2A55.toInt()
        val shaded = scrim.shade(navy)
        val channelGap = (0..2).maxOf { shift -> kotlin.math.abs(((shaded ushr (shift * 8)) and 0xFF) - ((navy ushr (shift * 8)) and 0xFF)) }
        assertTrue("moved by $channelGap", channelGap <= 40)
        assertTrue("blue is still the strongest channel", (shaded and 0xFF) > ((shaded ushr 16) and 0xFF))
    }

    @Test
    fun aBrightPictureIsDarkenedMoreThanADarkOne() {
        fun lift(pixel: Int) = ((scrim.shade(pixel) ushr 8) and 0xFF) - ((pixel ushr 8) and 0xFF)
        assertTrue(lift(0xFFFFFFFF.toInt()) < lift(0xFF404040.toInt()))
    }

    @Test
    fun theTintNeverGoesBelowTheGlassFloorEvenOverBlack() {
        val shaded = scrim.shade(0xFF000000.toInt())
        assertTrue("black glass stays near black", ((shaded ushr 8) and 0xFF) < 40)
    }
}
