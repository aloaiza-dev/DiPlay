package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsClockTest {
    private var now = 1_000_000L
    private val clock = LyricsClock { now }

    @Test
    fun advancesWhilePlaying() {
        clock.sync(10_000L, true)
        now += 2_000L
        assertEquals(12_000L, clock.positionMillis())
    }

    @Test
    fun freezesWhilePaused() {
        clock.sync(10_000L, false)
        now += 5_000L
        assertEquals(10_000L, clock.positionMillis())
    }

    @Test
    fun pausingKeepsTheTimeAlreadyPlayed() {
        clock.sync(10_000L, true)
        now += 3_000L
        clock.sync(null, false)
        now += 60_000L
        assertEquals(13_000L, clock.positionMillis())
    }

    @Test
    fun resyncJumpsOnSeek() {
        clock.sync(10_000L, true)
        now += 1_000L
        clock.sync(60_000L, true)
        assertEquals(60_000L, clock.positionMillis())
    }

    @Test
    fun resumeAfterLongPauseDoesNotCatchUp() {
        clock.sync(10_000L, false)
        now += 600_000L
        clock.sync(10_000L, true)
        assertEquals(10_000L, clock.positionMillis())
    }

    @Test
    fun nullBeforeFirstSync() {
        assertNull(clock.positionMillis())
    }

    @Test
    fun nullElapsedKeepsRunningPosition() {
        clock.sync(10_000L, true)
        now += 1_000L
        clock.sync(null, true)
        now += 1_000L
        assertEquals(12_000L, clock.positionMillis())
    }
}
