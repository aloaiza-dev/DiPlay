package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.shared.R
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Publishes CarPlay guidance to the directions card of XPeng's instrument cluster, through a
 * shell-UID helper ([XpengClusterTool]). Needs the navigation output switch
 * ([BydOutputSettings.enabled]) and its own opt-in ([BydOutputSettings.xpengCluster]).
 */
internal object XpengClusterBridge {
    private const val TAG = "DiPlay-XPeng-Cluster"
    private const val PROVIDER = "com.xiaopeng.instrument.api.publisher"

    private val lock = Any()
    private val route = BydHudRouteState()
    private val navigation = FceNavigationState()
    private var output: XpengClusterOutput? = null
    private var client: XpengClusterClient? = null
    private var context: Context? = null
    private var tickStarted = false
    private var clock: (Context) -> (Long) -> String = { app ->
        XpengClusterGuidance.clockFormatter(DateFormat.is24HourFormat(app))
    }
    private var labels: (Context) -> XpengClusterGuidance.Labels? = { app ->
        XpengClusterGuidance.Labels(
            roundabout = app.getString(R.string.xpeng_cluster_roundabout),
            roundaboutExit = { app.getString(R.string.xpeng_cluster_roundabout_exit, it) },
            arrive = app.getString(R.string.xpeng_cluster_arrive),
            left = app.getString(R.string.xpeng_cluster_left),
        )
    }
    // The head unit's theme, read from the app configuration. Assumes the cluster card follows it.
    private var night: (Context) -> Boolean = { app ->
        app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    /** Both must hold: an XPeng head unit, and its instrument app's output API. Other cars never see the XPeng settings. */
    fun available(context: Context): Boolean =
        isXpengHeadUnit() && runCatching { context.packageManager.resolveContentProvider(PROVIDER, 0) != null }.getOrDefault(false)

    internal fun isXpengHeadUnit(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND).any {
        it.contains("xpeng", ignoreCase = true) || it.contains("xiaopeng", ignoreCase = true)
    }

    /** For the diagnostic report: why a blank cluster is blank. No road names or places. */
    fun report(appContext: Context): String = synchronized(lock) {
        listOf(
            "providerVisible=${available(appContext)}",
            "navigationOutputEnabled=${BydOutputSettings.enabled(appContext)} xpengOptIn=${BydOutputSettings.xpengCluster(appContext)}",
            "initialized=${context != null} routeGuidance=${route.currentApple() != null} outputActive=${output?.active == true}",
            "helper: ${client?.lastEvent ?: "never started"}; updatesSent=${client?.sent ?: 0}",
        ).joinToString("\n")
    }

    fun initialize(appContext: Context) = synchronized(lock) {
        if (context != null) return@synchronized
        context = appContext.applicationContext
        client = XpengClusterClient(appContext).also { output = XpengClusterOutput(it) }
        Log.i(TAG, "XPeng cluster output ready")
        if (!tickStarted) {
            tickStarted = true
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-xpeng-cluster").apply { isDaemon = true }
            }.scheduleAtFixedRate(::tick, 1, 1, TimeUnit.SECONDS)
        }
    }

    fun onFrame(frame: Iap2Frame) = synchronized(lock) {
        if (context == null) return@synchronized
        val sourceBefore = navigation.sourceName
        navigation.accept(frame.messageId, frame.payload)
        val sourceAfter = navigation.sourceName
        // Maneuvers are cached and never resent, so a new navigation app, or one that reports no
        // source, must not inherit the previous app's arrow and road. Clear before this frame's own data.
        if (sourceBefore != null && sourceAfter != sourceBefore) route.clear()
        route.accept(frame.messageId, frame.payload)
        if (sourceAfter == "") route.clear()
        refreshLocked()
    }

    fun clear() = synchronized(lock) {
        route.clear() // The tick must not restore guidance after cleanup.
        navigation.clear()
        output?.stop()
    }

    // Guidance can expire without a frame, the switch can be turned off mid-route, and a coalesced
    // update is only sent on a later pass, so check every second.
    private fun tick() = synchronized(lock) {
        // An escaped exception would cancel every later run of the scheduled task.
        if (context != null) runCatching { refreshLocked() }.onFailure { Log.w(TAG, "tick failed", it) }
    }

    private fun refreshLocked() {
        val app = context ?: return
        val out = output ?: return
        if (!(BydOutputSettings.enabled(app) && BydOutputSettings.xpengCluster(app))) return out.stop()
        val apple = route.currentApple() ?: return out.stop()
        // No verified arrow (roundabout, U-turn, arrival) or no route totals yet: the card has nothing
        // true to show, and a held card would keep the last arrow with a frozen distance.
        val guidance = XpengClusterGuidance.from(apple, labels(app), night(app), clock(app)) ?: return out.stop()
        out.update(guidance)
    }

    /** Tests replace the transport and the clock, and reset state. */
    internal fun resetForTest(
        port: XpengClusterPort?,
        appContext: Context?,
        arrivalTime: (Long) -> String = { "00:00" },
        maneuverLabels: XpengClusterGuidance.Labels? = null,
        isNight: () -> Boolean = { true },
        nanoTime: () -> Long = System::nanoTime,
    ) = synchronized(lock) {
        route.clear()
        navigation.clear()
        output = port?.let { XpengClusterOutput(it, nanoTime) }
        context = appContext
        clock = { arrivalTime }
        labels = { maneuverLabels }
        night = { isNight() }
    }
}
