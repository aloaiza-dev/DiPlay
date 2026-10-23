package com.shilapi.xcertplay.lyrics

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.roundToLong

/** What a lookup came to. [Failed] is worth asking again; [None] is not. */
sealed interface LyricsOutcome {
    data class Found(val lyrics: SyncedLyrics) : LyricsOutcome
    object None : LyricsOutcome
    /** [reason] is for the log: what the lookup ran into. */
    data class Failed(val reason: String) : LyricsOutcome
}

/** Looks lyrics up on [worker] and keeps answers on disk, so a repeated song costs no request. */
class LyricsRepository(
    private val source: LyricsSource,
    private val cacheDir: File,
    private val worker: Executor,
    private val nowMillis: () -> Long,
) {
    /**
     * [onResult] runs on [worker]. A lookup whose turn comes when [isStale] is true is dropped without a request
     * or a reply: the worker is one thread, and a stale lookup must not hold up the next track's.
     */
    fun lookup(query: TrackQuery, isStale: () -> Boolean = { false }, onResult: (TrackQuery, LyricsOutcome) -> Unit) {
        worker.execute { if (!isStale()) onResult(query, resolve(query)) }
    }

    private fun resolve(query: TrackQuery): LyricsOutcome {
        val key = key(query)
        val hit = File(cacheDir, "$key.lrc")
        val miss = File(cacheDir, "$key.none")
        if (hit.isFile) {
            val cached = runCatching { LrcParser.parse(hit.readText()) }.getOrNull()
            if (cached != null) {
                hit.setLastModified(nowMillis())
                return LyricsOutcome.Found(cached)
            }
            hit.delete()
        }
        if (miss.isFile) {
            if (nowMillis() - miss.lastModified() < MISS_TTL_MILLIS) return LyricsOutcome.None
            miss.delete()
        }
        val raw = try {
            source.fetch(query)
        } catch (error: IOException) {
            return LyricsOutcome.Failed(error.message ?: error.javaClass.simpleName)
        }
        val lyrics = raw?.let(LrcParser::parse)?.takeIf { fitsDuration(it, query) }
        // A full or read-only disk must not cost the lyrics already fetched, or crash the worker.
        runCatching {
            cacheDir.mkdirs()
            if (lyrics != null) write(hit, checkNotNull(raw)) else write(miss, "")
            prune()
        }
        return if (lyrics != null) LyricsOutcome.Found(lyrics) else LyricsOutcome.None
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
