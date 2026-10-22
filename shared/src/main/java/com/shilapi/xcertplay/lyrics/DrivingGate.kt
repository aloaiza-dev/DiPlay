package com.shilapi.xcertplay.lyrics

/**
 * Whether the car moves, from speed readings. Two thresholds with a hold each way stop flicker at a
 * crawl. A missing reading never starts "moving", and a long silence ends it, so a lost signal cannot
 * keep the lyrics hidden.
 */
class DrivingGate(private val nowMillis: () -> Long) {
    private var isMoving = false
    private var aboveSince: Long? = null
    private var belowSince: Long? = null
    private var lastReadingAt: Long? = null

    val moving: Boolean
        get() {
            refresh()
            return isMoving
        }

    fun onSpeed(kmh: Int?) {
        refresh()
        val now = nowMillis()
        if (kmh == null) return
        lastReadingAt = now
        when {
            kmh >= START_KMH -> {
                if (aboveSince == null) aboveSince = now
                belowSince = null
            }
            kmh < STOP_KMH -> {
                if (belowSince == null) belowSince = now
                aboveSince = null
            }
            else -> {
                aboveSince = null
                belowSince = null
            }
        }
    }

    private fun refresh() {
        val now = nowMillis()
        val last = lastReadingAt
        if (last == null || now - last >= SIGNAL_LOST_MILLIS) {
            isMoving = false
            aboveSince = null
            belowSince = null
            return
        }
        val above = aboveSince
        val below = belowSince
        if (!isMoving && above != null && now - above >= START_HOLD_MILLIS) isMoving = true
        else if (isMoving && below != null && now - below >= STOP_HOLD_MILLIS) isMoving = false
    }

    private companion object {
        const val START_KMH = 5
        const val STOP_KMH = 2
        const val START_HOLD_MILLIS = 2_000L
        const val STOP_HOLD_MILLIS = 3_000L
        const val SIGNAL_LOST_MILLIS = 5_000L
    }
}
