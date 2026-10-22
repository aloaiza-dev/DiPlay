package com.shilapi.xcertplay.lyrics

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.roundToLong

/** Looks lyrics up on [worker] and keeps answers on disk, so a repeated song costs no request. */
class LyricsRepository(
    private val source: LyricsSource,
    private val cacheDir: File,
    private val worker: Executor,
    private val nowMillis: () -> Long,
) {
    /** [onResult] runs on [worker]; a null result means "show nothing". */
    fun lookup(query: TrackQuery, onResult: (TrackQuery, SyncedLyrics?) -> Unit) {
        worker.execute { onResult(query, resolve(query)) }
    }

    private fun resolve(query: TrackQuery): SyncedLyrics? {
        val key = key(query)
        val hit = File(cacheDir, "$key.lrc")
        val miss = File(cacheDir, "$key.none")
        if (hit.isFile) {
            val cached = runCatching { LrcParser.parse(hit.readText()) }.getOrNull()
            if (cached != null) {
                hit.setLastModified(nowMillis())
                return cached
            }
            hit.delete()
        }
        if (miss.isFile) {
            if (nowMillis() - miss.lastModified() < MISS_TTL_MILLIS) return null
            miss.delete()
        }
        val raw = try {
            source.fetch(query)
        } catch (_: IOException) {
            return null
        }
        val lyrics = raw?.let(LrcParser::parse)?.takeIf { fitsDuration(it, query) }
        cacheDir.mkdirs()
        if (lyrics != null) write(hit, checkNotNull(raw)) else write(miss, "")
        prune()
        return lyrics
    }

    // A reply that runs well past the track is another recording: show nothing rather than wrong lyrics.
    private fun fitsDuration(lyrics: SyncedLyrics, query: TrackQuery): Boolean {
        val duration = query.durationMillis ?: return true
        return lyrics.lines.last().startMillis <= duration + DURATION_SLACK_MILLIS
    }

    private fun write(file: File, text: String) {
        file.writeText(text)
        file.setLastModified(nowMillis())
    }

    private fun prune() {
        val files = cacheDir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun key(query: TrackQuery): String {
        val seconds = query.durationMillis?.let { (it / 1000.0).roundToLong() } ?: 0L
        val text = "${query.title.lowercase(Locale.ROOT)}|${query.artist.lowercase(Locale.ROOT)}|$seconds"
        return MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MISS_TTL_MILLIS = 24 * 3_600_000L
        const val DURATION_SLACK_MILLIS = 5_000L
        const val MAX_FILES = 200
    }
}
