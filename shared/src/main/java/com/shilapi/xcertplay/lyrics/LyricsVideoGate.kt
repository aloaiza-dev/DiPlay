package com.shilapi.xcertplay.lyrics

/**
 * Keeps the lyrics off a blank screen: they wait for CarPlay's first video frame. The frame can be seen in
 * more than one place (the AirPlay event, the video surface), and none of those signals is guaranteed on
 * every head unit and connection, so the gate also opens by itself [waitMillis] after a session starts.
 * Hiding the lyrics for good because one signal never came is worse than showing them a little early.
 */
class LyricsVideoGate(private val nowMillis: () -> Long, private val waitMillis: Long = WAIT_MILLIS) {
    private var session: Any? = null
    private var since = 0L
    private var frameSeen = false
    // The last session that drew a frame, kept because its frame event can be handled before its start.
    private var lastFramed: Any? = null

    /** True when the gate opened without any frame signal. For the log. */
    var openedByTimeout = false
        private set

    /** The same session again, as when a host adopts a running one, keeps its clock. */
    fun sessionStarted(session: Any) {
        if (this.session === session) return
        this.session = session
        since = nowMillis()
        frameSeen = lastFramed === session
        openedByTimeout = false
    }

    fun sessionEnded() {
        session = null
        frameSeen = false
        lastFramed = null
        openedByTimeout = false
    }

    /** [from] null means the video surface drew one. True only the first time a frame opens the gate. */
    fun frameDrawn(from: Any?): Boolean {
        if (from != null) lastFramed = from
        val current = session ?: return false
        if (frameSeen || (from != null && from !== current)) return false
        frameSeen = true
        return true
    }

    fun ready(): Boolean {
        if (session == null) return false
        if (frameSeen) return true
        val waited = nowMillis() - since >= waitMillis
        if (waited) openedByTimeout = true
        return waited
    }

    companion object {
        const val WAIT_MILLIS = 12_000L
    }
}
