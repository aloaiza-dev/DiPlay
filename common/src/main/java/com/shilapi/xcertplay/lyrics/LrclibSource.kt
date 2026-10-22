package com.shilapi.xcertplay.lyrics

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToLong

data class TrackQuery(val title: String, val artist: String, val album: String?, val durationMillis: Long?)

fun interface LyricsSource {
    /** Raw LRC text, or null when the service has no synced lyrics. Throws [IOException] on a failure worth retrying. */
    @Throws(IOException::class)
    fun fetch(query: TrackQuery): String?
}

/**
 * [get] returns the body, null for "not found", and throws [IOException] for any other failure.
 *
 * The exact lookup needs the spelling LRCLIB stores, and one song has many ("Song (Remastered 2008)",
 * "Song - Remastered 2008", other albums). So a miss falls back to a search, matched by title without its
 * suffix, by artist and by a duration within [DURATION_TOLERANCE_MILLIS]. At most three requests per track.
 */
class LrclibSource(private val get: (url: String) -> String?) : LyricsSource {
    override fun fetch(query: TrackQuery): String? {
        syncedLyricsOf(get(getUrl(query)))?.let { return it }
        val titles = listOf(query.title, withoutSuffix(query.title)).distinct().filter { it.isNotBlank() }
        for (title in titles) {
            pick(get(searchUrl(title, query.artist)), query)?.let { return it }
        }
        return null
    }

    private fun syncedLyricsOf(body: String?): String? {
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return synced(json)
    }

    private fun synced(json: JSONObject): String? {
        if (json.optBoolean("instrumental") || json.isNull("syncedLyrics")) return null
        return json.optString("syncedLyrics").takeIf { it.isNotBlank() }
    }

    private fun pick(body: String?, query: TrackQuery): String? {
        val results = body?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        val wantedTitle = baseTitle(query.title)
        val wantedArtist = plain(query.artist)
        var best: String? = null
        var bestGap = Long.MAX_VALUE
        for (index in 0 until results.length()) {
            val entry = results.optJSONObject(index) ?: continue
            val lyrics = synced(entry) ?: continue
            if (plain(entry.optString("artistName")) != wantedArtist) continue
            if (baseTitle(entry.optString("trackName")) != wantedTitle) continue
            val gap = durationGap(entry, query) ?: continue
            if (gap < bestGap) {
                best = lyrics
                bestGap = gap
            }
        }
        return best
    }

    /** Distance in milliseconds, 0 when the query has no duration, or null when the entry is too far away. */
    private fun durationGap(entry: JSONObject, query: TrackQuery): Long? {
        val wanted = query.durationMillis ?: return 0L
        if (!entry.has("duration")) return 0L
        val gap = abs((entry.optDouble("duration") * 1000).roundToLong() - wanted)
        return gap.takeIf { it <= DURATION_TOLERANCE_MILLIS }
    }

    private fun getUrl(query: TrackQuery) = buildString {
        append("https://lrclib.net/api/get?track_name=").append(encode(query.title))
        append("&artist_name=").append(encode(query.artist))
        query.album?.takeIf { it.isNotBlank() }?.let { append("&album_name=").append(encode(it)) }
        query.durationMillis?.let { append("&duration=").append((it / 1000.0).roundToLong()) }
    }

    private fun searchUrl(title: String, artist: String) =
        "https://lrclib.net/api/search?track_name=${encode(title)}&artist_name=${encode(artist)}"

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val DURATION_TOLERANCE_MILLIS = 3_000L
    }
}

/** Lower case, no accents, letters and digits only. */
private fun plain(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** The title without bracketed parts and without a " - Remastered 2008" style tail, as written. */
internal fun withoutSuffix(title: String): String =
    title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").replace(Regex("\\s+-\\s+.*$"), "").trim()

/** [withoutSuffix] reduced to what two spellings of one title share. */
internal fun baseTitle(title: String): String = plain(withoutSuffix(title))

private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 10_000
private const val MAXIMUM_BYTES = 1024 * 1024
private const val USER_AGENT = "DiPlay (https://github.com/shihabal3amri/DiPlay)"

internal fun lrclibHttpGet(url: String): String? {
    val connection = URL(url).openConnection() as? HttpURLConnection ?: throw IOException("Not an HTTP connection")
    try {
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        val status = connection.responseCode
        if (status == HttpURLConnection.HTTP_NOT_FOUND) return null
        if (status !in 200..299) throw IOException("Lyrics service answered $status")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        connection.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > MAXIMUM_BYTES) throw IOException("Lyrics reply too large")
            }
        }
        return output.toString(Charsets.UTF_8.name())
    } finally {
        connection.disconnect()
    }
}
