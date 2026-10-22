package com.shilapi.xcertplay.lyrics

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LyricsControllerTest {
    @get:Rule val folder = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()
    private val looper: Looper get() = Looper.getMainLooper()
    private val jobs = ArrayDeque<Runnable>()
    private var calls = 0
    private var lrc: (TrackQuery) -> String? = { "[00:01.00]one\n[00:05.00]two" }
    private var onNowPlaying: ((CarPlayNowPlaying) -> Unit)? = null
    private var onSpeed: ((Int?) -> Unit)? = null
    private var speedAvailable = true
    private var placement = LyricsPlacement.TOP_PILL
    private var hideWhileDriving = false
    private var night = true
    private var sidePanelOpen = false
    private var offsetMillis = 0L
    private val saved = mutableListOf<Int>()
    private val placements = mutableListOf<LyricsPlacement>()

    private val speed = object : LyricsSpeedSource {
        override val available get() = speedAvailable
        override fun observe(listener: (Int?) -> Unit): () -> Unit {
            onSpeed = listener
            return { onSpeed = null }
        }
    }

    private lateinit var pill: LyricsPillView
    private lateinit var rail: LyricsRailView
    private lateinit var controller: LyricsController

    @Before
    fun setUp() {
        pill = LyricsPillView(context)
        rail = LyricsRailView(context)
        val repository = LyricsRepository(LyricsSource { calls++; lrc(it) }, folder.root, Executor { jobs.add(it) }, ::now)
        controller = LyricsController(
            pill, rail, repository, speed, android.os.Handler(looper),
            observeNowPlaying = { listener -> onNowPlaying = listener; { onNowPlaying = null } },
            placement = { placement }, hideWhileDriving = { hideWhileDriving }, night = { night },
            sidePanelOpen = { sidePanelOpen }, nowMillis = ::now, offsetMillis = { offsetMillis },
            saveOffsetMillis = { saved += it; offsetMillis = it.toLong() },
            savePlacement = { placements += it; placement = it },
        )
    }

    private fun now() = SystemClock.uptimeMillis()

    private fun advance(millis: Long) = shadowOf(looper).idleFor(Duration.ofMillis(millis))

    private fun runJobs(reverse: Boolean = false) {
        val batch = jobs.toList().let { if (reverse) it.reversed() else it }
        jobs.clear()
        batch.forEach { it.run() }
        shadowOf(looper).idle()
    }

    private fun np(title: String?, elapsed: Long? = 0L, playing: Boolean = true, artist: String? = "Artist") =
        CarPlayNowPlaying(title = title, artist = artist, album = "Album", durationMillis = 200_000L, elapsedMillis = elapsed, playing = playing)

    private fun publish(update: CarPlayNowPlaying) {
        checkNotNull(onNowPlaying) { "controller is not observing" }(update)
        shadowOf(looper).idle()
    }

    private fun rows(view: ViewGroup) = (0 until view.childCount).map { view.getChildAt(it) as TextView }

    private fun pillRows() = listOf(pill.currentRow, pill.nextRow)

    private fun pillText() = pillRows().filter { it.visibility == View.VISIBLE }.map { it.text.toString() }

    @Test
    fun showsTheCurrentAndNextLineOnceLyricsArrive() {
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        assertEquals(View.VISIBLE, pill.visibility)
        assertEquals(listOf("one", "two"), pillText())
        assertEquals(View.GONE, rail.visibility)
    }

    @Test
    fun trackChangeClearsAtOnce() {
        controller.start()
        publish(np("Song A", elapsed = 2_000L))
        runJobs()
        assertEquals(View.VISIBLE, pill.visibility)
        publish(np("Song B", elapsed = 0L, artist = "Other"))
        assertEquals(View.GONE, pill.visibility)
        assertEquals(View.GONE, rail.visibility)
    }

    @Test
    fun lateReplyForTheOldTrackIsDropped() {
        lrc = { query -> "[00:01.00]${query.title} line" }
        controller.start()
        publish(np("Song A", elapsed = 2_000L, artist = "A"))
        publish(np("Song B", elapsed = 2_000L, artist = "B"))
        runJobs(reverse = true)
        assertEquals("Song B line", pillText().first())
    }

    @Test
    fun aTitleThatChangesPerLineDoesNotLookUpAfterTheSecondChange() {
        controller.start()
        publish(np("line one", elapsed = 1_000L))
        advance(1_000L)
        publish(np("line two", elapsed = 2_000L))
        advance(1_000L)
        publish(np("line three", elapsed = 3_000L))
        advance(1_000L)
        publish(np("line four", elapsed = 4_000L))
        runJobs()
        assertEquals(2, calls)
        assertEquals(View.GONE, pill.visibility)
    }

    @Test
    fun aRealTrackChangeAfterTitleChurnLooksUpAgain() {
        controller.start()
        publish(np("line one"))
        publish(np("line two"))
        publish(np("line three"))
        publish(np("New song", artist = "Someone else"))
        runJobs()
        assertEquals(3, calls)
        assertEquals(View.VISIBLE, pill.visibility)
    }

    @Test
    fun offPlacementNeverLooksUpOrObserves() {
        placement = LyricsPlacement.OFF
        controller.start()
        assertNull(onNowPlaying)
        assertEquals(0, calls)
    }

    @Test
    fun noLyricsFoundDrawsNothing() {
        lrc = { null }
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        assertEquals(View.GONE, pill.visibility)
        assertEquals(View.GONE, rail.visibility)
    }

    @Test
    fun hidesWhileMovingOnlyWhenTheOptionIsOn() {
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        repeat(8) { onSpeed?.invoke(30); advance(500L) }
        assertEquals("option off: still visible", View.VISIBLE, pill.visibility)

        controller.stop()
        hideWhileDriving = true
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        repeat(8) { onSpeed?.invoke(30); advance(500L) }
        assertEquals("option on, moving: hidden", View.GONE, pill.visibility)
        repeat(10) { onSpeed?.invoke(0); advance(500L) }
        assertEquals("stopped for a while: visible again", View.VISIBLE, pill.visibility)
    }

    @Test
    fun staysVisibleWithoutAnySpeedReading() {
        hideWhileDriving = true
        speedAvailable = false
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(60_000L)
        assertEquals(View.VISIBLE, pill.visibility)
    }

    @Test
    fun theRailYieldsToTheSidePanelButThePillDoesNot() {
        placement = LyricsPlacement.SIDE_RAIL
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        assertEquals(View.VISIBLE, rail.visibility)
        assertEquals(View.GONE, pill.visibility)
        sidePanelOpen = true
        advance(500L)
        assertEquals(View.GONE, rail.visibility)
        sidePanelOpen = false
        advance(500L)
        assertEquals(View.VISIBLE, rail.visibility)

        controller.stop()
        placement = LyricsPlacement.TOP_PILL
        sidePanelOpen = true
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        assertEquals(View.VISIBLE, pill.visibility)
    }

    @Test
    fun seekJumpsToTheNewLineAtOnce() {
        lrc = { "[00:10.00]early\n[01:30.00]late\n[01:40.00]later" }
        controller.start()
        publish(np("Song", elapsed = 10_000L))
        runJobs()
        assertEquals("early", pillText().first())
        publish(np("Song", elapsed = 90_000L))
        assertEquals("late", pillText().first())
    }

    @Test
    fun anEmptyTimedLineShowsAMusicNote() {
        lrc = { "[00:01.00]sing\n[00:05.00]\n[00:09.00]again" }
        controller.start()
        publish(np("Song", elapsed = 6_000L))
        runJobs()
        assertEquals("♪", pillText().first())
    }

    @Test
    fun beforeTheFirstLineOnlyAnImminentOneShowsAsNext() {
        lrc = { "[00:02.00]soon" }
        controller.start()
        publish(np("Song", elapsed = 0L))
        runJobs()
        assertEquals(listOf("soon"), pillText())
        controller.stop()
        lrc = { "[00:30.00]far" }
        controller.start()
        publish(np("Other", elapsed = 0L, artist = "Someone"))
        runJobs()
        assertEquals(View.GONE, pill.visibility)
    }

    @Test
    fun pauseDimsAfterOneSecondAndPlayRestores() {
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L) // the pill finishes entering
        publish(np("Song", elapsed = 2_000L, playing = false))
        assertEquals(1f, pill.alpha, 0.001f)
        advance(1_200L)
        assertEquals(0.6f, pill.alpha, 0.001f)
        publish(np("Song", elapsed = 2_000L, playing = true))
        assertEquals(1f, pill.alpha, 0.001f)
    }

    @Test
    fun retriesAfterANetworkFailure() {
        var attempts = 0
        lrc = { if (++attempts < 3) throw IOException("busy") else "[00:01.00]one\n[00:05.00]two" }
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        assertEquals(View.GONE, pill.visibility)
        advance(3_100L)
        runJobs()
        assertEquals("the first retry fails too", View.GONE, pill.visibility)
        advance(10_100L)
        runJobs()
        assertEquals(View.VISIBLE, pill.visibility)
        assertEquals(3, calls)
    }

    @Test
    fun givesUpAfterFiveRetries() {
        lrc = { throw IOException("down") }
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        for (wait in listOf(3_100L, 10_100L, 30_100L, 60_100L, 60_100L, 120_000L)) {
            advance(wait)
            runJobs()
        }
        assertEquals(6, calls)
    }

    @Test
    fun doesNotRetryWhenThereAreNoLyrics() {
        lrc = { null }
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(60_000L)
        runJobs()
        assertEquals(1, calls)
    }

    @Test
    fun stopsRetryingOnceTheTrackHasChanged() {
        lrc = { query -> if (query.title == "Song A") throw IOException("busy") else "[00:01.00]one" }
        controller.start()
        publish(np("Song A", elapsed = 2_000L, artist = "A"))
        runJobs()
        publish(np("Song B", elapsed = 2_000L, artist = "B"))
        runJobs()
        val before = calls
        advance(3_100L)
        runJobs()
        assertEquals(before, calls)
        assertEquals(View.VISIBLE, pill.visibility)
    }

    @Test
    fun musicThatStopsClearsTheOverlayAndPlayingBringsItBack() {
        placement = LyricsPlacement.TOP_PILL
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        publish(np("Song", elapsed = 2_000L, playing = false))
        advance(1_200L)
        assertEquals("dimmed, still there", View.VISIBLE, pill.visibility)
        advance(3_500L)
        assertEquals("cleared after a few seconds", View.GONE, pill.visibility)
        publish(np("Song", elapsed = 2_000L, playing = true))
        assertEquals(View.VISIBLE, pill.visibility)
    }

    @Test
    fun theRailIsClearedWhenMusicStopsToo() {
        placement = LyricsPlacement.SIDE_RAIL
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        publish(np("Song", elapsed = 2_000L, playing = false))
        advance(5_000L)
        assertEquals(View.GONE, rail.visibility)
    }

    @Test
    fun themeChangeRecoloursWithoutRestartingTheLine() {
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        val before = calls
        controller.onNightChanged(false)
        assertEquals(LyricsTheme.colors(false).current, pillRows()[0].currentTextColor)
        assertEquals(listOf("one", "two"), pillText())
        assertEquals(before, calls)
    }

    @Test
    fun theViewsStartInTheCurrentTheme() {
        night = false
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        assertEquals(LyricsTheme.colors(false).current, pillRows()[0].currentTextColor)
    }

    @Test
    fun endOfSessionClearsTheOverlay() {
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        publish(CarPlayNowPlaying())
        assertEquals(View.GONE, pill.visibility)
    }

    @Test
    fun stopHidesTheViewsAndStopsObserving() {
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        controller.stop()
        assertEquals(View.GONE, pill.visibility)
        assertEquals(View.GONE, rail.visibility)
        assertNull(onNowPlaying)
    }

    @Test
    fun aPositiveOffsetShowsALineEarlier() {
        lrc = { "[00:01.00]one\n[00:03.00]two" }
        offsetMillis = 600L
        controller.start()
        publish(np("Song", elapsed = 2_500L))
        runJobs()
        assertEquals("two", pillText().first())
    }

    @Test
    fun withoutAnOffsetTheSameMomentStillShowsTheEarlierLine() {
        lrc = { "[00:01.00]one\n[00:03.00]two" }
        controller.start()
        publish(np("Song", elapsed = 2_500L))
        runJobs()
        assertEquals("one", pillText().first())
    }

    @Test
    fun aNegativeOffsetShowsALineLater() {
        lrc = { "[00:01.00]one\n[00:03.00]two" }
        offsetMillis = -500L
        controller.start()
        publish(np("Song", elapsed = 3_200L))
        runJobs()
        assertEquals("one", pillText().first())
    }

    @Test
    fun anOffsetThatPushesBeforeTheStartDoesNotBreak() {
        lrc = { "[00:20.00]one" }
        offsetMillis = -2_000L
        controller.start()
        publish(np("Song", elapsed = 500L))
        runJobs()
        assertEquals(View.GONE, pill.visibility)
    }

    @Test
    fun aLineChangesOnTimeAndNotOnTheNextTick() {
        lrc = { "[00:01.00]a\n[00:01.10]b\n[00:09.00]c" }
        controller.start()
        publish(np("Song", elapsed = 1_000L))
        runJobs()
        assertEquals("a", pillText().first())
        advance(130L)
        assertEquals("b", pillText().first())
    }

    @Test
    fun exactSchedulingCountsTheOffset() {
        lrc = { "[00:01.00]a\n[00:02.00]b\n[00:09.00]c" }
        offsetMillis = 500L
        controller.start()
        publish(np("Song", elapsed = 600L))
        runJobs()
        assertEquals("a", pillText().first())
        advance(950L)
        assertEquals("b", pillText().first())
    }

    @Test
    fun theRailTimingControlSavesEachStepAndAppliesItAtOnce() {
        lrc = { "[00:01.00]one\n[00:03.00]two" }
        placement = LyricsPlacement.SIDE_RAIL
        controller.start()
        publish(np("Song", elapsed = 2_300L))
        runJobs()
        advance(400L) // the song plays on: 2.7 s now, still before the 3 s line
        assertEquals("one", rail.row(0).text.toString())
        rail.gearButton.performClick()
        rail.earlierButton.performClick()
        rail.earlierButton.performClick()
        assertEquals(listOf(250, 500), saved)
        shadowOf(looper).idle()
        assertEquals("the line moved earlier at once", "two", rail.row(0).text.toString())
    }

    @Test
    fun theRailControlStartsFromTheSavedTiming() {
        placement = LyricsPlacement.SIDE_RAIL
        offsetMillis = 750L
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        rail.gearButton.performClick()
        assertEquals("0.75 s earlier", rail.timingValue.text.toString())
    }

    @Test
    fun theGearGoesAwayWhileTheCarMovesEvenWithoutTheHideOption() {
        placement = LyricsPlacement.SIDE_RAIL
        hideWhileDriving = false
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        assertEquals(View.VISIBLE, rail.gearButton.visibility)
        repeat(8) { onSpeed?.invoke(30); advance(500L) }
        assertEquals(View.GONE, rail.gearButton.visibility)
        assertEquals("the lyrics themselves stay", View.VISIBLE, rail.visibility)
        repeat(10) { onSpeed?.invoke(0); advance(500L) }
        assertEquals(View.VISIBLE, rail.gearButton.visibility)
    }

    @Test
    fun thePillWatchesTheSpeedSoItsDoubleTapCanBeTakenAwayWhileDriving() {
        placement = LyricsPlacement.TOP_PILL
        hideWhileDriving = false
        controller.start()
        assertNotNull(onSpeed)
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        assertTrue("still: the pill takes the double tap", pill.isClickable)
        repeat(8) { onSpeed?.invoke(30); advance(500L) }
        assertFalse("moving: taps go through to CarPlay", pill.isClickable)
        assertEquals("the lyrics stay", View.VISIBLE, pill.visibility)
        repeat(10) { onSpeed?.invoke(0); advance(500L) }
        assertTrue(pill.isClickable)
    }

    private fun tap(view: View, downAt: Long) {
        val down = android.view.MotionEvent.obtain(downAt, downAt, android.view.MotionEvent.ACTION_DOWN, 5f, 5f, 0)
        view.dispatchTouchEvent(down)
        val up = android.view.MotionEvent.obtain(downAt, downAt + 40, android.view.MotionEvent.ACTION_UP, 5f, 5f, 0)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    @Test
    fun aDoubleTapOnThePillSavesTheRailAndShowsIt() {
        placement = LyricsPlacement.TOP_PILL
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        tap(pill, 10_000L)
        tap(pill, 10_150L)
        shadowOf(looper).idle()
        assertEquals(listOf(LyricsPlacement.SIDE_RAIL), placements)
        advance(400L)
        assertEquals(View.VISIBLE, rail.visibility)
        assertEquals("the pill fades out", View.GONE, pill.visibility)
    }

    @Test
    fun theRailsPillButtonSavesThePillAndShowsIt() {
        placement = LyricsPlacement.SIDE_RAIL
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        rail.pillButton.performClick()
        shadowOf(looper).idle()
        assertEquals(listOf(LyricsPlacement.TOP_PILL), placements)
        advance(400L)
        assertEquals(View.VISIBLE, pill.visibility)
        assertEquals(View.GONE, rail.visibility)
    }

    @Test
    fun theViewYouLeaveFadesOutWhileTheNewOneFadesIn() {
        placement = LyricsPlacement.TOP_PILL
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        tap(pill, 10_000L)
        tap(pill, 10_150L)
        // Right after the tap both views are on screen: the pill is on its way out, the rail already coming in.
        // (Robolectric steps its animation clock in one jump, so only this moment and the end can be checked.)
        assertEquals("the old view is still on its way out", View.VISIBLE, pill.visibility)
        assertEquals("the new view is already coming in", View.VISIBLE, rail.visibility)
        advance(400L)
        assertEquals(View.GONE, pill.visibility)
        assertEquals(View.VISIBLE, rail.visibility)
    }

    @Test
    fun afterSwitchingToTheRailTheRailKeepsItsTimingControl() {
        placement = LyricsPlacement.TOP_PILL
        offsetMillis = 500L
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        tap(pill, 10_000L)
        tap(pill, 10_150L)
        advance(400L)
        assertEquals(View.VISIBLE, rail.gearButton.visibility)
        assertEquals(View.VISIBLE, rail.pillButton.visibility)
    }

    @Test
    fun theLineAdvancesWithoutNewMetadata() {
        lrc = { "[00:01.00]a\n[00:03.00]b\n[00:05.00]c" }
        controller.start()
        publish(np("Song", elapsed = 1_000L))
        runJobs()
        assertEquals("a", pillText().first())
        advance(2_700L) // the line changes with a short fade
        assertEquals("b", pillText().first())
        assertNotNull(onNowPlaying)
    }
}
