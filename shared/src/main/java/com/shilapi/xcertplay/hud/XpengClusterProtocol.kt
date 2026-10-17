package com.shilapi.xcertplay.hud

import java.io.InputStream

/**
 * One line per request between the app and the shell-UID helper. The helper accepts only these typed
 * operations, never a raw Binder transaction. Text fields are hex, because the shell stream splits on
 * spaces and refuses newlines.
 */
internal object XpengClusterProtocol {
    const val MAX_LINE_BYTES = 512
    private const val MAX_TEXT_BYTES = 120
    private const val HEX = "0123456789abcdef"
    private val tokenPattern = Regex("[0-9a-f]{32}")

    sealed interface Request {
        data object Ping : Request
        data object Connect : Request
        data object Clear : Request
        data object Close : Request
        data class Info(val guidance: XpengClusterGuidance) : Request
    }

    fun validToken(token: String) = tokenPattern.matches(token)

    fun info(guidance: XpengClusterGuidance): String =
        "info ${guidance.roadType} ${guidance.remainingMeters} ${guidance.remainingSeconds} " +
            "${hex(guidance.arrivalTime)} ${hex(guidance.roadName)}"

    fun parse(line: String, token: String): Request? {
        if (!validToken(token) || line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) return null
        val f = line.split(' ')
        if (f.firstOrNull() != token) return null
        when (f.size) {
            2 -> return when (f[1]) {
                "ping" -> Request.Ping
                "connect" -> Request.Connect
                "clear" -> Request.Clear
                "close" -> Request.Close
                else -> null
            }
            7 -> if (f[1] == "info") {
                val roadType = f[2].toIntOrNull()?.takeIf { it in XpengClusterGuidance.ARROW_IDS } ?: return null
                val meters = f[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val seconds = f[4].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val arrival = unhex(f[5]) ?: return null
                val road = unhex(f[6]) ?: return null
                return Request.Info(XpengClusterGuidance(roadType, road, meters, seconds, arrival))
            }
        }
        return null
    }

    /** The helper's one-line reply in [output]; app_process may print its own lines before it. */
    fun reply(output: String?): String? = output?.lines()?.lastOrNull { it.isNotBlank() }?.trim()

    fun readLine(input: InputStream): String? {
        val b = java.io.ByteArrayOutputStream()
        repeat(MAX_LINE_BYTES + 1) {
            val c = input.read()
            if (c < 0) return null
            if (c == 10) return b.toString("UTF-8")
            b.write(c)
        }
        return null
    }

    // An empty string is "-" so that the field is never empty between two spaces.
    private fun hex(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_TEXT_BYTES) truncated(text) else it }
        return if (bytes.isEmpty()) "-" else bytes.joinToString("") { "%02x".format(it) }
    }

    private fun truncated(text: String): ByteArray {
        var cut = text
        while (cut.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) cut = cut.dropLast(1)
        return cut.toByteArray(Charsets.UTF_8)
    }

    private fun unhex(field: String): String? {
        if (field == "-") return ""
        if (field.length % 2 != 0 || field.length > MAX_TEXT_BYTES * 2) return null
        val out = ByteArray(field.length / 2)
        for (i in out.indices) {
            val high = HEX.indexOf(field[i * 2])
            val low = HEX.indexOf(field[i * 2 + 1])
            if (high < 0 || low < 0) return null
            out[i] = (high * 16 + low).toByte()
        }
        return String(out, Charsets.UTF_8)
    }
}
