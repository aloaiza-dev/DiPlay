package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.lyrics.LyricsPlacement
import com.shilapi.xcertplay.lyrics.LyricsRailSide
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

    @Test fun timingDefaultsToAHalfSecondEarlier() {
        assertEquals(500, AirPlayPersistence.loadLyricsOffsetMillis(context))
    }

    @Test fun timingRoundTripsIncludingNegativeValues() {
        for (millis in listOf(1000, -250, 0, 2000)) {
            AirPlayPersistence.saveLyricsOffsetMillis(context, millis)
            assertEquals(millis, AirPlayPersistence.loadLyricsOffsetMillis(context))
        }
    }

    @Test fun aStoredTimingOutsideTheRangeIsClamped() {
        prefs.edit().putInt("lyrics_offset_ms", 99_999).apply()
        assertEquals(2000, AirPlayPersistence.loadLyricsOffsetMillis(context))
        prefs.edit().putInt("lyrics_offset_ms", -99_999).apply()
        assertEquals(-2000, AirPlayPersistence.loadLyricsOffsetMillis(context))
    }

    @Test fun railSideDefaultsToAutoAndRoundTrips() {
        assertEquals(LyricsRailSide.AUTO, AirPlayPersistence.loadLyricsRailSide(context))
        for (side in LyricsRailSide.entries) {
            AirPlayPersistence.saveLyricsRailSide(context, side)
            assertEquals(side, AirPlayPersistence.loadLyricsRailSide(context))
        }
    }

    @Test fun anUnknownRailSideFallsBackToAuto() {
        prefs.edit().putString("lyrics_rail_side", "top").apply()
        assertEquals(LyricsRailSide.AUTO, AirPlayPersistence.loadLyricsRailSide(context))
    }

    @Test fun blurIsOnByDefaultAndRoundTrips() {
        assertEquals(true, AirPlayPersistence.loadLyricsBlur(context))
        AirPlayPersistence.saveLyricsBlur(context, false)
        assertEquals(false, AirPlayPersistence.loadLyricsBlur(context))
    }

    @Test fun blurIsOffByDefaultOnALowRamHeadUnitButAChoiceStillWins() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        org.robolectric.Shadows.shadowOf(manager).setIsLowRamDevice(true)
        assertEquals(false, AirPlayPersistence.loadLyricsBlur(context))
        AirPlayPersistence.saveLyricsBlur(context, true)
        assertEquals(true, AirPlayPersistence.loadLyricsBlur(context))
    }

    @Test fun switchHintsStartAtZeroAndRoundTrip() {
        assertEquals(0, AirPlayPersistence.loadLyricsSwitchHints(context))
        AirPlayPersistence.saveLyricsSwitchHints(context, 2)
        assertEquals(2, AirPlayPersistence.loadLyricsSwitchHints(context))
    }

    @Test fun lyricsSectionIsInDisplayOnly() {
        val owners = SettingsInformationArchitecture.sectionsByCategory
            .filterValues { SettingsSection.LYRICS in it }.keys
        assertEquals(setOf(SettingsCategory.DISPLAY), owners)
    }
}
