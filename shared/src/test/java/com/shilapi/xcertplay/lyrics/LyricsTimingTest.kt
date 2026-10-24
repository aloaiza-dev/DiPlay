package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTimingTest {
    @Test
    fun theDefaultIsAHalfSecondEarlier() {
        assertEquals(500, LyricsTiming.DEFAULT_MILLIS)
    }

    @Test
    fun presetsAreSortedAndIncludeOnTimeAndTheDefault() {
        val presets = LyricsTiming.PRESETS_MILLIS
        assertEquals(presets.sorted(), presets)
        assertTrue(0 in presets)
        assertTrue(LyricsTiming.DEFAULT_MILLIS in presets)
    }

    @Test
    fun presetsStayWithinTheRange() {
        assertTrue(LyricsTiming.PRESETS_MILLIS.all { it in LyricsTiming.MIN_MILLIS..LyricsTiming.MAX_MILLIS })
    }

    @Test
    fun theRangeIsTwoSecondsEitherWay() {
        assertEquals(-2000, LyricsTiming.MIN_MILLIS)
        assertEquals(2000, LyricsTiming.MAX_MILLIS)
    }

    @Test
    fun clampKeepsAStoredValueInRange() {
        assertEquals(2000, LyricsTiming.clamp(99_999))
        assertEquals(-2000, LyricsTiming.clamp(-99_999))
        assertEquals(750, LyricsTiming.clamp(750))
    }

    @Test
    fun aStepMovesAQuarterSecondEitherWay() {
        assertEquals(750, LyricsTiming.step(500, earlier = true))
        assertEquals(250, LyricsTiming.step(500, earlier = false))
        assertEquals(-250, LyricsTiming.step(0, earlier = false))
    }

    @Test
    fun aStepStopsAtTheEndsOfTheRange() {
        assertEquals(2000, LyricsTiming.step(2000, earlier = true))
        assertEquals(-2000, LyricsTiming.step(-2000, earlier = false))
        assertEquals(2000, LyricsTiming.step(1900, earlier = true))
    }

    @Test
    fun canStepTellsWhenAButtonShouldBeDisabled() {
        assertEquals(false, LyricsTiming.canStep(2000, earlier = true))
        assertEquals(true, LyricsTiming.canStep(2000, earlier = false))
        assertEquals(false, LyricsTiming.canStep(-2000, earlier = false))
        assertEquals(true, LyricsTiming.canStep(0, earlier = true))
    }

    @Test
    fun presetIndexFindsTheNearestPreset() {
        assertEquals(LyricsTiming.PRESETS_MILLIS.indexOf(500), LyricsTiming.nearestPresetIndex(500))
        assertEquals(LyricsTiming.PRESETS_MILLIS.indexOf(500), LyricsTiming.nearestPresetIndex(530))
        assertEquals(LyricsTiming.PRESETS_MILLIS.indexOf(2000), LyricsTiming.nearestPresetIndex(50_000))
    }
}
