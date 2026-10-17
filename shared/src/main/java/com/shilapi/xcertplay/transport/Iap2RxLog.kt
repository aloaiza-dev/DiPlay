package com.shilapi.xcertplay.transport

/**
 * The report line for each received iAP2 message. Now-playing (0x5001) and route-guidance
 * (0x5201) updates repeat every second or so: together they were over half of a 25,000-line
 * report (#473), and reports cut off by GitHub's size limits lost the lines that mattered (#536,
 * #606). Those two are logged the first [first] times, then once every [every] with a count.
 */
internal class Iap2RxLog(private val first: Int = 5, private val every: Int = 100) {
    private val counts = HashMap<Int, Int>()

    fun line(messageId: Int): String? {
        val id = "0x" + messageId.toString(16).padStart(4, '0')
        if (messageId !in PERIODIC) return "iap2 rx=$id"
        val count = (counts[messageId] ?: 0) + 1
        counts[messageId] = count
        return when {
            count <= first -> "iap2 rx=$id"
            count % every == 0 -> "iap2 rx=$id count=$count"
            else -> null
        }
    }

    companion object {
        private val PERIODIC = setOf(0x5001, 0x5201)
        private const val MAX_PARAMS = 40
        private const val MAX_SHOWN_VALUE_BYTES = 1

        /** Logcat tag for [structure] lines. Off until `setprop log.tag.DiPlay-iap2 VERBOSE`. */
        const val TRACE_TAG = "DiPlay-iap2"

        /**
         * One line with every parameter's id and length, such as `iap2 rx=0x5201 01:1=06 03:12 05:8`.
         * Only one-byte values (states, flags) are shown. Text (a one-character string is two bytes with
         * its terminator), indexes, times and distances are length only, so a shared capture does not carry road names or places.
         */
        fun structure(messageId: Int, payload: ByteArray): String {
            val out = StringBuilder("iap2 rx=0x").append(messageId.toString(16).padStart(4, '0'))
                .append(" bytes=").append(payload.size)
            var offset = 0
            var shown = 0
            while (offset + 4 <= payload.size) {
                val length = u16(payload, offset)
                if (length < 4 || offset + length > payload.size) { out.append(" malformed@").append(offset); break }
                if (shown++ == MAX_PARAMS) { out.append(" …"); break }
                val valueLength = length - 4
                out.append(' ').append(u16(payload, offset + 2).toString(16).padStart(2, '0')).append(':').append(valueLength)
                if (valueLength in 1..MAX_SHOWN_VALUE_BYTES) {
                    out.append('=')
                    for (i in 0 until valueLength) out.append("%02x".format(payload[offset + 4 + i]))
                }
                offset += length
            }
            if (offset < payload.size && offset + 4 > payload.size) out.append(" trailing=").append(payload.size - offset)
            return out.toString()
        }

        @Volatile private var preferenceOn = false

        /**
         * Reads the opt-in switch. `log.tag.*` is ignored by liblog on user builds (ro.debuggable=0, as on
         * the XPeng head unit), so a preference is the second switch: `diplay_trace.xml`, boolean `iap2`.
         */
        fun configure(context: android.content.Context) {
            preferenceOn = runCatching {
                context.getSharedPreferences("diplay_trace", android.content.Context.MODE_PRIVATE).getBoolean("iap2", false)
            }.getOrDefault(false)
        }

        /** Logs [structure] for a received message when a trace switch is on; never throws. */
        fun trace(messageId: Int, payload: () -> ByteArray) {
            try {
                val on = preferenceOn || android.util.Log.isLoggable(TRACE_TAG, android.util.Log.VERBOSE)
                if (!gateReported.getAndSet(true)) android.util.Log.i(TRACE_TAG, "trace gate on=$on")
                // Info, not verbose: the XPeng head unit drops V and D lines.
                if (on) android.util.Log.i(TRACE_TAG, structure(messageId, payload()))
            } catch (failure: Throwable) {
                if (!failureReported.getAndSet(true)) {
                    android.util.Log.w(TRACE_TAG, "trace failed ${failure.javaClass.simpleName}")
                }
            }
        }

        private val gateReported = java.util.concurrent.atomic.AtomicBoolean()
        private val failureReported = java.util.concurrent.atomic.AtomicBoolean()

        private fun u16(data: ByteArray, offset: Int) =
            ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }
}
