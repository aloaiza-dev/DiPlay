package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.security.SecureRandom

/**
 * Talks to [XpengClusterTool] over one interactive adb shell. Never asks for approval, so the car's
 * dialog cannot appear while driving; after a failure it waits [RETRY_MILLIS] before trying again.
 * Blocking: call from the output worker only.
 */
internal class XpengClusterClient(context: Context) : XpengClusterPort {
    private val app = context.applicationContext
    private var adb: LocalAdb? = null
    private var stream: LocalAdb.InteractiveShell? = null
    private var token: String? = null
    private var retryAtMillis = 0L

    /** Latest event, for the diagnostic report. Never holds road names or places. */
    @Volatile var lastEvent = "none"
        private set
    @Volatile var sent = 0
        private set

    private fun note(event: String) {
        lastEvent = "$event at ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}"
    }

    @Synchronized override fun connect(): Boolean = exchange("connect") == "ok"

    @Synchronized override fun info(guidance: XpengClusterGuidance): Boolean =
        exchange(XpengClusterProtocol.info(guidance)) == "ok"

    @Synchronized override fun clear() {
        // A dead helper must not delay the next route: no backoff after a failed close.
        if (stream != null) exchange("close", backoff = false)
        closeTransport()
    }

    private fun exchange(command: String, backoff: Boolean = true): String? {
        if (!ensure()) return null
        val reply = XpengClusterProtocol.reply(token?.let { stream?.exchangeBounded("$it $command", TIMEOUT_MILLIS) })
        if (reply == null || reply.startsWith("error")) {
            // The reply can echo provider text, which can carry the road name: keep only the exception class,
            // in the log as well as in the report.
            val failure = reply?.substringAfter("error=")?.substringBefore(':')?.take(60) ?: "no reply"
            Log.w(TAG, "helper '${command.substringBefore(' ')}' failed: $failure")
            note("'${command.substringBefore(' ')}' failed: $failure")
            closeTransport()
            if (backoff) retryAtMillis = SystemClock.elapsedRealtime() + RETRY_MILLIS
            return null
        }
        if (command.startsWith("info ")) sent++
        return reply
    }

    private fun ensure(): Boolean {
        if (stream != null) return true
        if (SystemClock.elapsedRealtime() < retryAtMillis) return false
        val client = LocalAdb(AdbKeys.load(app))
        val access = client.connect(mayAsk = false)
        if (access != LocalAdb.Access.READY) {
            note("ADB $access")
            client.close()
            retryAtMillis = SystemClock.elapsedRealtime() + RETRY_MILLIS
            Log.w(TAG, "ADB not available")
            return false
        }
        val next = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val apk = "'" + app.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
        val opened = try {
            client.openShell("CLASSPATH=$apk app_process /system/bin ${XpengClusterTool::class.java.name} --stdin $next")
        } catch (t: Throwable) {
            note("helper launch threw ${t.javaClass.simpleName}")
            client.close()
            throw t
        }
        adb = client
        stream = opened
        token = next
        val ready = opened != null &&
            XpengClusterProtocol.reply(opened.exchangeBounded("$next ping", STARTUP_TIMEOUT_MILLIS)) == "ready"
        if (!ready) {
            Log.w(TAG, "helper did not start")
            note(if (opened == null) "helper stream refused" else "helper did not answer ping")
            closeTransport()
            retryAtMillis = SystemClock.elapsedRealtime() + RETRY_MILLIS
        }
        if (ready) note("helper started")
        return ready
    }

    private fun closeTransport() {
        val oldStream = stream; stream = null
        val oldAdb = adb; adb = null
        token = null
        try { oldStream?.close() } finally { oldAdb?.close() }
    }

    private companion object {
        const val TAG = "DiPlay-XPeng-Cluster"
        const val TIMEOUT_MILLIS = 5000
        const val STARTUP_TIMEOUT_MILLIS = 10000
        const val RETRY_MILLIS = 30_000L
    }
}
