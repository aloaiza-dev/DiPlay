package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class XpengClusterBridgeTest {
    private val calls = mutableListOf<String>()
    private lateinit var app: Context
    private var now = 0L

    private val port = object : XpengClusterPort {
        override fun connect() = true.also { calls += "connect" }
        override fun info(guidance: XpengClusterGuidance) = true.also {
            calls += "${guidance.roadType}|${guidance.roadName}|${guidance.remainingMeters}|${guidance.remainingSeconds}|${guidance.arrivalTime}"
        }
        override fun clear() { calls += "clear" }
    }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        BydOutputSettings.setEnabled(app, true)
        BydOutputSettings.setXpengCluster(app, true)
        XpengClusterBridge.resetForTest(port, app, { "ETA$it" }) { now }
    }

    @After
    fun tearDown() = XpengClusterBridge.resetForTest(null, null)

    private fun maneuver(index: Int, type: Int, road: String = "") = XpengClusterBridge.onFrame(
        Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, index), tlv(0x03, type), tlv(0x04, *road.toByteArray().map { it.toInt() }.toIntArray(), 0))),
    )

    private fun u(value: Long, bytes: Int) = IntArray(bytes) { ((value shr (8 * (bytes - 1 - it))) and 0xff).toInt() }

    private fun route(state: Int, distance: Int, current: Int? = null, meters: Long = 3000, seconds: Long = 240, arrival: Long = 1_000) =
        XpengClusterBridge.onFrame(Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(
            tlv(0x01, state),
            tlv(0x05, *u(arrival, 8)), tlv(0x06, *u(seconds, 8)), tlv(0x07, *u(meters, 4)),
            tlv(0x0a, *u(distance.toLong(), 4)),
            *listOfNotNull(current?.let { tlv(0x0d, 0, it) }).toTypedArray(),
        )))

    private fun source(name: String) = XpengClusterBridge.onFrame(
        Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x13, *name.toByteArray().map { it.toInt() }.toIntArray(), 0))),
    )

    @Test
    fun `a new navigation app does not inherit the old arrow`() {
        source("Apple Maps"); maneuver(0, 2, "Calle 8"); route(1, 120, 0)
        calls.clear()
        source("Waze")
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `the new app's own data in the switching frame is kept`() {
        source("Apple Maps"); maneuver(0, 2); route(1, 120, 0)
        calls.clear(); now += 2_000_000_000L
        XpengClusterBridge.onFrame(Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(
            tlv(0x13, 'W'.code, 'a'.code, 'z'.code, 'e'.code, 0), tlv(0x01, 1),
            tlv(0x05, *u(2_000, 8)), tlv(0x06, *u(100, 8)), tlv(0x07, *u(900, 4)),
            tlv(0x0a, 0, 0, 0, 40), tlv(0x0d, 0, 0))))
        assertEquals(listOf("clear"), calls) // Apple's arrow is gone while the new app has no maneuver yet
        maneuver(0, 1, "Main St")
        assertEquals("7|40 m · Main St|900|100|ETA2000", calls.last())
    }

    @Test
    fun `an app that reports no source clears the old guidance`() {
        source("Apple Maps"); maneuver(0, 2); route(1, 120, 0)
        calls.clear()
        source("")
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `the same source repeated keeps the card`() {
        source("Apple Maps"); maneuver(0, 2); route(1, 120, 0)
        calls.clear(); source("Apple Maps")
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `only an XPeng head unit shows the XPeng features`() {
        fun detected(manufacturer: String, brand: String): Boolean {
            org.robolectric.shadows.ShadowBuild.setManufacturer(manufacturer)
            org.robolectric.shadows.ShadowBuild.setBrand(brand)
            return XpengClusterBridge.isXpengHeadUnit()
        }
        assertTrue(detected("XiaoPeng", "XPENG"))
        assertTrue(detected("XPENG", "other"))
        assertTrue(detected("other", "xiaopeng"))
        assertFalse(detected("BYD", "BYD"))
        assertFalse(detected("samsung", "google"))
        assertFalse(XpengClusterBridge.available(app))
    }

    @Test
    fun `left and right arrows follow the head unit theme`() {
        var night = false
        XpengClusterBridge.resetForTest(port, app, { "ETA$it" },
            XpengClusterGuidance.Labels("Roundabout", { "Roundabout, exit $it" }, "Arrive", "Left"), { night }) { now }
        maneuver(0, 1); route(1, 120, 0)
        assertEquals("9|Left · 120 m|3000|240|ETA1000", calls.last())
        night = true; calls.clear(); now += 2_000_000_000L
        route(1, 120, 0)
        assertEquals("7|120 m|3000|240|ETA1000", calls.last())
    }

    @Test
    fun `sharp turn and u-turn reach the card`() {
        maneuver(0, 47); route(1, 120, 0)
        assertEquals("9|120 m|3000|240|ETA1000", calls.last())
    }

    @Test
    fun `roundabout shows its words with the straight arrow`() {
        XpengClusterBridge.resetForTest(port, app, { "ETA$it" },
            XpengClusterGuidance.Labels("Roundabout", { "Roundabout, exit $it" }, "Arrive")) { now }
        maneuver(0, 30); route(1, 120, 0)
        assertEquals("1|Roundabout, exit 3 · 120 m|3000|240|ETA1000", calls.last())
    }

    @Test
    fun `nothing is sent until the driver opts in`() {
        BydOutputSettings.setXpengCluster(app, false)
        maneuver(0, 2); route(1, 120, 0)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `switch off sends nothing`() {
        BydOutputSettings.setEnabled(app, false)
        maneuver(0, 2); route(1, 120, 0)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `apple maneuver reaches the card with route totals`() {
        maneuver(0, 2, "Calle 8"); route(1, 120, 0)
        assertEquals(listOf("connect", "8|120 m · Calle 8|3000|240|ETA1000"), calls)
    }

    @Test
    fun `delta frames keep the cached maneuver`() {
        maneuver(0, 2, "Calle 8"); route(1, 120, 0)
        calls.clear(); now += 2_000_000_000L
        route(1, 100, meters = 2980) // no maneuver list in this frame
        assertEquals(listOf("8|100 m · Calle 8|2980|240|ETA1000"), calls)
    }

    @Test
    fun `an unknown maneuver type clears the card`() {
        maneuver(0, 2); route(1, 120, 0)
        calls.clear(); now += 2_000_000_000L
        maneuver(1, 99); route(1, 80, 1)
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `switching the output off mid route clears the card`() {
        maneuver(0, 2); route(1, 120, 0)
        calls.clear()
        BydOutputSettings.setXpengCluster(app, false)
        route(1, 100, meters = 2980)
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `arrival clears the card`() {
        maneuver(0, 2); route(1, 120, 0)
        calls.clear()
        route(2, 0)
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `session end clears the card`() {
        maneuver(0, 2); route(1, 120, 0)
        calls.clear()
        XpengClusterBridge.clear()
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `route without arrival time shows no card`() {
        maneuver(0, 2)
        XpengClusterBridge.onFrame(Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(
            tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 50), tlv(0x0d, 0, 0))))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `report shows the opt in route and output state without road text`() {
        maneuver(0, 2, "Calle 8"); route(1, 120, 0)
        val report = XpengClusterBridge.report(app)
        assertTrue(report, "xpengOptIn=true" in report && "routeGuidance=true" in report && "outputActive=true" in report)
        assertTrue(report, "Calle 8" !in report)
    }

    @Test
    fun `frames before initialize are ignored`() {
        XpengClusterBridge.resetForTest(port, null)
        maneuver(0, 2); route(1, 120, 0)
        assertTrue(calls.isEmpty())
    }

    private fun tlv(type: Int, vararg value: Int): ByteArray = ByteArrayOutputStream().apply {
        val length = value.size + 4
        write(length ushr 8); write(length); write(type ushr 8); write(type)
        value.forEach(::write)
    }.toByteArray()

    private fun tlvs(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().apply { values.forEach(::write) }.toByteArray()
}
