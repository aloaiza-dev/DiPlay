package com.shilapi.xcertplay.lyrics

import android.os.Looper
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LyricsNowPlayingFeedTest {
    private val idle get() = shadowOf(Looper.getMainLooper()).idle()
    private fun playing(elapsed: Long, playing: Boolean = true) = CarPlayNowPlaying(title = "t", artist = "a", elapsedMillis = elapsed, playing = playing)

    @After fun reset() {
        LyricsNowPlayingFeed.publish(CarPlayNowPlaying())
        idle
    }

    @Test
    fun replaysTheLatestStateToANewObserver() {
        LyricsNowPlayingFeed.publish(playing(5_000L))
        idle
        val seen = mutableListOf<CarPlayNowPlaying>()
        val stop = LyricsNowPlayingFeed.observe { seen += it }
        assertEquals(listOf(playing(5_000L)), seen)
        stop()
    }

    @Test
    fun deliversPositionOnlyAndPlayStateUpdates() {
        val seen = mutableListOf<CarPlayNowPlaying>()
        val stop = LyricsNowPlayingFeed.observe { seen += it }
        seen.clear()
        LyricsNowPlayingFeed.publish(playing(1_000L))
        LyricsNowPlayingFeed.publish(playing(1_500L))
        LyricsNowPlayingFeed.publish(playing(1_500L, playing = false))
        idle
        assertEquals(listOf(1_000L, 1_500L, 1_500L), seen.map { it.elapsedMillis })
        assertEquals(false, seen.last().playing)
        stop()
    }

    @Test
    fun stoppingEndsDelivery() {
        val seen = mutableListOf<CarPlayNowPlaying>()
        val stop = LyricsNowPlayingFeed.observe { seen += it }
        stop()
        seen.clear()
        LyricsNowPlayingFeed.publish(playing(2_000L))
        idle
        assertTrue(seen.isEmpty())
    }
}
