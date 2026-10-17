package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class Iap2RxLogTest {
    @Test fun periodicUpdatesAreThinnedAndOtherMessagesKept() {
        val log = Iap2RxLog(first = 2, every = 5)
        val nowPlaying = (1..10).map { log.line(0x5001) }
        assertEquals(
            listOf("iap2 rx=0x5001", "iap2 rx=0x5001", null, null, "iap2 rx=0x5001 count=5", null, null, null, null,
                "iap2 rx=0x5001 count=10"),
            nowPlaying,
        )
        assertEquals(List(10) { "iap2 rx=0x4158" }, (1..10).map { log.line(0x4158) })
        assertEquals("iap2 rx=0x5201", log.line(0x5201))
    }

    private fun tlv(id: Int, vararg value: Int) = byteArrayOf(
        ((value.size + 4) shr 8).toByte(), (value.size + 4).toByte(), (id shr 8).toByte(), id.toByte(),
        *ByteArray(value.size) { value[it].toByte() },
    )

    @Test fun structureShowsIdsAndLengthsButNotText() {
        val payload = tlv(0x01, 6) + tlv(0x03, *"Calle 8".map { it.code }.toIntArray(), 0) + tlv(0x07, 0, 0, 0x0b, 0xb8)
        assertEquals("iap2 rx=0x5201 bytes=${payload.size} 01:1=06 03:8 07:4", Iap2RxLog.structure(0x5201, payload))
    }

    @Test fun structureHidesTwoByteValuesSuchAsOneCharacterText() {
        val payload = tlv(0x03, 'A'.code, 0) + tlv(0x0d, 0, 1)
        assertEquals("iap2 rx=0x5201 bytes=${payload.size} 03:2 0d:2", Iap2RxLog.structure(0x5201, payload))
    }

    @Test fun structureReportsMalformedAndEmptyPayloads() {
        assertEquals("iap2 rx=0x4158 bytes=0", Iap2RxLog.structure(0x4158, ByteArray(0)))
        assertEquals("iap2 rx=0x0001 bytes=4 malformed@0", Iap2RxLog.structure(1, byteArrayOf(0, 2, 0, 1)))
        assertEquals("iap2 rx=0x0001 bytes=3 trailing=3", Iap2RxLog.structure(1, byteArrayOf(0, 5, 0)))
    }

    @Test fun structureCapsTheParameterCount() {
        val payload = (1..50).map { tlv(it) }.reduce { a, b -> a + b }
        assertEquals(true, Iap2RxLog.structure(0x5202, payload).endsWith(" …"))
    }
}
