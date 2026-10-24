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

    // A miss cached by an earlier, weaker lookup must not hide what the current one can find.
    @Test
    fun missesSavedByAnOlderLookupAreIgnored() {
        val seconds = 200L
        val legacy = java.security.MessageDigest.getInstance("SHA-1")
            .digest("song|artist|$seconds".toByteArray()).joinToString("") { "%02x".format(it) }
        File(folder.root, "$legacy.none").apply { writeText(""); setLastModified(now) }
        assertNotNull("looked up again", lookup(repository()))
        assertEquals(1, calls)
    }

    @Test
    fun aMissIsReportedAsNoneAndAFailureAsFailed() {
        reply = { null }
        assertEquals(LyricsOutcome.None, outcome(repository()))
        reply = { throw IOException("busy") }
        assertTrue(outcome(repository(), query.copy(title = "Other")) is LyricsOutcome.Failed)
    }

    @Test
    fun lyricsAreStillReturnedWhenTheCacheCannotBeWritten() {
        val blocked = folder.newFile("not-a-directory")
        assertNotNull(lookup(repository(blocked)))
        reply = { null }
        assertEquals(LyricsOutcome.None, outcome(repository(blocked), query.copy(title = "Other")))
    }

    @Test
    fun aLookupThatWentStaleBeforeItsTurnIsSkippedWithoutARequest() {
        var answered = false
        repository().lookup(query, isStale = { true }) { _, _ -> answered = true }
        assertEquals(0, calls)
        assertEquals(false, answered)
    }

    @Test
    fun anUnexpectedErrorInTheSourceIsAFailureAndNeverCrashesTheWorker() {
        reply = { throw IllegalArgumentException("Cannot round NaN value.") }
        val failed = outcome(repository()) as LyricsOutcome.Failed
        assertEquals("Cannot round NaN value.", failed.reason)
    }

    @Test
    fun aFailureSaysWhyForTheLog() {
        reply = { throw IOException("Lyrics service answered 503") }
        val failed = outcome(repository()) as LyricsOutcome.Failed
        assertEquals("Lyrics service answered 503", failed.reason)
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
    fun lastLineWithinTwentySecondsOfTheTrackIsAccepted() {
        reply = { "[00:01.00]a\n[03:40.00]b" }
        assertNotNull(lookup(repository()))
        reply = { "[00:01.00]a\n[03:40.50]b" }
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

    // A head unit's storage is small: files of an older cache version are removed, and the total size is capped.
    @Test
    fun filesOfAnOlderCacheVersionAreRemovedOnTheNextWrite() {
        val old = File(folder.root, "0123456789abcdef.lrc").apply { writeText("[00:01.00]old") }
        assertNotNull(lookup(repository()))
        assertTrue("the old file is gone", !old.exists())
    }

    @Test
    fun theCacheStaysUnderItsByteCapAndKeepsTheNewestFiles() {
        val line = "[00:01.00]" + "x".repeat(990) + "\n[00:03.00]b"
        reply = { line }
        val repository = LyricsRepository(source, folder.root, { it.run() }, { now }, maxBytes = 5_000L)
        for (index in 0 until 10) {
            now += 1_000L
            lookup(repository, query.copy(title = "Song $index"))
        }
        val size = folder.root.listFiles()!!.sumOf { it.length() }
        assertTrue("size $size", size <= 5_000L)
        val before = calls
        now += 1_000L
        lookup(repository, query.copy(title = "Song 9"))
        assertEquals("the newest is still cached", before, calls)
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
