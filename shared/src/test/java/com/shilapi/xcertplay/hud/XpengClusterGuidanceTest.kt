package com.shilapi.xcertplay.hud

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class XpengClusterGuidanceTest {
    private fun maneuver(type: Int, road: String = "Calle 8", distance: Int = 120) = BydAppleManeuver(
        distanceMeters = distance, type = type, drivingSide = 0, road = road,
        remainingSeconds = 240, remainingMeters = 3000, arrivalEpochSeconds = 1_000,
    )

    @Test
    fun `basic turns map to the card's left right and straight arrows`() {
        assertEquals(7, XpengClusterGuidance.roadType(1))
        assertEquals(8, XpengClusterGuidance.roadType(2))
        assertEquals(1, XpengClusterGuidance.roadType(3))
    }

    @Test
    fun `the day theme has no left or right image, so right uses 0 and left uses the sharp-left arrow`() {
        assertEquals(9, XpengClusterGuidance.roadType(1, night = false))
        assertEquals(9, XpengClusterGuidance.roadType(20, night = false))
        assertEquals(0, XpengClusterGuidance.roadType(2, night = false))
        assertEquals(0, XpengClusterGuidance.roadType(21, night = false))
        assertEquals(7, XpengClusterGuidance.roadType(1, night = true))
        assertEquals(8, XpengClusterGuidance.roadType(2, night = true))
        listOf(3, 13, 14, 47, 48).forEach {
            assertEquals("type $it", XpengClusterGuidance.roadType(it, night = true), XpengClusterGuidance.roadType(it, night = false))
        }
    }

    @Test
    fun `a day left turn says Left in the text and the other turns do not`() {
        val named = XpengClusterGuidance.Labels("Roundabout", { "Roundabout, exit $it" }, "Arrive", "Left")
        fun text(type: Int, night: Boolean) = XpengClusterGuidance.from(maneuver(type = type), named, night) { "" }!!.roadName
        assertEquals("Left · 120 m · Calle 8", text(1, night = false))
        assertEquals("Left · 120 m · Calle 8", text(20, night = false))
        assertEquals("120 m · Calle 8", text(1, night = true))
        assertEquals("120 m · Calle 8", text(2, night = false))
        assertEquals(0, XpengClusterGuidance.from(maneuver(type = 2), named, night = false) { "" }!!.roadType)
        assertEquals(9, XpengClusterGuidance.from(maneuver(type = 1), named, night = false) { "" }!!.roadType)
    }

    @Test
    fun `slight and sharp turns use their own arrows`() {
        assertEquals(5, XpengClusterGuidance.roadType(13))
        assertEquals(6, XpengClusterGuidance.roadType(14))
        assertEquals(9, XpengClusterGuidance.roadType(47))
        assertEquals(16, XpengClusterGuidance.roadType(48))
    }

    @Test
    fun `u-turn arrow follows the driving side`() {
        assertEquals(17, XpengClusterGuidance.roadType(4, drivingSide = 0))
        assertEquals(18, XpengClusterGuidance.roadType(4, drivingSide = 1))
    }

    @Test
    fun `roundabouts and arrival have no glyph so they show straight`() {
        listOf(6, 7, 10, 12, 24, 25, 27, 28, 46).forEach { assertEquals("type $it", 1, XpengClusterGuidance.roadType(it)) }
    }

    @Test
    fun `unknown types give no guidance`() {
        listOf(0, 99, 255).forEach { assertNull("type $it", XpengClusterGuidance.roadType(it)) }
        assertNull(XpengClusterGuidance.from(maneuver(type = 99)) { "x" })
    }

    @Test
    fun `guidance carries route totals and puts the turn distance in the text`() {
        val g = XpengClusterGuidance.from(maneuver(type = 2)) { "21:20" }!!
        assertEquals(XpengClusterGuidance(8, "120 m · Calle 8", 3000, 240, "21:20"), g)
    }

    private val labels = XpengClusterGuidance.Labels("Roundabout", { "Roundabout, exit $it" }, "Arrive")

    @Test
    fun `roundabout and arrival say what they are in the text`() {
        fun text(type: Int) = XpengClusterGuidance.from(maneuver(type = type), labels) { "" }!!.roadName
        assertEquals("Roundabout · 120 m · Calle 8", text(6))
        assertEquals("Roundabout, exit 3 · 120 m · Calle 8", text(30))
        assertEquals("Arrive · 120 m · Calle 8", text(12))
        assertEquals("120 m · Calle 8", text(2))
    }

    @Test
    fun `turn distance switches to kilometres from 1000 metres`() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try {
            assertEquals("0 m", XpengClusterGuidance.distanceText(0))
            assertEquals("999 m", XpengClusterGuidance.distanceText(999))
            assertEquals("1.0 km", XpengClusterGuidance.distanceText(1000))
            assertEquals("1.5 km", XpengClusterGuidance.distanceText(1530))
            assertEquals("9.9 km", XpengClusterGuidance.distanceText(9949))
            assertEquals("10 km", XpengClusterGuidance.distanceText(10_000))
            assertEquals("13 km", XpengClusterGuidance.distanceText(12_600))
            assertEquals("1.5 km · Calle 8", XpengClusterGuidance.from(maneuver(type = 2, distance = 1530)) { "" }!!.roadName)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `missing road leaves only the distance`() {
        assertEquals("120 m", XpengClusterGuidance.from(maneuver(type = 2, road = "")) { "" }!!.roadName)
    }

    @Test
    fun `route totals missing from the iPhone give no guidance`() {
        assertNull(XpengClusterGuidance.from(maneuver(type = 2).copy(remainingMeters = null)) { "" })
        assertNull(XpengClusterGuidance.from(maneuver(type = 2).copy(remainingSeconds = null)) { "" })
        assertNull(XpengClusterGuidance.from(maneuver(type = 2).copy(arrivalEpochSeconds = null)) { "" })
    }

    @Test
    fun `json uses the cards field names`() {
        val json = org.json.JSONObject(XpengClusterGuidance(8, "Right in 120 m", 3000, 240, "21:20").toJson())
        assertEquals(8, json.getInt("roadType"))
        assertEquals("Right in 120 m", json.getString("roadName"))
        assertEquals(3000, json.getInt("resDistance"))
        assertEquals(240, json.getInt("resTime"))
        assertEquals("21:20", json.getString("arrivalTime"))
    }

    @Test
    fun `clock follows the 12 and 24 hour setting`() {
        val utc = TimeZone.getTimeZone("UTC")
        val evening = 21L * 3600 + 5 * 60
        assertEquals("21:05", XpengClusterGuidance.clockFormatter(true, utc)(evening))
        assertEquals("9:05", XpengClusterGuidance.clockFormatter(false, utc)(evening).substringBefore(' '))
    }
}
