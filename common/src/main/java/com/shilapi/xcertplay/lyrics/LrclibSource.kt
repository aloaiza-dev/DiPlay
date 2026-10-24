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
 * [get] returns the body, null for "not found" (404 or 400), and throws [IOException] for any other failure.
 *
 * LRCLIB's exact lookup answers 503 the first time it sees a track and 200 on the next call, while its
 * search answers in about half a second. So the search comes first: by title without its suffix, by the
 * primary artist (a full credit such as "Clean Bandit, Jess Glynne" can fail there), matched by the artist
 * credit and by a duration within [DURATION_TOLERANCE_MILLIS]. The exact lookup is the last resort. At most
 * three requests per track. "No lyrics" is returned only when every request that was made answered.
 */
class LrclibSource(private val get: (url: String) -> String?) : LyricsSource {
    override fun fetch(query: TrackQuery): String? {
        var failure: IOException? = null
        val titles = listOf(query.title, withoutSuffix(query.title)).distinct().filter { it.isNotBlank() }
        for (title in titles) {
            try {
                pick(get(searchUrl(title, primaryArtist(query.artist))), query)?.let { return it }
            } catch (error: IOException) {
                failure = error
                break // the other spelling would meet the same trouble: try the exact lookup instead
            }
        }
        try {
            syncedLyricsOf(get(getUrl(query)))?.let { return it }
        } catch (error: IOException) {
            failure = failure ?: error
        }
        failure?.let { throw it }
        return null
    }

    // A reply that is not JSON (a captive portal's page) is a failure to retry, not "no lyrics" to cache.
    private fun syncedLyricsOf(body: String?): String? {
        val json = body?.let { runCatching { JSONObject(it) }.getOrElse { throw IOException("Lyrics reply is not JSON") } } ?: return null
        return synced(json)
    }

    private fun synced(json: JSONObject): String? {
        if (json.optBoolean("instrumental") || json.isNull("syncedLyrics")) return null
        return json.optString("syncedLyrics").takeIf { it.isNotBlank() }
    }

    private fun pick(body: String?, query: TrackQuery): String? {
        val results = body?.let { runCatching { JSONArray(it) }.getOrElse { throw IOException("Lyrics reply is not JSON") } } ?: return null
        val wantedTitle = baseTitle(query.title)
        val wantedArtists = artistKeys(query.artist)
        var best: String? = null
        var bestGap = Long.MAX_VALUE
        for (index in 0 until results.length()) {
            val entry = results.optJSONObject(index) ?: continue
            val lyrics = synced(entry) ?: continue
            if (artistKeys(entry.optString("artistName")).intersect(wantedArtists).isEmpty()) continue
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
        // LRCLIB sends "duration": null for some entries, and optDouble turns that into NaN.
        val seconds = entry.optDouble("duration").takeIf { it.isFinite() } ?: return 0L
        val gap = abs((seconds * 1000).roundToLong() - wanted)
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
        // Wide on purpose: the phone's edit of a song often differs from LRCLIB's by ten seconds or more, and the closest wins.
        const val DURATION_TOLERANCE_MILLIS = 20_000L
    }
}

// A credit is a list of artists in many spellings: "A, B", "A & B", "A feat. B", "A • B", "A; B".
private val ARTIST_SEPARATORS = Regex("""\s*(?:,|;|&|/|\+|•|\bfeat\.?(?=\s)|\bft\.?(?=\s)|\bfeaturing\b|\bwith\b|\bx\b|\band\b)\s*""", RegexOption.IGNORE_CASE)

/** The artists of a credit, each in [plain] form. Two credits name the same song when they share one. */
internal fun artistKeys(credit: String): Set<String> =
    credit.split(ARTIST_SEPARATORS).map(::plain).filter { it.isNotBlank() }.toSet()

/** The first artist of a credit as written, for a search that a long credit can make fail. */
internal fun primaryArtist(credit: String): String =
    credit.split(ARTIST_SEPARATORS).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: credit

/** Lower case, no accents, letters and digits only. */
internal fun plain(text: String): String =
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
        // 400 too: LRCLIB can refuse an exact lookup it cannot match, and the search fallback must still run.
        if (status == HttpURLConnection.HTTP_NOT_FOUND || status == HttpURLConnection.HTTP_BAD_REQUEST) return null
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
