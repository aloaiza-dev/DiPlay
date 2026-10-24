package com.shilapi.xcertplay.lyrics

object LrcParser {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /** The timed lines of an LRC text, or null when it has none. */
    fun parse(raw: String): SyncedLyrics? {
        val rows = raw.removePrefix("﻿").split('\n').map { it.trimEnd('\r') }
        // LRC: a positive offset makes the lyrics appear sooner.
        val offset = rows.firstNotNullOfOrNull { offsetTag.find(it.trimStart())?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
        val lines = ArrayList<LyricLine>()
        for (row in rows) {
            val stamps = ArrayList<Long>()
            var rest = row.trimStart()
            while (true) {
                val match = timestamp.matchAt(rest, 0) ?: break
                val (minutes, seconds, fraction) = match.destructured
                stamps += minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fraction.padEnd(3, '0').toLong()
                rest = rest.substring(match.value.length)
            }
            if (stamps.isEmpty()) continue
            val text = rest.trim()
            for (stamp in stamps) lines += LyricLine((stamp - offset).coerceAtLeast(0L), text)
        }
        if (lines.isEmpty()) return null
        return SyncedLyrics(lines.sortedBy { it.startMillis })
    }
}
