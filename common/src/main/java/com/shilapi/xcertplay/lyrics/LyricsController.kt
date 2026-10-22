package com.shilapi.xcertplay.lyrics

import android.os.Handler
import android.view.View
import android.util.Log
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
    /** True: the rail stays while the car moves, minus its touch controls. False: the pill replaces it. */
    private val keepRailWhileDriving: () -> Boolean,
    private val sidePanelOpen: () -> Boolean,
    private val nowMillis: () -> Long,
    /** How much earlier the lyrics show than their timestamps say; see [LyricsTiming]. Read on every render. */
    private val offsetMillis: () -> Long,
    /** Persists the timing the rail's control sets, so Settings and the rail always agree. */
    private val saveOffsetMillis: (Int) -> Unit,
    /** Persists the layout the rail's pill icon or a double tap on the pill switches to. */
    private val savePlacement: (LyricsPlacement) -> Unit,
    /** How many tracks the pill has taught the double tap on; see [HINT_LIMIT]. */
    private val switchHintCount: () -> Int,
    private val saveSwitchHintCount: (Int) -> Unit,
) {
    private var clock = LyricsClock(nowMillis)
    private var gate = DrivingGate(nowMillis)
    private var stopNowPlaying: (() -> Unit)? = null
    private var stopSpeed: (() -> Unit)? = null
    private var started = false
    private var nextChangeMillis: Long? = null
    private var identity: TrackQuery? = null
    @Volatile private var generation = 0
    private var lyrics: SyncedLyrics? = null
    private var playing = false
    private var pausedSince: Long? = null
    private var suppressed = false
    private var jumped = false
    private val titleChanges = ArrayDeque<Long>()
    private var hintedFor = -1
    private val hideHint = Runnable { pill.setHint(false) }

    private val tick = Runnable {
        render()
        scheduleTick()
    }

    fun start() {
        stop()
        if (placement() == LyricsPlacement.OFF) return
        started = true
        onNightChanged(night())
        stopNowPlaying = observeNowPlaying(::onNowPlaying)
        // The rail's timing control: each tap is saved and takes effect on the next render.
        rail.setTimingControl(offsetMillis().toInt()) { millis ->
            saveOffsetMillis(millis)
            render()
        }
        // Either view switches to the other: the pill by a double tap, the rail by its pill icon.
        rail.setSwitchAction { switchTo(LyricsPlacement.TOP_PILL) }
        pill.setSwitchAction { switchTo(LyricsPlacement.SIDE_RAIL) }
        // The speed hides the lyrics while driving when asked, and in every case takes the touch controls
        // away (the pill's double tap, the rail's icons): a moving car must not get a tap target.
        stopSpeed = speed.observe { kmh ->
            gate.onSpeed(kmh)
            render()
        }
    }

    private fun switchTo(target: LyricsPlacement) {
        // A driver who double-taps knows the gesture.
        if (target == LyricsPlacement.SIDE_RAIL) saveSwitchHintCount(HINT_LIMIT)
        savePlacement(target)
        render()
    }

    fun stop() {
        started = false
        nextChangeMillis = null
        handler.removeCallbacks(tick)
        handler.removeCallbacks(hideHint)
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
        pill.setSwitchAction(null)
        rail.setSwitchAction(null)
        pill.hide()
        rail.hide()
    }

    fun onNightChanged(night: Boolean) {
        pill.setNight(night)
        rail.setNight(night)
    }

    private fun onNowPlaying(update: CarPlayNowPlaying) {
        val query = trackOf(update)
        // The old track's position is no use to a new one: wait for the phone to report its own.
        if (query != identity) clock = LyricsClock(nowMillis)
        val expected = clock.positionMillis()
        clock.sync(update.elapsedMillis, update.playing)
        val actual = clock.positionMillis()
        if (expected != null && actual != null && abs(actual - expected) > SEEK_MILLIS) jumped = true
        playing = update.playing
        if (update.playing) pausedSince = null else if (pausedSince == null) pausedSince = nowMillis()
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
        scheduleTick()
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
        request(query, attempt = 0)
    }

    // A failed lookup is not cached, so nothing would ask again until the song changed. The head unit's
    // network can be slow to come up after CarPlay connects, so ask again with growing pauses.
    private fun request(query: TrackQuery, attempt: Int) {
        val forTrack = generation
        repository.lookup(query, isStale = { forTrack != generation }) { _, outcome ->
            handler.post {
                if (forTrack != generation) return@post
                when (outcome) {
                    is LyricsOutcome.Found -> {
                        Log.i(TAG, "\"${query.title}\" / ${query.artist}: ${outcome.lyrics.lines.size} lines")
                        lyrics = outcome.lyrics
                        render()
                    }
                    LyricsOutcome.None -> Log.i(TAG, "\"${query.title}\" / ${query.artist}: no synced lyrics")
                    LyricsOutcome.Failed -> {
                        val delay = RETRY_DELAYS_MILLIS.getOrNull(attempt)
                        Log.i(TAG, "\"${query.title}\" / ${query.artist}: lookup failed" +
                            if (delay != null) ", retry in ${delay / 1000} s" else ", giving up")
                        if (delay != null) handler.postDelayed({
                            if (started && forTrack == generation) request(query, attempt + 1)
                        }, delay)
                    }
                }
            }
        }
    }

    private fun render() {
        if (!started) return
        val mode = placement()
        val lines = lyrics
        // The timing offset moves the position the lines are looked up at: earlier lyrics for a positive one.
        val position = clock.positionMillis()?.let { (it + offsetMillis()).coerceAtLeast(0L) }
        val moving = gate.moving
        val hiddenForDriving = hideWhileDriving() && moving
        val railBlocked = mode == LyricsPlacement.SIDE_RAIL && sidePanelOpen()
        val stopped = !playing && pausedSince?.let { nowMillis() - it >= PAUSE_HIDE_MILLIS } == true
        if (mode == LyricsPlacement.OFF || lines == null || position == null || suppressed || hiddenForDriving || railBlocked || stopped) {
            // Music that stops and a car that moves fade the overlay out; every other reason clears it at once.
            if (hiddenForDriving || stopped) {
                pill.fadeOut()
                rail.fadeOut()
            } else {
                pill.hide()
                rail.hide()
            }
            nextChangeMillis = null
            return
        }
        val index = lines.indexAt(position)
        nextChangeMillis = if (!playing) null else untilNextChange(lines, index, position)
        val dimmed = !playing && pausedSince?.let { nowMillis() - it >= PAUSE_DIM_MILLIS } == true
        pill.setDimmed(dimmed)
        rail.setDimmed(dimmed)
        val animate = !jumped
        jumped = false
        // The view that is not wanted fades out while the wanted one comes in: a switch reads as one move.
        // Unless asked to keep the rail, a moving car gets the pill whatever the saved layout.
        if (mode == LyricsPlacement.TOP_PILL || (moving && !keepRailWhileDriving())) {
            rail.fadeOut()
            pill.setInteractive(!moving)
            showPill(lines, index, position, animate)
            if (mode == LyricsPlacement.TOP_PILL && !moving) teachDoubleTap() else pill.setHint(false)
        } else {
            pill.fadeOut()
            rail.setControlsAvailable(!moving)
            rail.show(lines.lines.map { it.text.ifBlank { NOTE } }, index)
        }
    }

    // The first few tracks a parked driver sees on the pill carry a short hint for the double tap.
    private fun teachDoubleTap() {
        if (hintedFor == generation || pill.visibility != View.VISIBLE) return
        val seen = switchHintCount()
        if (seen >= HINT_LIMIT) return
        hintedFor = generation
        saveSwitchHintCount(seen + 1)
        pill.setHint(true)
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, HINT_MILLIS)
    }

    private fun showPill(lyrics: SyncedLyrics, index: Int, position: Long, animate: Boolean) {
        if (index < 0) {
            val first = lyrics.lines.first()
            if (first.startMillis - position <= PRE_ROLL_MILLIS) pill.show(null, first.text.ifBlank { NOTE }, animate) else pill.hide()
            return
        }
        pill.show(lyrics.lines[index].text.ifBlank { NOTE }, lyrics.lines.getOrNull(index + 1)?.text, animate)
    }

    /** Milliseconds until what the views show changes: the next line, or the first line's pre-roll. */
    private fun untilNextChange(lyrics: SyncedLyrics, index: Int, position: Long): Long? {
        if (index >= 0) return lyrics.lines.getOrNull(index + 1)?.let { it.startMillis - position }
        val untilFirst = lyrics.lines.first().startMillis - position
        return if (untilFirst > PRE_ROLL_MILLIS) untilFirst - PRE_ROLL_MILLIS else untilFirst
    }

    // Wake at the exact moment the line changes, and at least every tick for the other things that change.
    private fun scheduleTick() {
        if (!started || identity == null) return
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, (nextChangeMillis ?: TICK_MILLIS).coerceIn(MIN_WAIT_MILLIS, TICK_MILLIS))
    }

    private companion object {
        const val NOTE = "♪"
        const val TICK_MILLIS = 250L
        const val HINT_LIMIT = 3
        const val HINT_MILLIS = 4_000L
        const val MIN_WAIT_MILLIS = 16L
        const val TAG = "DiPlay-Lyrics"
        val RETRY_DELAYS_MILLIS = listOf(3_000L, 10_000L, 30_000L, 60_000L, 60_000L)
        const val PAUSE_DIM_MILLIS = 1_000L
        const val PAUSE_HIDE_MILLIS = 4_000L
        const val PRE_ROLL_MILLIS = 3_000L
        const val SEEK_MILLIS = 2_000L
        const val TITLE_CHURN_WINDOW_MILLIS = 10_000L
    }
}
