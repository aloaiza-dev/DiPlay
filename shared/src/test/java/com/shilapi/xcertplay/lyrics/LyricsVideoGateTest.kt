package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsVideoGateTest {
    private var now = 1_000L
    private val gate = LyricsVideoGate({ now }, waitMillis = 12_000L)
    private val session = Any()

    @Test
    fun nothingIsReadyWithoutASession() {
        assertFalse(gate.ready())
        now += 60_000L
        assertFalse("no session, however long we wait", gate.ready())
    }

    @Test
    fun aDrawnFrameOpensTheGateAtOnce() {
        gate.sessionStarted(session)
        assertFalse(gate.ready())
        assertTrue("the first frame is news", gate.frameDrawn(session))
        assertTrue(gate.ready())
        assertFalse("later frames are not", gate.frameDrawn(session))
    }

    @Test
    fun aFrameFromTheVideoSurfaceCountsWithoutNamingASession() {
        gate.sessionStarted(session)
        assertTrue(gate.frameDrawn(null))
        assertTrue(gate.ready())
    }

    @Test
    fun aFrameWithoutAnySessionIsIgnored() {
        assertFalse(gate.frameDrawn(null))
        gate.sessionStarted(session)
        assertFalse("it must not have counted", gate.ready())
    }

    @Test
    fun aFrameOfAnotherSessionIsIgnored() {
        gate.sessionStarted(session)
        assertFalse(gate.frameDrawn(Any()))
        assertFalse(gate.ready())
    }

    @Test
    fun aSessionWhoseFrameSignalNeverArrivesOpensTheGateAfterTheWait() {
        gate.sessionStarted(session)
        now += 11_999L
        assertFalse(gate.ready())
        now += 1L
        assertTrue("the lyrics must never stay hidden for good", gate.ready())
        assertTrue(gate.openedByTimeout)
    }

    @Test
    fun theWaitCountsFromTheSessionStartAndNotFromEachCall() {
        gate.sessionStarted(session)
        now += 5_000L
        gate.sessionStarted(session) // the same session again, as when a host adopts it
        now += 7_000L
        assertTrue(gate.ready())
    }

    @Test
    fun aNewSessionStartsClosedAgain() {
        gate.sessionStarted(session)
        gate.frameDrawn(session)
        assertTrue(gate.ready())
        gate.sessionStarted(Any())
        assertFalse("a new session has drawn nothing yet", gate.ready())
    }

    @Test
    fun aSessionThatEndedClosesTheGateForGood() {
        gate.sessionStarted(session)
        gate.frameDrawn(session)
        gate.sessionEnded()
        assertFalse(gate.ready())
        now += 60_000L
        assertFalse(gate.ready())
        assertEquals(false, gate.openedByTimeout)
    }
}
