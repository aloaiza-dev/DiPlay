package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XpengClusterOutputTest {
    private class FakePort : XpengClusterPort {
        val calls = mutableListOf<String>()
        var reachable = true
        override fun connect() = reachable.also { calls += "connect:$it" }
        override fun info(guidance: XpengClusterGuidance) = reachable.also { calls += "info:${guidance.roadName}" }
        override fun clear() { calls += "clear" }
    }

    private var now = 0L
    private val port = FakePort()
    private val output = XpengClusterOutput(port) { now }
    private fun g(type: Int = 8, text: String = "100 m", meters: Int = 3000) = XpengClusterGuidance(type, text, meters, 60, "21:20")
    private fun seconds(n: Int) { now += n * 1_000_000_000L }

    @Test
    fun `first update connects then sends`() {
        output.update(g())
        assertEquals(listOf("connect:true", "info:100 m"), port.calls)
    }

    @Test
    fun `identical guidance is sent once`() {
        output.update(g()); seconds(5); output.update(g())
        assertEquals(listOf("connect:true", "info:100 m"), port.calls)
    }

    @Test
    fun `distance changes are coalesced to one a second`() {
        output.update(g()); port.calls.clear()
        seconds(0); output.update(g(text = "90 m"))
        assertTrue(port.calls.isEmpty())
        seconds(1); output.update(g(text = "80 m"))
        assertEquals(listOf("info:80 m"), port.calls)
    }

    @Test
    fun `a new arrow is sent at once`() {
        output.update(g()); port.calls.clear()
        output.update(g(type = 7, text = "Left"))
        assertEquals(listOf("info:Left"), port.calls)
    }

    @Test
    fun `stop clears once and a later update connects again`() {
        output.update(g()); output.stop(); output.stop()
        assertEquals(1, port.calls.count { it == "clear" })
        port.calls.clear()
        output.update(g())
        assertEquals(listOf("connect:true", "info:100 m"), port.calls)
    }

    @Test
    fun `stop before any guidance does nothing`() {
        output.stop()
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `unreachable helper retries the connection on the next update`() {
        port.reachable = false
        output.update(g())
        assertFalse(output.active)
        port.reachable = true; port.calls.clear()
        output.update(g())
        assertEquals(listOf("connect:true", "info:100 m"), port.calls)
    }

    @Test
    fun `a failed send drops back to reconnecting`() {
        output.update(g()); port.reachable = false; seconds(2)
        output.update(g(text = "50 m"))
        assertFalse(output.active)
        port.reachable = true; port.calls.clear()
        output.update(g(text = "40 m"))
        assertEquals(listOf("connect:true", "info:40 m"), port.calls)
    }
}
