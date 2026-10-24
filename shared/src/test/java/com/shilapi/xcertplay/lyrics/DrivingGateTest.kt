package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingGateTest {
    private var now = 0L
    private val gate = DrivingGate { now }

    /** Reports [kmh] every 500 ms for [millis], with a report at the start and at the end. */
    private fun hold(kmh: Int?, millis: Long) {
        var elapsed = 0L
        while (true) {
            gate.onSpeed(kmh)
            if (elapsed >= millis) return
            val step = minOf(500L, millis - elapsed)
            now += step
            elapsed += step
        }
    }

    @Test
    fun startsNotMoving() {
        assertFalse(gate.moving)
    }

    @Test
    fun movingAfterFiveKmhForTwoSeconds() {
        hold(5, 1_900L)
        assertFalse(gate.moving)
        hold(5, 100L)
        assertTrue(gate.moving)
    }

    @Test
    fun belowFiveKmhNeverStartsMoving() {
        hold(4, 60_000L)
        assertFalse(gate.moving)
    }

    @Test
    fun aDipBelowFiveRestartsTheTwoSecondHold() {
        hold(5, 1_500L)
        hold(4, 500L)
        hold(5, 1_500L)
        assertFalse(gate.moving)
        hold(5, 500L)
        assertTrue(gate.moving)
    }

    @Test
    fun stillAfterUnderTwoKmhForThreeSeconds() {
        hold(30, 3_000L)
        assertTrue(gate.moving)
        hold(1, 2_900L)
        assertTrue(gate.moving)
        hold(1, 100L)
        assertFalse(gate.moving)
    }

    @Test
    fun middleBandKeepsState() {
        hold(3, 60_000L)
        assertFalse(gate.moving)
        hold(30, 3_000L)
        hold(3, 60_000L)
        assertTrue(gate.moving)
    }

    @Test
    fun nullSpeedNeverHides() {
        hold(null, 60_000L)
        assertFalse(gate.moving)
    }

    @Test
    fun lostSignalWhileMovingClearsAfterFiveSeconds() {
        hold(30, 3_000L)
        assertTrue(gate.moving)
        hold(null, 4_900L)
        assertTrue(gate.moving)
        hold(null, 100L)
        assertFalse(gate.moving)
    }

    @Test
    fun silenceCountsAsLostSignal() {
        hold(30, 3_000L)
        now += 5_000L
        assertFalse(gate.moving)
    }
}
