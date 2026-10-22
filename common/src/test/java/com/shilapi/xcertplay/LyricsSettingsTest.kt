package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.lyrics.LyricsPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LyricsSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() { prefs.edit().clear().apply() }

    @Test fun defaultsAreOffAndVisible() {
        assertEquals(LyricsPlacement.OFF, AirPlayPersistence.loadLyricsPlacement(context))
        assertFalse(AirPlayPersistence.loadHideLyricsWhileDriving(context))
    }

    @Test fun placementRoundTrips() {
        for (placement in LyricsPlacement.entries) {
            AirPlayPersistence.saveLyricsPlacement(context, placement)
            assertEquals(placement, AirPlayPersistence.loadLyricsPlacement(context))
        }
    }

    @Test fun unknownStoredValueFallsBackToOff() {
        prefs.edit().putString("lyrics_placement", "future-layout").apply()
        assertEquals(LyricsPlacement.OFF, AirPlayPersistence.loadLyricsPlacement(context))
    }

    @Test fun hideWhileDrivingRoundTrips() {
        AirPlayPersistence.saveHideLyricsWhileDriving(context, true)
        assertEquals(true, AirPlayPersistence.loadHideLyricsWhileDriving(context))
    }

    @Test fun lyricsSectionIsInDisplayOnly() {
        val owners = SettingsInformationArchitecture.sectionsByCategory
            .filterValues { SettingsSection.LYRICS in it }.keys
        assertEquals(setOf(SettingsCategory.DISPLAY), owners)
    }
}
