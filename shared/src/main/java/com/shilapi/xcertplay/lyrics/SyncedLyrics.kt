package com.shilapi.xcertplay.lyrics

data class LyricLine(val startMillis: Long, val text: String)

/** Timed lyric lines, sorted by start time. */
class SyncedLyrics(val lines: List<LyricLine>) {
    /** The line playing at [positionMillis]; -1 before the first line starts. */
    fun indexAt(positionMillis: Long): Int {
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMillis <= positionMillis) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
