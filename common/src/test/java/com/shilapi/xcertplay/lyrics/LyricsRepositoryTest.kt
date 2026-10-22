package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LyricsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = 1_000_000_000L
    private var calls = 0
    private var reply: () -> String? = { "[00:01.00]a\n[00:03.00]b" }
    private val source = LyricsSource { calls++; reply() }
    private val query = TrackQuery("Song", "Artist", "Album", 200_000L)

    private fun repository(dir: File = folder.root) =
        LyricsRepository(source, dir, { it.run() }, { now })

    private fun outcome(repository: LyricsRepository, track: TrackQuery = query): LyricsOutcome {
        var result: LyricsOutcome? = null
        var delivered: TrackQuery? = null
        repository.lookup(track) { q, outcome -> delivered = q; result = outcome }
        assertEquals(track, delivered)
        return checkNotNull(result)
    }

    private fun lookup(repository: LyricsRepository, track: TrackQuery = query): SyncedLyrics? =
        (outcome(repository, track) as? LyricsOutcome.Found)?.lyrics

    @Test
    fun cachesHitOnDisk() {
        assertNotNull(lookup(repository()))
        assertNotNull(lookup(repository()))
        assertEquals(1, calls)
    }

    @Test
    fun cachesMissUntilExpiry() {
        reply = { null }
        assertNull(lookup(repository()))
        now += 23 * 3_600_000L
        assertNull(lookup(repository()))
        assertEquals(1, calls)
        now += 2 * 3_600_000L
        assertNull(lookup(repository()))
        assertEquals(2, calls)
    }

    @Test
    fun aMissIsReportedAsNoneAndAFailureAsFailed() {
        reply = { null }
        assertEquals(LyricsOutcome.None, outcome(repository()))
        reply = { throw IOException("busy") }
        assertEquals(LyricsOutcome.Failed, outcome(repository(), query.copy(title = "Other")))
    }

    @Test
    fun lyricsAreStillReturnedWhenTheCacheCannotBeWritten() {
        val blocked = folder.newFile("not-a-directory")
        assertNotNull(lookup(repository(blocked)))
        reply = { null }
        assertEquals(LyricsOutcome.None, outcome(repository(blocked), query.copy(title = "Other")))
    }

    @Test
    fun doesNotCacheNetworkFailure() {
        reply = { throw IOException("busy") }
        assertNull(lookup(repository()))
        reply = { "[00:01.00]a" }
        assertNotNull(lookup(repository()))
        assertEquals(2, calls)
    }

    @Test
    fun durationMismatchIsRejected() {
        reply = { "[00:01.00]a\n[05:00.00]b" }
        assertNull(lookup(repository()))
    }

    @Test
    fun lastLineWithinFiveSecondsOfTheTrackIsAccepted() {
        reply = { "[00:01.00]a\n[03:25.00]b" }
        assertNotNull(lookup(repository()))
        reply = { "[00:01.00]a\n[03:25.50]b" }
        assertNull(lookup(repository(), query.copy(title = "Other")))
    }

    @Test
    fun unknownDurationSkipsTheMismatchCheck() {
        reply = { "[00:01.00]a\n[59:00.00]b" }
        assertNotNull(lookup(repository(), query.copy(durationMillis = null)))
    }

    @Test
    fun corruptCacheFileIsRefetched() {
        assertNotNull(lookup(repository()))
        folder.root.listFiles()!!.single { it.name.endsWith(".lrc") }.writeText("not lyrics")
        assertNotNull(lookup(repository()))
        assertEquals(2, calls)
    }

    @Test
    fun cacheDirStaysUnderBound() {
        val repository = repository()
        for (index in 0 until 250) {
            now += 1_000L
            lookup(repository, query.copy(title = "Song $index"))
        }
        assertEquals(200, folder.root.listFiles()!!.size)
        val before = calls
        now += 1_000L
        lookup(repository, query.copy(title = "Song 249"))
        assertEquals(before, calls)
        lookup(repository, query.copy(title = "Song 0"))
        assertTrue(calls > before)
    }
}
