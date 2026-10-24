package com.shilapi.xcertplay.lyrics

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

interface LyricsSpeedSource {
    /** Whether DiPlay can read a speed at all. When false, the lyrics are never hidden for driving. */
    val available: Boolean

    /** [listener] gets km/h, or null for a reading without a speed. Returns the function that stops it. */
    fun observe(listener: (Int?) -> Unit): () -> Unit
}

/** GPS speed. It needs no vehicle bus, so it works on any head unit that grants DiPlay the location permission. */
class LocationLyricsSpeedSource(context: Context) : LyricsSpeedSource {
    private val app = context.applicationContext
    private val manager get() = app.getSystemService(LocationManager::class.java)

    override val available: Boolean
        get() = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED && LocationManager.GPS_PROVIDER in manager.allProviders

    @SuppressLint("MissingPermission")
    override fun observe(listener: (Int?) -> Unit): () -> Unit {
        if (!available) {
            listener(null)
            return {}
        }
        val updates = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                listener(if (location.hasSpeed()) (location.speed * MPS_TO_KMH).roundToInt() else null)
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Called only before Android 10")
            override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
        }
        val subscribed = runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MILLIS, 0f, updates, Looper.getMainLooper(),
            )
        }.isSuccess
        if (!subscribed) {
            listener(null)
            return {}
        }
        return { runCatching { manager.removeUpdates(updates) } }
    }

    private companion object {
        const val MPS_TO_KMH = 3.6f
        const val UPDATE_INTERVAL_MILLIS = 1_000L
    }
}
