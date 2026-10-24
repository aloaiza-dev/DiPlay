package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class XpengMapWindowTest {
    @Test fun ordinaryDevicesDoNotAcquireAnInstrumentWindow() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(XpengMapWindow.available(app))
        assertFalse(XpengMapWindow.enabled(app))
    }

    @Test fun requestedSourceMatchesOemCanvasAndKeepsTheMarkerInsideTheVisibleCenter() {
        val config = XpengMapWindow.streamConfig()
        assertEquals(XpengMapWindow.WIDTH, config.widthPixels)
        assertEquals(XpengMapWindow.HEIGHT, config.heightPixels)
        assertEquals(CarPlayClusterDisplay.MAP_URL, config.initialUrl)
        assertEquals(1920, config.widthPixels)
        assertEquals(720, config.heightPixels)
        assertEquals(30, config.fps)
        val safe = requireNotNull(config.safeArea)
        assertTrue(safe.left + safe.right < config.widthPixels)
        assertTrue(safe.top + safe.bottom < config.heightPixels)
        assertTrue(safe.left >= XpengMapMask.CLEAR_LEFT)
        assertTrue(safe.right >= XpengMapMask.CLEAR_LEFT)
        assertTrue(safe.top >= XpengMapMask.CLEAR_TOP)
        assertTrue(config.heightPixels - safe.bottom <= XpengMapMask.CLEAR_BOTTOM)
    }
}
