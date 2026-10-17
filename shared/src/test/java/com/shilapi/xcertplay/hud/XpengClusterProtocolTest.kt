package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XpengClusterProtocolTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun `info round trips road names with spaces unicode and an empty field`() {
        listOf("Calle 8 Las Tulipanias", "Rue de l’Été · 北京路", "").forEach { road ->
            val g = XpengClusterGuidance(8, road, 3000, 240, "9:05 PM")
            val parsed = XpengClusterProtocol.parse("$token ${XpengClusterProtocol.info(g)}", token)
            assertEquals(XpengClusterProtocol.Request.Info(g), parsed)
        }
    }

    @Test
    fun `every arrow the mapper can emit is accepted by the protocol`() {
        val types = (0..60).flatMap { type ->
            listOf(0, 1).flatMap { side -> listOf(true, false).map { night -> XpengClusterGuidance.roadType(type, side, night) } }
        }.filterNotNull()
        assertTrue(types.toSet().size > 6)
        assertTrue(0 in types)
        types.toSet().forEach { id ->
            val g = XpengClusterGuidance(id, "x", 1, 1, "t")
            assertEquals("roadType $id", XpengClusterProtocol.Request.Info(g),
                XpengClusterProtocol.parse("$token ${XpengClusterProtocol.info(g)}", token))
        }
    }

    @Test
    fun `info line holds no space inside a field and no newline`() {
        val line = XpengClusterProtocol.info(XpengClusterGuidance(7, "a b\nc", 1, 2, "9:05 PM"))
        assertEquals(6, line.split(' ').size)
        assertFalse('\n' in line)
    }

    @Test
    fun `long text is cut to fit one line`() {
        val g = XpengClusterGuidance(7, "é".repeat(200), 1, 2, "x")
        val line = "$token ${XpengClusterProtocol.info(g)}"
        assertTrue(line.toByteArray().size <= XpengClusterProtocol.MAX_LINE_BYTES)
        assertTrue(XpengClusterProtocol.parse(line, token) is XpengClusterProtocol.Request.Info)
    }

    @Test
    fun `plain operations parse`() {
        assertEquals(XpengClusterProtocol.Request.Ping, XpengClusterProtocol.parse("$token ping", token))
        assertEquals(XpengClusterProtocol.Request.Connect, XpengClusterProtocol.parse("$token connect", token))
        assertEquals(XpengClusterProtocol.Request.Clear, XpengClusterProtocol.parse("$token clear", token))
        assertEquals(XpengClusterProtocol.Request.Close, XpengClusterProtocol.parse("$token close", token))
    }

    @Test
    fun `wrong token unknown operation and bad fields are refused`() {
        assertNull(XpengClusterProtocol.parse("${"f".repeat(32)} ping", token))
        assertNull(XpengClusterProtocol.parse("$token transact", token))
        assertNull(XpengClusterProtocol.parse("$token info 2 1 2 3a 3a", token))
        assertNull(XpengClusterProtocol.parse("$token info 8 -1 2 3a 3a", token))
        assertNull(XpengClusterProtocol.parse("$token info 8 1 2 zz 3a", token))
        assertNull(XpengClusterProtocol.parse("$token info 8 1 2 -1 3a", token))
        assertNull(XpengClusterProtocol.parse("$token info 8 1 2 +f 3a", token))
        assertNull(XpengClusterProtocol.parse("$token info 8 1 2 3A 3a", token))
        assertNull(XpengClusterProtocol.parse("$token info 8 1 2 3a", token))
        assertNull(XpengClusterProtocol.parse("short ping", "short"))
        assertNull(XpengClusterProtocol.parse("$token ping ".padEnd(600, 'x'), token))
    }

    @Test
    fun `reply is the last non blank line`() {
        assertEquals("ok", XpengClusterProtocol.reply("HotFixRoot: noise\nok\n\n"))
        assertNull(XpengClusterProtocol.reply(null))
    }

    @Test
    fun `readLine stops at newline and refuses an over long line`() {
        assertEquals("a b", XpengClusterProtocol.readLine("a b\nrest".byteInputStream()))
        assertNull(XpengClusterProtocol.readLine("x".repeat(600).byteInputStream()))
        assertNull(XpengClusterProtocol.readLine("no newline".byteInputStream()))
    }
}
