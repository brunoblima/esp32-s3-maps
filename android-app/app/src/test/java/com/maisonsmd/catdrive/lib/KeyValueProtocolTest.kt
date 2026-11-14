package com.maisonsmd.catdrive.lib

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyValueProtocolTest {
    @Test
    fun encodePreservesEntryOrderAndUsesNewlineSeparators() {
        val payload = linkedMapOf(
            "nextRd" to "Main Street",
            "distToNext" to "200 m",
            "eta" to "10:30"
        )

        assertEquals(
            "nextRd=Main Street\ndistToNext=200 m\neta=10:30",
            KeyValueProtocol.encode(payload)
        )
    }

    @Test
    fun encodeEmptyMapReturnsEmptyPayload() {
        assertEquals("", KeyValueProtocol.encode(emptyMap()))
    }
}
