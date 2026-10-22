package com.shilapi.xcertplay.lyrics

import android.os.Handler
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import kotlin.math.abs

/**
 * Joins the iPhone's Now Playing updates to the lyrics views: looks lyrics up for a new track, follows
 * the playback position and decides what the views show. Everything runs on the [handler]'s thread;
 * [repository] replies from its worker are posted back to it.
 */
internal class LyricsController(
    private val pill: LyricsPillView,
    private val rail: LyricsRailView,
    private val repository: LyricsRepository,
    private val speed: LyricsSpeedSource,
    private val handler: Handler,
    private val observeNowPlaying: ((CarPlayNowPlaying) -> Unit) -> (() -> Unit),
    private val placement: () -> LyricsPlacement,
    private val hideWhileDriving: () -> Boolean,
    private val night: () -> Boolean,
    private val sidePanelOpen: () -> Boolean,
    private val nowMillis: () -> Long,
) {
    private var clock = LyricsClock(nowMillis)
    private var gate = DrivingGate(nowMillis)
    private var stopNowPlaying: (() -> Unit)? = null
    private var stopSpeed: (() -> Unit)? = null
    private var started = false
    private var ticking = false
    private var identity: TrackQuery? = null
    private var generation = 0
    private var lyrics: SyncedLyrics? = null
    private var playing = false
    private var pausedSince: Long? = null
    private var suppressed = false
    private var jumped = false
    private val titleChanges = ArrayDeque<Long>()

    private val tick = Runnable {
        ticking = false
        render()
        ensureTicking()
    }

    fun start() {
        stop()
        if (placement() == LyricsPlacement.OFF) return
        started = true
        onNightChanged(night())
        stopNowPlaying = observeNowPlaying(::onNowPlaying)
        if (hideWhileDriving()) stopSpeed = speed.observe { kmh ->
            gate.onSpeed(kmh)
            render()
        }
    }

    fun stop() {
        started = false
        ticking = false
        handler.removeCallbacks(tick)
        stopNowPlaying?.invoke()
        stopNowPlaying = null
        stopSpeed?.invoke()
        stopSpeed = null
        generation++
        identity = null
        lyrics = null
        suppressed = false
        titleChanges.clear()
        pausedSince = null
        clock = LyricsClock(nowMillis)
        gate = DrivingGate(nowMillis)
        pill.hide()
        rail.hide()
    }

    fun onNightChanged(night: Boolean) {
        pill.setNight(night)
        rail.setNight(night)
    }

    private fun onNowPlaying(update: CarPlayNowPlaying) {
        val expected = clock.positionMillis()
        clock.sync(update.elapsedMillis, update.playing)
        val actual = clock.positionMillis()
        if (expected != null && actual != null && abs(actual - expected) > SEEK_MILLIS) jumped = true
        playing = update.playing
        if (update.playing) pausedSince = null else if (pausedSince == null) pausedSince = nowMillis()
        val query = trackOf(update)
        if (query == null) {
            identity = null
            lyrics = null
            generation++
        } else if (query != identity) {
            lyrics = null
            generation++
            lookUp(query)
        }
        render()
        ensureTicking()
    }

    private fun trackOf(update: CarPlayNowPlaying): TrackQuery? {
        val title = update.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val artist = update.artist?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return TrackQuery(title, artist, update.album, update.durationMillis)
    }

    private fun lookUp(query: TrackQuery) {
        val previous = identity
        identity = query
        // Some apps put the current lyric line in the title. Two title-only changes within ten seconds
        // mean this is one of them: stop looking up until the artist, album or duration changes.
        val titleOnly = previous != null && previous.artist == query.artist &&
            previous.album == query.album && previous.durationMillis == query.durationMillis
        if (titleOnly) {
            val now = nowMillis()
            titleChanges.addLast(now)
            while (titleChanges.first() < now - TITLE_CHURN_WINDOW_MILLIS) titleChanges.removeFirst()
            suppressed = titleChanges.size >= 2
        } else {
            titleChanges.clear()
            suppressed = false
        }
        if (suppressed) return
        val forTrack = generation
        repository.lookup(query) { _, result ->
            handler.post {
                if (forTrack != generation) return@post
                lyrics = result
                render()
            }
        }
    }

    private fun render() {
        if (!started) return
        val mode = placement()
        val lines = lyrics
        val position = clock.positionMillis()
        val hiddenForDriving = hideWhileDriving() && gate.moving
        val railBlocked = mode == LyricsPlacement.SIDE_RAIL && sidePanelOpen()
        if (mode == LyricsPlacement.OFF || lines == null || position == null || suppressed || hiddenForDriving || railBlocked) {
            pill.hide()
            rail.hide()
            return
        }
        val index = lines.indexAt(position)
        val dimmed = !playing && pausedSince?.let { nowMillis() - it >= PAUSE_DIM_MILLIS } == true
        pill.setDimmed(dimmed)
        rail.setDimmed(dimmed)
        val animate = !jumped
        jumped = false
        if (mode == LyricsPlacement.TOP_PILL) {
            rail.hide()
            showPill(lines, index, position, animate)
        } else {
            pill.hide()
            rail.show(lines.lines.map { it.text.ifBlank { NOTE } }, index)
        }
    }

    private fun showPill(lyrics: SyncedLyrics, index: Int, position: Long, animate: Boolean) {
        if (index < 0) {
            val first = lyrics.lines.first()
            if (first.startMillis - position <= PRE_ROLL_MILLIS) pill.show(null, first.text.ifBlank { NOTE }, animate) else pill.hide()
            return
        }
        pill.show(lyrics.lines[index].text.ifBlank { NOTE }, lyrics.lines.getOrNull(index + 1)?.text, animate)
    }

    private fun ensureTicking() {
        if (!started || ticking || identity == null) return
        ticking = true
        handler.postDelayed(tick, TICK_MILLIS)
    }

    private companion object {
        const val NOTE = "♪"
        const val TICK_MILLIS = 250L
        const val PAUSE_DIM_MILLIS = 1_000L
        const val PRE_ROLL_MILLIS = 3_000L
        const val SEEK_MILLIS = 2_000L
        const val TITLE_CHURN_WINDOW_MILLIS = 10_000L
    }
}
