package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LrclibSourceTest {
    private fun source(reply: String?, urls: MutableList<String> = mutableListOf()) =
        LrclibSource { url -> urls += url; reply }

    @Test
    fun buildsEncodedGetUrlWithDurationInSeconds() {
        val urls = mutableListOf<String>()
        source(null, urls).fetch(TrackQuery("A & B", "Artist", "Album", 215_500L))
        assertEquals(
            "https://lrclib.net/api/get?track_name=A+%26+B&artist_name=Artist&album_name=Album&duration=216",
            urls.single(),
        )
    }

    @Test
    fun omitsAlbumAndDurationWhenUnknown() {
        val urls = mutableListOf<String>()
        source(null, urls).fetch(TrackQuery("Song", "Artist", null, null))
        assertEquals("https://lrclib.net/api/get?track_name=Song&artist_name=Artist", urls.single())
    }

    @Test
    fun returnsSyncedLyricsField() {
        val reply = """{"id":1,"instrumental":false,"plainLyrics":"x","syncedLyrics":"[00:01.00] a"}"""
        assertEquals("[00:01.00] a", source(reply).fetch(TrackQuery("s", "a", null, null)))
    }

    @Test
    fun returnsNullWhenThereAreNoSyncedLyrics() {
        val query = TrackQuery("s", "a", null, null)
        assertNull(source("""{"instrumental":false,"plainLyrics":"x","syncedLyrics":null}""").fetch(query))
        assertNull(source("""{"instrumental":true,"syncedLyrics":""}""").fetch(query))
        assertNull(source(null).fetch(query))
    }

    @Test
    fun returnsNullForAReplyThatIsNotJson() {
        assertNull(source("<html>busy</html>").fetch(TrackQuery("s", "a", null, null)))
    }
}
