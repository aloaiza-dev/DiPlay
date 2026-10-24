package com.shilapi.xcertplay.hud

import java.util.concurrent.TimeUnit

internal interface XpengClusterPort {
    /** Starts the cluster's projection and navigation state. False when the helper is not reachable. */
    fun connect(): Boolean
    fun info(guidance: XpengClusterGuidance): Boolean
    fun clear()
}

/** Sends guidance to the cluster card, skipping repeats and coalescing distance updates. One thread only. */
internal class XpengClusterOutput(
    private val port: XpengClusterPort,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    var active = false
        private set
    private var last: XpengClusterGuidance? = null
    private var lastSentNs = 0L

    fun update(guidance: XpengClusterGuidance) {
        if (!active) {
            if (!port.connect()) return
            active = true
            last = null
        }
        val previous = last
        if (guidance == previous) return
        // The remaining distance changes with every frame. A new arrow is sent at once, the rest at most once a second.
        if (previous != null && guidance.roadType == previous.roadType &&
            nanoTime() - lastSentNs < MIN_INTERVAL_NS) return
        if (port.info(guidance)) {
            last = guidance
            lastSentNs = nanoTime()
        } else {
            active = false // The helper restarts clean, so the next update connects again.
        }
    }

    fun stop() {
        if (!active) return
        port.clear()
        active = false
        last = null
    }

    private companion object {
        val MIN_INTERVAL_NS = TimeUnit.SECONDS.toNanos(1)
    }
}
