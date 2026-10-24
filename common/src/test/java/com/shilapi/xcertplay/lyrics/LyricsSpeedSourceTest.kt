package com.shilapi.xcertplay.lyrics

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LyricsSpeedSourceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(LocationManager::class.java)

    private fun fix(metersPerSecond: Float?, atMillis: Long = 0L) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 1.0
        longitude = 2.0
        time = 1_000_000L + atMillis
        elapsedRealtimeNanos = atMillis * 1_000_000L
        if (metersPerSecond != null) speed = metersPerSecond
    }

    private fun deliver(location: Location) {
        shadowOf(manager).simulateLocation(location)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun withGps() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    @Test
    fun withoutPermissionIsUnavailableAndReportsNoSpeed() {
        val readings = mutableListOf<Int?>()
        val source = LocationLyricsSpeedSource(context)
        assertFalse(source.available)
        source.observe { readings += it }
        assertEquals(listOf<Int?>(null), readings)
    }

    @Test
    fun convertsMetersPerSecondToRoundedKmh() {
        withGps()
        val readings = mutableListOf<Int?>()
        val source = LocationLyricsSpeedSource(context)
        assertTrue(source.available)
        val stop = source.observe { readings += it }
        deliver(fix(10f))
        deliver(fix(1.4f, atMillis = 2_000L))
        stop()
        assertEquals(listOf<Int?>(36, 5), readings)
    }

    @Test
    fun aFixWithoutSpeedReportsNull() {
        withGps()
        val readings = mutableListOf<Int?>()
        LocationLyricsSpeedSource(context).observe { readings += it }
        deliver(fix(null))
        assertEquals(listOf<Int?>(null), readings)
    }

    @Test
    fun stoppingEndsUpdates() {
        withGps()
        val readings = mutableListOf<Int?>()
        val stop = LocationLyricsSpeedSource(context).observe { readings += it }
        stop()
        deliver(fix(10f))
        assertTrue(readings.isEmpty())
    }
}
