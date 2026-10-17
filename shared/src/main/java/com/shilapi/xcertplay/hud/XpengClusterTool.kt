package com.shilapi.xcertplay.hud

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import java.io.File

/**
 * Called by app_process under the authorized shell identity. The XPeng instrument provider requires
 * `LAUNCH_RESTRICTED_COMPONENT`, which only that identity holds. Reads typed requests from stdin
 * ([XpengClusterProtocol]) and answers one line plus `done` for each.
 */
object XpengClusterTool {
    @JvmStatic fun main(args: Array<String>) {
        if (args.size != 2 || args[0] != "--stdin") return
        val token = args[1].takeIf(XpengClusterProtocol::validToken) ?: return
        val ownership = runCatching {
            AmbientWorkerOwnerLock.tryAcquire(File("/data/local/tmp/diplay-xpeng-cluster.lock"))
        }.getOrNull()
        if (ownership == null) {
            println("error worker-busy"); println("done"); System.out.flush()
            return
        }
        val router = InstrumentRouter()
        try {
            while (true) {
                val line = XpengClusterProtocol.readLine(System.`in`) ?: break
                val request = XpengClusterProtocol.parse(line, token) ?: break
                val reply = try {
                    when (request) {
                        XpengClusterProtocol.Request.Ping -> "ready"
                        XpengClusterProtocol.Request.Connect -> { router.connect(); "ok" }
                        is XpengClusterProtocol.Request.Info -> { router.info(request.guidance); "ok" }
                        XpengClusterProtocol.Request.Clear -> { router.disconnect(); "ok" }
                        XpengClusterProtocol.Request.Close -> { router.disconnect(); "closed" }
                    }
                } catch (t: Throwable) {
                    "error=" + ((t.cause ?: t).javaClass.simpleName + ":" + (t.cause ?: t).message.orEmpty())
                        .replace(Regex("[\\r\\n ]+"), " ").take(120)
                }
                println(reply); println("done"); System.out.flush()
                if (request == XpengClusterProtocol.Request.Close) break
            }
        } finally {
            // EOF means the app died or dropped the stream: do not leave a stale card on the cluster.
            runCatching { router.disconnect() }
            ownership.close()
        }
    }
}

/** The instrument app's CarPlay/Android Auto output API, as verified on a G9 (Android 12). */
@SuppressLint("PrivateApi")
private class InstrumentRouter {
    private var router: IBinder? = null
    private var connected = false

    fun connect() {
        // Connection and navigation state first: the card shows only after both.
        transact(CODE_STATE, "state", "1")
        connected = true // from here a failure still needs disconnect() to undo the first call
        transact(CODE_NAV_STATE, "state", "1")
    }

    fun info(guidance: XpengClusterGuidance) {
        transact(CODE_INFO, "info", guidance.toJson())
    }

    /** Clears navigation and then the connection. Safe to repeat. */
    fun disconnect() {
        if (!connected) return
        connected = false
        runCatching { transact(CODE_NAV_STATE, "state", "0") }
        runCatching { transact(CODE_STATE, "state", "0") }
    }

    private fun transact(code: Int, key: String, value: String) {
        try {
            send(binder(), code, key, value)
        } catch (_: DeadObjectException) {
            // The instrument app restarted and forgot its state: replay it before the call that failed.
            router = null
            if (connected && code == CODE_INFO) {
                send(binder(), CODE_STATE, "state", "1")
                send(binder(), CODE_NAV_STATE, "state", "1")
            }
            send(binder(), code, key, value)
        }
    }

    private fun send(remote: IBinder, code: Int, key: String, value: String) {
        val uri = Uri.parse("content://$DESCRIPTOR").buildUpon().appendQueryParameter(key, value).build()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            uri.writeToParcel(data, 0) // the router reads a bare Uri, with no null marker
            check(remote.transact(code, data, reply, 0)) { "transaction-unsupported-$code" }
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun binder(): IBinder = router ?: acquire().also { router = it }

    private fun acquire(): IBinder {
        val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val activityManager = Class.forName("android.app.IActivityManager")
        val token = Binder()
        val holder = activityManager
            .getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(manager, AUTHORITY, 0, token, "diplay-xpeng-cluster")
            ?: error("provider-unavailable")
        try {
            val provider = holder.javaClass.getField("provider").get(holder)
            val attributionClass = Class.forName("android.content.AttributionSource")
            val builder = Class.forName("android.content.AttributionSource\$Builder")
                .getConstructor(Int::class.javaPrimitiveType).newInstance(SHELL_UID)
            builder.javaClass.getMethod("setPackageName", String::class.java).invoke(builder, "com.android.shell")
            val attribution = builder.javaClass.getMethod("build").invoke(builder)
            val result = Class.forName("android.content.IContentProvider")
                .getMethod("call", attributionClass, String::class.java, String::class.java, String::class.java, Bundle::class.java)
                .invoke(provider, attribution, AUTHORITY, "CarplayApiRouterService", null, null) as Bundle?
            val remote = result?.getBinder("binder") ?: error("router-missing")
            check(remote.interfaceDescriptor == DESCRIPTOR) { "unexpected-interface" }
            return remote
        } finally {
            runCatching {
                activityManager.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
                    .invoke(manager, AUTHORITY, token)
            }
        }
    }

    private companion object {
        const val AUTHORITY = "com.xiaopeng.instrument.api.publisher"
        const val DESCRIPTOR = "com.xiaopeng.instrument.ui.manager.CarplayApiRouterService"
        const val SHELL_UID = 2000
        // Transaction codes of the tested build. The provider also publishes a manifest of them, but
        // its format was never captured, so another firmware needs it read before these can change.
        const val CODE_INFO = 0
        const val CODE_NAV_STATE = 4
        const val CODE_STATE = 5
    }
}
