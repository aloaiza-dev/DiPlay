package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRailSideTest {
    @Test
    fun autoPutsTheRailOppositeTheDockWhichFollowsTheDrivingSide() {
        assertFalse("left-hand drive: rail on the right", LyricsRailSide.AUTO.onLeft(rightHandDrive = false))
        assertTrue("right-hand drive: rail on the left", LyricsRailSide.AUTO.onLeft(rightHandDrive = true))
    }

    @Test
    fun leftAndRightIgnoreTheDrivingSide() {
        for (rightHandDrive in listOf(true, false)) {
            assertTrue(LyricsRailSide.LEFT.onLeft(rightHandDrive))
            assertFalse(LyricsRailSide.RIGHT.onLeft(rightHandDrive))
        }
    }

    @Test
    fun anUnknownStoredKeyFallsBackToAuto() {
        assertEquals(LyricsRailSide.AUTO, LyricsRailSide.fromKey("future-side"))
        assertEquals(LyricsRailSide.AUTO, LyricsRailSide.fromKey(null))
    }

    @Test
    fun everySideRoundTripsThroughItsKey() {
        for (side in LyricsRailSide.entries) assertEquals(side, LyricsRailSide.fromKey(side.key))
    }
}
