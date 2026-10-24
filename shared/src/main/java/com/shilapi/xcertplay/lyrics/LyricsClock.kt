package com.shilapi.xcertplay.lyrics

/**
 * Playback position between the iPhone's elapsed-time updates. It advances with the local clock
 * while playing and freezes on pause. Every update with an elapsed time re-syncs it.
 */
class LyricsClock(private val nowMillis: () -> Long) {
    private var position: Long? = null
    private var syncedAt = 0L
    private var playing = false

    fun sync(elapsedMillis: Long?, playing: Boolean) {
        val now = nowMillis()
        val next = elapsedMillis ?: positionMillis()
        if (next != null) {
            position = next
            syncedAt = now
        }
        this.playing = playing
    }

    fun positionMillis(): Long? {
        val base = position ?: return null
        return if (playing) base + (nowMillis() - syncedAt) else base
    }
}
