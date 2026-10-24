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
    private var hintsSeen = 3
    private val backdropCalls = mutableListOf<String>()
    private val backdrop = object : LyricsBackdropControl {
        override fun setNight(night: Boolean) { backdropCalls += "night:$night" }
        override fun wake() { backdropCalls += "wake" }
        override fun sleep() { backdropCalls += "sleep" }
    }
    private var videoReady = true
    private val hintCounts = mutableListOf<Int>()

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
        pill = LyricsPillView(context).apply { layout(0, 0, 300, 100) }
        rail = LyricsRailView(context)
        val repository = LyricsRepository(LyricsSource { calls++; lrc(it) }, folder.root, Executor { jobs.add(it) }, ::now)
        controller = LyricsController(
            pill, rail, repository, speed, android.os.Handler(looper),
            observeNowPlaying = { listener -> onNowPlaying = listener; { onNowPlaying = null } },
            placement = { placement }, hideWhileDriving = { hideWhileDriving }, night = { night },
            sidePanelOpen = { sidePanelOpen }, nowMillis = ::now, offsetMillis = { offsetMillis },
            saveOffsetMillis = { saved += it; offsetMillis = it.toLong() },
            savePlacement = { placements += it; placement = it },
            videoReady = { videoReady }, backdrop = backdrop, noLyricsLabel = { "No lyrics" }, failedLabel = { "Can't load" }, switchHintCount = { hintsSeen }, saveSwitchHintCount = { hintCounts += it; hintsSeen = it },
        )
    }

    private fun now() = SystemClock.uptimeMillis()

    // These tests are about which line shows when; the clock must not run on while a fade plays.
    private fun noAnimations() {
        android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

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
        assertEquals("the lookups queued for earlier titles went stale and never asked", 0, calls)
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
        assertEquals("only the real track is asked; the churned titles went stale", 1, calls)
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
    fun noLyricsFoundDrawsOnlyTheNotice() {
        lrc = { null }
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        assertEquals(listOf("No lyrics"), pillText())
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
        assertEquals("the first failure is quiet: a retry usually fixes it", View.GONE, pill.visibility)
        advance(3_100L)
        runJobs()
        assertEquals("the second failure says so", listOf("Can't load"), pillText())
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
        noAnimations()
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
        noAnimations()
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
        noAnimations()
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
    fun theRailAndItsControlsStayWhileTheCarMoves() {
        placement = LyricsPlacement.SIDE_RAIL
        hideWhileDriving = false
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        assertEquals(View.VISIBLE, rail.gearButton.visibility)
        repeat(8) { onSpeed?.invoke(30); advance(500L) }
        advance(400L)
        assertEquals("the lyrics stay for the passenger", View.VISIBLE, rail.visibility)
        assertEquals(View.GONE, pill.visibility)
        assertEquals("the controls stay for the driver", View.VISIBLE, rail.gearButton.visibility)
        assertEquals("nothing was saved", LyricsPlacement.SIDE_RAIL, placement)
    }

    @Test
    fun theDoubleTapOnThePillWorksWhileTheCarMoves() {
        placement = LyricsPlacement.TOP_PILL
        hideWhileDriving = false
        controller.start()
        assertNotNull(onSpeed)
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        repeat(8) { onSpeed?.invoke(30); advance(500L) }
        tap(pill, 10_000L)
        tap(pill, 10_150L)
        assertEquals("moving: the double tap still switches", listOf(LyricsPlacement.SIDE_RAIL), placements)
    }

    // The host feeds the pill every touch; it consumes none.
    private fun tap(view: LyricsPillView, downAt: Long) {
        val down = android.view.MotionEvent.obtain(downAt, downAt, android.view.MotionEvent.ACTION_DOWN, 5f, 5f, 0)
        view.onHostTouch(down)
        val up = android.view.MotionEvent.obtain(downAt, downAt + 40, android.view.MotionEvent.ACTION_UP, 5f, 5f, 0)
        view.onHostTouch(up)
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
    fun theRailsEyeButtonHidesTheLyricsOfThisSongOnlyAndTheNextSongBringsThemBack() {
        placement = LyricsPlacement.SIDE_RAIL
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        assertEquals(View.VISIBLE, rail.visibility)
        rail.hideButton.performClick()
        advance(600L)
        assertEquals("hidden", View.GONE, rail.visibility)
        advance(30_000L)
        assertEquals("it stays hidden while the song plays", View.GONE, rail.visibility)
        publish(np("Another song", elapsed = 1_000L))
        runJobs()
        advance(600L)
        assertEquals("the next song shows them again", View.VISIBLE, rail.visibility)
        assertEquals("nothing was saved", LyricsPlacement.SIDE_RAIL, placement)
    }

    @Test
    fun aLateAlbumOrDurationUpdateOfTheSameSongKeepsTheLyricsHidden() {
        placement = LyricsPlacement.SIDE_RAIL
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        rail.hideButton.performClick()
        advance(600L)
        publish(np("Song", elapsed = 3_000L).copy(durationMillis = 201_000L, album = "Album (Deluxe)"))
        runJobs()
        advance(600L)
        assertEquals("same title and artist: still hidden", View.GONE, rail.visibility)
        publish(np("Another song", elapsed = 1_000L))
        runJobs()
        publish(np("Song", elapsed = 1_000L))
        runJobs()
        advance(600L)
        assertEquals("another song came in between: shown again", View.VISIBLE, rail.visibility)
    }

    @Test
    fun aSpellingChangeOfTheSameSongKeepsItHidden() {
        placement = LyricsPlacement.SIDE_RAIL
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        rail.hideButton.performClick()
        advance(600L)
        publish(np("SONG (Remastered 2009)", elapsed = 3_000L, artist = "artist"))
        runJobs()
        advance(600L)
        assertEquals(View.GONE, rail.visibility)
    }

    @Test
    fun whenTheMusicStopsTheHideEndsSoTheSongComesBackNextTime() {
        placement = LyricsPlacement.SIDE_RAIL
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        rail.hideButton.performClick()
        advance(600L)
        publish(np(null))
        runJobs()
        publish(np("Song", elapsed = 1_000L))
        runJobs()
        advance(600L)
        assertEquals(View.VISIBLE, rail.visibility)
    }

    @Test
    fun aSwipeUpOnThePillHidesTheLyricsOfThisSong() {
        placement = LyricsPlacement.TOP_PILL
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        assertEquals(View.VISIBLE, pill.visibility)
        val down = android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_DOWN, 5f, 5f, 0)
        val move = android.view.MotionEvent.obtain(0L, 50L, android.view.MotionEvent.ACTION_MOVE, 5f, -300f, 0)
        pill.onHostTouch(down)
        pill.onHostTouch(move)
        down.recycle()
        move.recycle()
        advance(600L)
        assertEquals(View.GONE, pill.visibility)
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
    fun aNewTrackWithoutAPositionDoesNotInheritTheOldTracksPosition() {
        lrc = { "[00:01.00]a\n[00:03.00]b\n[01:00.00]c" }
        controller.start()
        publish(np("Song A", elapsed = 2_000L, artist = "A"))
        runJobs()
        assertEquals(View.VISIBLE, pill.visibility)
        publish(np("Song B", elapsed = null, artist = "B"))
        runJobs()
        advance(400L)
        assertEquals("no position yet: nothing is shown", View.GONE, pill.visibility)
        publish(np("Song B", elapsed = 100L, artist = "B"))
        advance(400L)
        assertEquals(View.VISIBLE, pill.visibility)
    }

    @Test
    fun theRailFadesOutLikeThePillWhenDrivingHidesIt() {
        placement = LyricsPlacement.SIDE_RAIL
        hideWhileDriving = true
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        assertEquals(View.VISIBLE, rail.visibility)
        val steps = mutableListOf<Pair<Int, Int>>()
        repeat(8) {
            onSpeed?.invoke(30)
            val right = rail.visibility
            advance(500L)
            steps += right to rail.visibility
        }
        assertTrue("on the step the car counts as moving the rail is still on its way out", steps.contains(View.VISIBLE to View.GONE))
    }

    @Test
    fun nothingShowsBeforeCarPlaysFirstFrameAndTheLyricsAppearWithIt() {
        videoReady = false
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        assertEquals(View.GONE, pill.visibility)
        assertEquals(View.GONE, rail.visibility)
        videoReady = true
        controller.onVideoChanged()
        advance(400L)
        assertEquals(View.VISIBLE, pill.visibility)
    }

    @Test
    fun theLyricsGoWhenTheVideoSessionEnds() {
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        assertEquals(View.VISIBLE, pill.visibility)
        videoReady = false
        controller.onVideoChanged()
        advance(400L)
        assertEquals(View.GONE, pill.visibility)
    }

    @Test
    fun aTrackWithoutLyricsShowsASmallNoticeThatFadesAway() {
        lrc = { null }
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        assertEquals(View.VISIBLE, pill.visibility)
        assertEquals(listOf("No lyrics"), pillText())
        assertEquals("a notice is not a layout switch", View.GONE, rail.visibility)
        advance(4_000L)
        assertEquals(View.GONE, pill.visibility)
    }

    @Test
    fun theNoticeAppearsOnThePillEvenWhenTheLayoutIsTheRail() {
        lrc = { null }
        placement = LyricsPlacement.SIDE_RAIL
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        assertEquals(listOf("No lyrics"), pillText())
        assertEquals(View.GONE, rail.visibility)
    }

    @Test
    fun aFailedLookupSaysSoOnceAndNotOnEveryRetry() {
        lrc = { throw IOException("offline") }
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        assertEquals("a first failure shows nothing", View.GONE, pill.visibility)
        advance(3_100L)
        runJobs()
        advance(400L)
        assertEquals(listOf("Can't load"), pillText())
        advance(4_000L)
        assertEquals(View.GONE, pill.visibility)
        advance(10_100L)
        runJobs()
        advance(400L)
        assertEquals("the later retries fail quietly", View.GONE, pill.visibility)
    }

    @Test
    fun lyricsThatArriveOnARetryReplaceTheNotice() {
        var attempts = 0
        lrc = { if (++attempts < 3) throw IOException("offline") else "[00:01.00]one\n[00:30.00]two" }
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(3_100L)
        runJobs()
        advance(400L)
        assertEquals(listOf("Can't load"), pillText())
        advance(10_100L)
        runJobs()
        advance(400L)
        assertEquals(listOf("one", "two"), pillText())
    }

    @Test
    fun aNewTrackClearsTheNoticeAtOnce() {
        lrc = { null }
        controller.start()
        publish(np("Song A", elapsed = 2_000L, artist = "A"))
        runJobs()
        advance(400L)
        assertEquals(View.VISIBLE, pill.visibility)
        publish(np("Song B", elapsed = 0L, artist = "B"))
        assertEquals(View.GONE, pill.visibility)
    }

    @Test
    fun noNoticeBeforeTheFirstFrameWhileHiddenForDrivingOrWhenOff() {
        lrc = { null }
        videoReady = false
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        assertEquals(View.GONE, pill.visibility)
        videoReady = true
        hideWhileDriving = true
        repeat(8) { onSpeed?.invoke(30); advance(500L) }
        publish(np("Song 2", elapsed = 2_000L, artist = "B"))
        runJobs()
        advance(400L)
        assertEquals(View.GONE, pill.visibility)
    }

    @Test
    fun theNoticeTimerStartsWhenItFirstShowsNotWhenTheLookupAnswers() {
        lrc = { null }
        videoReady = false
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(5_000L) // longer than the notice lasts, with nothing on screen
        videoReady = true
        controller.onVideoChanged()
        advance(400L)
        assertEquals(listOf("No lyrics"), pillText())
        advance(4_000L)
        assertEquals(View.GONE, pill.visibility)
    }

    @Test
    fun theNoticeIsNotATapTarget() {
        lrc = { null }
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        tap(pill, 10_000L)
        tap(pill, 10_150L)
        assertTrue("a double tap on a notice does nothing", placements.isEmpty())
    }

    @Test
    fun theBackdropCopiesRunOnlyWhileSomethingShows() {
        controller.start()
        assertTrue("sleeping at the start", "wake" !in backdropCalls)
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        advance(400L)
        assertEquals("wake", backdropCalls.last())
        publish(np("Other", elapsed = 0L, artist = "B"))
        assertEquals("a cleared view stops the copies", "sleep", backdropCalls.last())
    }

    @Test
    fun theBackdropFollowsTheTheme() {
        night = true
        controller.start()
        controller.onNightChanged(false)
        assertTrue(backdropCalls.contains("night:true"))
        assertEquals("night:false", backdropCalls.last { it.startsWith("night") })
    }

    @Test
    fun stoppingTheControllerSleepsTheBackdrop() {
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        controller.stop()
        assertEquals("sleep", backdropCalls.last())
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
