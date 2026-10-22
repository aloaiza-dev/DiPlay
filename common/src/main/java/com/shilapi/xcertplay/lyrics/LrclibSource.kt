package com.shilapi.xcertplay.lyrics

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToLong

data class TrackQuery(val title: String, val artist: String, val album: String?, val durationMillis: Long?)

fun interface LyricsSource {
    /** Raw LRC text, or null when the service has no synced lyrics. Throws [IOException] on a failure worth retrying. */
    @Throws(IOException::class)
    fun fetch(query: TrackQuery): String?
}

/** [get] returns the body, null for "not found", and throws [IOException] for any other failure. */
class LrclibSource(private val get: (url: String) -> String?) : LyricsSource {
    override fun fetch(query: TrackQuery): String? {
        val body = get(url(query)) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optBoolean("instrumental") || json.isNull("syncedLyrics")) return null
        return json.optString("syncedLyrics").takeIf { it.isNotBlank() }
    }

    private fun url(query: TrackQuery) = buildString {
        append("https://lrclib.net/api/get?track_name=").append(encode(query.title))
        append("&artist_name=").append(encode(query.artist))
        query.album?.takeIf { it.isNotBlank() }?.let { append("&album_name=").append(encode(it)) }
        query.durationMillis?.let { append("&duration=").append((it / 1000.0).roundToLong()) }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}

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
