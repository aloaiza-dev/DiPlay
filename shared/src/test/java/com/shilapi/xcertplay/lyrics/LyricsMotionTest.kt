package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMotionTest {
    private val ratio = 0.714f

    private fun assertPose(pose: LyricsMotion.Pose, alpha: Float, translateY: Float, scale: Float) {
        assertEquals("alpha", alpha, pose.alpha, 0.001f)
        assertEquals("translateY", translateY, pose.translateY, 0.001f)
        assertEquals("scale", scale, pose.scale, 0.001f)
    }

    @Test
    fun theOldCurrentLineStartsInPlaceAndLeavesUpward() {
        assertPose(LyricsMotion.outgoing(0f), 1f, 0f, 1f)
        val end = LyricsMotion.outgoing(1f)
        assertEquals(0f, end.alpha, 0.001f)
        assertTrue("leaves upward", end.translateY < 0f)
    }

    @Test
    fun theOldNextLineRisesIntoTheCurrentSlotAndGrowsToItsSize() {
        assertPose(LyricsMotion.morph(0f, ratio), 1f, 0f, 1f)
        assertPose(LyricsMotion.morph(1f, ratio), 0f, -1f, 1f / ratio)
    }

    @Test
    fun theIncomingCurrentLineStartsInTheNextSlotAndSettlesInPlace() {
        assertPose(LyricsMotion.incomingCurrent(0f, ratio), 0f, 1f, ratio)
        assertPose(LyricsMotion.incomingCurrent(1f, ratio), 1f, 0f, 1f)
    }

    @Test
    fun theNewNextLineFadesUpFromBelow() {
        val start = LyricsMotion.incomingNext(0f)
        assertEquals(0f, start.alpha, 0.001f)
        assertTrue("starts below", start.translateY > 0f)
        assertPose(LyricsMotion.incomingNext(1f), 1f, 0f, 1f)
    }

    @Test
    fun theMorphingLineNeverLeavesAGapInTheMiddle() {
        var e = 0f
        while (e <= 1f) {
            val total = LyricsMotion.morph(e, ratio).alpha + LyricsMotion.incomingCurrent(e, ratio).alpha
            assertTrue("e=$e total=$total", total >= 0.95f)
            e += 0.05f
        }
    }

    @Test
    fun aRailRowFurtherFromTheCentreStartsItsMoveLater() {
        assertEquals(0f, LyricsMotion.staggered(0f, 3), 0.001f)
        assertEquals(1f, LyricsMotion.staggered(1f, 3), 0.001f)
        assertEquals("the current row leads", 0.3f, LyricsMotion.staggered(0.3f, 0), 0.001f)
        assertTrue(LyricsMotion.staggered(0.3f, 3) < LyricsMotion.staggered(0.3f, 1))
        assertEquals("past rows count by distance too", LyricsMotion.staggered(0.4f, 2), LyricsMotion.staggered(0.4f, -2), 0.001f)
    }

    @Test
    fun theRollIsSoftEnoughToReadAsOneGentleMove() {
        assertTrue(LyricsMotion.ROLL_MILLIS in 450L..600L)
        assertTrue(LyricsMotion.EXIT_MILLIS < LyricsMotion.ENTER_MILLIS)
        assertTrue("the dip is a dim, not a blink", LyricsMotion.DIP_ALPHA >= 0.5f)
        assertTrue("the outgoing line drifts, it does not fly", LyricsMotion.outgoing(1f).translateY > -0.7f)
    }

    @Test
    fun theOutgoingLineIsGoneBeforeTheRollEnds() {
        assertEquals(0f, LyricsMotion.outgoing(0.6f).alpha, 0.001f)
    }

    @Test
    fun smoothstepClampsAndIsMonotonic() {
        assertEquals(0f, LyricsMotion.smooth(0.2f, 0.8f, 0f), 0.0001f)
        assertEquals(1f, LyricsMotion.smooth(0.2f, 0.8f, 1f), 0.0001f)
        var last = -1f
        var x = 0f
        while (x <= 1f) {
            val value = LyricsMotion.smooth(0.2f, 0.8f, x)
            assertTrue(value >= last)
            last = value
            x += 0.05f
        }
    }

    @Test
    fun theRailWheelKeepsTheCurrentLineFullAndFadesWithDistance() {
        assertEquals(1f, LyricsMotion.railAlpha(0), 0.001f)
        assertEquals(1f, LyricsMotion.railScale(0), 0.001f)
        for (distance in 1..3) {
            assertTrue(LyricsMotion.railAlpha(distance) < LyricsMotion.railAlpha(distance - 1))
            assertTrue(LyricsMotion.railScale(distance) < LyricsMotion.railScale(distance - 1))
            assertTrue(LyricsMotion.railAlpha(-distance) < LyricsMotion.railAlpha(-distance + 1))
            assertTrue(LyricsMotion.railScale(-distance) < LyricsMotion.railScale(-distance + 1))
        }
    }

    @Test
    fun theUpcomingLineIsBrighterThanThePastOneAtTheSameDistance() {
        for (distance in 1..3) assertTrue(LyricsMotion.railAlpha(distance) > LyricsMotion.railAlpha(-distance))
    }

    @Test
    fun theRailWheelFlattensBeyondItsReach() {
        assertEquals(LyricsMotion.railAlpha(3), LyricsMotion.railAlpha(9), 0.001f)
        assertEquals(LyricsMotion.railScale(-3), LyricsMotion.railScale(-9), 0.001f)
    }

    @Test
    fun theNextRailLineStaysReadable() {
        assertTrue(LyricsMotion.railAlpha(1) >= 0.7f)
    }

    @Test
    fun entranceAndExitAreShorterThanTheRoll() {
        assertTrue(LyricsMotion.EXIT_MILLIS < LyricsMotion.ENTER_MILLIS)
        assertTrue(LyricsMotion.ENTER_MILLIS <= LyricsMotion.ROLL_MILLIS)
    }
}
