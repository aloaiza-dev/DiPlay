package com.shilapi.xcertplay.lyrics

import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/**
 * Every Now Playing update, position and play state included, for the lyrics. `ClusterMusicState` is not
 * enough: it publishes only metadata changes, because republishing the artwork twice a second for the
 * position exhausted memory on one head unit. This feed carries no artwork, and it does no work while
 * nothing observes it.
 */
internal object LyricsNowPlayingFeed {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(CarPlayNowPlaying) -> Unit>() // main thread
    @Volatile private var latest = CarPlayNowPlaying()
    @Volatile private var observed = false

    /** Any thread. */
    fun publish(info: CarPlayNowPlaying) {
        latest = info
        if (!observed) return
        main.post { listeners.toList().forEach { it(info) } }
    }

    /** Main thread. The current state is delivered at once. Returns the function that stops observing. */
    fun observe(listener: (CarPlayNowPlaying) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners.add(listener)
        observed = true
        listener(latest)
        return {
            listeners.remove(listener)
            observed = listeners.isNotEmpty()
        }
    }
}
