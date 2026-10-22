package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LrclibSourceTest {
    private val urls = mutableListOf<String>()

    /** [replies] maps a URL fragment to the body; a URL that matches none is "not found". */
    private fun source(vararg replies: Pair<String, String?>) = LrclibSource { url ->
        urls += url
        replies.firstOrNull { (fragment, _) -> url.contains(fragment) }?.second
    }

    private fun entry(track: String, artist: String, seconds: Double, synced: String? = "[00:01.00] $track", instrumental: Boolean = false) =
        """{"trackName":"$track","artistName":"$artist","duration":$seconds,"instrumental":$instrumental,""" +
            """"syncedLyrics":${synced?.let { "\"$it\"" } ?: "null"}}"""

    private val query = TrackQuery("Matador (Remasterizado 2008)", "Los Fabulosos Cadillacs", "Vasos Vacíos", 276_000L)

    @Test
    fun buildsEncodedGetUrlWithDurationInSeconds() {
        source().fetch(TrackQuery("A & B", "Artist", "Album", 215_500L))
        assertEquals(
            "https://lrclib.net/api/get?track_name=A+%26+B&artist_name=Artist&album_name=Album&duration=216",
            urls.first(),
        )
    }

    @Test
    fun omitsAlbumAndDurationWhenUnknown() {
        source().fetch(TrackQuery("Song", "Artist", null, null))
        assertEquals("https://lrclib.net/api/get?track_name=Song&artist_name=Artist", urls.first())
    }

    @Test
    fun returnsSyncedLyricsFromTheExactLookup() {
        val reply = """{"id":1,"instrumental":false,"plainLyrics":"x","syncedLyrics":"[00:01.00] a"}"""
        assertEquals("[00:01.00] a", source("api/get" to reply).fetch(query))
        assertEquals(1, urls.size)
    }

    @Test
    fun exactLookupWithoutSyncedLyricsFallsBackToSearch() {
        val result = source(
            "api/get" to """{"instrumental":false,"plainLyrics":"x","syncedLyrics":null}""",
            "api/search" to "[${entry("Matador", "Los Fabulosos Cadillacs", 276.0, synced = "[00:02.00] found")}]",
        ).fetch(query)
        assertEquals("[00:02.00] found", result)
    }

    @Test
    fun searchFindsTheDashVariantOfARemasteredTitle() {
        val results = "[" + listOf(
            entry("Matador", "Los Fabulosos Cadillacs", 304.0, synced = "[00:01.00] chau"),
            entry("Matador - Remasterizado 2008", "Los Fabulosos Cadillacs", 276.0, synced = "[00:01.00] right one"),
            entry("El Matador (Live) [Remasterizado 2008]", "Los Fabulosos Cadillacs", 320.0),
        ).joinToString(",") + "]"
        assertEquals("[00:01.00] right one", source("api/search" to results).fetch(query))
    }

    @Test
    fun searchPicksTheClosestDurationWithinThreeSeconds() {
        val results = "[" + listOf(
            entry("Matador", "Los Fabulosos Cadillacs", 278.5, synced = "[00:01.00] far"),
            entry("Matador", "Los Fabulosos Cadillacs", 276.4, synced = "[00:01.00] near"),
        ).joinToString(",") + "]"
        assertEquals("[00:01.00] near", source("api/search" to results).fetch(query))
    }

    @Test
    fun searchRejectsADurationMoreThanThreeSecondsAway() {
        val results = "[" + entry("Matador", "Los Fabulosos Cadillacs", 304.0) + "]"
        assertNull(source("api/search" to results).fetch(query))
    }

    @Test
    fun searchRejectsAnotherArtist() {
        val results = "[" + entry("Matador", "Somebody Else", 276.0) + "]"
        assertNull(source("api/search" to results).fetch(query))
    }

    @Test
    fun searchSkipsInstrumentalsAndEntriesWithoutSyncedLyrics() {
        val results = "[" + listOf(
            entry("Matador", "Los Fabulosos Cadillacs", 276.0, synced = null),
            entry("Matador", "Los Fabulosos Cadillacs", 276.0, instrumental = true),
        ).joinToString(",") + "]"
        assertNull(source("api/search" to results).fetch(query))
    }

    @Test
    fun searchWithAnUnknownDurationTakesTheFirstMatchingTrack() {
        val results = "[" + listOf(
            entry("Other song", "Los Fabulosos Cadillacs", 100.0),
            entry("Matador", "Los Fabulosos Cadillacs", 304.0, synced = "[00:01.00] first match"),
        ).joinToString(",") + "]"
        assertEquals("[00:01.00] first match", source("api/search" to results).fetch(query.copy(durationMillis = null)))
    }

    @Test
    fun matchingIgnoresCaseAndAccents() {
        val results = "[" + entry("MATADOR", "LOS FABULOSOS CADILLACS", 276.0, synced = "[00:01.00] ok") + "]"
        assertEquals("[00:01.00] ok", source("api/search" to results).fetch(TrackQuery("Matádor", "Los Fabulosos Cadillacs", null, 276_000L)))
    }

    @Test
    fun asksForAtMostThreeRequests() {
        source().fetch(query)
        assertTrue("requests: $urls", urls.size <= 3)
        assertTrue(urls.first().contains("api/get"))
        assertTrue(urls.drop(1).all { it.contains("api/search") })
    }

    @Test
    fun theSecondSearchUsesTheTitleWithoutItsSuffix() {
        source().fetch(query)
        assertTrue("urls: $urls", urls.any { it.contains("api/search") && it.contains("track_name=Matador&") })
    }

    @Test
    fun returnsNullWhenThereAreNoSyncedLyricsAnywhere() {
        val query = TrackQuery("s", "a", null, null)
        assertNull(source("api/get" to """{"instrumental":false,"plainLyrics":"x","syncedLyrics":null}""").fetch(query))
        assertNull(source("api/get" to """{"instrumental":true,"syncedLyrics":""}""").fetch(query))
        assertNull(source().fetch(query))
    }

    @Test
    fun returnsNullForAReplyThatIsNotJson() {
        assertNull(source("api" to "<html>busy</html>").fetch(TrackQuery("s", "a", null, null)))
    }

    @Test(expected = IOException::class)
    fun aNetworkFailureIsNotHiddenByTheFallback() {
        LrclibSource { throw IOException("busy") }.fetch(query)
    }
}
