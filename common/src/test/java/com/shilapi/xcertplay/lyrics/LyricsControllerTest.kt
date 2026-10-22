package com.shilapi.xcertplay.lyrics

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
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
            sidePanelOpen = { sidePanelOpen }, nowMillis = ::now,
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

    private fun pillText() = rows(pill).filter { it.visibility == View.VISIBLE }.map { it.text.toString() }

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
        publish(np("Song", elapsed = 2_000L, playing = false))
        assertEquals(1f, pill.alpha, 0.001f)
        advance(1_200L)
        assertEquals(0.6f, pill.alpha, 0.001f)
        publish(np("Song", elapsed = 2_000L, playing = true))
        assertEquals(1f, pill.alpha, 0.001f)
    }

    @Test
    fun themeChangeRecoloursWithoutRestartingTheLine() {
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        val before = calls
        controller.onNightChanged(false)
        assertEquals(LyricsTheme.colors(false).current, rows(pill)[0].currentTextColor)
        assertEquals(listOf("one", "two"), pillText())
        assertEquals(before, calls)
    }

    @Test
    fun theViewsStartInTheCurrentTheme() {
        night = false
        controller.start()
        publish(np("Song", elapsed = 2_000L))
        runJobs()
        assertEquals(LyricsTheme.colors(false).current, rows(pill)[0].currentTextColor)
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
